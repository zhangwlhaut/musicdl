package com.musicdl.car.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicdl.car.data.MusicRepository
import com.musicdl.car.data.dto.*
import com.musicdl.car.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(private val repo: MusicRepository = MusicRepository()) : ViewModel() {
    private val _recent = MutableStateFlow<UiState<List<RecentPlay>>>(UiState.Loading)
    val recent: StateFlow<UiState<List<RecentPlay>>> = _recent.asStateFlow()

    private val _collections = MutableStateFlow<UiState<List<MusicCollection>>>(UiState.Loading)
    val collections: StateFlow<UiState<List<MusicCollection>>> = _collections.asStateFlow()

    private val _recommend = MutableStateFlow<UiState<RecommendResponse>>(UiState.Loading)
    val recommend: StateFlow<UiState<RecommendResponse>> = _recommend.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _recent.value = UiState.fromResult(repo.recent(30))
        }
        viewModelScope.launch {
            _collections.value = UiState.fromResult(repo.collections())
        }
        viewModelScope.launch {
            _recommend.value = UiState.fromResult(repo.recommend())
        }
    }
}

class RecentViewModel(private val repo: MusicRepository = MusicRepository()) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<RecentPlay>>>(UiState.Loading)
    val state: StateFlow<UiState<List<RecentPlay>>> = _state.asStateFlow()
    fun refresh() { viewModelScope.launch { _state.value = UiState.fromResult(repo.recent(120)) } }
    fun clear() { viewModelScope.launch { repo.clearRecent(); refresh() } }
}

class FavoritesViewModel(private val repo: MusicRepository = MusicRepository()) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Song>>> = _state.asStateFlow()
    fun refresh() { viewModelScope.launch {
        _state.value = UiState.fromResult(repo.favorites().map { it.songs })
    } }
}

class LocalViewModel(private val repo: MusicRepository = MusicRepository()) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Song>>> = _state.asStateFlow()
    fun refresh() { viewModelScope.launch { _state.value = UiState.fromResult(repo.localMusic()) } }
}

class CollectionViewModel(private val repo: MusicRepository = MusicRepository()) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Song>>> = _state.asStateFlow()
    fun load(id: Long) {
        viewModelScope.launch { _state.value = UiState.fromResult(repo.collectionSongs(id)) }
    }
}

class RemotePlaylistViewModel(private val repo: MusicRepository = MusicRepository()) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Song>>> = _state.asStateFlow()
    /**
     * 加载远程歌单或专辑的歌曲列表。
     * @param contentType "playlist"(默认) 或 "album",决定调 /playlist.json 还是 /album.json
     */
    fun load(source: String, id: String, contentType: String = "playlist") {
        viewModelScope.launch {
            val result = if (contentType == "album") repo.albumDetail(id, source)
            else repo.playlistDetail(id, source)
            _state.value = UiState.fromResult(result.map { it.songs })
        }
    }
}

class SearchViewModel(private val repo: MusicRepository = MusicRepository()) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 搜索类型: "song" / "playlist" / "album" — 后端 search.json 直接接受这三个值 */
    private val _type = MutableStateFlow("song")
    val type: StateFlow<String> = _type.asStateFlow()

    private val _results = MutableStateFlow<UiState<SearchResponse>?>(null)
    val results: StateFlow<UiState<SearchResponse>?> = _results.asStateFlow()

    fun setQuery(q: String) { _query.value = q }

    /** 切换类型;非空 query 时自动重发请求,空 query 时仅清空结果。 */
    fun setType(t: String) {
        if (_type.value == t) return
        _type.value = t
        if (_query.value.trim().isNotEmpty()) run() else _results.value = null
    }

    fun run() {
        val q = _query.value.trim()
        if (q.isEmpty()) { _results.value = null; return }
        _results.value = UiState.Loading
        viewModelScope.launch {
            _results.value = UiState.fromResult(repo.search(q, type = _type.value))
        }
    }
}

/**
 * 歌单分类入口:加载各音源支持的分类标签(按 group 分组)。
 */
class PlaylistCategoriesViewModel(private val repo: MusicRepository = MusicRepository()) : ViewModel() {
    private val _state = MutableStateFlow<UiState<PlaylistCategoriesResponse>>(UiState.Loading)
    val state: StateFlow<UiState<PlaylistCategoriesResponse>> = _state.asStateFlow()

    /** 当前选中的音源 tab(空串=自动取第一个非空 tab) */
    private val _selectedSource = MutableStateFlow("")
    val selectedSource: StateFlow<String> = _selectedSource.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val result = repo.playlistCategories()
            _state.value = UiState.fromResult(result)
            // 默认选第一个有 group 的源
            if (_selectedSource.value.isBlank()) {
                result.getOrNull()?.tabs?.firstOrNull { it.groups.isNotEmpty() }?.let {
                    _selectedSource.value = it.source
                }
            }
        }
    }

    fun selectSource(source: String) { _selectedSource.value = source }
}

/**
 * 某一分类下的歌单列表(单页 limit=120,够展示)。
 */
class CategoryPlaylistsViewModel(private val repo: MusicRepository = MusicRepository()) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<Playlist>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Playlist>>> = _state.asStateFlow()

    fun load(source: String, categoryId: String) {
        viewModelScope.launch {
            _state.value = UiState.Loading
            val result = repo.categoryPlaylists(source, categoryId).map { it.playlists }
            _state.value = UiState.fromResult(result)
        }
    }
}

/**
 * "我的" Tab 的聚合 ViewModel:同时拉收藏 / 歌单 / 本地 / 最近,每个区块单独 StateFlow
 */
class MineViewModel(private val repo: MusicRepository = MusicRepository()) : ViewModel() {
    private val _favorites = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val favorites: StateFlow<UiState<List<Song>>> = _favorites.asStateFlow()

    private val _collections = MutableStateFlow<UiState<List<MusicCollection>>>(UiState.Loading)
    val collections: StateFlow<UiState<List<MusicCollection>>> = _collections.asStateFlow()

    private val _local = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val local: StateFlow<UiState<List<Song>>> = _local.asStateFlow()

    private val _recent = MutableStateFlow<UiState<List<RecentPlay>>>(UiState.Loading)
    val recent: StateFlow<UiState<List<RecentPlay>>> = _recent.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _favorites.value = UiState.fromResult(repo.favorites().map { it.songs })
        }
        viewModelScope.launch {
            _collections.value = UiState.fromResult(repo.collections())
        }
        viewModelScope.launch {
            _local.value = UiState.fromResult(repo.localMusic())
        }
        viewModelScope.launch {
            _recent.value = UiState.fromResult(repo.recent(120))
        }
    }
}
