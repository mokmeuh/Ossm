package com.ossm.remote.session

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Phone-owned cap for desired Live targets from both native and browser producers. */
internal const val LIVE_TARGET_SLEW_RATE_PERCENT_PER_SECOND = 80f
internal const val LIVE_TARGET_INITIAL_STEP_PERCENT = 5f
private const val LIVE_TARGET_MIN_ELAPSED_MILLIS = 16L
private const val LIVE_RETURN_RAMP_MIN_ELAPSED_MILLIS = 50L

/** First bounded step when the actuator walks up to the finger after the first touch. */
internal const val LIVE_JOIN_INITIAL_STEP_PERCENT = 2f

/** Normal release/entry return cap: 100 -> 0 takes at least five seconds. */
internal const val LIVE_RELEASE_RETURN_RATE_PERCENT_PER_SECOND = 20f

internal class LiveTargetSlew(
    initialPercent: Int,
    private val ratePercentPerSecond: Float = LIVE_TARGET_SLEW_RATE_PERCENT_PER_SECOND,
    private val initialStepPercent: Float = LIVE_TARGET_INITIAL_STEP_PERCENT,
    private val descentRatePercentPerSecond: Float = ratePercentPerSecond,
) {
    private var currentPercent = initialPercent.coerceIn(0, 100).toFloat()
    private var desiredPercent = currentPercent
    private var firstAdvance = true
    private var lastAtMillis = 0L

    val current: Int
        get() = currentPercent.roundToInt().coerceIn(0, 100)

    val desired: Int
        get() = desiredPercent.roundToInt().coerceIn(0, 100)

    fun setDesired(percent: Int) {
        desiredPercent = percent.coerceIn(0, 100).toFloat()
    }

    fun advance(uptimeMillis: Long): Int {
        // A monotonic clock can have the same sample across adjacent coroutine ticks (and a
        // virtual test clock may not advance at all). Keep a small deterministic floor so a held
        // desired target still progresses instead of spinning forever at one scalar value.
        val elapsedMillis = (uptimeMillis - lastAtMillis)
            .coerceAtLeast(LIVE_TARGET_MIN_ELAPSED_MILLIS)
        val delta = desiredPercent - currentPercent
        val rate = if (delta < 0f) descentRatePercentPerSecond else ratePercentPerSecond
        val rateLimit = if (firstAdvance) {
            initialStepPercent.coerceAtLeast(0f)
        } else {
            (rate.coerceAtLeast(0f) * elapsedMillis / 1_000f).coerceAtLeast(0f)
        }
        currentPercent = when {
            delta > rateLimit -> currentPercent + rateLimit
            delta < -rateLimit -> currentPercent - rateLimit
            else -> desiredPercent
        }.coerceIn(0f, 100f)
        firstAdvance = false
        lastAtMillis = uptimeMillis
        return current
    }

    fun isAtDesired(): Boolean = current == desired
}

/** Deterministic scalar ramp used by normal release and Live-entry convergence. */
internal class LiveReturnRamp(
    initialPercent: Int,
    private val ratePercentPerSecond: Float = LIVE_RELEASE_RETURN_RATE_PERCENT_PER_SECOND,
    startAtMillis: Long = 0L,
) {
    private var currentPercent = initialPercent.coerceIn(0, 100).toFloat()
    private var lastAtMillis = startAtMillis
    private var firstAdvance = true

    val isComplete: Boolean
        get() = currentPercent <= 0f

    val current: Int
        get() = currentPercent.roundToInt().coerceIn(0, 100)

    fun advance(uptimeMillis: Long): Int {
        val elapsedMillis = if (firstAdvance) {
            0L
        } else {
            (uptimeMillis - lastAtMillis).coerceAtLeast(LIVE_RETURN_RAMP_MIN_ELAPSED_MILLIS)
        }
        val rate = ratePercentPerSecond.coerceIn(0f, LIVE_RELEASE_RETURN_RATE_PERCENT_PER_SECOND)
        currentPercent = (currentPercent - rate * elapsedMillis / 1_000f).coerceAtLeast(0f)
        lastAtMillis = uptimeMillis
        firstAdvance = false
        return current
    }
}

internal fun liveReturnDurationMillis(
    initialPercent: Int,
    ratePercentPerSecond: Float = LIVE_RELEASE_RETURN_RATE_PERCENT_PER_SECOND,
): Long {
    val rate = ratePercentPerSecond.coerceIn(0f, LIVE_RELEASE_RETURN_RATE_PERCENT_PER_SECOND)
    if (initialPercent <= 0 || rate <= 0f) return 0L
    return ceil(initialPercent.coerceIn(0, 100) * 1_000f / rate).toLong()
}
