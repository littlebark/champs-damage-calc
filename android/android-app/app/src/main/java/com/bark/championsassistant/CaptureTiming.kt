package com.bark.championsassistant

/** 一次截图的耗时信息：请求时刻、隐藏 HUD 的等待耗时、复制帧耗时，用于真机排查延迟。 */
internal data class CaptureTiming(
    val requestedAtNanos: Long,
    val hideWaitMs: Double,
    val frameCopyMs: Double,
)
