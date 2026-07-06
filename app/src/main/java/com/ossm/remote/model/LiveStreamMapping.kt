package com.ossm.remote.model

internal fun mapLiveSliderPercentToStreamPosition(rawSliderPercent: Int): Int {
    val clampedSlider = rawSliderPercent.coerceIn(0, 100)
    return clampedSlider.coerceIn(2, 98)
}

internal fun mapLiveDisplayPercentToRawSliderPercent(displayPercent: Int): Int {
    val clampedDisplay = displayPercent.coerceIn(0, 100)
    return 100 - clampedDisplay
}
