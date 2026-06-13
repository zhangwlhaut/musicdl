package com.musicdl.car.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicdl.car.data.ApiClient
import com.musicdl.car.data.dto.Playlist
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.UiState
import com.musicdl.car.ui.component.DetailHeader
import com.musicdl.car.ui.component.TileCard
import com.musicdl.car.ui.currentWindow
import com.musicdl.car.ui.viewmodel.CategoryPlaylistsViewModel

/**
 * 分类歌单列表 —— 横向铺满,每行一个 TileCard(类似推荐页 layout)。
 * 分类场景磁贴较小,用 1f sizeMultiplier。
 */
@Composable
fun CategoryPlaylistsScreen(
    source: String,
    categoryId: String,
    categoryName: String,
    onBack: () -> Unit,
    onOpenRemote: (String, String, String, String?, String?) -> Unit,
    vm: CategoryPlaylistsViewModel = viewModel(),
) {
    LaunchedEffect(source, categoryId) { vm.load(source, categoryId) }
    val state by vm.state.collectAsState()
    val win = currentWindow()
    val hPad = AppDimensions.contentPadding(win)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = hPad, end = hPad, top = 16.dp, bottom = 24.dp),
    ) {
        DetailHeader(
            title = categoryName.ifBlank { "歌单列表" },
            onBack = onBack,
        )
        Spacer(Modifier.height(16.dp))

        when (val s = state) {
            is UiState.Loading -> EmptyHint("加载中…")
            is UiState.Error -> EmptyHint("加载失败:${s.message}")
            is UiState.Success -> {
                val pls = s.data
                if (pls.isEmpty()) { EmptyHint("该分类暂无歌单"); return@Column }

                // 5 列网格风格:用多个 LazyRow 每行 5 个,或一个懒列表。使用 LazyRow 换行显示
                PlaylistGrid(
                    playlists = pls,
                    onOpenRemote = onOpenRemote,
                )
            }
        }
    }
}

/**
 * 歌单网格:将列表分成每行 [colsPerRow] 个,每行一个 LazyRow 连续展示。
 * 多个 LazyRow 在 verticalScroll parent 内会导致测量问题,
 * 改用普通 Row 水平排列再垂直包裹:用 chunked + forEach。
 */
@Composable
private fun PlaylistGrid(
    playlists: List<Playlist>,
    onOpenRemote: (String, String, String, String?, String?) -> Unit,
    colsPerRow: Int = 5,
) {
    val win = currentWindow()
    val tileWidth = AppDimensions.tileWidth(win)
    // 估算可用宽度:满宽 - padding。每行放 colsPerRow + 中间间距
    // 简单做法:直接堆 Row 每行 colsPerRow 个

    playlists.chunked(colsPerRow).forEach { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            row.forEach { pl ->
                TileCard(
                    title = pl.name,
                    coverUrl = ApiClient.proxiedCover(pl.cover),
                    subtitle = pl.creator,
                    onClick = { onOpenRemote(pl.source, pl.id, pl.name, pl.cover, pl.creator) },
                    sizeMultiplier = 1f,
                )
            }
            // 某行不足 colsPerRow 时用空白填满使对齐
            repeat(colsPerRow - row.size) {
                Spacer(Modifier.width(tileWidth + 12.dp)) // tileWidth + padding(end=12)
            }
        }
    }
}