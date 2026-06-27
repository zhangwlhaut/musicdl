package com.musicdl.car.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musicdl.car.MainActivity
import com.musicdl.car.data.ApiClient
import com.musicdl.car.data.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockTimer: java.util.Timer? = null

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicDL:PlaybackWakeLock").apply {
            setReferenceCounted(false)
        }

        /**
         * 定期续期 WakeLock，确保息屏后 CPU 持续运行:
         * - PARTIAL_WAKE_LOCK 默认不超时，但某些厂商 ROM（如 MIUI）会限制长时 WakeLock
         * - 每 10 分钟重新 acquire（带 30 分钟超时），相当于 "滚动续约"
         */
        wakeLockTimer = java.util.Timer("WakeLockRenewal", false).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    holdWakeLock()
                }
            }, 5 * 60 * 1000L, 10 * 60 * 1000L) // 首次 5 分钟后，之后每 10 分钟续一次
        }

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
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player?.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                android.util.Log.d("PlaybackService", "onPlayWhenReadyChanged: playWhenReady=$playWhenReady, reason=$reason")
            }
            override fun onPlaybackStateChanged(state: Int) {
                android.util.Log.d("PlaybackService", "onPlaybackStateChanged: state=$state")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                android.util.Log.d("PlaybackService", "onIsPlayingChanged: isPlaying=$isPlaying")
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("PlaybackService", "onPlayerError: ", error)
            }
        })

        session = MediaLibrarySession.Builder(this, player!!, librarySessionCallback)
            .setSessionActivity(activityPendingIntent())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val p = player
        val forceForeground = startInForegroundRequired || (p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED)
        super.onUpdateNotification(session, forceForeground)

        if (forceForeground) {
            holdWakeLock()
        } else {
            releaseWakeLock()
        }
    }

    /** 持锁：每次 acquire 带 30 分钟超时，配合定时器实现滚动续约 */
    private fun holdWakeLock() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(30 * 60 * 1000L)
                android.util.Log.d("PlaybackService", "WakeLock acquired (30min)")
            } else {
                // 已经持有就续约——重新 acquire 刷新超时
                wakeLock?.acquire(30 * 60 * 1000L)
                android.util.Log.d("PlaybackService", "WakeLock renewed (30min)")
            }
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "Failed to acquire/renew wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                android.util.Log.d("PlaybackService", "WakeLock released")
            }
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "Failed to release wake lock", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 取消 WakeLock 续期定时器
        try {
            wakeLockTimer?.cancel()
            wakeLockTimer = null
        } catch (e: Exception) { /* ignore */ }

        releaseWakeLock()
        wakeLock = null
        session.release()
        player?.release()
        player = null
        super.onDestroy()
    }

    // --- callbacks ---

    private val librarySessionCallback = object : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            android.util.Log.d("PlaybackService", "onConnect: packageName=${controller.packageName}, uid=${controller.uid}")
            return super.onConnect(session, controller)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: android.os.Bundle
        ): ListenableFuture<androidx.media3.session.SessionResult> {
            android.util.Log.d("PlaybackService", "onCustomCommand: action=${customCommand.customAction}")
            return super.onCustomCommand(session, controller, customCommand, args)
        }


        private fun logBundle(bundle: android.os.Bundle?, prefix: String = "  ") {
            if (bundle == null) return
            try {
                for (key in bundle.keySet()) {
                    val value = bundle.get(key)
                    if (value is android.os.Bundle) {
                        android.util.Log.d("PlaybackService", "$prefix$key -> Bundle:")
                        logBundle(value, "$prefix  ")
                    } else {
                        android.util.Log.d("PlaybackService", "$prefix$key -> $value")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "Failed to log bundle", e)
            }
        }

        private val searchResultsCache = java.util.concurrent.ConcurrentHashMap<String, List<MediaItem>>()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            android.util.Log.d("PlaybackService", "onGetLibraryRoot from packageName=${browser.packageName}")
            val rootMediaItem = MediaItem.Builder()
                .setMediaId("ROOT")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Root")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootMediaItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
            android.util.Log.d("PlaybackService", "onGetChildren parentId='$parentId'")
            return Futures.immediateFuture(LibraryResult.ofItemList(listOf(), params))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            android.util.Log.d("PlaybackService", "onSearch query='$query' from packageName=${browser.packageName}")
            return serviceScope.future {
                try {
                    val result = repo.search(query).getOrNull()
                    val songs = result?.songsSafe
                    if (!songs.isNullOrEmpty()) {
                        val mediaItems = songs.map { it.toMediaItem() }
                        searchResultsCache[query] = mediaItems
                        android.util.Log.d("PlaybackService", "onSearch found ${mediaItems.size} items for '$query'")
                        session.notifySearchResultChanged(browser, query, mediaItems.size, params)
                    } else {
                        searchResultsCache[query] = emptyList()
                        session.notifySearchResultChanged(browser, query, 0, params)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PlaybackService", "Error in onSearch", e)
                    searchResultsCache[query] = emptyList()
                    session.notifySearchResultChanged(browser, query, 0, params)
                }
                LibraryResult.ofVoid()
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
            android.util.Log.d("PlaybackService", "onGetSearchResult query='$query' page=$page pageSize=$pageSize")
            val items = searchResultsCache[query] ?: emptyList()
            val fromIndex = page * pageSize
            val toIndex = minOf(fromIndex + pageSize, items.size)
            
            val list = if (fromIndex < items.size) {
                items.subList(fromIndex, toIndex)
            } else {
                emptyList()
            }
            
            val immutableList = com.google.common.collect.ImmutableList.copyOf(list)
            return Futures.immediateFuture(LibraryResult.ofItemList(immutableList, params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            return serviceScope.future {
                val resolved = mutableListOf<MediaItem>()
                for (item in mediaItems) {
                    val mediaId = item.mediaId
                    val requestMetadata = item.requestMetadata
                    val mediaMetadata = item.mediaMetadata

                    // Log incoming item details
                    android.util.Log.d("PlaybackService", "onAddMediaItems: mediaId='$mediaId'")
                    android.util.Log.d("PlaybackService", "  requestMetadata: searchQuery='${requestMetadata.searchQuery}', mediaUri='${requestMetadata.mediaUri}'")
                    requestMetadata.extras?.let { extras ->
                        for (key in extras.keySet()) {
                            android.util.Log.d("PlaybackService", "    reqExtra: $key -> ${extras.get(key)}")
                        }
                    }
                    android.util.Log.d("PlaybackService", "  mediaMetadata: title='${mediaMetadata.title}', artist='${mediaMetadata.artist}', album='${mediaMetadata.albumTitle}'")
                    mediaMetadata.extras?.let { extras ->
                        for (key in extras.keySet()) {
                            android.util.Log.d("PlaybackService", "    metaExtra: $key -> ${extras.get(key)}")
                        }
                    }

                    // Extract search query
                    var query = requestMetadata.searchQuery?.toString()?.trim()
                    if (query.isNullOrEmpty()) {
                        // Fallback: check extras
                        val queryKeys = arrayOf(
                            "EXTRA_KEYWORDS_SEARCH", "keywords", "query", "keyword", "search_word", 
                            "searchKey", "EXTRA_SEARCH_KEY", "search_key", "key", "text", "search_text", 
                            "EXTRA_SEARCH_WORD", "voice_query", "name", "songName", "song_name", "title", "audio_name"
                        )
                        val artistKeys = arrayOf(
                            "EXTRA_ARTIST_SEARCH", "artist", "artist_name", "artistName", "singer", "singer_name"
                        )
                        
                        fun extractFromBundle(extras: android.os.Bundle): String? {
                            for (key in queryKeys) {
                                val v = extras.getString(key) ?: extras.get(key)?.toString()
                                if (!v.isNullOrBlank()) {
                                    var artistVal: String? = null
                                    for (aKey in artistKeys) {
                                        val a = extras.getString(aKey) ?: extras.get(aKey)?.toString()
                                        if (!a.isNullOrBlank()) {
                                            artistVal = a.trim()
                                            break
                                        }
                                    }
                                    return if (!artistVal.isNullOrBlank()) "${v.trim()} $artistVal" else v.trim()
                                }
                            }
                            return null
                        }
                        
                        requestMetadata.extras?.let { extras ->
                            query = extractFromBundle(extras)
                        }
                        if (query.isNullOrEmpty()) {
                            mediaMetadata.extras?.let { extras ->
                                query = extractFromBundle(extras)
                            }
                        }
                    }

                    // If mediaId seems to be a text query (contains letters or Chinese characters and is not system ID)
                    if (query.isNullOrEmpty() && mediaId.isNotBlank() &&
                        !mediaId.contains("\u0001") &&
                        !mediaId.startsWith("[") &&
                        !mediaId.startsWith("androidx.media3.") &&
                        !mediaId.equals("ROOT", ignoreCase = true) &&
                        !mediaId.all { it.isDigit() }) {
                        query = mediaId.trim()
                    }

                    if (!query.isNullOrEmpty()) {
                        android.util.Log.d("PlaybackService", "onAddMediaItems: query extracted='$query', performing search...")
                        val searchResult = repo.search(query!!).getOrNull()
                        val songs = searchResult?.songsSafe
                        if (!songs.isNullOrEmpty()) {
                            android.util.Log.d("PlaybackService", "onAddMediaItems: search found ${songs.size} songs for '$query', playing first: ${songs[0].name}")
                            songs.take(20).forEach { song ->
                                resolved.add(song.toMediaItem())
                            }
                        } else {
                            android.util.Log.d("PlaybackService", "onAddMediaItems: search returned no results for '$query'")
                        }
                    } else {
                        val parts = parseMediaId(mediaId)
                        if (parts != null) {
                            android.util.Log.d("PlaybackService", "onAddMediaItems: parts found id=${parts.first}, source=${parts.second}")
                            resolved.add(
                                item.buildUpon()
                                    .setUri(streamUrlFor(parts.first, parts.second, item))
                                    .build()
                            )
                        } else if (mediaId.isNotBlank() &&
                                   !mediaId.contains("\u0001") &&
                                   !mediaId.startsWith("[") &&
                                   !mediaId.startsWith("androidx.media3.") &&
                                   !mediaId.equals("ROOT", ignoreCase = true)) {
                            // Treat as raw NetEase ID
                            val rawId = mediaId.trim()
                            android.util.Log.d("PlaybackService", "onAddMediaItems: Detected raw NetEase ID: '$rawId'")

                            val title = mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "语音播放歌曲"
                            val artist = mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: "未知歌手"
                            val album = mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() } ?: ""
                            val cover = mediaMetadata.artworkUri

                            val resolvedMetadata = mediaMetadata.buildUpon()
                                .setTitle(title)
                                .setArtist(artist)
                                .setAlbumTitle(album)
                                .setArtworkUri(cover)
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .build()

                            val resolvedItemTemp = item.buildUpon()
                                .setMediaId(buildMediaId(rawId, "netease"))
                                .setMediaMetadata(resolvedMetadata)
                                .build()

                            val finalUri = streamUrlFor(rawId, "netease", resolvedItemTemp)

                            val resolvedItem = resolvedItemTemp.buildUpon()
                                .setUri(finalUri)
                                .build()

                            resolved.add(resolvedItem)
                        } else if (item.localConfiguration?.uri != null) {
                            android.util.Log.d("PlaybackService", "onAddMediaItems: uri already present, adding directly")
                            resolved.add(item)
                        } else {
                            android.util.Log.d("PlaybackService", "onAddMediaItems: unhandled item, skipping")
                        }
                    }
                }
                android.util.Log.d("PlaybackService", "onAddMediaItems: returning resolved size=${resolved.size}")
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
