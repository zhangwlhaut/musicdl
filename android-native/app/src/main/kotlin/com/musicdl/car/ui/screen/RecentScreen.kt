package com.musicdl.car.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicdl.car.data.dto.Song
import com.musicdl.car.playback.toSong
import com.musicdl.car.ui.UiState
import com.musicdl.car.ui.component.DetailHeader
import com.musicdl.car.ui.component.SongRow
import com.musicdl.car.ui.viewmodel.RecentViewModel

@Composable
fun RecentScreen(
    onPlay: (List<Song>, Int) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onBack: () -> Unit,
    vm: RecentViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsState()
    val songs: List<Song> = (state as? UiState.Success)?.data?.map { it.toSong() } ?: emptyList()

    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        DetailHeader(
            title = "最近播放",
            subtitle = if (songs.isNotEmpty()) "${songs.size} 首" else null,
            onBack = onBack,
            onPlayAll = if (songs.isNotEmpty()) ({ onPlayAll(songs) }) else null,
            onShuffle = if (songs.isNotEmpty()) ({ onShufflePlay(songs) }) else null,
            trailing = {
                OutlinedButton(onClick = { vm.clear() }) { Text("清空") }
            },
        )
        Spacer(Modifier.height(12.dp))

        when (val s = state) {
            is UiState.Success -> {
                if (s.data.isEmpty()) EmptyHint("还没有播放记录")
                else LazyColumn { items(songs) { song ->
                    SongRow(song, onClick = { onPlay(songs, songs.indexOf(song)) })
                } }
            }
            is UiState.Loading -> EmptyHint("加载中…")
            is UiState.Error -> EmptyHint("加载失败:${s.message}")
        }
    }
}
