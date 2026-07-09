package com.ossm.remote.ble

internal fun mapLiveStreamPositionPercent(
    sliderPercent: Int,
    liveInvert: Boolean
): Int {
    val clamped = sliderPercent.coerceIn(0, 100)
    val mapped = if (liveInvert) 100 - clamped else clamped
    return mapped.coerceIn(2, 98)
}

internal fun isLatencyCompensationEnabledForLive(): Boolean = true
