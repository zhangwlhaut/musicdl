package com.musicdl.car.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicdl.car.data.MusicRepository
import com.musicdl.car.data.dto.Song
import com.musicdl.car.ui.Toaster
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 共享的下载状态:Activity 范围内所有 SongRow / 详情页共用一份。
 *
 * status: idle / downloading / done / failed(失败原因放 [errors])
 */
class DownloadViewModel(private val repo: MusicRepository = MusicRepository()) : ViewModel() {

    enum class Status { IDLE, DOWNLOADING, DONE, FAILED }

    /** key = "${id}|${source}" → 状态 */
    private val _states = MutableStateFlow<Map<String, Status>>(emptyMap())
    val states: StateFlow<Map<String, Status>> = _states.asStateFlow()

    /** UI 弹 Snackbar 用:每次下载完成发一条消息。 */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private fun key(song: Song) = song.id + "|" + song.source

    fun statusOf(song: Song): Status = _states.value[key(song)] ?: Status.IDLE

    /** 触发单首下载。已在 DOWNLOADING / DONE 的歌跳过。 */
    fun download(song: Song) {
        val k = key(song)
        val cur = _states.value[k] ?: Status.IDLE
        if (cur == Status.DOWNLOADING) {
            Toaster.show("「${song.name}」正在下载中…")
            return
        }
        if (cur == Status.DONE) {
            Toaster.show("「${song.name}」已下载")
            return
        }
        if (song.source == "local" || song.source.isBlank()) {
            Toaster.show("本地歌曲无需下载")
            return
        }
        _states.update { it + (k to Status.DOWNLOADING) }
        viewModelScope.launch {
            downloadWithFallback(song, k)
        }
    }

    /** 下载一首歌,失败后自动尝试跨源切换再重试一次。 */
    private suspend fun downloadWithFallback(song: Song, stateKey: String, isRetry: Boolean = false) {
        repo.downloadSong(song)
            .onSuccess { resp ->
                val saved = (resp["saved"] as? Boolean) == true ||
                    (resp["skipped"] != null)
                if (saved) {
                    _states.update { it + (stateKey to Status.DONE) }
                    val msg = "已下载:${song.name}"
                    _events.tryEmit(msg)
                    Toaster.show(msg)
                } else {
                    _states.update { it + (stateKey to Status.FAILED) }
                    val warn = resp["warning"]?.toString() ?: "未保存"
                    val msg = "下载失败:${song.name}:${warn}"
                    _events.tryEmit(msg)
                    Toaster.long(msg)
                }
            }
            .onFailure { e ->
                if (!isRetry) {
                    // 首次失败:尝试跨源切换
                    val alt = repo.switchSource(song)
                    if (alt != null && (alt.source != song.source || alt.id != song.id)) {
                        downloadWithFallback(alt, stateKey, isRetry = true)
                        return@onFailure
                    }
                }
                _states.update { it + (stateKey to Status.FAILED) }
                val msg = "下载失败:${song.name}:${e.message ?: "未知错误"}"
                _events.tryEmit(msg)
                Toaster.long(msg)
            }
    }

    /** 批量下载:串行调用,避免后端压力。完成后汇总一条消息。 */
    fun downloadAll(songs: List<Song>) {
        if (songs.isEmpty()) {
            Toaster.show("没有可下载的歌曲")
            return
        }
        val onlineCount = songs.count { it.source != "local" && it.source.isNotBlank() }
        if (onlineCount == 0) {
            Toaster.show("没有可下载的在线歌曲")
            return
        }
        Toaster.show("开始下载 $onlineCount 首歌曲…")
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            for (song in songs) {
                if (song.source == "local" || song.source.isBlank()) continue
                val k = key(song)
                val cur = _states.value[k] ?: Status.IDLE
                if (cur == Status.DONE) { ok++; continue }
                _states.update { it + (k to Status.DOWNLOADING) }
                val result = downloadOnceWithFallback(song)
                if (result) {
                    _states.update { it + (k to Status.DONE) }
                    ok++
                } else {
                    _states.update { it + (k to Status.FAILED) }
                    fail++
                }
            }
            val msg = "下载完成:成功 $ok 首,失败 $fail 首"
            _events.tryEmit(msg)
            Toaster.long(msg)
        }
    }

    /** 内部用:批量下载里单首歌的下载,返回是否成功(失败会自动尝试一次切源)。 */
    private suspend fun downloadOnceWithFallback(song: Song): Boolean {
        val first = repo.downloadSong(song)
        first.onSuccess { resp ->
            if ((resp["saved"] as? Boolean) == true) return true
        }
        // 第一次失败或未保存:尝试切换源
        val alt = repo.switchSource(song) ?: return false
        if (alt.source == song.source && alt.id == song.id) return false
        val retry = repo.downloadSong(alt)
        retry.onSuccess { resp ->
            if ((resp["saved"] as? Boolean) == true) return true
        }
        return false
    }
}
