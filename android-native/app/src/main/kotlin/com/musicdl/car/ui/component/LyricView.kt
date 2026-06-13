package com.musicdl.car.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicdl.car.data.dto.LyricLine
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.currentWindow

/**
 * 滚动歌词视图 — 网易云风格:
 *   当前句:红色 22sp 加粗,居中
 *   其他句:灰色 18sp
 *   切换当前句时自动滚动到居中位置
 *   空歌词时显示"暂无歌词"占位
 */
@Composable
fun LyricView(
    lines: List<LyricLine>,
    currentLineIndex: Int,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "暂无歌词",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
            )
        }
        return
    }

    val win = currentWindow()
    val currentSp = AppDimensions.lyricCurrentSp(win)
    val otherSp = AppDimensions.lyricOtherSp(win)
    val vSpacing = if (win.width == com.musicdl.car.ui.WindowSize.COMPACT) 6.dp else 10.dp
    val listState = rememberLazyListState()

    LaunchedEffect(currentLineIndex, lines.size) {
        if (currentLineIndex in lines.indices) {
            val viewportHeight = listState.layoutInfo.viewportSize.height
            val centerOffset = if (viewportHeight > 0) -(viewportHeight / 2 - 40) else 0
            listState.animateScrollToItem(currentLineIndex, centerOffset)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(
            vertical = if (win.height == com.musicdl.car.ui.WindowHeight.SHORT) 80.dp else 160.dp,
            horizontal = 16.dp,
        ),
    ) {
        items(lines) { line ->
            val idx = lines.indexOf(line)
            val isCurrent = idx == currentLineIndex
            Text(
                text = line.text,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (isCurrent) currentSp.sp else otherSp.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = vSpacing),
            )
        }
    }
}
