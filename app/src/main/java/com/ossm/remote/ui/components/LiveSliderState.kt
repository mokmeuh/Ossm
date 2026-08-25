package com.ossm.remote.ui.components

import kotlin.math.abs

internal class LiveSliderState(
    private val movementThresholdPx: Float = 0f,
    private val precisionEnabled: Boolean = false
) {
    var valuePercent: Float = 0f
        private set
    var visibleRange: ClosedFloatingPointRange<Float> = GLOBAL_RANGE
        private set

    private var active = false
    private var trackHeightPx = 1f
    private var dragOriginY = 0f
    private var dragOriginValue = 0f
    private var stationaryAnchorY = 0f
    private var latestPointerY = 0f
    private var stationarySinceMillis = 0L
    private var halfSpan: Float? = null

    fun onDown(pointerY: Float, trackHeightPx: Float, uptimeMillis: Long): Float {
        return onDown(
            valuePercent = livePositionForPointer(pointerY, trackHeightPx),
            pointerY = pointerY,
            trackHeightPx = trackHeightPx,
            uptimeMillis = uptimeMillis
        )
    }

    fun onDown(
        valuePercent: Float,
        pointerY: Float,
        trackHeightPx: Float,
        uptimeMillis: Long
    ): Float {
        active = true
        visibleRange = GLOBAL_RANGE
        halfSpan = null
        this.trackHeightPx = trackHeightPx.coerceAtLeast(1f)
        this.valuePercent = valuePercent.coerceIn(GLOBAL_RANGE)
        dragOriginY = pointerY
        dragOriginValue = this.valuePercent
        stationaryAnchorY = pointerY
        latestPointerY = pointerY
        stationarySinceMillis = uptimeMillis
        return valuePercent
    }

    fun onMove(pointerY: Float, uptimeMillis: Long): Float? {
        if (!active) return null
        latestPointerY = pointerY
        if (abs(pointerY - stationaryAnchorY) <= movementThresholdPx) return null

        val span = visibleRange.endInclusive - visibleRange.start
        valuePercent = (dragOriginValue + (dragOriginY - pointerY) / trackHeightPx * span)
            .coerceIn(GLOBAL_RANGE)
        stationaryAnchorY = pointerY
        stationarySinceMillis = uptimeMillis
        return valuePercent
    }

    fun advanceTime(uptimeMillis: Long): Boolean {
        if (!precisionEnabled || !active || uptimeMillis - stationarySinceMillis < NARROW_DELAY_MILLIS) {
            return false
        }
        val currentHalfSpan = halfSpan
        val nextHalfSpan = when {
            currentHalfSpan == null -> FIRST_HALF_SPAN
            currentHalfSpan > MINIMUM_HALF_SPAN ->
                (currentHalfSpan - NARROW_STEP).coerceAtLeast(MINIMUM_HALF_SPAN)
            else -> return false
        }
        visibleRange = if (currentHalfSpan == null) {
            10f..90f
        } else {
            centeredRange(valuePercent, nextHalfSpan)
        }
        halfSpan = nextHalfSpan
        stationarySinceMillis = uptimeMillis
        dragOriginY = latestPointerY
        dragOriginValue = valuePercent
        return true
    }

    fun endTouch() {
        active = false
        resetRange()
    }

    private fun resetRange() {
        visibleRange = GLOBAL_RANGE
        halfSpan = null
    }

    private fun centeredRange(center: Float, halfSpan: Float): ClosedFloatingPointRange<Float> {
        val span = halfSpan * 2f
        val start = (center - halfSpan).coerceIn(GLOBAL_RANGE.start, GLOBAL_RANGE.endInclusive - span)
        return start..(start + span)
    }

    private companion object {
        val GLOBAL_RANGE = 0f..100f
        const val NARROW_DELAY_MILLIS = 2_000L
        const val NARROW_STEP = 10f
        const val FIRST_HALF_SPAN = 40f
        const val MINIMUM_HALF_SPAN = 5f
    }
}

internal fun liveThumbCenterY(
    valuePercent: Float,
    trackHeightPx: Float,
    trackInsetPx: Float
): Float {
    val height = trackHeightPx.coerceAtLeast(1f)
    val inset = trackInsetPx.coerceIn(0f, height / 2f)
    val top = inset
    val bottom = height - inset
    return bottom - (bottom - top) * (valuePercent.coerceIn(0f, 100f) / 100f)
}

internal fun isLiveThumbHit(pointerY: Float, thumbY: Float, hitRadiusPx: Float): Boolean =
    abs(pointerY - thumbY) <= hitRadiusPx.coerceAtLeast(0f)

internal fun livePositionForPointer(
    pointerY: Float,
    trackHeightPx: Float
): Float {
    val height = trackHeightPx.coerceAtLeast(1f)
    return ((1f - pointerY.coerceIn(0f, height) / height) * 100f).coerceIn(0f, 100f)
}
