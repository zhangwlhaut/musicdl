package com.musicdl.car.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicdl.car.data.MusicRepository
import com.musicdl.car.data.dto.QrLoginResult
import com.musicdl.car.data.dto.QrLoginSession
import com.musicdl.car.data.dto.QrLoginStatus
import com.musicdl.car.data.dto.RecommendResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 处理设置页的二维码登录流程 + 已保存 cookie 状态。
 *
 * 用法:
 *   - [startLogin] 选定一个 source(netease / qq / kugou) → 创建 session,自动开始轮询
 *   - [cancel] 关闭对话框时清理轮询协程
 *   - [refreshCookies] 拉一次后端 /cookies,UI 展示各源是否已登录
 */
class SettingsViewModel(
    private val repo: MusicRepository = MusicRepository(),
) : ViewModel() {

    sealed interface QrUiState {
        data object Idle : QrUiState
        data class Loading(val source: String) : QrUiState
        data class Active(
            val source: String,
            val session: QrLoginSession,
            val result: QrLoginResult?,
        ) : QrUiState
        data class Finished(
            val source: String,
            val result: QrLoginResult,
        ) : QrUiState
        data class Error(val source: String, val message: String) : QrUiState
    }

    private val _qr = MutableStateFlow<QrUiState>(QrUiState.Idle)
    val qr: StateFlow<QrUiState> = _qr.asStateFlow()

    /** map: source -> 已保存 cookie 字符串(若有)。空表示未登录。 */
    private val _cookies = MutableStateFlow<Map<String, String>>(emptyMap())
    val cookies: StateFlow<Map<String, String>> = _cookies.asStateFlow()

    sealed interface UserPlaylistsUiState {
        data object Idle : UserPlaylistsUiState
        data object Loading : UserPlaylistsUiState
        data class Success(val data: RecommendResponse) : UserPlaylistsUiState
        data class Error(val message: String) : UserPlaylistsUiState
    }

    /** "我的在线歌单"列表(三源汇总)。登录成功后会自动刷新一次。 */
    private val _userPlaylists = MutableStateFlow<UserPlaylistsUiState>(UserPlaylistsUiState.Idle)
    val userPlaylists: StateFlow<UserPlaylistsUiState> = _userPlaylists.asStateFlow()

    sealed interface ImportResult {
        data class Created(val name: String) : ImportResult
        data class Duplicate(val name: String) : ImportResult
        data class Failed(val message: String) : ImportResult
    }

    /** 单次导入结果事件,UI 消费后调 [consumeImportResult] 清掉。 */
    private val _lastImportResult = MutableStateFlow<ImportResult?>(null)
    val lastImportResult: StateFlow<ImportResult?> = _lastImportResult.asStateFlow()

    /** 正在导入的歌单 key="${source}|${id}",用于按钮 disable + 转圈。 */
    private val _importing = MutableStateFlow<Set<String>>(emptySet())
    val importing: StateFlow<Set<String>> = _importing.asStateFlow()

    private var pollJob: Job? = null

    fun refreshCookies() {
        viewModelScope.launch {
            repo.cookies().onSuccess { _cookies.value = it }
        }
    }

    /**
     * 拉取「我的在线歌单」。
     * 先确保 cookies 已就绪(如果还是空 map,先拉一次),再根据已登录的源拉对应歌单。
     * 这样避免「首次进入设置页时 cookies 还没回来,导致 loggedIn 为空 → Success(空) → 白屏」的问题。
     */
    fun refreshUserPlaylists() {
        _userPlaylists.value = UserPlaylistsUiState.Loading
        viewModelScope.launch {
            // cookies 为空 → 先同步拉一次再判断;不为空就直接用现成的
            if (_cookies.value.isEmpty()) {
                repo.cookies().onSuccess { _cookies.value = it }
            }
            val loggedIn = _cookies.value.filter { it.value.isNotBlank() }.keys
                .filter { it in SUPPORTED_USER_PLAYLIST_SOURCES }
            if (loggedIn.isEmpty()) {
                _userPlaylists.value = UserPlaylistsUiState.Success(RecommendResponse())
                return@launch
            }
            repo.userPlaylists(loggedIn.toList())
                .onSuccess { _userPlaylists.value = UserPlaylistsUiState.Success(it) }
                .onFailure { _userPlaylists.value = UserPlaylistsUiState.Error(it.message ?: "加载失败") }
        }
    }

    /**
     * 如果歌单列表还没有加载成功(Idle/Loading/Error/空 Success),触发一次刷新;
     * 已经有数据则保持现状,避免每次切回设置页都白屏闪烁。
     */
    fun ensureUserPlaylistsLoaded() {
        val s = _userPlaylists.value
        val hasData = s is UserPlaylistsUiState.Success && s.data.tabs.any { it.safePlaylists.isNotEmpty() }
        if (!hasData) refreshUserPlaylists()
    }

    /** 把某个在线歌单导入为本地集合。后端按 source+external_id 去重。 */
    fun importPlaylist(
        source: String, externalId: String, name: String,
        cover: String? = null, creator: String? = null, trackCount: Int = 0,
    ) {
        val k = "$source|$externalId"
        if (k in _importing.value) return
        _importing.value = _importing.value + k
        viewModelScope.launch {
            repo.importRemotePlaylist(
                source = source, externalId = externalId, name = name,
                cover = cover, creator = creator, trackCount = trackCount,
            ).onSuccess { resp ->
                _lastImportResult.value = if (resp.duplicate)
                    ImportResult.Duplicate(resp.name.ifBlank { name })
                else ImportResult.Created(resp.name.ifBlank { name })
            }.onFailure {
                _lastImportResult.value = ImportResult.Failed(it.message ?: "导入失败")
            }
            _importing.value = _importing.value - k
        }
    }

    fun consumeImportResult() { _lastImportResult.value = null }

    /** 选定 source,创建二维码,并开始轮询。重复调用会先取消上一轮。 */
    fun startLogin(source: String) {
        cancel()
        _qr.value = QrUiState.Loading(source)
        viewModelScope.launch {
            repo.createQrLogin(source).onSuccess { session ->
                if (session.key.isBlank()) {
                    _qr.value = QrUiState.Error(source, "服务端未返回 key")
                    return@onSuccess
                }
                _qr.value = QrUiState.Active(source, session, null)
                pollJob = launch { poll(source, session) }
            }.onFailure { err ->
                _qr.value = QrUiState.Error(source, err.message ?: "创建二维码失败")
            }
        }
    }

    /** 关闭对话框 / 切换 source 时调用,停止轮询。 */
    fun cancel() {
        pollJob?.cancel()
        pollJob = null
        _qr.value = QrUiState.Idle
    }

    private suspend fun poll(source: String, session: QrLoginSession) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val r = repo.pollQrLogin(source, session.key).getOrNull()
            if (r == null) {
                // 网络抖动,等一会儿重试
                delay(POLL_INTERVAL_MS)
                continue
            }
            val current = _qr.value
            if (current is QrUiState.Active && current.session.key == session.key) {
                _qr.value = current.copy(result = r)
            }
            if (r.isFinal) {
                _qr.value = QrUiState.Finished(source, r)
                if (r.status == QrLoginStatus.SUCCESS) {
                    refreshCookies()
                    // 登录成功后,后端立刻能列出该源的歌单——主动刷一次
                    refreshUserPlaylists()
                }
                return
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }

    companion object {
        const val POLL_INTERVAL_MS = 2200L
        val SUPPORTED_USER_PLAYLIST_SOURCES = setOf("netease", "qq", "kugou")
    }
}
