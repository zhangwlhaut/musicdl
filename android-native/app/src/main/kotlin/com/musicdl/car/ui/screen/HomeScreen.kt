package com.musicdl.car.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicdl.car.data.dto.Song
import com.musicdl.car.playback.toSong
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.UiState
import com.musicdl.car.ui.component.TileCard
import com.musicdl.car.ui.currentWindow
import com.musicdl.car.ui.viewmodel.HomeViewModel

/**
 * 首页:最近播放 + 我的歌单 + 推荐歌单(按音源分组)
 * 网易云车机风:整页垂直滚动,每个区块标题(左对齐 22sp)+ 横滑卡片
 */
@Composable
fun HomeScreen(
    onPlaySong: (List<Song>, Int) -> Unit,
    onOpenCollection: (Long) -> Unit,
    onOpenRemote: (String, String, String, String?, String?) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.refresh() }
    val recent by vm.recent.collectAsState()
    val collections by vm.collections.collectAsState()
    val recommend by vm.recommend.collectAsState()
    val win = currentWindow()
    val hPad = AppDimensions.contentPadding(win)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = hPad, end = hPad, top = 16.dp, bottom = 24.dp)
    ) {
        SectionHeader("最近播放")
        when (val s = recent) {
            is UiState.Success -> {
                val songs = s.data.map { it.toSong() }
                if (songs.isEmpty()) EmptyHint("暂无最近播放")
                else LazyRow { items(songs) { song ->
                    TileCard(
                        title = song.name,
                        coverUrl = com.musicdl.car.data.ApiClient.coverUrl(song),
                        onClick = { onPlaySong(songs, songs.indexOf(song)) },
                        subtitle = song.artist,
                    )
                } }
            }
            is UiState.Loading -> EmptyHint("加载中…")
            is UiState.Error -> EmptyHint("加载失败:${s.message}")
        }

        Spacer(Modifier.height(28.dp))
        SectionHeader("我的歌单")
        when (val s = collections) {
            is UiState.Success -> {
                if (s.data.isEmpty()) EmptyHint("还没有创建歌单")
                else LazyRow { items(s.data) { col ->
                    TileCard(
                        title = col.name,
                        coverUrl = com.musicdl.car.data.ApiClient.proxiedCover(col.cover),
                        subtitle = "${col.trackCount} 首",
                        onClick = { onOpenCollection(col.id) },
                    )
                } }
            }
            is UiState.Loading -> EmptyHint("加载中…")
            is UiState.Error -> EmptyHint("加载失败:${s.message}")
        }

        Spacer(Modifier.height(28.dp))
        SectionHeader("推荐歌单")
        when (val s = recommend) {
            is UiState.Success -> {
                s.data.tabs.take(4).forEach { tab ->
                    Text(
                        tab.sourceName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp),
                        fontSize = 14.sp,
                    )
                    LazyRow { items(tab.safePlaylists.take(20)) { pl ->
                        TileCard(
                            title = pl.name,
                            coverUrl = com.musicdl.car.data.ApiClient.proxiedCover(pl.cover),
                            subtitle = pl.creator,
                            onClick = { onOpenRemote(pl.source, pl.id, pl.name, pl.cover, pl.creator) },
                        )
                    } }
                }
            }
            is UiState.Loading -> EmptyHint("加载中…")
            is UiState.Error -> EmptyHint("加载失败:${s.message}")
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    val win = currentWindow()
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = AppDimensions.sectionTitleSp(win).sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 10.dp),
    )
}

@Composable
fun EmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
