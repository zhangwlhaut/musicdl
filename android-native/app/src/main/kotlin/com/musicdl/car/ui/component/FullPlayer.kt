@file:OptIn(ExperimentalMaterial3Api::class)

package com.musicdl.car.ui.component

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.musicdl.car.data.dto.LyricLine
import com.musicdl.car.data.dto.Song
import com.musicdl.car.playback.parseMediaId
import com.musicdl.car.ui.AppDimensions
import com.musicdl.car.ui.WindowSize
import com.musicdl.car.ui.currentWindow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.foundation.lazy.itemsIndexed

/**
 * 全屏正在播放页 — 网易云车机风格,响应式:
 *   横屏 & 宽度≥600dp: 左封面 + 右歌词
 *   竖屏 / 窄屏:       封面上(含控件) + 歌词下(滚动),使用竖向布局
 *
 *   封面大小、字号均随 [WindowSpec] 缩放。
 */
@Composable
fun FullPlayer(
    isPlaying: Boolean,
    title: String?,
    artist: String?,
    artworkUri: String?,
    positionMs: Long,
    durationMs: Long,
    lyricLines: List<LyricLine>,
    currentLineIndex: Int,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    playlistQueue: List<Song>,
    currentMediaId: String?,
    onPlayQueueIndex: (Int) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val win = currentWindow()
    val twoCols = win.canSplitTwoCols
    val hPadding = AppDimensions.contentPadding(win)
    var showQueue by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶栏:左折叠按钮 + 居中标题 + 右播放列表按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (win.isCompact) 48.dp else 64.dp)
                .padding(horizontal = hPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCollapse, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "收起",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "正在播放",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showQueue = true }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.QueueMusic,
                    contentDescription = "当前播放列表",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        // 主体
        if (twoCols) {
            // 左右双栏
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LeftPanel(
                    isPlaying = isPlaying,
                    title = title,
                    artist = artist,
                    artworkUri = artworkUri,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onSeek = onSeek,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = hPadding),
                )
                LyricView(
                    lines = lyricLines,
                    currentLineIndex = currentLineIndex,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        } else {
            // 上下单栏:封面在上,歌词在下
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // 顶部:封面 + 控件(高度约 55% 可用空间)
                LeftPanel(
                    isPlaying = isPlaying,
                    title = title,
                    artist = artist,
                    artworkUri = artworkUri,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onSeek = onSeek,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    compact = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.55f)
                        .padding(horizontal = hPadding),
                )
                // 底部:歌词
                LyricView(
                    lines = lyricLines,
                    currentLineIndex = currentLineIndex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.45f),
                )
            }
        }
    }

    if (showQueue) {
        ModalBottomSheet(
            onDismissRequest = { showQueue = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.6f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "播放列表 (${playlistQueue.size}首)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(onClick = { showQueue = false }) {
                        Text("关闭", color = MaterialTheme.colorScheme.primary)
                    }
                }
                
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    itemsIndexed(playlistQueue) { idx, song ->
                        val isCurrent = currentMediaId != null && 
                                        parseMediaId(currentMediaId)?.first == song.id &&
                                        parseMediaId(currentMediaId)?.second == song.source
                                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    onPlayQueueIndex(idx)
                                    showQueue = false
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isCurrent) {
                                Text(
                                    "▶",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(24.dp)
                                )
                            } else {
                                Text(
                                    "${idx + 1}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(24.dp)
                                )
                            }
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.name,
                                    fontSize = 15.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = song.artist ?: "未知歌手",
                                    fontSize = 12.sp,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            
                            Spacer(Modifier.width(8.dp))
                            
                            if (song.source.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = song.source.uppercase(),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Left panel ────────────────────────────────────────────────

@Composable
private fun LeftPanel(
    isPlaying: Boolean,
    title: String?,
    artist: String?,
    artworkUri: String?,
    positionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val win = currentWindow()
    val coverSize = AppDimensions.fullCover(win)
    val titleSp = AppDimensions.fullTitleSp(win)
    val btnSize = if (compact) 56.dp else 72.dp
    val playBtnSize = if (compact) 64.dp else 88.dp
    val playIconSz = if (compact) 36.dp else 48.dp
    val shuffleIconSz = if (compact) 22.dp else 28.dp

    // 封面旋转动画
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(15_000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
        } else {
            rotation.stop()
        }
    }

    Column(
        modifier = modifier.padding(vertical = if (compact) 4.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 旋转封面
        Box(
            modifier = Modifier
                .size(coverSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .rotate(rotation.value),
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
                Text(
                    "♪",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (coverSize.value * 0.2f).sp,
                )
            }
        }

        Spacer(Modifier.height(if (compact) 8.dp else 20.dp))

        Text(
            text = title ?: "未在播放",
            fontSize = titleSp.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!artist.isNullOrBlank()) {
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            Text(
                text = artist,
                fontSize = if (compact) 14.sp else 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(if (compact) 8.dp else 20.dp))

        // 进度条
        Slider(
            value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
            onValueChange = { frac -> onSeek((frac * durationMs).toLong()) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 8.dp else 0.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            ),
            thumb = {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    )
                )
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = if (compact) 8.dp else 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(positionMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(durationMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(if (compact) 6.dp else 12.dp))

        // 控制行:随机 - 上一首 - 播放 - 下一首 - 循环
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleShuffle, modifier = Modifier.size(btnSize * 0.75f)) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = if (shuffleEnabled) "关闭随机" else "随机播放",
                    tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(shuffleIconSz),
                )
            }
            Spacer(Modifier.width(if (compact) 4.dp else 12.dp))
            IconButton(onClick = onPrevious, modifier = Modifier.size(btnSize)) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "上一首",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(if (compact) 28.dp else 36.dp),
                )
            }
            Spacer(Modifier.width(if (compact) 8.dp else 16.dp))
            Box(
                modifier = Modifier
                    .size(playBtnSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onPlayPause, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(playIconSz),
                    )
                }
            }
            Spacer(Modifier.width(if (compact) 8.dp else 16.dp))
            IconButton(onClick = onNext, modifier = Modifier.size(btnSize)) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(if (compact) 28.dp else 36.dp),
                )
            }
            Spacer(Modifier.width(if (compact) 4.dp else 12.dp))
            val (repeatIcon, repeatTint, repeatDesc) = when (repeatMode) {
                androidx.media3.common.Player.REPEAT_MODE_ONE ->
                    Triple(Icons.Default.RepeatOne, MaterialTheme.colorScheme.primary, "单曲循环")
                androidx.media3.common.Player.REPEAT_MODE_ALL ->
                    Triple(Icons.Default.Repeat, MaterialTheme.colorScheme.primary, "列表循环")
                else ->
                    Triple(Icons.Default.Repeat, MaterialTheme.colorScheme.onSurfaceVariant, "顺序播放")
            }
            IconButton(onClick = onCycleRepeat, modifier = Modifier.size(btnSize * 0.75f)) {
                Icon(
                    repeatIcon,
                    contentDescription = repeatDesc,
                    tint = repeatTint,
                    modifier = Modifier.size(shuffleIconSz),
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}