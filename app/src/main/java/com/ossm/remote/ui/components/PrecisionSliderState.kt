package com.ossm.remote.ui.components

import kotlin.math.abs

internal enum class PrecisionLevel {
    Global,
    Fine30,
    Fine20,
    Fine10,
    Fine5;

    val halfSpan: Float?
        get() = when (this) {
            Global -> null
            Fine30 -> 30f
            Fine20 -> 20f
            Fine10 -> 10f
            Fine5 -> 5f
        }

    val next: PrecisionLevel?
        get() = when (this) {
            Global -> Fine30
            Fine30 -> Fine20
            Fine20 -> Fine10
            Fine10 -> Fine5
            Fine5 -> null
        }
}

internal class PrecisionSliderState(
    private val movementThresholdPx: Float = 8f
) {
    var valuePercent: Float = 0f
        private set
    var level: PrecisionLevel = PrecisionLevel.Global
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

    fun onDown(valuePercent: Float, pointerX: Float, trackWidthPx: Float, uptimeMillis: Long) {
        active = true
        this.valuePercent = valuePercent.coerceIn(GLOBAL_RANGE)
        this.trackWidthPx = trackWidthPx.coerceAtLeast(1f)
        dragOriginX = pointerX
        dragOriginValue = this.valuePercent
        stationaryAnchorX = pointerX
        latestPointerX = pointerX
        stationarySinceMillis = uptimeMillis
        resetVisualRange()
    }

    fun onMove(pointerX: Float, uptimeMillis: Long): Boolean {
        if (!active) return false

        val previousValue = valuePercent
        latestPointerX = pointerX

        // The movement threshold is only a stationary-clock debounce. Mapping must still
        // happen for every pointer position so a slow/small drag does not appear frozen or
        // advance in an 8 px staircase. The drag remains relative to the original (or last
        // precision-narrowed) origin, so a tap still cannot teleport the value.
        val span = visibleRange.endInclusive - visibleRange.start
        valuePercent = (dragOriginValue + (pointerX - dragOriginX) / trackWidthPx * span)
            .coerceIn(GLOBAL_RANGE)

        if (abs(pointerX - stationaryAnchorX) > movementThresholdPx) {
            stationaryAnchorX = pointerX
            stationarySinceMillis = uptimeMillis
        }

        return valuePercent != previousValue
    }

    fun advanceTime(uptimeMillis: Long): Boolean {
        if (!active || uptimeMillis - stationarySinceMillis < LEVEL_DELAY_MILLIS) return false
        val nextLevel = level.next ?: return false

        level = nextLevel
        visibleRange = centeredRange(valuePercent, checkNotNull(level.halfSpan) * 2f)
        stationarySinceMillis = uptimeMillis
        dragOriginX = latestPointerX
        dragOriginValue = valuePercent
        return true
    }

    fun release(): Float? {
        if (!active) return null
        active = false
        val committed = valuePercent
        resetVisualRange()
        return committed
    }

    fun cancel() {
        active = false
        resetVisualRange()
    }

    private fun resetVisualRange() {
        level = PrecisionLevel.Global
        visibleRange = GLOBAL_RANGE
    }

    private fun centeredRange(center: Float, span: Float): ClosedFloatingPointRange<Float> {
        val half = span / 2f
        val start = (center - half).coerceIn(GLOBAL_RANGE.start, GLOBAL_RANGE.endInclusive - span)
        return start..(start + span)
    }

    private companion object {
        val GLOBAL_RANGE = 0f..100f
        const val LEVEL_DELAY_MILLIS = 1_500L
    }
}
