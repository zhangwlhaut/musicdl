package com.musicdl.car.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicdl.car.data.dto.PlaylistCategoryGroup
import com.musicdl.car.data.dto.PlaylistCategoryTab
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.UiState
import com.musicdl.car.ui.component.DetailHeader
import com.musicdl.car.ui.currentWindow
import com.musicdl.car.ui.viewmodel.PlaylistCategoriesViewModel

/**
 * 歌单分类入口页 —— 顶部音源 tab 切换,下方按 group 分组排布的分类标签 chip。
 * 点击 chip 跳转到 CategoryPlaylistsScreen。
 */
@Composable
fun PlaylistCategoriesScreen(
    onBack: () -> Unit,
    onOpenCategory: (source: String, categoryId: String, categoryName: String) -> Unit,
    vm: PlaylistCategoriesViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsState()
    val selectedSource by vm.selectedSource.collectAsState()
    val win = currentWindow()
    val hPad = AppDimensions.contentPadding(win)

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = hPad, end = hPad, top = 16.dp, bottom = 16.dp)
    ) {
        DetailHeader(title = "歌单分类", onBack = onBack)
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            is UiState.Loading -> EmptyHint("加载中…")
            is UiState.Error -> EmptyHint("加载失败:${s.message}")
            is UiState.Success -> {
                val tabs = s.data.tabs.filter { it.groups.isNotEmpty() }
                if (tabs.isEmpty()) {
                    EmptyHint("暂无可用分类")
                    return@Column
                }
                val currentSource = selectedSource.ifBlank { tabs.first().source }
                val currentTab = tabs.firstOrNull { it.source == currentSource } ?: tabs.first()

                SourceTabBar(
                    tabs = tabs,
                    current = currentTab.source,
                    onSelect = vm::selectSource,
                )
                Spacer(Modifier.height(12.dp))

                CategoryGroupList(
                    groups = currentTab.groups,
                    onChipClick = { cat ->
                        onOpenCategory(currentTab.source, cat.id, cat.name)
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceTabBar(
    tabs: List<PlaylistCategoryTab>,
    current: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = tab.source == current
            val label = tab.sourceName.ifBlank { tab.source }
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(tab.source) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun CategoryGroupList(
    groups: List<PlaylistCategoryGroup>,
    onChipClick: (com.musicdl.car.data.dto.PlaylistCategory) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(groups) { group ->
            Column {
                if (group.name.isNotBlank()) {
                    Text(
                        group.name,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                }
                FlowChipRow(
                    items = group.categories,
                    onClick = onChipClick,
                )
            }
        }
    }
}

/**
 * 简易 chip 流式布局 —— 用 FlowRow 排列分类标签,自动换行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChipRow(
    items: List<com.musicdl.car.data.dto.PlaylistCategory>,
    onClick: (com.musicdl.car.data.dto.PlaylistCategory) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { cat ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onClick(cat) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cat.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                    )
                    if (cat.hot) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "热",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
