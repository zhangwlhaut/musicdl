package com.musicdl.car.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.musicdl.car.data.ApiClient
import com.musicdl.car.data.dto.RecentPlay
import com.musicdl.car.data.dto.Song

/**
 * Bidirectional mapping between our [Song] DTO and Media3 [MediaItem].
 * mediaId encodes id|source so we can re-hydrate a Song from a MediaItem
 * coming back through the MediaSession.Callback path.
 */

private const val MEDIA_ID_SEP = "\u0001"

fun Song.toMediaItem(): MediaItem {
    val coverUri = ApiClient.coverUrl(this)?.toUri()
    // 优先使用预解析的 CDN 直链,避免息屏后走 Go proxy 触发外网连接被拦截
    val uri = StreamUrlCache.getDirectUrl(this) ?: ApiClient.streamUrl(this)
    return MediaItem.Builder()
        .setMediaId(buildMediaId(id, source))
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(coverUri)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()
}

fun RecentPlay.toSong(): Song = Song(
    id = id, source = source, name = name, artist = artist,
    album = album, cover = cover, duration = duration, extra = extra
)

fun buildMediaId(id: String, source: String): String = id + MEDIA_ID_SEP + source

fun parseMediaId(mediaId: String): Pair<String, String>? {
    val idx = mediaId.indexOf(MEDIA_ID_SEP)
    if (idx <= 0) return null
    return mediaId.substring(0, idx) to mediaId.substring(idx + 1)
}
