package com.musicdl.car

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.musicdl.car.data.ServerBootstrap
import com.musicdl.car.data.MusicRepository
import com.musicdl.car.data.dto.Song
import com.musicdl.car.playback.PlaybackController
import com.musicdl.car.playback.parseMediaId
import com.musicdl.car.ui.LocalWindowSpec
import com.musicdl.car.ui.NavRoutes
import com.musicdl.car.ui.WindowSpec
import com.musicdl.car.ui.classifyHeight
import com.musicdl.car.ui.classifyWidth
import com.musicdl.car.ui.component.FullPlayer
import com.musicdl.car.ui.component.MiniPlayer
import com.musicdl.car.ui.component.TopTabBar
import com.musicdl.car.ui.screen.*
import com.musicdl.car.ui.theme.MusicDLTheme
import com.musicdl.car.ui.viewmodel.LyricViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var playback: PlaybackController

    /** API ≤ 29 写 /sdcard/Download 需要 WRITE_EXTERNAL_STORAGE 运行时授权。30+ Download/ 公开可写,跳过。 */
    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — 不阻塞 UI:Go 端创建目录失败时会回退到 app 私有目录 */ }

    private fun ensureDownloadPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return // Android 11+:Download/ 公开可写
        val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestStoragePermission.launch(perm)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 方向跟随设备真实姿态(Manifest 已声明 unspecified)——
        // 不在 onCreate 里再赋值,避免 Activity 创建后二次触发方向切换。

        ensureDownloadPermission()

        com.musicdl.car.ui.Toaster.init(this)

        playback = PlaybackController(this)
        playback.connect()
        playback.autoResume()

        setContent {
            MusicDLTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WithWindowSpec {
                        AppRoot(playback)
                    }
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        android.util.Log.d("MainActivity", "handleIntent: action = ${intent?.action}, data = ${intent?.data}")
        intent?.extras?.let { extras ->
            for (key in extras.keySet()) {
                android.util.Log.d("MainActivity", "  extra: $key -> ${extras.get(key)}")
            }
        }

        val uri = intent?.data
        if (uri != null) {
            val path = uri.path ?: ""
            val scheme = uri.scheme ?: ""

            if (scheme == "orpheus" || scheme == "orpheuswidget") {
                android.util.Log.d("MainActivity", "handleIntent: matched netease scheme")
                var songId: String? = null

                val host = uri.host ?: ""
                if (host == "song" || path.startsWith("/song")) {
                    songId = uri.lastPathSegment
                }
                if (songId.isNullOrBlank() || !songId.all { it.isDigit() }) {
                    songId = uri.getQueryParameter("id") ?: uri.getQueryParameter("musicId")
                }

                android.util.Log.d("MainActivity", "handleIntent: parsed songId = $songId")
                if (!songId.isNullOrBlank() && songId.all { it.isDigit() }) {
                    lifecycleScope.launch {
                        var retry = 0
                        while (!playback.isConnected() && retry < 30) {
                            kotlinx.coroutines.delay(100)
                            retry++
                        }
                        val song = Song(
                            id = songId,
                            source = "netease",
                            name = "语音播放歌曲",
                            artist = "网易云音乐"
                        )
                        playback.playNow(listOf(song), 0)
                        android.util.Log.d("MainActivity", "handleIntent: playing song $songId via playback.playNow")
                    }
                }
                return
            }
        }

        // Check if there is a search query in intent extras or data query parameters
        val query = extractQueryFromIntent(intent)
        if (!query.isNullOrBlank()) {
            android.util.Log.d("MainActivity", "handleIntent: extracted search query = $query")
            lifecycleScope.launch {
                var retry = 0
                while (!playback.isConnected() && retry < 30) {
                    kotlinx.coroutines.delay(100)
                    retry++
                }
                try {
                    val repo = MusicRepository()
                    val result = repo.search(query).getOrNull()
                    val songs = result?.songsSafe
                    if (!songs.isNullOrEmpty()) {
                        playback.playNow(songs, 0)
                        android.util.Log.d("MainActivity", "handleIntent: playing searched song: ${songs[0].name}")
                    } else {
                        android.util.Log.d("MainActivity", "handleIntent: no songs found for query: $query")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error searching and playing from intent", e)
                }
            }
        }
    }

    private fun extractQueryFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        val extras = intent.extras
        
        val queryKeys = arrayOf(
            "query", "keyword", "search_word", "searchKey", "EXTRA_SEARCH_KEY",
            "search_key", "key", "text", "search_text", "EXTRA_SEARCH_WORD",
            "voice_query", "name", "songName", "song_name", "title", "audio_name"
        )
        
        if (extras != null) {
            for (key in queryKeys) {
                val v = extras.getString(key) ?: extras.get(key)?.toString()
                if (!v.isNullOrBlank()) {
                    val artistKeys = arrayOf("artist", "artist_name", "artistName", "singer", "singer_name")
                    var artistVal: String? = null
                    for (aKey in artistKeys) {
                        val a = extras.getString(aKey) ?: extras.get(aKey)?.toString()
                        if (!a.isNullOrBlank()) {
                            artistVal = a
                            break
                        }
                    }
                    return if (!artistVal.isNullOrBlank()) "$v $artistVal" else v
                }
            }
        }
        
        val uri = intent.data
        if (uri != null) {
            for (key in queryKeys) {
                val v = uri.getQueryParameter(key)
                if (!v.isNullOrBlank()) {
                    val artistKeys = arrayOf("artist", "artist_name", "artistName", "singer", "singer_name")
                    var artistVal: String? = null
                    for (aKey in artistKeys) {
                        val a = uri.getQueryParameter(aKey)
                        if (!a.isNullOrBlank()) {
                            artistVal = a
                            break
                        }
                    }
                    return if (!artistVal.isNullOrBlank()) "$v $artistVal" else v
                }
            }
        }
        
        return null
    }

    override fun onDestroy() {
        playback.release()
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(playback: PlaybackController) {
    val serverState by ServerBootstrap.state.collectAsState()

    when (val state = serverState) {
        is ServerBootstrap.State.Starting -> ServerStartingScreen()
        is ServerBootstrap.State.Failed -> ServerErrorScreen(state.error.message ?: "未知错误")
        is ServerBootstrap.State.Ready -> AppContent(playback)
    }
}

@Composable
private fun ServerStartingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "本地服务启动中…",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
        )
    }
}

