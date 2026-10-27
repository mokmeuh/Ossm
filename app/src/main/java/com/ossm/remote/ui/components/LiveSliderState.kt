package com.ossm.remote.ui.components

import kotlin.math.abs
import kotlin.math.roundToInt

internal data class LiveTrackGeometry(
    val top: Float,
    val bottom: Float,
    val height: Float,
    val thumbY: Float
)

/**
 * Movement below this distance (px) does not restart the Precision stationary clock.
 * It never gates position emission: every integer change of the displayed target is sent.
 */
internal const val LIVE_STATIONARY_THRESHOLD_PX = 8f

internal class LiveSliderState(
    private val stationaryThresholdPx: Float = 0f,
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
    private var lastEmittedPercent = 0

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
        lastEmittedPercent = this.valuePercent.roundToInt()
        dragOriginY = pointerY
        dragOriginValue = this.valuePercent
        stationaryAnchorY = pointerY
        latestPointerY = pointerY
        stationarySinceMillis = uptimeMillis
        return this.valuePercent
    }

    fun onMove(pointerY: Float, uptimeMillis: Long): Float? {
        if (!active) return null
        latestPointerY = pointerY
        // Clock only: sensor jitter within the threshold must not keep Precision from arming.
        if (abs(pointerY - stationaryAnchorY) > stationaryThresholdPx) {
            stationaryAnchorY = pointerY
            stationarySinceMillis = uptimeMillis
        }

        // Emission is never gated by the threshold: the displayed integer target is the only dedupe.
        val span = visibleRange.endInclusive - visibleRange.start
        valuePercent = (dragOriginValue + (dragOriginY - pointerY) / trackHeightPx * span)
            .coerceIn(GLOBAL_RANGE)
        // A sub-percent touch must not arm motion at the unchanged displayed target.
        val nextPercent = valuePercent.roundToInt()
        if (nextPercent == lastEmittedPercent) return null
        lastEmittedPercent = nextPercent
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
        stationaryAnchorY = latestPointerY
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

internal fun liveDisplayedFraction(
    valuePercent: Float,
    visibleRange: ClosedFloatingPointRange<Float>
): Float {
    val start = visibleRange.start
    val end = visibleRange.endInclusive
    val span = (end - start).coerceAtLeast(1f)
    return ((valuePercent.coerceIn(0f, 100f) - start) / span).coerceIn(0f, 1f)
}

internal fun liveTrackGeometry(
    valuePercent: Float,
    visibleRange: ClosedFloatingPointRange<Float>,
    canvasHeightPx: Float,
    trackInsetPx: Float
): LiveTrackGeometry {
    val height = canvasHeightPx.coerceAtLeast(1f)
    val inset = trackInsetPx.coerceIn(0f, height / 2f)
    val top = inset
    val bottom = (height - inset).coerceAtLeast(top)
    val trackHeight = (bottom - top).coerceAtLeast(1f)
    val thumbY = bottom - trackHeight * liveDisplayedFraction(valuePercent, visibleRange)
    return LiveTrackGeometry(top = top, bottom = bottom, height = trackHeight, thumbY = thumbY)
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
