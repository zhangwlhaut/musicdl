package com.musicdl.car.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.UiState
import com.musicdl.car.ui.component.TileCard
import com.musicdl.car.ui.currentWindow
import com.musicdl.car.ui.viewmodel.HomeViewModel

/**
 * 发现 Tab:按音源分组横滑推荐歌单。每组上方一个源名标签。
 * 顶部提供「按分类浏览」入口跳转 PlaylistCategoriesScreen。
 */
@Composable
fun DiscoverScreen(
    onOpenRemote: (String, String, String, String?, String?) -> Unit,
    onOpenCategories: () -> Unit = {},
    vm: HomeViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.refresh() }
    val recommend by vm.recommend.collectAsState()
    val win = currentWindow()
    val hPad = AppDimensions.contentPadding(win)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = hPad, end = hPad, top = 16.dp, bottom = 24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { SectionHeader("发现音乐") }
            OutlinedButton(
                onClick = onOpenCategories,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Icon(
                    Icons.Default.Category,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("分类", fontSize = 14.sp)
            }
        }

        when (val s = recommend) {
            is UiState.Success -> {
                s.data.tabs.forEach { tab ->
                    val pls = tab.safePlaylists
                    if (pls.isEmpty()) return@forEach
                    Text(
                        tab.sourceName,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                    )
                    LazyRow { items(pls) { pl ->
                        TileCard(
                            title = pl.name,
                            coverUrl = com.musicdl.car.data.ApiClient.proxiedCover(pl.cover),
                            subtitle = pl.creator,
                            onClick = { onOpenRemote(pl.source, pl.id, pl.name, pl.cover, pl.creator) },
                            sizeMultiplier = 1.15f,  // 发现页用稍大磁贴
                        )
                    } }
                }
            }
            is UiState.Loading -> EmptyHint("加载中…")
            is UiState.Error -> EmptyHint("加载失败:${s.message}")
        }
    }
}
