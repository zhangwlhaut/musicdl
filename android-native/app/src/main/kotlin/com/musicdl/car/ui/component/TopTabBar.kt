package com.musicdl.car.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.NavRoutes
import com.musicdl.car.ui.WindowSize
import com.musicdl.car.ui.currentWindow

/**
 * 顶部 Tab 条 — 网易云车机版风格,响应式:
 *   小屏: 56dp / 16sp / 紧凑 / 横向滚动
 *   中屏: 60dp / 18sp
 *   大屏: 64-72dp / 20-22sp
 *   COMPACT 宽度下隐藏 Logo,腾出空间给 Tab。
 */
@Composable
fun TopTabBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val win = currentWindow()
    val barHeight = AppDimensions.topBar(win)
    val tabFont = AppDimensions.tabFontSp(win)
    val logoFont = AppDimensions.logoFontSp(win)
    val hSidePadding = AppDimensions.contentPadding(win)
    val showLogo = win.width != WindowSize.COMPACT
    val tabHPad = if (win.width == WindowSize.COMPACT) 10.dp else 16.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = hSidePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLogo) {
            Text(
                text = "MusicDL",
                color = MaterialTheme.colorScheme.primary,
                fontSize = logoFont.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(if (win.width == WindowSize.LARGE) 48.dp else 32.dp))
        }

        // Tab 区域 — 窄屏时允许横向滚动
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavRoutes.TOP_TABS.forEach { (route, label) ->
                TabItem(
                    label = label,
                    selected = currentRoute.startsWith(route, ignoreCase = true),
                    onClick = { onNavigate(route) },
                    fontSp = tabFont,
                    horizontalPadding = tabHPad,
                )
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    fontSp: Int,
    horizontalPadding: androidx.compose.ui.unit.Dp,
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = horizontalPadding)
            .drawBehind {
                if (selected) {
                    val y = size.height - 6f
                    val barWidth = size.width * 0.5f
                    val startX = (size.width - barWidth) / 2f
                    drawLine(
                        color = primary,
                        start = Offset(startX, y),
                        end = Offset(startX + barWidth, y),
                        strokeWidth = 4f,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = fontSp.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
