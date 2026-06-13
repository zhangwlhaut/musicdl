package com.musicdl.car.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 网易云车机版风格配色:经典红 + 近黑底色
val NeteaseRed = Color(0xFFC20C0C)
val CarBg = Color(0xFF0A0A0A)          // 顶层背景
val CarSurface = Color(0xFF1A1A1A)     // 卡片 / TopBar / BottomBar
val CarSurfaceVariant = Color(0xFF262626)
val CarText = Color(0xFFF2F2F2)
val CarTextMuted = Color(0xFFA0A0A0)
val CarDivider = Color(0xFF2A2A2A)

private val CarColors = darkColorScheme(
    primary = NeteaseRed,
    onPrimary = Color.White,
    secondary = NeteaseRed,
    background = CarBg,
    onBackground = CarText,
    surface = CarSurface,
    onSurface = CarText,
    surfaceVariant = CarSurfaceVariant,
    onSurfaceVariant = CarTextMuted,
    outline = CarDivider,
    error = NeteaseRed,
)

@Composable
fun MusicDLTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CarColors,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
