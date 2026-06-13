package com.musicdl.car.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicdl.car.data.dto.Song
import com.musicdl.car.playback.toSong
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.UiState
import com.musicdl.car.ui.component.AddTile
import com.musicdl.car.ui.component.CreateCollectionDialog
import com.musicdl.car.ui.component.SongRow
import com.musicdl.car.ui.component.TileCard
import com.musicdl.car.ui.currentWindow
import com.musicdl.car.ui.viewmodel.CollectionsHubViewModel
import com.musicdl.car.ui.viewmodel.MineViewModel

/**
 * "我的" Tab:收藏 / 我的歌单 / 本地音乐 / 最近播放 四个区块
 */
@Composable
fun MineScreen(
    onPlay: (List<Song>, Int) -> Unit,
    onOpenCollection: (Long) -> Unit,
    onNavigateDetail: (String) -> Unit,
    vm: MineViewModel = viewModel(),
) {
    val owner = LocalContext.current as ComponentActivity
    val hub: CollectionsHubViewModel = viewModel(viewModelStoreOwner = owner)

    LaunchedEffect(Unit) {
        vm.refresh()
        hub.refresh()
    }
    val favorites by vm.favorites.collectAsState()
    val collections by vm.collections.collectAsState()
    val local by vm.local.collectAsState()
    val recent by vm.recent.collectAsState()
    val hubEvent by hub.events.collectAsState()
    val win = currentWindow()
    val hPad = AppDimensions.contentPadding(win)

    var showCreate by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // 监听 Hub 事件:新建成功 → 刷新 MineScreen 自己的 collections 列表;失败 → snackbar
    LaunchedEffect(hubEvent) {
        val e = hubEvent ?: return@LaunchedEffect
        when (e) {
            is CollectionsHubViewModel.HubEvent.Created -> {
                vm.refresh()
                snackbar.showSnackbar("已创建歌单「${e.name}」")
            }
            is CollectionsHubViewModel.HubEvent.Added -> {
                vm.refresh()
                snackbar.showSnackbar("已加入「${e.collectionName}」")
            }
            is CollectionsHubViewModel.HubEvent.Failed -> {
                snackbar.showSnackbar(e.message)
            }
        }
        hub.consumeEvent()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = hPad, end = hPad, top = 16.dp, bottom = 24.dp),
        ) {
            SectionHeader("我的收藏")
            when (val s = favorites) {
                is UiState.Success -> {
                    if (s.data.isEmpty()) EmptyHint("还没有收藏歌曲")
                    else {
                        s.data.take(10).forEach { song ->
                            SongRow(song, onClick = { onPlay(s.data, s.data.indexOf(song)) })
                        }
                        Text(
                            "查看全部",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable { onNavigateDetail("favorites") }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
                is UiState.Loading -> EmptyHint("加载中…")
                is UiState.Error -> EmptyHint("加载失败:${s.message}")
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("我的歌单")
            when (val s = collections) {
                is UiState.Success -> {
                    LazyRow {
                        item { AddTile(onClick = { showCreate = true }) }
                        items(s.data) { col ->
                            TileCard(
                                title = col.name,
                                coverUrl = com.musicdl.car.data.ApiClient.proxiedCover(col.cover),
                                subtitle = "${col.trackCount} 首",
                                onClick = { onOpenCollection(col.id) },
                            )
                        }
                    }
                }
                is UiState.Loading -> EmptyHint("加载中…")
                is UiState.Error -> EmptyHint("加载失败:${s.message}")
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("本地音乐")
            when (val s = local) {
                is UiState.Success -> {
                    if (s.data.isEmpty()) EmptyHint("没有本地音乐")
                    else {
                        s.data.take(10).forEach { song ->
                            SongRow(song, onClick = { onPlay(s.data, s.data.indexOf(song)) })
                        }
                        Text(
                            "查看全部",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable { onNavigateDetail("local") }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
                is UiState.Loading -> EmptyHint("加载中…")
                is UiState.Error -> EmptyHint("加载失败:${s.message}")
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("最近播放")
            when (val s = recent) {
                is UiState.Success -> {
                    if (s.data.isEmpty()) EmptyHint("还没有播放记录")
                    else {
                        s.data.take(10).forEach { recentPlay ->
                            val song = recentPlay.toSong()
                            SongRow(song, onClick = {
                                // recent list items need to be wrapped in a full list for playNow
                                onPlay(s.data.map { it.toSong() }, s.data.indexOf(recentPlay))
                            })
                        }
                        Text(
                            "查看全部",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable { onNavigateDetail("recent") }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
                is UiState.Loading -> EmptyHint("加载中…")
                is UiState.Error -> EmptyHint("加载失败:${s.message}")
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )
    }

    if (showCreate) {
        CreateCollectionDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, desc ->
                hub.create(name, desc)
                showCreate = false
            },
        )
    }
}
