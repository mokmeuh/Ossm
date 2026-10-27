package com.ossm.remote.voice

/**
 * Voix 2.3.28: continuous rhythm ("commence lentement"). Back-and-forth strokes between the
 * Voix range minimum (logical 0) and a peak depth that rises linearly from [depthStart] to
 * [depthEnd] over [rampSeconds], with a cadence rising from [speedStart] to [speedEnd]
 * (percent of the Voix speed maximum, itself bounded by the remote envelope). After the ramp
 * it keeps the final level until STOP or a new instruction.
 */
data class VoiceMotif(
    val depthStart: Int,
    val depthEnd: Int,
    val speedStart: Int,
    val speedEnd: Int,
    val rampSeconds: Int,
)

/** Modulations understood while a motif runs. */
enum class VoiceModulation { FASTER, SLOWER, DEEPER, SHALLOWER, HOLD }

/** Strict bounds: one out-of-range field rejects the motif (nothing is clamped). */
object VoiceMotifValidator {
    const val KIND_BACK_AND_FORTH = "va-et-vient"
    val DEPTH_RANGE = 0..100
    val SPEED_RANGE = 1..100
    val RAMP_RANGE_S = 1..300

    fun isValid(motif: VoiceMotif): Boolean =
        motif.depthStart in DEPTH_RANGE && motif.depthEnd in DEPTH_RANGE &&
            motif.speedStart in SPEED_RANGE && motif.speedEnd in SPEED_RANGE &&
            motif.rampSeconds in RAMP_RANGE_S
}

/** Current levels of a motif: peak depth (logical 0..100) and cadence (percent 1..100). */
data class VoiceMotifLevels(val depth: Float, val speed: Float)

object VoiceMotifMath {
    /** Speed step of "plus vite" / "plus lentement" (percentage points of the maximum). */
    const val SPEED_STEP = 15
    /** Depth step of "plus profond" / "moins profond" (logical points). */
    const val DEPTH_STEP = 10

    // 2.3.29 floors applied when a motif STARTS (an LLM answer that is too timid moved ~4 %/s
    // over 18 % and felt like nothing). Everything stays inside the Voix range and under the
    // Voix speed maximum / remote envelope; explicit modulations may still go below.
    const val FLOOR_DEPTH_START = 30
    const val FLOOR_DEPTH_END = 60
    const val FLOOR_SPEED_START = 20
    const val FLOOR_SPEED_END = 40

    /** Raises a too-weak motif to perceptible levels (never lowers anything). */
    fun withStartFloors(motif: VoiceMotif): VoiceMotif {
        val depthStart = maxOf(motif.depthStart, FLOOR_DEPTH_START)
        val speedStart = maxOf(motif.speedStart, FLOOR_SPEED_START)
        return motif.copy(
            depthStart = depthStart,
            depthEnd = maxOf(motif.depthEnd, depthStart, FLOOR_DEPTH_END),
            speedStart = speedStart,
            speedEnd = maxOf(motif.speedEnd, speedStart, FLOOR_SPEED_END),
        )
    }

    fun levelsAt(motif: VoiceMotif, elapsedMs: Long): VoiceMotifLevels {
        val rampMs = motif.rampSeconds.coerceAtLeast(1) * 1000L
        val f = (elapsedMs.coerceAtLeast(0L).toFloat() / rampMs).coerceIn(0f, 1f)
        return VoiceMotifLevels(
            depth = motif.depthStart + (motif.depthEnd - motif.depthStart) * f,
            speed = motif.speedStart + (motif.speedEnd - motif.speedStart) * f,
        )
    }

    /**
     * New motif that starts from the CURRENT levels (no jump in the curves): faster/slower and
     * deeper/shallower shift the current level and the remaining end by one step (the
     * progression continues); HOLD ("garde ce rythme", "arrête d'augmenter") freezes both.
     */
    fun modulate(motif: VoiceMotif, elapsedMs: Long, modulation: VoiceModulation): VoiceMotif {
        val now = levelsAt(motif, elapsedMs)
        val d = Math.round(now.depth)
        val v = Math.round(now.speed)
        val remainingS = ((motif.rampSeconds * 1000L - elapsedMs.coerceAtLeast(0L)) / 1000L).toInt()
        val ramping = remainingS >= 1
        val depthEnd = if (ramping) motif.depthEnd else d
        val speedEnd = if (ramping) motif.speedEnd else v
        val ramp = if (ramping) remainingS else 1
        fun depth(x: Int) = x.coerceIn(VoiceMotifValidator.DEPTH_RANGE)
        fun speed(x: Int) = x.coerceIn(VoiceMotifValidator.SPEED_RANGE)
        return when (modulation) {
            VoiceModulation.FASTER ->
                VoiceMotif(d, depthEnd, speed(v + SPEED_STEP), speed(speedEnd + SPEED_STEP), ramp)
            VoiceModulation.SLOWER ->
                VoiceMotif(d, depthEnd, speed(v - SPEED_STEP), speed(speedEnd - SPEED_STEP), ramp)
            VoiceModulation.DEEPER ->
                VoiceMotif(depth(d + DEPTH_STEP), depth(depthEnd + DEPTH_STEP), v, speedEnd, ramp)
            VoiceModulation.SHALLOWER ->
                VoiceMotif(depth(d - DEPTH_STEP), depth(depthEnd - DEPTH_STEP), v, speedEnd, ramp)
            VoiceModulation.HOLD -> VoiceMotif(d, d, speed(v), speed(v), 1)
        }
    }
}
