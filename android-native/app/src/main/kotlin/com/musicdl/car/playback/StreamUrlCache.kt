package com.musicdl.car.playback

import com.musicdl.car.data.dto.Song
import java.util.concurrent.ConcurrentHashMap

/**
 * 预解析流媒体 URL 缓存。
 *
 * 在亮屏/网络可用时提前解析歌曲的真实 CDN 直链并缓存，这样息屏后切歌时
 * ExoPlayer 可以直接使用缓存的 CDN URL 播放，不需要走 Go Proxy 做外网中转，
 * 避免 Xiaomi 息屏网络限制导致的新建 TCP 连接被拦截问题。
 *
 * 缓存键: "${song.id}|${song.source}" (与 buildMediaId 格式一致)
 * 缓存值: 真实的 CDN 音频 URL
 */
object StreamUrlCache {

    private const val MAX_SIZE = 200

    /** 已确认可用的音源缓存: cacheKey -> working source */
    private val workingSourceCache = ConcurrentHashMap<String, String>()

    /** 真实 CDN URL 缓存: cacheKey -> direct CDN URL */
    private val directUrlCache = ConcurrentHashMap<String, String>()

    // ---------------------------------------------------------------
    // 真实 CDN URL 缓存 (供 toMediaItem 使用)
    // ---------------------------------------------------------------

    fun getDirectUrl(song: Song): String? = directUrlCache[keyOf(song)]

    fun putDirectUrl(song: Song, url: String) {
        trimIfNeeded(directUrlCache)
        directUrlCache[keyOf(song)] = url
    }

    fun putDirectUrl(mediaId: String, url: String) {
        trimIfNeeded(directUrlCache)
        directUrlCache[mediaId] = url
    }

    // ---------------------------------------------------------------
    // 可用音源缓存 (避免 switch_source 重复调用)
    // ---------------------------------------------------------------

    /** 获取已确认可用的替代音源,没有则返回 null */
    fun getWorkingSource(song: Song): String? = workingSourceCache[keyOf(song)]

    /** 记录某首歌在某个音源上可用 */
    fun putWorkingSource(song: Song, source: String) {
        trimIfNeeded(workingSourceCache)
        workingSourceCache[keyOf(song)] = source
    }

    /** 从 switch_source 结果中更新缓存 */
    fun recordSwitchResult(originalKey: String, replacement: Song) {
        // 记录: 原(songKey) -> 替代音源
        trimIfNeeded(workingSourceCache)
        workingSourceCache[originalKey] = replacement.source
        // 同时记录替代歌的 key,后续直接用它
        val replacementKey = keyOf(replacement)
        workingSourceCache[replacementKey] = replacement.source
    }

    // ---------------------------------------------------------------
    // 清空 / 裁剪
    // ---------------------------------------------------------------

    /** 切换歌单或重新开始播放时调用 */
    fun clear() {
        workingSourceCache.clear()
        directUrlCache.clear()
    }

    private fun trimIfNeeded(map: ConcurrentHashMap<*, *>) {
        if (map.size >= MAX_SIZE) {
            map.clear()
        }
    }

    private fun keyOf(song: Song): String = buildMediaId(song.id, song.source)
}
