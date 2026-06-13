package com.musicdl.car.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicdl.car.data.dto.Song
import com.musicdl.car.ui.UiState
import com.musicdl.car.ui.component.DetailHeader
import com.musicdl.car.ui.component.SongRow
import com.musicdl.car.ui.viewmodel.DownloadViewModel
import com.musicdl.car.ui.viewmodel.FavoritesViewModel

@Composable
fun FavoritesScreen(
    onPlay: (List<Song>, Int) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onBack: () -> Unit,
    vm: FavoritesViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsState()
    val songs: List<Song> = (state as? UiState.Success)?.data ?: emptyList()

    val owner = LocalContext.current as ComponentActivity
    val dlVm: DownloadViewModel = viewModel(viewModelStoreOwner = owner)
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(dlVm) { dlVm.events.collect { snackbarHost.showSnackbar(it) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            DetailHeader(
                title = "我的收藏",
                subtitle = if (songs.isNotEmpty()) "${songs.size} 首" else null,
                onBack = onBack,
                onPlayAll = if (songs.isNotEmpty()) ({ onPlayAll(songs) }) else null,
                onShuffle = if (songs.isNotEmpty()) ({ onShufflePlay(songs) }) else null,
                onDownloadAll = if (songs.any { it.source != "local" }) ({ dlVm.downloadAll(songs) }) else null,
            )
            Spacer(Modifier.height(12.dp))
            when (val s = state) {
                is UiState.Success -> {
                    if (s.data.isEmpty()) EmptyHint("还没有收藏歌曲")
                    else LazyColumn { items(s.data) { song ->
                        SongRow(song, onClick = { onPlay(s.data, s.data.indexOf(song)) })
                    } }
                }
                is UiState.Loading -> EmptyHint("加载中…")
                is UiState.Error -> EmptyHint("加载失败:${s.message}")
            }
        }
        SnackbarHost(snackbarHost, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
    }
}
