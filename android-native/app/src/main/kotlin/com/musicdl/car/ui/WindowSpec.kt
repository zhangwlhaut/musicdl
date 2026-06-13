package com.musicdl.car.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 响应式窗口尺寸分档 — 用来在不同设备上调整字体/间距/卡片大小/布局方向。
 *
 * 分档参考 Material3 WindowSizeClass:
 *   COMPACT   < 600dp     — 普通手机竖屏
 *   MEDIUM    600~840dp   — 平板竖屏 / 小车机
 *   EXPANDED  840~1200dp  — 平板横屏 / 标准车机
 *   LARGE    ≥ 1200dp     — 大屏车机 / 2K 中控
 */
enum class WindowSize { COMPACT, MEDIUM, EXPANDED, LARGE }

enum class WindowHeight { SHORT, MEDIUM, TALL }

/**
 * 当前窗口的尺寸信息(宽 + 高)。由 [AppDimensions] 在根部计算并通过 CompositionLocal 提供。
 */
data class WindowSpec(
    val widthDp: Int,
    val heightDp: Int,
    val width: WindowSize,
    val height: WindowHeight,
    val isLandscape: Boolean,
) {
    /** 是否使用紧凑(单列/小字)布局 */
    val isCompact: Boolean get() = width == WindowSize.COMPACT
    /** 是否双栏可用(全屏播放等场景) */
    val canSplitTwoCols: Boolean get() = width >= WindowSize.EXPANDED && isLandscape
}

val LocalWindowSpec = staticCompositionLocalOf<WindowSpec> {
    error("WindowSpec not provided")
}

object AppDimensions {
    /** 顶部 Tab 栏高度 */
    fun topBar(spec: WindowSpec): Dp = when (spec.width) {
        WindowSize.COMPACT -> 56.dp
        WindowSize.MEDIUM -> 60.dp
        WindowSize.EXPANDED -> 64.dp
        WindowSize.LARGE -> 72.dp
    }

    /** Tab 文字大小 */
    fun tabFontSp(spec: WindowSpec): Int = when (spec.width) {
        WindowSize.COMPACT -> 16
        WindowSize.MEDIUM -> 18
        WindowSize.EXPANDED -> 20
        WindowSize.LARGE -> 22
    }

    /** Logo 大小 */
    fun logoFontSp(spec: WindowSpec): Int = when (spec.width) {
        WindowSize.COMPACT -> 18
        WindowSize.MEDIUM -> 20
        WindowSize.EXPANDED -> 22
        WindowSize.LARGE -> 26
    }

    /** 底部 Mini Player 高度 */
    fun miniPlayer(spec: WindowSpec): Dp = when (spec.width) {
        WindowSize.COMPACT -> 64.dp
        WindowSize.MEDIUM -> 72.dp
        WindowSize.EXPANDED -> 80.dp
        WindowSize.LARGE -> 96.dp
    }

    /** Mini Player 封面大小 */
    fun miniCover(spec: WindowSpec): Dp = when (spec.width) {
        WindowSize.COMPACT -> 44.dp
        WindowSize.MEDIUM -> 52.dp
        WindowSize.EXPANDED -> 56.dp
        WindowSize.LARGE -> 64.dp
    }

    /** Tile Card 宽度(发现页大卡片可在此基础上 *1.25) */
    fun tileWidth(spec: WindowSpec): Dp = when (spec.width) {
        WindowSize.COMPACT -> 132.dp
        WindowSize.MEDIUM -> 152.dp
        WindowSize.EXPANDED -> 168.dp
        WindowSize.LARGE -> 200.dp
    }

    /** SongRow 行高最小值 */
    fun rowHeight(spec: WindowSpec): Dp = when (spec.width) {
        WindowSize.COMPACT -> 56.dp
        WindowSize.MEDIUM -> 60.dp
        WindowSize.EXPANDED -> 64.dp
        WindowSize.LARGE -> 72.dp
    }

    /** SongRow 封面 */
    fun rowCover(spec: WindowSpec): Dp = when (spec.width) {
        WindowSize.COMPACT -> 44.dp
        WindowSize.MEDIUM -> 48.dp
        WindowSize.EXPANDED -> 52.dp
        WindowSize.LARGE -> 60.dp
    }

    /** 区块标题 fontSize */
    fun sectionTitleSp(spec: WindowSpec): Int = when (spec.width) {
        WindowSize.COMPACT -> 18
        WindowSize.MEDIUM -> 20
        WindowSize.EXPANDED -> 22
        WindowSize.LARGE -> 26
    }

    /** 内容区水平内边距 */
    fun contentPadding(spec: WindowSpec): Dp = when (spec.width) {
        WindowSize.COMPACT -> 12.dp
        WindowSize.MEDIUM -> 18.dp
        WindowSize.EXPANDED -> 24.dp
        WindowSize.LARGE -> 36.dp
    }

    /** 全屏播放页大封面 */
    fun fullCover(spec: WindowSpec): Dp {
        // 取宽高中的较小者的 40-50%,夹在 200..420 之间
        val base = minOf(spec.widthDp, spec.heightDp)
        val target = (base * 0.42f).toInt()
        return target.coerceIn(200, 420).dp
    }

    /** 全屏播放页主标题 */
    fun fullTitleSp(spec: WindowSpec): Int = when (spec.width) {
        WindowSize.COMPACT -> 20
        WindowSize.MEDIUM -> 24
        WindowSize.EXPANDED -> 26
        WindowSize.LARGE -> 30
    }

    /** 全屏歌词当前行 */
    fun lyricCurrentSp(spec: WindowSpec): Int = when (spec.width) {
        WindowSize.COMPACT -> 18
        WindowSize.MEDIUM -> 20
        WindowSize.EXPANDED -> 22
        WindowSize.LARGE -> 26
    }

    /** 全屏歌词其他行 */
    fun lyricOtherSp(spec: WindowSpec): Int = when (spec.width) {
        WindowSize.COMPACT -> 14
        WindowSize.MEDIUM -> 16
        WindowSize.EXPANDED -> 18
        WindowSize.LARGE -> 20
    }
}

/** 把 dp 宽度映射到 WindowSize 档位 */
fun classifyWidth(widthDp: Int): WindowSize = when {
    widthDp < 600 -> WindowSize.COMPACT
    widthDp < 840 -> WindowSize.MEDIUM
    widthDp < 1200 -> WindowSize.EXPANDED
    else -> WindowSize.LARGE
}

fun classifyHeight(heightDp: Int): WindowHeight = when {
    heightDp < 480 -> WindowHeight.SHORT
    heightDp < 900 -> WindowHeight.MEDIUM
    else -> WindowHeight.TALL
}

/** 便捷快捷:在 Composable 里读当前 WindowSpec */
@Composable
@ReadOnlyComposable
fun currentWindow(): WindowSpec = LocalWindowSpec.current
