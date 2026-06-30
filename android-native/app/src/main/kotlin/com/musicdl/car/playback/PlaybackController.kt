package com.musicdl.car.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.musicdl.car.data.MusicRepository
import com.musicdl.car.data.dto.Song
import com.musicdl.car.ui.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI-side handle on the playback service.
 *
 * 设计要点:
 * - **写入路径(setMediaItems/addMediaItems/play/seek 等)直接用同进程的
 *   [PlaybackService.player]**,完全绕开 [MediaController] 的 Binder IPC——
 *   这避免了大列表 setMediaItems 时单次 Parcel 超过 1MB 触发
 *   TransactionTooLargeException 导致整批操作静默失败。
 * - **读取路径(状态监听 / 外部控制路由)仍走 MediaController**,这样
 *   通知栏 / 蓝牙按键 / 车机方向盘的指令仍由 MediaSession 接收并转给 ExoPlayer。
 */
class PlaybackController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo = MusicRepository()

    private var controller: MediaController? = null

    /** 同进程直接访问 ExoPlayer,绕开 IPC。Service 还没启动时为 null。 */
    private val player: Player? get() = PlaybackService.player ?: controller

    /** 每个歌曲已尝试过自动切源的音源集合 (songKey -> Set of sources) */
    private val switchedSourcesPerSong = mutableMapOf<String, MutableSet<String>>()

    /** 当前正在播放的完整歌单(用于预解析后续歌曲) */
    private var currentPlaylist: List<Song> = emptyList()

    /** 预解析 Job，切换歌单时取消旧的 */
    private var preloadJob: kotlinx.coroutines.Job? = null

    private val songListAdapter by lazy {
        com.musicdl.car.data.ApiClient.moshi.adapter<List<Song>>(
            com.squareup.moshi.Types.newParameterizedType(List::class.java, Song::class.java)
        )
    }

    /** 连续播放失败计数，达到上限停止播放，避免无网时无限切歌 */
    private var consecutiveFailureCount = 0

    /** 标记是否为播放失败触发的自动切歌，防止在 transition 中重置失败计数 */
    private var isProgrammaticSkip = false

    private companion object {
        /** controller(IPC)路径首批塞入的歌曲数:保证起播立刻有歌。 */
        const val FIRST_CHUNK = 50
        /** controller(IPC)路径后续 addMediaItems 每批数量。 */
        const val APPEND_CHUNK = 80

        /** 把 Media3 的英文错误码翻译成给用户看的中文提示。 */
        fun describeError(error: PlaybackException): String = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接超时"
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "服务器返回数据格式错误"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "服务器返回错误(${(error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode ?: "?"})"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "音频文件不存在"
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有访问音频的权限"
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "明文 HTTP 被禁止"
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "音频读取越界"
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "网络/读取错误"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "音频格式不支持或已损坏"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "解码器初始化失败"
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "解码失败,格式不支持"
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "音频输出设备异常"
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> "版权保护错误"
            PlaybackException.ERROR_CODE_TIMEOUT -> "操作超时"
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "已落后于直播窗口"
            PlaybackException.ERROR_CODE_REMOTE_ERROR -> "远端播放服务出错"
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> "播放器内部状态异常"
            else -> "未知错误(${error.errorCodeName})"
        }
    }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentMediaId = MutableStateFlow<String?>(null)
    val currentMediaId: StateFlow<String?> = _currentMediaId.asStateFlow()

    private val _currentTitle = MutableStateFlow<String?>(null)
    val currentTitle: StateFlow<String?> = _currentTitle.asStateFlow()

    private val _currentArtist = MutableStateFlow<String?>(null)
    val currentArtist: StateFlow<String?> = _currentArtist.asStateFlow()

    private val _currentArtworkUri = MutableStateFlow<String?>(null)
    val currentArtworkUri: StateFlow<String?> = _currentArtworkUri.asStateFlow()

    private val _currentAlbum = MutableStateFlow<String?>(null)
    val currentAlbum: StateFlow<String?> = _currentAlbum.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    /** [Player.REPEAT_MODE_OFF] / [Player.REPEAT_MODE_ONE] / [Player.REPEAT_MODE_ALL] */
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _playlistQueue = MutableStateFlow<List<Song>>(emptyList())
    val playlistQueue: StateFlow<List<Song>> = _playlistQueue.asStateFlow()

    fun connect() {
        if (controller != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            controller = future.get()
            // 只给 controller 挂监听，避免同进程下事件重复回调两次
            controller?.addListener(playerListener)
            refreshFromController()
            startPositionPump()
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        controller?.release()
        controller = null
    }

    fun isConnected(): Boolean = PlaybackService.player != null || controller != null

    /**
     * 应用启动时调用:若队列为空,自动拉取最近播放并起播,实现"打开即续播"。
     * - 进程被杀重新打开时,PlaybackService 也已重启,player.mediaItemCount == 0
     * - 进程仍存活(Service 也在)时,player 已有队列 → 直接跳过,不打断当前播放
     */
    private data class RestoredState(
        val songs: List<Song>,
        val index: Int,
        val shuffleEnabled: Boolean,
        val repeatMode: Int
    )

    fun autoResume() {
        scope.launch {
            // 等 PlaybackService 起来(通常 MainActivity 已经触发 connect)。
            var waited = 0
            while (PlaybackService.player == null && waited < 3000) {
                delay(100); waited += 100
            }
            val p = PlaybackService.player ?: return@launch
            if (p.mediaItemCount > 0) return@launch

            // 优先尝试从本地持久化中恢复上次播放的歌单和模式
            val restored = withContext(Dispatchers.IO) {
                try {
                    val prefs = appContext.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
                    val json = prefs.getString("last_playlist_json", null)
                    val index = prefs.getInt("last_played_index", 0)
                    val shuffleEnabled = prefs.getBoolean("last_shuffle_enabled", false)
                    val repeatMode = prefs.getInt("last_repeat_mode", Player.REPEAT_MODE_OFF)
                    if (!json.isNullOrEmpty()) {
                        val songs = songListAdapter.fromJson(json)
                        if (!songs.isNullOrEmpty()) {
                            RestoredState(songs, index, shuffleEnabled, repeatMode)
                        } else null
                    } else null
                } catch (e: Exception) {
                    android.util.Log.e("PlaybackController", "Failed to restore playback state", e)
                    null
                }
            }

            if (restored != null) {
                val safeStart = restored.index.coerceIn(0, restored.songs.lastIndex)
                android.util.Log.d(
                    "PlaybackController",
                    "autoResume: restoring playlist of size=${restored.songs.size}, index=$safeStart, shuffle=${restored.shuffleEnabled}, repeat=${restored.repeatMode}"
                )
                p.shuffleModeEnabled = restored.shuffleEnabled
                p.repeatMode = restored.repeatMode
                p.setMediaItems(restored.songs.map { it.toMediaItem() }, safeStart, 0L)
                p.prepare()
                p.play()
                
                // 更新内部 flow 状态，使得 UI 立即更新
                _shuffleEnabled.value = restored.shuffleEnabled
                _repeatMode.value = restored.repeatMode
                
                Toaster.show("已自动恢复播放")
                return@launch
            }

            // 降级：拉取最近播放历史
            val recentResult = withContext(Dispatchers.IO) { repo.recent(60) }
            recentResult.onSuccess { list ->
                if (list.isEmpty()) return@onSuccess
                val songs = list.map { it.toSong() }
                p.setMediaItems(songs.map { it.toMediaItem() }, 0, 0L)
                p.prepare()
                // 不自动 play(),只准备好——用户按播放或迷你播放器才开始,避免打开 App 突然出声。
                Toaster.show("已恢复最近播放列表，按播放继续")
            }
        }
    }

    /**
     * 起播一个列表。优先用同进程 ExoPlayer(无 IPC 限制);
     * 同进程不可用时回退到 MediaController(走 Binder IPC,大列表必须分批)。
     */
    fun playNow(songs: List<Song>, startIndex: Int = 0) {
        consecutiveFailureCount = 0
        switchedSourcesPerSong.clear()
        StreamUrlCache.clear()
        if (songs.isEmpty()) {
            Toaster.show("没有可播放的歌曲")
            return
        }
        // 保存歌单并启动预解析(提前确认后续歌曲的可用音源和 CDN 直链)
        currentPlaylist = songs
        val p = PlaybackService.player
        if (p != null) {
            val safeStart = startIndex.coerceIn(0, songs.lastIndex)
            p.setMediaItems(songs.map { it.toMediaItem() }, safeStart, 0L)
            p.prepare()
            p.play()
            // 起播后立即预解析后续歌曲的真实 CDN URL(亮屏时网络可用)
            preResolveUpcomingUrls(songs, startIndex, p)
            return
        }
        val c = controller ?: run {
            Toaster.show("播放器尚未就绪，请稍后重试")
            return
        }
        // controller 路径走 Binder IPC,单次 Parcel 超过 ~1MB 会抛
        // TransactionTooLargeException,因此必须分批塞。
        playNowChunkedViaController(c, songs, startIndex)
    }

    /**
     * 预解析即将播放的歌曲的真实 CDN URL。
     *
     * 在亮屏/网络可用时,提前逐个调用后端解析歌曲的真实音频直链,写入
     * [StreamUrlCache]。这样息屏后切歌时 [Song.toMediaItem] 可以直接
     * 使用缓存的 CDN URL(ExoPlayer 直连 CDN),避免走 Go proxy 触发
     * 新的外网 TCP 连接被 Xiaomi 息屏限制拦截。
     *
     * 如果主音源不可用(`/music/inspect 失败`),则自动调用
     * `/music/switch_source` 寻找替代音源并缓存。
     */
    private fun preResolveUpcomingUrls(songs: List<Song>, startIndex: Int, exoPlayer: Player) {
        preloadJob?.cancel()
        preloadJob = scope.launch {
            val start = (startIndex + 1).coerceAtMost(songs.lastIndex)
            val end = (start + 10).coerceAtMost(songs.size) // 预解析后续 10 首
            for (i in start until end) {
                val song = songs[i]
                val key = buildMediaId(song.id, song.source)
                if (StreamUrlCache.getDirectUrl(song) != null) continue // 已缓存

                // 1) 解析真实 CDN URL
                val cdnUrl = withContext(Dispatchers.IO) { resolveCdnUrl(song) }
                if (cdnUrl != null) {
                    StreamUrlCache.putDirectUrl(song, cdnUrl)
                    // 替换 ExoPlayer 队列中的 mediaItem 为直链版本
                    replaceMediaItemCached(exoPlayer, i, song)
                    continue
                }

                // 2) 主音源不可用 → 尝试自动换源
                android.util.Log.d("PlaybackController", "preResolve: primary source failed for '${song.name}', trying switch_source")
                val replacement = withContext(Dispatchers.IO) { repo.switchSource(song) }
                if (replacement != null) {
                    StreamUrlCache.recordSwitchResult(key, replacement)
                    // 同时缓存替代歌的 CDN URL
                    val altUrl = withContext(Dispatchers.IO) { resolveCdnUrl(replacement) }
                    if (altUrl != null) {
                        StreamUrlCache.putDirectUrl(replacement, altUrl)
                    }
                    replaceMediaItemCached(exoPlayer, i, replacement)
                    android.util.Log.d("PlaybackController", "preResolve: switched '${song.name}' to source=${replacement.source}")
                }
            }
        }
    }

    /** 向 Go Server 的 /music/inspect 查询真实 CDN URL */
    private fun resolveCdnUrl(song: Song): String? {
        return try {
            val url = java.net.URL(
                "http://127.0.0.1:37777/music/inspect" +
                "?id=${java.net.URLEncoder.encode(song.id, "UTF-8")}" +
                "&source=${java.net.URLEncoder.encode(song.source, "UTF-8")}" +
                "&duration=${song.duration}"
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            // 解析 JSON: {"url":"https://...","valid":true,...}
            val obj = org.json.JSONObject(json)
            if (obj.optBoolean("valid", false)) {
                obj.optString("url", null)
            } else null
        } catch (e: Exception) {
            android.util.Log.w("PlaybackController", "resolveCdnUrl failed for '${song.name}'", e)
            null
        }
    }

    /** 将 ExoPlayer 队列中指定位置的 mediaItem 替换为预解析后的版本 */
    private fun replaceMediaItemCached(exoPlayer: Player, index: Int, song: Song) {
        try {
            if (index in 0 until exoPlayer.mediaItemCount) {
                exoPlayer.replaceMediaItem(index, song.toMediaItem())
            }
        } catch (e: Exception) {
            android.util.Log.w("PlaybackController", "replaceMediaItemCached failed at $index", e)
        }
    }

    /** 顺序播放整个列表(从头开始,关闭 shuffle)。 */
    fun playAll(songs: List<Song>) {
        if (songs.isEmpty()) {
            Toaster.show("歌单为空,无法播放")
            return
        }
        val p = PlaybackService.player ?: controller ?: run {
            Toaster.show("播放器尚未就绪")
            return
        }
        p.shuffleModeEnabled = false
        _shuffleEnabled.value = false
        playNow(songs, 0)
    }

    /** 随机播放整个列表(开启 shuffle,从随机位置开始)。 */
    fun playShuffled(songs: List<Song>) {
        if (songs.isEmpty()) {
            Toaster.show("歌单为空,无法随机播放")
            return
        }
        val p = PlaybackService.player ?: controller ?: run {
            Toaster.show("播放器尚未就绪")
            return
        }
        p.shuffleModeEnabled = true
        _shuffleEnabled.value = true
        val start = (0 until songs.size).random()
        playNow(songs, start)
    }

    /**
     * 通过 MediaController(IPC)分批起播。首批包含 startIndex 周围 [FIRST_CHUNK]
     * 首,使 setMediaItems 立即可起播;前后剩余歌曲按真实顺序分批 addMediaItems。
     */
    private fun playNowChunkedViaController(c: MediaController, songs: List<Song>, startIndex: Int) {
        val safeStart = startIndex.coerceIn(0, songs.lastIndex)
        val firstFrom = safeStart.coerceAtMost(maxOf(0, songs.size - FIRST_CHUNK))
        val firstTo = (firstFrom + FIRST_CHUNK).coerceAtMost(songs.size)
        val firstBatch = songs.subList(firstFrom, firstTo).map { it.toMediaItem() }
        val localStart = safeStart - firstFrom

        c.setMediaItems(firstBatch, localStart, 0L)
        c.prepare()
        c.play()

        if (firstFrom > 0) {
            val prefix = songs.subList(0, firstFrom).map { it.toMediaItem() }
            prefix.chunked(APPEND_CHUNK).forEachIndexed { idx, batch ->
                c.addMediaItems(idx * APPEND_CHUNK, batch)
            }
        }
        if (firstTo < songs.size) {
            val suffix = songs.subList(firstTo, songs.size).map { it.toMediaItem() }
            suffix.chunked(APPEND_CHUNK).forEach { batch -> c.addMediaItems(batch) }
        }
    }

    fun toggleShuffle() {
        val p = player ?: return
        val next = !p.shuffleModeEnabled
        p.shuffleModeEnabled = next
        _shuffleEnabled.value = next
    }

    /** 循环模式三态轮换:OFF → ALL → ONE → OFF */
    fun cycleRepeatMode() {
        val p = player ?: return
        val next = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        p.repeatMode = next
        _repeatMode.value = next
    }

    fun playQueue(songs: List<Song>) = playNow(songs, 0)

    fun playQueueIndex(index: Int) {
        val p = player ?: return
        if (index in 0 until p.mediaItemCount) {
            p.seekTo(index, 0L)
            p.prepare()
            p.play()
        }
    }

    fun addToQueue(songs: List<Song>) {
        val p = PlaybackService.player ?: controller ?: return
        p.addMediaItems(songs.map { it.toMediaItem() })
        if (p.playbackState == Player.STATE_IDLE) {
            p.prepare()
        }
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun next() {
        consecutiveFailureCount = 0
        player?.seekToNext()
    }
    fun previous() {
        consecutiveFailureCount = 0
        player?.seekToPrevious()
    }
    fun seekTo(ms: Long) {
        consecutiveFailureCount = 0
        player?.seekTo(ms)
    }

    // --- listeners ---

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                consecutiveFailureCount = 0
            }
            PlaybackLogger.log("onIsPlayingChanged: isPlaying=$isPlaying, playWhenReady=${player?.playWhenReady}, playbackState=${player?.playbackState}")
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateStr = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            PlaybackLogger.log("onPlaybackStateChanged: state=$stateStr, isPlaying=${player?.isPlaying}")
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val reasonStr = when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
                else -> "UNKNOWN"
            }
            PlaybackLogger.log("onPlayWhenReadyChanged: playWhenReady=$playWhenReady, reason=$reasonStr")
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshFromController()
            val reasonStr = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                else -> "UNKNOWN"
            }
            PlaybackLogger.log("onMediaItemTransition: mediaId=${mediaItem?.mediaId}, title=${mediaItem?.mediaMetadata?.title}, reason=$reasonStr")
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || 
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                if (!isProgrammaticSkip) {
                    consecutiveFailureCount = 0
                }
            }
            isProgrammaticSkip = false
            savePlaybackState()
        }
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = refreshFromController()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
            savePlaybackState()
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
            savePlaybackState()
        }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            refreshFromController()
            savePlaybackState()
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            PlaybackLogger.logError("onPlayerError: errorCodeName=${error.errorCodeName}, errorCode=${error.errorCode}, message=${error.message}", error)
            tryAutoSwitchSource(error)
        }
    }

    private fun getSongKey(mediaItem: MediaItem): String {
        val md = mediaItem.mediaMetadata
        val title = md.title?.toString()?.trim()?.lowercase().orEmpty()
        val artist = md.artist?.toString()?.trim()?.lowercase().orEmpty()
        if (title.isEmpty()) {
            return mediaItem.mediaId
        }
        return "$title|$artist"
    }

    private fun skipToNextOrStop() {
        val p = player ?: return
        consecutiveFailureCount++
        android.util.Log.d("PlaybackController", "skipToNextOrStop: consecutiveFailureCount=$consecutiveFailureCount, hasNext=${p.hasNextMediaItem()}")
        if (consecutiveFailureCount >= 5) {
            Toaster.long("连续播放失败次数过多，已停止播放")
            android.util.Log.w("PlaybackController", "skipToNextOrStop: reached consecutive failure limit, stopping player")
            consecutiveFailureCount = 0
            p.stop()
            return
        }

        if (p.hasNextMediaItem()) {
            isProgrammaticSkip = true
            p.seekToNext()
            p.prepare()
            p.play()
        } else {
            Toaster.show("播放列表已结束")
            android.util.Log.d("PlaybackController", "skipToNextOrStop: no next media item, stopping player")
            p.stop()
        }
    }

    /**
     * 参考前端 `autoSwitchInvalidSources` 的思路:播放失败时调用后端
     * `/music/switch_source` 找一个跨源的最佳替代,replace 当前 mediaItem 后继续播放。
     * 每个 mediaId 只切换一次,防止替代依然失败时陷入循环。
     */
    private fun tryAutoSwitchSource(error: androidx.media3.common.PlaybackException) {
        val errMsg = describeError(error)
        val p = player ?: run {
            android.util.Log.w("PlaybackController", "tryAutoSwitchSource: player is null")
            Toaster.long("播放失败:$errMsg")
            return
        }
        val failingItem = p.currentMediaItem ?: run {
            android.util.Log.w("PlaybackController", "tryAutoSwitchSource: currentMediaItem is null")
            Toaster.long("播放失败:$errMsg")
            return
        }
        val failingMediaId = failingItem.mediaId
        val songKey = getSongKey(failingItem)
        val parsed = parseMediaId(failingMediaId)
        
        android.util.Log.d("PlaybackController", "tryAutoSwitchSource: failingMediaId=$failingMediaId, songKey=$songKey")
        
        if (parsed == null) {
            android.util.Log.w("PlaybackController", "tryAutoSwitchSource: parsed mediaId is null, skipping")
            val title = failingItem.mediaMetadata.title?.toString().orEmpty()
            val prefix = if (title.isNotBlank()) "「$title」" else "歌曲"
            Toaster.long("${prefix}播放失败:$errMsg")
            skipToNextOrStop()
            return
        }

        val failingSource = parsed.second
        val triedSources = switchedSourcesPerSong.getOrPut(songKey) { mutableSetOf() }
        triedSources.add(failingSource)
        android.util.Log.d("PlaybackController", "tryAutoSwitchSource: added failingSource=$failingSource to triedSources=$triedSources")

        val md = failingItem.mediaMetadata
        val origSong = Song(
            id = parsed.first,
            source = failingSource,
            name = md.title?.toString() ?: "",
            artist = md.artist?.toString(),
            album = md.albumTitle?.toString(),
            cover = md.artworkUri?.toString(),
            duration = (p.duration.takeIf { it > 0 } ?: 0L).toInt() / 1000,
        )
        val title = origSong.name.ifBlank { "当前歌曲" }
        Toaster.show("「$title」播放失败,正在尝试切换音源…")

        val failingIndex = p.currentMediaItemIndex
        scope.launch {
            android.util.Log.d("PlaybackController", "switchSource: requesting alternative for $origSong")

            // 优先检查缓存：之前预解析过该歌曲的可用音源
            val cachedSource = StreamUrlCache.getWorkingSource(origSong)
            if (cachedSource != null && !triedSources.contains(cachedSource)) {
                android.util.Log.d("PlaybackController", "switchSource: using cached working source=$cachedSource")
                val cachedSong = origSong.copy(source = cachedSource)
                applyReplacement(curPlayer = p, failingIndex = failingIndex,
                    failingMediaId = failingMediaId, replacement = cachedSong)
                return@launch
            }

            val replacement = withContext(Dispatchers.IO) { repo.switchSource(origSong) }
            val curPlayer = player
            if (curPlayer == null) {
                android.util.Log.w("PlaybackController", "switchSource: player became null during async call")
                return@launch
            }
            android.util.Log.d("PlaybackController", "switchSource: received replacement=$replacement")
            if (replacement == null || triedSources.contains(replacement.source)) {
                android.util.Log.w("PlaybackController", "switchSource: replacement is null or already tried, skipping to next song")
                Toaster.long("「$title」所有可用音源均切换失败")
                skipToNextOrStop()
                return@launch
            }
            // Check if the current item at failingIndex has changed before replacing
            if (failingIndex >= curPlayer.mediaItemCount || curPlayer.getMediaItemAt(failingIndex).mediaId != failingMediaId) {
                android.util.Log.w("PlaybackController", "switchSource: index/mediaId changed, aborting replacement. index=$failingIndex, expected=$failingMediaId")
                return@launch
            }
            // 缓存切换结果，避免息屏后重复调用 switch_source
            // 用 buildMediaId 格式保持一致,StreamUrlCache.keyOf 也使用同一格式
            val originalIdKey = buildMediaId(parsed.first, failingSource)
            StreamUrlCache.recordSwitchResult(originalIdKey, replacement)
            applyReplacement(curPlayer, failingIndex, failingMediaId, replacement)
        }
    }

    /**
     * 将 ExoPlayer 队列中失败位置的歌曲替换为替代音源并续播。
     * 同时将替代歌的 CDN URL 缓存起来，避免息屏后切歌再次触发外网请求。
     */
    private fun applyReplacement(curPlayer: Player, failingIndex: Int, failingMediaId: String, replacement: Song) {
        // 尝试缓存替代歌的 CDN 直链(后台异步,不阻塞播放)
        scope.launch {
            val cdnUrl = withContext(Dispatchers.IO) { resolveCdnUrl(replacement) }
            if (cdnUrl != null) {
                StreamUrlCache.putDirectUrl(replacement, cdnUrl)
            }
        }
        android.util.Log.d("PlaybackController", "switchSource: replacing item at $failingIndex with ${replacement.source} (${replacement.id})")
        curPlayer.replaceMediaItem(failingIndex, replacement.toMediaItem())
        curPlayer.seekTo(failingIndex, 0L)
        curPlayer.prepare()
        curPlayer.play()
        Toaster.show("已切换到「${replacement.source}」")
    }

    private fun refreshFromController() {
        val p = player ?: return
        _isPlaying.value = p.isPlaying
        _currentMediaId.value = p.currentMediaItem?.mediaId
        val md = p.currentMediaItem?.mediaMetadata
        _currentTitle.value = md?.title?.toString()
        _currentArtist.value = md?.artist?.toString()
        _currentArtworkUri.value = md?.artworkUri?.toString()
        _currentAlbum.value = md?.albumTitle?.toString()
        _durationMs.value = p.duration.coerceAtLeast(0)
        _shuffleEnabled.value = p.shuffleModeEnabled
        _repeatMode.value = p.repeatMode

        // 更新当前播放队列状态
        val count = p.mediaItemCount
        val list = ArrayList<Song>(count)
        for (i in 0 until count) {
            val item = p.getMediaItemAt(i)
            val parsed = parseMediaId(item.mediaId)
            val id = parsed?.first ?: item.mediaId
            val source = parsed?.second ?: ""
            val md = item.mediaMetadata
            list.add(
                Song(
                    id = id,
                    source = source,
                    name = md.title?.toString() ?: "未知歌曲",
                    artist = md.artist?.toString() ?: "未知歌手",
                    album = md.albumTitle?.toString(),
                    cover = md.artworkUri?.toString()
                )
            )
        }
        _playlistQueue.value = list
    }

    private fun startPositionPump() {
        scope.launch {
            while (true) {
                player?.let { p ->
                    _positionMs.value = p.currentPosition.coerceAtLeast(0)
                    if (_durationMs.value <= 0L) {
                        _durationMs.value = p.duration.coerceAtLeast(0)
                    }
                }
                delay(500)
            }
        }
    }

    private fun savePlaybackState() {
        val p = player ?: return
        val count = p.mediaItemCount
        if (count == 0) return
        val songs = ArrayList<Song>(count)
        for (i in 0 until count) {
            val item = p.getMediaItemAt(i)
            val parsed = parseMediaId(item.mediaId)
            val id = parsed?.first ?: item.mediaId
            val source = parsed?.second ?: ""
            val md = item.mediaMetadata
            songs.add(
                Song(
                    id = id,
                    source = source,
                    name = md.title?.toString() ?: "",
                    artist = md.artist?.toString(),
                    album = md.albumTitle?.toString(),
                    cover = md.artworkUri?.toString()
                )
            )
        }
        val currentIdx = p.currentMediaItemIndex
        val shuffleEnabled = p.shuffleModeEnabled
        val repeatMode = p.repeatMode

        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val json = songListAdapter.toJson(songs)
                    val prefs = appContext.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("last_playlist_json", json)
                        .putInt("last_played_index", currentIdx)
                        .putBoolean("last_shuffle_enabled", shuffleEnabled)
                        .putInt("last_repeat_mode", repeatMode)
                        .apply()
                } catch (e: Exception) {
                    android.util.Log.e("PlaybackController", "Failed to save playback state", e)
                }
            }
        }
    }
}
