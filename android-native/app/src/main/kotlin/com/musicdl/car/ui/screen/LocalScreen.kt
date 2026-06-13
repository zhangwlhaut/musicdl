package com.musicdl.car.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicdl.car.data.dto.Song
import com.musicdl.car.ui.UiState
import com.musicdl.car.ui.component.DetailHeader
import com.musicdl.car.ui.component.SongRow
import com.musicdl.car.ui.viewmodel.LocalViewModel

@Composable
fun LocalScreen(
    onPlay: (List<Song>, Int) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onBack: () -> Unit,
    vm: LocalViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsState()
    val songs: List<Song> = (state as? UiState.Success)?.data ?: emptyList()

    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        DetailHeader(
            title = "本地音乐",
            subtitle = if (songs.isNotEmpty()) "${songs.size} 首" else null,
            onBack = onBack,
            onPlayAll = if (songs.isNotEmpty()) ({ onPlayAll(songs) }) else null,
            onShuffle = if (songs.isNotEmpty()) ({ onShufflePlay(songs) }) else null,
        )
        Spacer(Modifier.height(12.dp))
        when (val s = state) {
            is UiState.Success -> {
                if (s.data.isEmpty()) EmptyHint("未扫描到本地音乐")
                else LazyColumn { items(s.data) { song ->
                    SongRow(song, onClick = { onPlay(s.data, s.data.indexOf(song)) })
                } }
            }
            is UiState.Loading -> EmptyHint("加载中…")
            is UiState.Error -> EmptyHint("加载失败:${s.message}")
        }
    }
}
