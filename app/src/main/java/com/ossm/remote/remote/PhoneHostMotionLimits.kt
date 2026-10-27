package com.ossm.remote.remote

import com.ossm.remote.model.StrokeEngineCommand
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Local limits owned by the phone for the lifetime of one remote session.
 *
 * The browser may request a value inside this envelope, but it cannot replace
 * the envelope. The Store intersects [depthMinimum]/[depthMaximum] with any
 * lower local setting made while the session is active.
 */
data class PhoneHostMotionLimits(
    val speedMaximum: Float,
    val depthMinimum: Float,
    val depthMaximum: Float,
    /**
     * Symmetric sensation INTENSITY (0..0.5) the browser may use around the neutral 0.5.
     * Sensation is bipolar (0..1, 0.5 = neutral, firmware -100..0..+100): the browser may
     * request any value in [sensationMinimum]..[sensationMaximum] = 0.5 - d .. 0.5 + d.
     * Lowering it re-centres the current sensation into the narrower range; raising it never
     * moves the current sensation.
     */
    val sensationIntensity: Float = SENSATION_MAX_INTENSITY,
) {
    private val safeSpeedMaximum = speedMaximum.coerceIn(0f, 1f)
    private val safeSensationIntensity =
        sensationIntensity.takeUnless { it.isNaN() }?.coerceIn(0f, SENSATION_MAX_INTENSITY) ?: 0f
    private val safeDepthMinimum = depthMinimum.coerceIn(0f, 1f)
    private val safeDepthMaximum = depthMaximum.coerceIn(safeDepthMinimum, 1f)

    /** Lower bound (0..0.5) of the sensation range the browser may request. */
    val sensationMinimum: Float get() = SENSATION_NEUTRAL - safeSensationIntensity

    /** Upper bound (0.5..1) of the sensation range the browser may request. */
    val sensationMaximum: Float get() = SENSATION_NEUTRAL + safeSensationIntensity

    fun clampSpeed(requested: Float): Float = requested.coerceIn(0f, safeSpeedMaximum)

    /**
     * Clamp into [sensationMinimum]..[sensationMaximum]; NaN falls back to neutral.
     * The lower bound is nudged up by a float epsilon: BLE encodes `(value * 100).toInt()`
     * (truncation), so 0.29999995 would otherwise leave as 29 % below a 30 % bound. The upper
     * bound needs no nudge since truncation only lowers it.
     */
    fun clampSensation(requested: Float): Float {
        if (requested.isNaN()) return SENSATION_NEUTRAL
        val upper = sensationMaximum
        val lower = minOf(sensationMinimum + LOWER_BOUND_EPSILON, upper)
        return requested.coerceIn(lower, upper)
    }

    /**
     * Clamp both endpoints without allowing a remote request to widen the
     * captured interval. Ordering is preserved for malformed caller input.
     */
    fun clampDepthRange(requestedMinimum: Float, requestedMaximum: Float): Pair<Float, Float> {
        val orderedMinimum = minOf(requestedMinimum, requestedMaximum)
        val orderedMaximum = maxOf(requestedMinimum, requestedMaximum)
        return orderedMinimum.coerceIn(safeDepthMinimum, safeDepthMaximum) to
            orderedMaximum.coerceIn(safeDepthMinimum, safeDepthMaximum)
    }

    /**
     * Intersect this captured interval with a local interval. A null result is
     * an incompatible-range stop/reject condition; callers must not reuse the
     * stale captured envelope or widen it implicitly.
     */
    fun intersectLocalDepthRange(localMinimum: Float, localMaximum: Float): PhoneHostMotionLimits? {
        val localLow = minOf(localMinimum, localMaximum).coerceIn(0f, 1f)
        val localHigh = maxOf(localMinimum, localMaximum).coerceIn(localLow, 1f)
        val intersectedLow = maxOf(safeDepthMinimum, localLow)
        val intersectedHigh = minOf(safeDepthMaximum, localHigh)
        if (intersectedLow <= intersectedHigh) {
            return copy(
                depthMinimum = intersectedLow,
                depthMaximum = intersectedHigh,
            )
        }
        return null
    }

    /**
     * Map a remote Live percentage into the phone's allowed physical interval.
     * A remote 0..100 gesture therefore cannot reach outside the local range.
     */
    fun mapLivePositionPercent(remotePercent: Int, liveInvert: Boolean = false): Int {
        val normalized = remotePercent.coerceIn(0, 100) / 100f
        val physical = if (liveInvert) {
            safeDepthMinimum + normalized * (safeDepthMaximum - safeDepthMinimum)
        } else {
            safeDepthMaximum - normalized * (safeDepthMaximum - safeDepthMinimum)
        }
        return (physical * 100f)
            .roundToInt()
            .coerceIn(0, 100)
    }

    /**
     * Return the position that the device will receive after the existing Live
     * inverse mapping. This is intentionally separate from the UI percentage:
     * callers can assert the actual actuator bound even when Live inversion is
     * disabled.
     */
    fun mapLiveDevicePositionPercent(remotePercent: Int, liveInvert: Boolean): Int {
        return mapLivePositionPercent(remotePercent, liveInvert)
    }

    /** Existing BLE mapping is the inverse of [mapLiveDevicePositionPercent]. */
    fun mapLiveUiPositionForBle(remotePercent: Int, liveInvert: Boolean): Int {
        val device = mapLiveDevicePositionPercent(remotePercent, liveInvert)
        return if (liveInvert) device else 100 - device
    }

    /** Clamp an already device-mapped/physical Live target without re-scaling it. */
    fun clampPhysicalPositionPercent(physicalPercent: Int): Int = physicalPercent.coerceIn(
        (safeDepthMinimum * 100f).roundToInt(),
        (safeDepthMaximum * 100f).roundToInt(),
    )

    /** Convert a bounded physical/device target back to the existing BLE raw argument. */
    fun mapPhysicalPositionForBle(physicalPercent: Int, liveInvert: Boolean): Int {
        val boundedPhysical = clampPhysicalPositionPercent(physicalPercent)
        return if (liveInvert) boundedPhysical else 100 - boundedPhysical
    }

    fun hasMinimumDepthSpan(minimumSpan: Float = MINIMUM_DEPTH_SPAN): Boolean =
        safeDepthMaximum - safeDepthMinimum >= minimumSpan

    fun clampStrokeEngineCommand(command: StrokeEngineCommand): StrokeEngineCommand {
        val (minimum, maximum) = clampDepthRange(command.depthMin, command.depthMax)
        return command.copy(
            speed = clampSpeed(command.speed),
            depthMin = minimum,
            depthMax = maximum,
            sensation = clampSensation(command.sensation),
        )
    }

    companion object {
        private const val MINIMUM_DEPTH_SPAN = 0.05f
        private const val LOWER_BOUND_EPSILON = 0.00001f

        /** Neutral sensation on the app's 0..1 scale (firmware 0 on -100..+100). */
        const val SENSATION_NEUTRAL = 0.5f
        const val SENSATION_MAX_INTENSITY = 0.5f

        /** Local slider position (0..1) to the symmetric intensity d = |s - 0.5| (0..0.5). */
        fun sensationIntensityOf(sliderValue: Float): Float =
            if (sliderValue.isNaN()) {
                0f
            } else {
                abs(sliderValue.coerceIn(0f, 1f) - SENSATION_NEUTRAL).coerceIn(0f, SENSATION_MAX_INTENSITY)
            }
    }
}

/** Ordinary phone readiness; intentionally contains no firmware remote lease state. */
data class PhoneHostReadiness(
    val connected: Boolean,
    val machineReady: Boolean,
    val postSetupReady: Boolean,
) {
    val allowsRemoteMotion: Boolean
        get() = connected && machineReady && postSetupReady

    /** Compatibility spelling for older adapter fixtures. */
    val allowsInternetMotion: Boolean
        get() = allowsRemoteMotion
}

