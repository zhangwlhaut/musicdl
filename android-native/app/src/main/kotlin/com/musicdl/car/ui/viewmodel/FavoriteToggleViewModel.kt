package com.musicdl.car.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicdl.car.data.MusicRepository
import com.musicdl.car.data.dto.Song
import com.musicdl.car.ui.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 共享的"歌曲是否已收藏"状态缓存,作为 Activity 范围 ViewModel 使用,
 * 这样不同 Tab/详情页里的 SongRow 看到的心形状态是一致的。
 *
 * 真值来源:后端 `/favorites/contains` 与 `/favorites/toggle`。
 */
class FavoriteToggleViewModel(
    private val repo: MusicRepository = MusicRepository(),
) : ViewModel() {

    /** key = "id|source" → 是否已收藏。未出现的 key 表示尚未查询过。 */
    private val _states = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val states: StateFlow<Map<String, Boolean>> = _states.asStateFlow()

    private fun key(song: Song) = song.id + "|" + song.source

    fun isFavorited(song: Song): Boolean? = _states.value[key(song)]

    /** 第一次出现某首歌时调用,异步拉取它的收藏状态。已查询过的不会重复请求。 */
    fun probe(song: Song) {
        val k = key(song)
        if (_states.value.containsKey(k)) return
        viewModelScope.launch {
            repo.favoritesContains(song.id, song.source).onSuccess { fav ->
                _states.update { it + (k to fav) }
            }
        }
    }

    /** 用户点击心形:翻转并写回后端,以后端返回值为准更新缓存。 */
    fun toggle(song: Song) {
        val k = key(song)
        viewModelScope.launch {
            repo.toggleFavorite(song).onSuccess { fav ->
                _states.update { it + (k to fav) }
                Toaster.show(if (fav) "已收藏「${song.name}」" else "已取消收藏「${song.name}」")
            }.onFailure { e ->
                Toaster.long("收藏操作失败:${e.message ?: "未知错误"}")
            }
        }
    }
}
