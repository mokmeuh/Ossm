package com.ossm.remote.ui.components

import kotlin.math.abs

internal class LiveSliderState(
    private val movementThresholdPx: Float = 8f
) {
    var valuePercent: Float = 0f
        private set
    var visibleRange: ClosedFloatingPointRange<Float> = GLOBAL_RANGE
        private set

    private var active = false
    private var trackWidthPx = 1f
    private var dragOriginX = 0f
    private var dragOriginValue = 0f
    private var stationaryAnchorX = 0f
    private var latestPointerX = 0f
    private var stationarySinceMillis = 0L
    private var halfSpan: Float? = null

    fun onDown(valuePercent: Float, pointerX: Float, trackWidthPx: Float, uptimeMillis: Long) {
        active = true
        this.valuePercent = valuePercent.coerceIn(GLOBAL_RANGE)
        this.trackWidthPx = trackWidthPx.coerceAtLeast(1f)
        dragOriginX = pointerX
        dragOriginValue = this.valuePercent
        stationaryAnchorX = pointerX
        latestPointerX = pointerX
        stationarySinceMillis = uptimeMillis
        visibleRange = GLOBAL_RANGE
        halfSpan = null
    }

    fun onMove(pointerX: Float, uptimeMillis: Long): Boolean {
        if (!active) return false
        latestPointerX = pointerX
        if (abs(pointerX - stationaryAnchorX) <= movementThresholdPx) return false

        val span = visibleRange.endInclusive - visibleRange.start
        valuePercent = (dragOriginValue + (pointerX - dragOriginX) / trackWidthPx * span)
            .coerceIn(GLOBAL_RANGE)
        stationaryAnchorX = pointerX
        stationarySinceMillis = uptimeMillis
        return true
    }

    fun advanceTime(uptimeMillis: Long): Boolean {
        if (!active || uptimeMillis - stationarySinceMillis < NARROW_DELAY_MILLIS) return false
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
        dragOriginX = latestPointerX
        dragOriginValue = valuePercent
        return true
    }

    fun release(): Float? {
        if (!active) return null
        active = false
        val committed = valuePercent
        resetRange()
        return committed
    }

    fun cancel() {
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
