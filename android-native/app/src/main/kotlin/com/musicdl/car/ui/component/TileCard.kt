package com.musicdl.car.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.WindowSize
import com.musicdl.car.ui.currentWindow

/**
 * 网易云车机风格的响应式卡片:
 *   宽度依据 WindowSpec 自适应(小=132 / 中=152 / 大=168 / 超大=200)
 *   字号、内边距相应缩放。
 *   sizeMultiplier 用于"发现页"等需要更大磁贴的场景(传 1.25 即可放大 25%)。
 */
@Composable
fun TileCard(
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    sizeMultiplier: Float = 1f,
) {
    val win = currentWindow()
    val baseWidth: Dp = AppDimensions.tileWidth(win) * sizeMultiplier
    val titleSp = when (win.width) {
        WindowSize.COMPACT -> 13
        WindowSize.MEDIUM -> 14
        WindowSize.EXPANDED -> 15
        WindowSize.LARGE -> 17
    }
    val subtitleSp = (titleSp - 2).coerceAtLeast(10)
    val placeholderIcon = (baseWidth.value * 0.3f).dp

    Column(
        modifier = modifier
            .width(baseWidth)
            .padding(end = 12.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .size(baseWidth)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(placeholderIcon),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = titleSp.sp,
            fontWeight = FontWeight.Medium,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = subtitleSp.sp,
            )
        }
    }
}