@Composable
private fun ServerErrorScreen(msg: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("本地服务启动失败", color = MaterialTheme.colorScheme.error, fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppContent(playback: PlaybackController) {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route ?: NavRoutes.HOME

    val reporterScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val repo = remember { MusicRepository() }

    // Whether the full-screen NowPlaying overlay is visible.
    var showFull by rememberSaveable { mutableStateOf(false) }

    // PlaybackController state for MiniPlayer / FullPlayer
    val isPlaying by playback.isPlaying.collectAsState()
    val title by playback.currentTitle.collectAsState()
    val artist by playback.currentArtist.collectAsState()
    val artwork by playback.currentArtworkUri.collectAsState()
    val album by playback.currentAlbum.collectAsState()
    val mediaId by playback.currentMediaId.collectAsState()
    val pos by playback.positionMs.collectAsState()
    val dur by playback.durationMs.collectAsState()
    val shuffleEnabled by playback.shuffleEnabled.collectAsState()
    val repeatMode by playback.repeatMode.collectAsState()
    val playlistQueue by playback.playlistQueue.collectAsState()

    // Lyric ViewModel — driven from PlaybackController.
    val lyricVm: LyricViewModel = viewModel()
    val lyricLines by lyricVm.lines.collectAsState()
    val currentLineIndex by lyricVm.currentLineIndex.collectAsState()

    // Re-fetch lyric whenever the current song changes.
    LaunchedEffect(mediaId) {
        val mid = mediaId
        if (mid == null) {
            lyricVm.onSongChanged(null)
        } else {
            val (id, source) = parseMediaId(mid) ?: (mid to "")
            lyricVm.onSongChanged(
                Song(
                    id = id,
                    source = source,
                    name = title ?: "",
                    artist = artist,
                    album = album,
                    cover = artwork,
                    duration = (dur / 1000).toInt(),
                    extra = null,
                )
            )
        }
    }
    // Update current line index as playback position advances.
    LaunchedEffect(pos) { lyricVm.onPositionChanged(pos) }

    val playSongs: (List<Song>, Int) -> Unit = { songs, index ->
        playback.playNow(songs, index)
        if (index in songs.indices) {
            reporterScope.launch {
                runCatching { repo.reportRecent(songs[index]) }
            }
        }
    }

    val playAll: (List<Song>) -> Unit = { songs ->
        playback.playAll(songs)
        if (songs.isNotEmpty()) {
            reporterScope.launch { runCatching { repo.reportRecent(songs[0]) } }
        }
    }

    val shufflePlay: (List<Song>) -> Unit = { songs ->
        playback.playShuffled(songs)
        // 报告"播放过"——以列表首项为代表,简化处理
        if (songs.isNotEmpty()) {
            reporterScope.launch { runCatching { repo.reportRecent(songs[0]) } }
        }
    }

    val goBack: () -> Unit = {
        if (!navController.popBackStack()) {
            navController.navigate(NavRoutes.HOME) {
                popUpTo(NavRoutes.HOME) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    val navigateTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(NavRoutes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { TopTabBar(currentRoute = currentRoute, onNavigate = navigateTab) },
            bottomBar = {
                MiniPlayer(
                    isPlaying = isPlaying,
                    title = title,
                    artist = artist,
                    artworkUri = artwork,
                    positionMs = pos,
                    durationMs = dur,
                    onPlayPause = playback::togglePlayPause,
                    onNext = playback::next,
                    onPrevious = playback::previous,
                    onExpand = { showFull = true },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            NavHost(
                navController,
                startDestination = NavRoutes.HOME,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                composable(NavRoutes.HOME) {
                    HomeScreen(
                        onPlaySong = playSongs,
                        onOpenCollection = { id -> navController.navigate(NavRoutes.collection(id)) },
                        onOpenRemote = { src, id, name, cover, creator ->
                            navController.navigate(NavRoutes.remotePlaylist(src, id, name, cover, creator))
                        },
                    )
                }
                composable(NavRoutes.DISCOVER) {
                    DiscoverScreen(
                        onOpenRemote = { src, id, name, cover, creator ->
                            navController.navigate(NavRoutes.remotePlaylist(src, id, name, cover, creator))
                        },
                        onOpenCategories = { navController.navigate(NavRoutes.CATEGORIES) },
                    )
                }
                composable(NavRoutes.MINE) {
                    MineScreen(
                        onPlay = playSongs,
                        onOpenCollection = { id -> navController.navigate(NavRoutes.collection(id)) },
                        onNavigateDetail = { which ->
                            when (which) {
                                "favorites" -> navController.navigate(NavRoutes.FAVORITES)
                                "local" -> navController.navigate(NavRoutes.LOCAL)
                                "recent" -> navController.navigate(NavRoutes.RECENT)
                            }
                        },
                    )
                }
                composable(NavRoutes.SEARCH) {
                    SearchScreen(
                        onPlay = playSongs,
                        onOpenRemote = { src, id, name, cover, creator ->
                            navController.navigate(NavRoutes.remotePlaylist(src, id, name, cover, creator))
                        },
                        onOpenAlbum = { src, id, name, cover, creator ->
                            navController.navigate(
                                NavRoutes.remotePlaylist(src, id, name, cover, creator, contentType = "album")
                            )
                        },
                    )
                }
                composable(NavRoutes.SETTINGS) {
                    SettingsScreen()
                }
                composable(NavRoutes.RECENT) {
                    RecentScreen(
                        onPlay = playSongs,
                        onPlayAll = playAll,
                        onShufflePlay = shufflePlay,
                        onBack = goBack,
                    )
                }
                composable(NavRoutes.FAVORITES) {
                    FavoritesScreen(
                        onPlay = playSongs,
                        onPlayAll = playAll,
                        onShufflePlay = shufflePlay,
                        onBack = goBack,
                    )
                }
                composable(NavRoutes.LOCAL) {
                    LocalScreen(
                        onPlay = playSongs,
                        onPlayAll = playAll,
                        onShufflePlay = shufflePlay,
                        onBack = goBack,
                    )
                }
                composable(
                    NavRoutes.COLLECTION,
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    CollectionDetailScreen(
                        collectionId = id,
                        onPlay = playSongs,
                        onPlayAll = playAll,
                        onShufflePlay = shufflePlay,
                        onBack = goBack,
                    )
                }
                composable(
                    NavRoutes.REMOTE_PLAYLIST,
                    arguments = listOf(
                        navArgument("source") { type = NavType.StringType },
                        navArgument("id") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType; defaultValue = "" },
                        navArgument("cover") { type = NavType.StringType; defaultValue = "" },
                        navArgument("creator") { type = NavType.StringType; defaultValue = "" },
                        navArgument("content_type") { type = NavType.StringType; defaultValue = "playlist" },
                    ),
                ) { entry ->
                    val src = java.net.URLDecoder.decode(entry.arguments?.getString("source") ?: "", "UTF-8")
                    val id = java.net.URLDecoder.decode(entry.arguments?.getString("id") ?: "", "UTF-8")
                    val name = java.net.URLDecoder.decode(entry.arguments?.getString("name") ?: "", "UTF-8")
                    val cover = java.net.URLDecoder.decode(entry.arguments?.getString("cover") ?: "", "UTF-8").takeIf { it.isNotBlank() }
                    val creator = java.net.URLDecoder.decode(entry.arguments?.getString("creator") ?: "", "UTF-8").takeIf { it.isNotBlank() }
                    val contentType = java.net.URLDecoder.decode(entry.arguments?.getString("content_type") ?: "playlist", "UTF-8")
                    RemotePlaylistScreen(
                        source = src,
                        id = id,
                        playlistName = name,
                        playlistCover = cover,
                        playlistCreator = creator,
                        onPlay = playSongs,
                        onPlayAll = playAll,
                        onShufflePlay = shufflePlay,
                        onBack = goBack,
                        contentType = contentType,
                    )
                }
                composable(NavRoutes.CATEGORIES) {
                    PlaylistCategoriesScreen(
                        onBack = goBack,
                        onOpenCategory = { src, catId, catName ->
                            navController.navigate(NavRoutes.categoryPlaylists(src, catId, catName))
                        },
                    )
                }
                composable(
                    NavRoutes.CATEGORY_PLAYLISTS,
                    arguments = listOf(
                        navArgument("source") { type = NavType.StringType },
                        navArgument("category_id") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    val src = java.net.URLDecoder.decode(entry.arguments?.getString("source") ?: "", "UTF-8")
                    val catId = java.net.URLDecoder.decode(entry.arguments?.getString("category_id") ?: "", "UTF-8")
                    val name = java.net.URLDecoder.decode(entry.arguments?.getString("name") ?: "", "UTF-8")
                    CategoryPlaylistsScreen(
                        source = src,
                        categoryId = catId,
                        categoryName = name,
                        onBack = goBack,
                        onOpenRemote = { s, i, n, c, cr ->
                            navController.navigate(NavRoutes.remotePlaylist(s, i, n, c, cr))
                        },
                    )
                }
            }
        }

        // 全屏 NowPlaying:从底部滑入,覆盖整个 Scaffold
        AnimatedVisibility(
            visible = showFull,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            FullPlayer(
                isPlaying = isPlaying,
                title = title,
                artist = artist,
                artworkUri = artwork,
                positionMs = pos,
                durationMs = dur,
                lyricLines = lyricLines,
                currentLineIndex = currentLineIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                playlistQueue = playlistQueue,
                currentMediaId = mediaId,
                onPlayQueueIndex = playback::playQueueIndex,
                onPlayPause = playback::togglePlayPause,
                onNext = playback::next,
                onPrevious = playback::previous,
                onSeek = playback::seekTo,
                onToggleShuffle = playback::toggleShuffle,
                onCycleRepeat = playback::cycleRepeatMode,
                onCollapse = { showFull = false },
            )
        }
    }
}

/** Read screen size once and provide [LocalWindowSpec] to the entire subtree. */
@Composable
private fun WithWindowSpec(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value.roundToInt()
        val h = maxHeight.value.roundToInt()
        val spec = remember(w, h) {
            WindowSpec(
                widthDp = w,
                heightDp = h,
                width = classifyWidth(w),
                height = classifyHeight(h),
                isLandscape = w >= h,
            )
        }
        CompositionLocalProvider(LocalWindowSpec provides spec) {
            content()
        }
    }
}
