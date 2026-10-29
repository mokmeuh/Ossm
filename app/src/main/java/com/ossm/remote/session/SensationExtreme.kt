package com.ossm.remote.session

import com.ossm.remote.viewmodel.ControlUiState
import com.ossm.remote.viewmodel.RandomTarget

/**
 * Extreme sensation used by the "Max" option: the top of the range while the actuator
 * advances (fast advance) and the bottom of it while it retracts (fast retract).
 *
 * The range is the user range [userMin]..[userMax], never wider than the envelope
 * [envelopeMin]..[envelopeMax]. If the two do not intersect, the nearest envelope bound is used.
 */
internal fun sensationExtreme(
    advance: Boolean,
    userMin: Float,
    userMax: Float,
    envelopeMin: Float = 0f,
    envelopeMax: Float = 1f
): Float {
    val envLo = minOf(envelopeMin, envelopeMax).coerceIn(0f, 1f)
    val envHi = maxOf(envelopeMin, envelopeMax).coerceIn(0f, 1f)
    val lo = minOf(userMin, userMax).coerceIn(envLo, envHi)
    val hi = maxOf(userMin, userMax).coerceIn(envLo, envHi)
    return if (advance) hi else lo
}

/** "Max" and "Random" sensation are mutually exclusive: turning one on turns the other off. */
internal fun ControlUiState.withSensationToggle(target: RandomTarget, enabled: Boolean): ControlUiState =
    when (target) {
        RandomTarget.SENSATION -> copy(randSensation = enabled, maxSensation = maxSensation && !enabled)
        RandomTarget.SENSATION_MAX -> copy(maxSensation = enabled, randSensation = randSensation && !enabled)
        else -> this
    }
