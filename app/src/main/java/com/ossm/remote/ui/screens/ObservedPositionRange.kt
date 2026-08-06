package com.ossm.remote.ui.screens

internal class ObservedPositionRange private constructor(
    private val minMm: Float?,
    private val maxMm: Float?,
    private val lastMm: Float?
) {
    val markerFraction: Float?
        get() {
            val min = minMm ?: return null
            val max = maxMm ?: return null
            val last = lastMm ?: return null
            val observedTravelMm = max - min
            if (observedTravelMm < MIN_OBSERVED_TRAVEL_MM) return null

            return ((last - min) / observedTravelMm).coerceIn(0f, 1f)
        }

    fun observe(positionMm: Float): ObservedPositionRange {
        if (!positionMm.isFinite()) return this

        return ObservedPositionRange(
            minMm = minMm?.coerceAtMost(positionMm) ?: positionMm,
            maxMm = maxMm?.coerceAtLeast(positionMm) ?: positionMm,
            lastMm = positionMm
        )
    }

    companion object {
        private const val MIN_OBSERVED_TRAVEL_MM = 5f

        fun empty(): ObservedPositionRange = ObservedPositionRange(
            minMm = null,
            maxMm = null,
            lastMm = null
        )
    }
}
