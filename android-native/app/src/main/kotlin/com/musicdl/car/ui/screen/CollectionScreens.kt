package com.musicdl.car.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicdl.car.data.MusicRepository
import com.musicdl.car.data.dto.Song
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.UiState
import com.musicdl.car.ui.component.DetailHeader
import com.musicdl.car.ui.component.SongRow
import com.musicdl.car.ui.currentWindow
import com.musicdl.car.ui.viewmodel.CollectionViewModel
import com.musicdl.car.ui.viewmodel.DownloadViewModel
import com.musicdl.car.ui.viewmodel.RemotePlaylistViewModel
import kotlinx.coroutines.launch

@Composable
fun CollectionDetailScreen(
    collectionId: Long,
    onPlay: (List<Song>, Int) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onBack: () -> Unit,
    vm: CollectionViewModel = viewModel(),
) {
    LaunchedEffect(collectionId) { vm.load(collectionId) }
    val state by vm.state.collectAsState()
    val songs: List<Song> = (state as? UiState.Success)?.data ?: emptyList()
    val hPad = AppDimensions.contentPadding(currentWindow())

    val owner = LocalContext.current as ComponentActivity
    val dlVm: DownloadViewModel = viewModel(viewModelStoreOwner = owner)
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(dlVm) {
        dlVm.events.collect { msg -> snackbarHost.showSnackbar(msg) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = hPad, end = hPad, top = 12.dp, bottom = 12.dp)) {
            DetailHeader(
                title = "歌单",
                subtitle = if (songs.isNotEmpty()) "${songs.size} 首" else null,
                onBack = onBack,
                onPlayAll = if (songs.isNotEmpty()) ({ onPlayAll(songs) }) else null,
                onShuffle = if (songs.isNotEmpty()) ({ onShufflePlay(songs) }) else null,
                onDownloadAll = if (songs.any { it.source != "local" }) ({ dlVm.downloadAll(songs) }) else null,
            )
            Spacer(Modifier.height(12.dp))
            when (val s = state) {
                is UiState.Success -> {
                    if (s.data.isEmpty()) EmptyHint("空歌单")
                    else LazyColumn { items(s.data) { song ->
                        SongRow(song, onClick = { onPlay(s.data, s.data.indexOf(song)) })
                    } }
                }
                is UiState.Loading -> EmptyHint("加载中…")
                is UiState.Error -> EmptyHint("加载失败:${s.message}")
            }
        }
        SnackbarHost(
            snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        )
    }
}

@Composable
fun RemotePlaylistScreen(
    source: String,
    id: String,
    playlistName: String,
    playlistCover: String?,
    playlistCreator: String?,
    onPlay: (List<Song>, Int) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onBack: () -> Unit,
    contentType: String = "playlist",
    vm: RemotePlaylistViewModel = viewModel(),
) {
    LaunchedEffect(source, id, contentType) { vm.load(source, id, contentType) }
    val state by vm.state.collectAsState()
    val songs: List<Song> = (state as? UiState.Success)?.data ?: emptyList()
    val hPad = AppDimensions.contentPadding(currentWindow())

    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val repo = remember { MusicRepository() }
    var importing by remember { mutableStateOf(false) }

    val owner = LocalContext.current as ComponentActivity
    val dlVm: DownloadViewModel = viewModel(viewModelStoreOwner = owner)
    LaunchedEffect(dlVm) {
        dlVm.events.collect { msg -> snackbarHost.showSnackbar(msg) }
    }

    val isAlbum = contentType == "album"
    val effectiveName = playlistName.ifBlank { if (isAlbum) "在线专辑" else "在线歌单" }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = hPad, end = hPad, top = 12.dp, bottom = 12.dp)) {
            DetailHeader(
                title = effectiveName,
                subtitle = if (songs.isNotEmpty()) "${songs.size} 首 · $source" else source,
                onBack = onBack,
                onPlayAll = if (songs.isNotEmpty()) ({ onPlayAll(songs) }) else null,
                onShuffle = if (songs.isNotEmpty()) ({ onShufflePlay(songs) }) else null,
                onDownloadAll = if (songs.isNotEmpty()) ({ dlVm.downloadAll(songs) }) else null,
                trailing = {
                    OutlinedButton(
                        enabled = !importing,
                        onClick = {
                            importing = true
                            scope.launch {
                                repo.importRemotePlaylist(
                                    source = source,
                                    externalId = id,
                                    name = effectiveName,
                                    cover = playlistCover,
                                    creator = playlistCreator,
                                    trackCount = songs.size,
                                    contentType = contentType,
                                ).onSuccess { resp ->
                                    snackbarHost.showSnackbar(
                                        if (resp.duplicate) (if (isAlbum) "已收藏过该专辑" else "已收藏过该歌单")
                                        else (if (isAlbum) "已收藏到我的歌单" else "已收藏到我的歌单")
                                    )
                                }.onFailure {
                                    snackbarHost.showSnackbar("收藏失败:${it.message ?: "未知错误"}")
                                }
                                importing = false
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isAlbum) "收藏专辑" else "收藏歌单", fontSize = 14.sp)
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            when (val s = state) {
                is UiState.Success -> {
                    if (s.data.isEmpty()) EmptyHint("歌单为空")
                    else LazyColumn { items(s.data) { song ->
                        SongRow(song, onClick = { onPlay(s.data, s.data.indexOf(song)) })
                    } }
                }
                is UiState.Loading -> EmptyHint("加载中…")
                is UiState.Error -> EmptyHint("加载失败:${s.message}")
            }
        }
        SnackbarHost(
            snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        )
    }
}