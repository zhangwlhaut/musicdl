package com.musicdl.car.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musicdl.car.MainActivity
import com.musicdl.car.data.ApiClient
import com.musicdl.car.data.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future

/**
 * Foreground MediaSessionService. Media3 wires up:
 *  - AudioFocus + ducking + auto-pause on call (via setAudioAttributes(_, true))
 *  - Bluetooth disconnect → pause (via setHandleAudioBecomingNoisy)
 *  - Notification + lockscreen controls (DefaultMediaNotificationProvider, default)
 *  - System voice / steering-wheel keys (MediaSession routes them to the Player)
 *
 * The MediaLibrarySession variant additionally lets the system voice assistant
 * push search queries via [onAddMediaItems] when the items carry searchQuery
 * in their RequestMetadata.
 */
class PlaybackService : MediaLibraryService() {

    companion object {
        /** 同进程直接访问 ExoPlayer,绕开 MediaController IPC Binder 限制。
         *  在 [onCreate] 中赋值,[onDestroy] 中清空。 */
        @JvmStatic
        var player: ExoPlayer? = null
            private set

        private var simpleCache: androidx.media3.datasource.cache.SimpleCache? = null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo = MusicRepository()

    private lateinit var session: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()

        val baseDataSourceFactory = DefaultDataSource.Factory(
            this,
            OkHttpDataSource.Factory(ApiClient.okHttpClient())
        )

        if (simpleCache == null) {
            val cacheDir = java.io.File(cacheDir, "media_cache")
            val evictor = androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(1024L * 1024L * 512L) // 512MB limit
            val databaseProvider = androidx.media3.database.StandaloneDatabaseProvider(this)
            simpleCache = androidx.media3.datasource.cache.SimpleCache(cacheDir, evictor, databaseProvider)
        }

        val dataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(simpleCache!!)
            .setUpstreamDataSourceFactory(baseDataSourceFactory)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        session = MediaLibrarySession.Builder(this, player!!, librarySessionCallback)
            .setSessionActivity(activityPendingIntent())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session.release()
        player?.release()
        player = null
        super.onDestroy()
    }

    // --- callbacks ---

    private val librarySessionCallback = object : MediaLibrarySession.Callback {

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            // Two paths:
            //   1. searchQuery present  →  voice assistant: search server, return top results
            //   2. mediaId encodes id|source → already a known song, rebuild URI
            return serviceScope.future {
                val resolved = mutableListOf<MediaItem>()
                for (item in mediaItems) {
                    val query = item.requestMetadata.searchQuery?.toString()?.trim()
                    if (!query.isNullOrEmpty()) {
                        repo.search(query).getOrNull()?.songsSafe?.take(20)?.forEach { song ->
                            resolved.add(song.toMediaItem())
                        }
                    } else {
                        val parts = parseMediaId(item.mediaId)
                        if (parts != null) {
                            // Caller does not know the streaming URL — patch it in.
                            resolved.add(
                                item.buildUpon()
                                    .setUri(streamUrlFor(parts.first, parts.second, item))
                                    .build()
                            )
                        } else if (item.localConfiguration?.uri != null) {
                            resolved.add(item)
                        }
                    }
                }
                resolved
            }
        }
    }

    private fun streamUrlFor(id: String, source: String, item: MediaItem): android.net.Uri {
        val md = item.mediaMetadata
        val song = com.musicdl.car.data.dto.Song(
            id = id, source = source,
            name = md.title?.toString() ?: "",
            artist = md.artist?.toString(),
            album = md.albumTitle?.toString(),
            cover = md.artworkUri?.toString()
        )
        return android.net.Uri.parse(ApiClient.streamUrl(song))
    }

    private fun activityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, 0, intent, flags)
    }
}
