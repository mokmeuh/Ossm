package com.ossm.remote.voice

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Mode Voix (2.3.26) — base only. A spoken instruction will be turned into steps by a future
 * ON-DEVICE model (no network). This file holds the executable step model and its strict bounds.
 */

/** One executable step: logical target 0..100 (0 = home, 100 = deep), travel duration, hold. */
data class VoiceStep(val targetPercent: Int, val moveMs: Int, val holdMs: Int)

/** Strict validation: any out-of-bounds field rejects the whole plan (nothing is clamped). */
object VoiceStepValidator {
    const val MIN_TARGET = 0
    const val MAX_TARGET = 100
    const val MIN_MOVE_MS = 200
    const val MAX_MOVE_MS = 10_000
    const val MIN_HOLD_MS = 0
    const val MAX_HOLD_MS = 10_000
    const val MAX_STEPS = 12

    fun isValid(step: VoiceStep): Boolean =
        step.targetPercent in MIN_TARGET..MAX_TARGET &&
            step.moveMs in MIN_MOVE_MS..MAX_MOVE_MS &&
            step.holdMs in MIN_HOLD_MS..MAX_HOLD_MS

    /** The plan itself when valid, null otherwise (empty, too long or one invalid step). */
    fun validate(steps: List<VoiceStep>): List<VoiceStep>? {
        if (steps.isEmpty() || steps.size > MAX_STEPS) return null
        return steps.takeIf { it.all(::isValid) }?.toList()
    }
}

object VoicePlanner {
    /** Logical rate (%/s) at a 100 % voice speed maximum (100 % travel in 250 ms). */
    const val FULL_SPEED_RATE_PERCENT_PER_SECOND = 400f

    fun maxRateFor(speedMaximum: Float): Float =
        FULL_SPEED_RATE_PERCENT_PER_SECOND * speedMaximum.coerceIn(0f, 1f)

    /** Minimum travel time so that [distance] points never exceed the user maximum. */
    fun minimumMoveMs(distance: Int, maxRatePercentPerSecond: Float): Int {
        if (distance <= 0) return 0
        val rate = maxRatePercentPerSecond.coerceAtLeast(0.5f)
        return ceil(distance * 1000f / rate).toInt()
    }

    /**
     * Applies the user speed maximum to an already validated plan: each move lasts at least
     * distance / max rate (it may then exceed [VoiceStepValidator.MAX_MOVE_MS], safety wins).
     * A step whose target equals the position does not move (0 ms) and only holds.
     */
    fun bound(steps: List<VoiceStep>, startPercent: Int, maxRatePercentPerSecond: Float): List<VoiceStep> {
        var position = startPercent.coerceIn(0, 100)
        return steps.map { step ->
            val target = step.targetPercent.coerceIn(0, 100)
            val distance = abs(target - position)
            val move = if (distance == 0) 0 else maxOf(step.moveMs, minimumMoveMs(distance, maxRatePercentPerSecond))
            position = target
            VoiceStep(target, move, step.holdMs)
        }
    }
}

/** What the screen shows about the last instruction. */
enum class VoiceInterpretation {
    NONE,
    PLANNED,        // a valid plan was accepted and is executing
    REJECTED,       // plan outside the strict bounds: nothing moves
    NOT_UNDERSTOOD, // interpreter could not understand: nothing moves
    STOPPED,        // STOP (button or interpreter)
    NOT_READY,      // Live not ready / speed maximum at 0: nothing moves
}

/** Interpreter input: raw microphone audio (16 kHz mono PCM16) or text. */
sealed interface VoiceInput {
    class Audio(val pcm16: ShortArray, val sampleRateHz: Int = VoiceAudioSource.SAMPLE_RATE_HZ) : VoiceInput
    data class Text(val text: String) : VoiceInput
}

sealed interface VoiceInterpretResult {
    data class Steps(val steps: List<VoiceStep>, val immediate: Boolean = false) : VoiceInterpretResult
    data object Stop : VoiceInterpretResult
    /** 2.3.28: continuous back-and-forth rhythm (bounds checked by the store). */
    data class Motif(val motif: VoiceMotif) : VoiceInterpretResult
    /** 2.3.28: modulation of the running motif ("plus vite", "garde ce rythme", ...). */
    data class Modulate(val modulation: VoiceModulation) : VoiceInterpretResult
    data object NotUnderstood : VoiceInterpretResult
    /** Nothing usable was heard (empty transcript): no state change, nothing moves. */
    data object Ignored : VoiceInterpretResult
}

/**
 * Seam for the future on-device model (e.g. Google AI Edge / Gemma). Implementations must run
 * fully on the phone. Whatever they return is validated by [VoiceStepValidator] and bounded by
 * [VoicePlanner] before reaching the Live pipeline; free text is never executed.
 */
interface VoiceCommandInterpreter {
    /** False while no on-device model is installed: the screen says so and nothing listens. */
    val isInstalled: Boolean

    suspend fun interpret(input: VoiceInput): VoiceInterpretResult
}

/** 2.3.26 default: no on-device model yet. */
object NotInstalledVoiceCommandInterpreter : VoiceCommandInterpreter {
    override val isInstalled: Boolean = false
    override suspend fun interpret(input: VoiceInput): VoiceInterpretResult = VoiceInterpretResult.NotUnderstood
}

/** Deterministic interpreter for tests: returns the scripted result for any input. */
class FakeVoiceCommandInterpreter(
    var next: VoiceInterpretResult = VoiceInterpretResult.NotUnderstood,
    override val isInstalled: Boolean = true,
) : VoiceCommandInterpreter {
    val inputs = mutableListOf<VoiceInput>()
    override suspend fun interpret(input: VoiceInput): VoiceInterpretResult {
        inputs += input
        return next
    }
}
