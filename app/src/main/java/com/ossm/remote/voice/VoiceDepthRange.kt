package com.ossm.remote.voice

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Voix depth range (2.3.28): the user picks a minimum and a maximum (fractions 0..1 of the
 * full stroke) that are always intersected with the hard depth limits. Voice targets are
 * logical percentages: 0 % = the range minimum, 100 % = the range maximum.
 */
object VoiceDepthRange {
    /** Same minimum span as the hard depth limits. */
    const val MIN_SPAN = 0.05f

    /**
     * Orders, clamps into [limitMin]..[limitMax] and widens to [MIN_SPAN] (inside the limits
     * when they allow it). Never returns a range outside the limits.
     */
    fun effective(
        voiceMin: Float,
        voiceMax: Float,
        limitMin: Float,
        limitMax: Float,
    ): Pair<Float, Float> {
        val lowLimit = minOf(limitMin, limitMax).coerceIn(0f, 1f)
        val highLimit = maxOf(limitMin, limitMax).coerceIn(0f, 1f)
        val a = (if (voiceMin.isNaN()) 0f else voiceMin).coerceIn(lowLimit, highLimit)
        val b = (if (voiceMax.isNaN()) 1f else voiceMax).coerceIn(lowLimit, highLimit)
        var low = minOf(a, b)
        var high = maxOf(a, b)
        val span = minOf(MIN_SPAN, highLimit - lowLimit)
        if (high - low < span) {
            if (low + span <= highLimit) {
                high = low + span
            } else {
                high = highLimit
                low = highLimit - span
            }
        }
        return low to high
    }

    /** Logical voice percent (0..100) -> Live scalar (0..100) inside [range]. */
    fun scalarFor(logical: Int, range: Pair<Float, Float>): Int {
        val low = range.first.coerceIn(0f, 1f) * 100f
        val high = range.second.coerceIn(0f, 1f) * 100f
        return (low + (high - low) * logical.coerceIn(0, 100) / 100f).roundToInt().coerceIn(0, 100)
    }

    /**
     * Inverse of [scalarFor], clamped to 0..100: the logical position a new plan starts from
     * when the range changed under the current scalar.
     */
    fun logicalFor(scalar: Int, range: Pair<Float, Float>): Int {
        val low = range.first.coerceIn(0f, 1f) * 100f
        val high = range.second.coerceIn(0f, 1f) * 100f
        if (high - low <= 0f) return 0
        return ((scalar.coerceIn(0, 100) - low) / (high - low) * 100f).roundToInt().coerceIn(0, 100)
    }

    /**
     * Largest scalar step one legitimate Voix target can carry ([maxStepLogical] logical points
     * scaled into the range) plus one point of rounding. A larger gap between the current
     * scalar and the next voice target is a discontinuity and goes through the bounded ramp.
     */
    fun directTolerance(maxStepLogical: Int, range: Pair<Float, Float>): Int {
        val span = (range.second - range.first).coerceIn(0f, 1f)
        return ceil(maxStepLogical.coerceAtLeast(0) * span).toInt() + 1
    }
}
