package com.musicdl.car.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.currentWindow

/**
 * 底部迷你播放栏 — 网易云风格:
 *   高 80dp,顶部 2dp 进度细线,从左到右:
 *     [48dp 封面] [标题/歌手两行] [Spacer] [⏮ ▶ ⏭]
 *   整体可点击 → 触发 onExpand,展开全屏 NowPlaying
 *   即使无歌曲也固定高度,避免 Scaffold 抖动
 */
@Composable
fun MiniPlayer(
    isPlaying: Boolean,
    title: String?,
    artist: String?,
    artworkUri: String?,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val primary = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outline
    val win = currentWindow()
    val height = AppDimensions.miniPlayer(win)
    val coverSize = AppDimensions.miniCover(win)
    val btnSize = (height.value * 0.7f).dp.coerceIn(40.dp, 64.dp)
    val iconSize = (btnSize.value * 0.5f).dp
    val playIconSize = (btnSize.value * 0.65f).dp
    val titleSp = if (win.width == com.musicdl.car.ui.WindowSize.COMPACT) 14 else 16
    val artistSp = if (win.width == com.musicdl.car.ui.WindowSize.COMPACT) 11 else 12

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.surface)
            .drawBehind {
                drawLine(
                    color = trackColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 2f,
                )
                drawLine(
                    color = primary,
                    start = Offset(0f, 0f),
                    end = Offset(size.width * progress, 0f),
                    strokeWidth = 2f,
                )
            }
            .clickable(enabled = title != null, onClick = onExpand)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(coverSize)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (artworkUri != null) {
                AsyncImage(
                    model = artworkUri,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title ?: "未在播放",
                fontSize = titleSp.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = artist ?: "—",
                fontSize = artistSp.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onPrevious, modifier = Modifier.size(btnSize)) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "上一首",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(iconSize),
            )
        }
        IconButton(onClick = onPlayPause, modifier = Modifier.size(btnSize)) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(playIconSize),
            )
        }
        IconButton(onClick = onNext, modifier = Modifier.size(btnSize)) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "下一首",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
