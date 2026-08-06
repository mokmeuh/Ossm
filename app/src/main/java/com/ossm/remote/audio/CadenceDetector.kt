package com.ossm.remote.audio

import javax.inject.Inject

data class CadenceReading(
    val peakDetected: Boolean,
    val peaksPerMinute: Float,
    val speedFraction: Float
)

class CadenceDetector @Inject constructor() {
    private val peakTimestampsMs = ArrayDeque<Long>()
    private var isArmed = true
    private var lastPeakTimestampMs: Long? = null

    fun consume(normalizedLevel: Float, timestampMs: Long): CadenceReading {
        val level = normalizedLevel.coerceIn(0f, 1f)
        val peakDetected = isArmed && level >= PEAK_THRESHOLD && recordPeak(timestampMs)

        if (level <= REARM_THRESHOLD) {
            isArmed = true
        } else if (isArmed && level >= PEAK_THRESHOLD) {
            isArmed = false
        }

        discardExpiredPeaks(timestampMs)
        val peaksPerMinute = peakTimestampsMs.size * MILLIS_PER_MINUTE.toFloat() / HISTORY_MS
        val speedFraction = if (lastPeakTimestampMs == null ||
            timestampMs - lastPeakTimestampMs!! >= SILENCE_TIMEOUT_MS
        ) {
            0f
        } else {
            (peaksPerMinute / MAX_PEAKS_PER_MINUTE).coerceIn(0f, 1f)
        }

        return CadenceReading(peakDetected, peaksPerMinute, speedFraction)
    }

    private fun recordPeak(timestampMs: Long): Boolean {
        isArmed = false
        if (lastPeakTimestampMs?.let { timestampMs - it < REFRACTORY_MS } == true) {
            return false
        }

        peakTimestampsMs.addLast(timestampMs)
        lastPeakTimestampMs = timestampMs
        return true
    }

    private fun discardExpiredPeaks(timestampMs: Long) {
        while (peakTimestampsMs.firstOrNull()?.let { timestampMs - it >= HISTORY_MS } == true) {
            peakTimestampsMs.removeFirst()
        }
    }

    private companion object {
        const val PEAK_THRESHOLD = 0.35f
        const val REARM_THRESHOLD = 0.20f
        const val REFRACTORY_MS = 250L
        const val HISTORY_MS = 4_000L
        const val SILENCE_TIMEOUT_MS = 3_000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val MAX_PEAKS_PER_MINUTE = 120f
    }
}
