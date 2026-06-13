package com.musicdl.car.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicdl.car.data.MusicRepository
import com.musicdl.car.data.dto.MusicCollection
import com.musicdl.car.data.dto.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Activity 级 ViewModel:统一管理"手动歌单"列表 + 新建 / 加入歌曲操作。
 *
 * - MineScreen 用它显示「+ 新建歌单」磁贴并 refresh()
 * - SongRow 用它弹"加入到歌单"sheet,操作完后 refresh() 让首页磁贴的"x 首"同步
 */
class CollectionsHubViewModel(
    private val repo: MusicRepository = MusicRepository(),
) : ViewModel() {

    /** 只包含手动歌单(include_imported=0),用于"加入到歌单"选择。 */
    private val _manual = MutableStateFlow<List<MusicCollection>>(emptyList())
    val manual: StateFlow<List<MusicCollection>> = _manual.asStateFlow()

    private val _events = MutableStateFlow<HubEvent?>(null)
    val events: StateFlow<HubEvent?> = _events.asStateFlow()

    sealed class HubEvent {
        data class Created(val id: Long, val name: String) : HubEvent()
        data class Added(val collectionName: String, val songName: String) : HubEvent()
        data class Failed(val message: String) : HubEvent()
    }

    fun consumeEvent() { _events.value = null }

    fun refresh() {
        viewModelScope.launch {
            repo.collections(includeImported = false).onSuccess { list ->
                _manual.value = list
            }
        }
    }

    /**
     * 新建一个手动歌单。如果传入了 [thenAddSong],创建成功后会立刻把该歌曲加进去。
     */
    fun create(
        name: String,
        description: String = "",
        thenAddSong: Song? = null,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _events.value = HubEvent.Failed("歌单名不能为空")
            return
        }
        viewModelScope.launch {
            repo.createCollection(trimmed, description).onSuccess { resp ->
                _events.value = HubEvent.Created(resp.id, resp.name.ifBlank { trimmed })
                refresh()
                if (thenAddSong != null && resp.id > 0) {
                    addSong(resp.id, resp.name.ifBlank { trimmed }, thenAddSong)
                }
            }.onFailure { err ->
                _events.value = HubEvent.Failed("新建失败:${err.message}")
            }
        }
    }

    fun addSong(collectionId: Long, collectionName: String, song: Song) {
        viewModelScope.launch {
            repo.addSongToCollection(collectionId, song).onSuccess {
                _events.value = HubEvent.Added(collectionName, song.name)
                refresh()
            }.onFailure { err ->
                _events.value = HubEvent.Failed("加入失败:${err.message}")
            }
        }
    }
}
