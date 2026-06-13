package com.musicdl.car.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicdl.car.data.MusicRepository
import com.musicdl.car.data.dto.LyricLine
import com.musicdl.car.data.dto.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 歌词 ViewModel。
 *
 * 由外部(FullPlayer Composable)驱动:
 *   - [onSongChanged] — 在 PlaybackController.currentMediaId 变化时调用(去抖 300ms)
 *   - [onPositionChanged] — 在 PositionMs 更新时每帧调用,计算当前行索引
 *
 * 暴露 [lines] 和 [currentLineIndex] 供 LyricView 渲染。
 */
class LyricViewModel(
    private val repo: MusicRepository = MusicRepository(),
) : ViewModel() {

    private val _lines = MutableStateFlow<List<LyricLine>>(emptyList())
    val lines: StateFlow<List<LyricLine>> = _lines.asStateFlow()

    private val _currentLineIndex = MutableStateFlow(0)
    val currentLineIndex: StateFlow<Int> = _currentLineIndex.asStateFlow()

    /** 缓存当前加载的歌曲标识,避免重复拉取同首歌歌词。 */
    private var currentSongKey: String? = null

    fun onSongChanged(song: Song?) {
        val key = song?.let { "${it.source}|${it.id}" }
        if (key == null) {
            _lines.value = emptyList()
            currentSongKey = null
            return
        }
        if (key == currentSongKey) return
        currentSongKey = key

        viewModelScope.launch {
            delay(300) // 去抖,避免快速切歌时频繁拉取
            _lines.value = repo.lyric(song).getOrDefault(emptyList())
            _currentLineIndex.value = 0
        }
    }

    fun onPositionChanged(positionMs: Long) {
        val lines = _lines.value
        if (lines.isEmpty()) return
        val idx = lines.indexOfLast { it.timeMs <= positionMs }
        _currentLineIndex.value = idx.coerceAtLeast(0)
    }
}