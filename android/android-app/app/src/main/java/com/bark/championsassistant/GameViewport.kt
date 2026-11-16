package com.bark.championsassistant

import kotlin.math.roundToInt

/** 游戏画面在截图里占的矩形（相对整块区域）。 */
internal data class GameViewportBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/**
 * 把整块屏幕区域换算成游戏画面的实际矩形。
 *
 * 游戏是固定宽高比（横屏 16:9）渲染，四周可能有状态栏或黑边，所以取「最大等比内接矩形 + 居中」：
 * 屏幕比游戏更宽 → 上下留边；屏幕比游戏更高 → 左右留边。
 */
internal fun centeredGameViewport(
    width: Int,
    height: Int,
    aspectRatio: Double,
): GameViewportBounds {
    if (width <= 0 || height <= 0 || aspectRatio <= 0.0) {
        return GameViewportBounds(left = 0, top = 0, width = width.coerceAtLeast(0), height = height.coerceAtLeast(0))
    }
    val screenAspect = width.toDouble() / height.toDouble()
    return if (screenAspect > aspectRatio) {
        val targetWidth = (height * aspectRatio).roundToInt().coerceIn(1, width)
        GameViewportBounds(
            left = (width - targetWidth) / 2,
            top = 0,
            width = targetWidth,
            height = height,
        )
    } else {
        val targetHeight = (width / aspectRatio).roundToInt().coerceIn(1, height)
        GameViewportBounds(
            left = 0,
            top = (height - targetHeight) / 2,
            width = width,
            height = targetHeight,
        )
    }
}
