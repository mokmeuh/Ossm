package com.ossm.remote.session

import com.ossm.remote.voice.VoiceModulation
import com.ossm.remote.voice.VoiceMotif
import com.ossm.remote.voice.VoiceMotifLevels
import com.ossm.remote.voice.VoiceMotifMath
import com.ossm.remote.voice.VoiceStep
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Executes Voix steps as a stream of logical Live targets (0..100).
 *
 * 2.3.29: targets are SEGMENTS sized by the wanted speed, not 1 % ticks. The binary Live
 * firmware executes each target within ~250 ms, so 1 % per target capped the real speed at a
 * few %/s. Each target now carries its own duration (<= [SEGMENT_MS]) and a step of
 * speed x duration (integer, at most [MAX_STEP] points); it is emitted at the START of its
 * segment so the actuator arrives at the end of it. Planned move durations are respected.
 *
 * A sequence submitted during a move replaces the current one when that move ends, or
 * immediately when [submit] is called with `immediate = true` or while holding/idle. The end
 * of a sequence stays in place.
 *
 * A continuous [VoiceMotif] ([submitMotif]) runs back-and-forth strokes between 0 and a
 * rising peak at a cadence that is a percentage of [maxRate] (logical %/s, read for every
 * segment so a lowered maximum or envelope applies at once). Turning points are always
 * emitted (the peak is really reached). It starts from the current position and never ends by
 * itself: only [cancel], a new plan/motif or a failing [canRun] stop it.
 */
internal class VoiceSequenceRunner(
    private val scope: CoroutineScope,
    private val canRun: () -> Boolean,
    private val emit: (position: Int, durationMs: Int) -> Unit,
    private val onStep: (Int?) -> Unit,
    private val onFinished: (completed: Boolean) -> Unit,
    private val maxRate: () -> Float = { 0f },
    /** Called once when a motif ramp reaches its final level (journal: level change). */
    private val onRampDone: (VoiceMotifLevels) -> Unit = {},
) {
    private var job: Job? = null
    private var pending: List<VoiceStep>? = null
    private var moving = false
    private var moveTarget: Int? = null

    /** Running motif (null for a step plan or idle) and the time spent in it. */
    var motif: VoiceMotif? = null
        private set
    private var motifElapsedMs = 0L
    private var rampDoneReported = false

    /** Last logical target emitted (or the start position after a reset). */
    var position: Int = 0
        private set

    val isActive: Boolean get() = job?.isActive == true

    val motifActive: Boolean get() = motif != null && isActive

    /** Where a non-immediate sequence submitted now would start from. */
    fun nextStartPercent(immediate: Boolean): Int =
        if (!immediate && moving) moveTarget ?: position else position

    fun resetPosition(percent: Int) {
        position = percent.coerceIn(0, 100)
    }

    fun submit(steps: List<VoiceStep>, immediate: Boolean) {
        if (steps.isEmpty()) return
        if (!immediate && moving && job?.isActive == true) {
            pending = steps
            return
        }
        start { run(steps) }
    }

    /** Starts a continuous motif from the current position (replaces any plan or motif). */
    fun submitMotif(next: VoiceMotif) {
        start(next) { runMotif() }
    }

    /**
     * Applies a modulation to the running motif without restarting the strokes: the new curves
     * start from the current levels. Returns the new motif, or null when no motif runs.
     */
    fun modulate(modulation: VoiceModulation): VoiceMotif? {
        val current = motif ?: return null
        if (job?.isActive != true) return null
        val next = VoiceMotifMath.modulate(current, motifElapsedMs, modulation)
        motif = next
        motifElapsedMs = 0L
        rampDoneReported = false
        return next
    }

    fun cancel() {
        val wasActive = job?.isActive == true
        job?.cancel()
        job = null
        pending = null
        moving = false
        moveTarget = null
        motif = null
        motifElapsedMs = 0L
        if (wasActive) onStep(null)
    }

    private fun start(nextMotif: VoiceMotif? = null, body: suspend () -> Unit) {
        job?.cancel()
        pending = null
        moving = false
        moveTarget = null
        motif = nextMotif
        motifElapsedMs = 0L
        rampDoneReported = false
        val next = scope.launch(start = CoroutineStart.LAZY) { body() }
        job = next
        next.start()
    }

    private suspend fun run(initial: List<VoiceStep>) {
        val self = coroutineContext[Job]
        var steps = initial
        var index = 0
        var completed = false
        try {
            while (index < steps.size) {
                if (!canRun()) return
                val step = steps[index]
                onStep(index)
                val from = position
                val to = step.targetPercent.coerceIn(0, 100)
                moving = true
                moveTarget = to
                if (from != to && step.moveMs > 0) {
                    val segments = segmentCount(abs(to - from), step.moveMs)
                    var previousAt = 0L
                    for (k in 1..segments) {
                        if (!coroutineContext.isActive || !canRun()) return
                        val at = step.moveMs.toLong() * k / segments
                        val duration = (at - previousAt).toInt()
                        val next = (from + (to - from) * k.toFloat() / segments).roundToInt().coerceIn(0, 100)
                        if (next != position) {
                            position = next
                            emit(next, duration)
                        }
                        delay(duration.toLong())
                        previousAt = at
                    }
                    if (!coroutineContext.isActive || !canRun()) return
                }
                if (position != to) {
                    if (!canRun()) return
                    position = to
                    emit(to, MIN_SEGMENT_MS)
                }
                moving = false
                moveTarget = null
                val replacement = pending
                if (replacement != null) {
                    pending = null
                    steps = replacement
                    index = 0
                    continue
                }
                if (step.holdMs > 0) {
                    var held = 0L
                    while (held < step.holdMs) {
                        val chunk = minOf(SEGMENT_MS, step.holdMs - held)
                        delay(chunk)
                        held += chunk
                        if (!canRun()) return
                    }
                }
                index++
            }
            completed = true
        } finally {
            finish(self, completed)
        }
    }

    private suspend fun runMotif() {
        val self = coroutineContext[Job]
        var exact = position.toFloat()
        var goingDeep = true
        try {
            if (!canRun()) return
            onStep(0)
            while (true) {
                if (!coroutineContext.isActive || !canRun()) return
                val current = motif ?: return
                val levels = VoiceMotifMath.levelsAt(current, motifElapsedMs)
                if (!rampDoneReported && motifElapsedMs >= current.rampSeconds * 1000L) {
                    rampDoneReported = true
                    onRampDone(levels)
                }
                val peak = levels.depth.coerceIn(0f, 100f)
                val rate = maxRate().coerceAtLeast(0f) * levels.speed.coerceIn(0f, 100f) / 100f
                if (rate <= 0f || (goingDeep && peak <= 0f && exact <= 0f)) {
                    delay(SEGMENT_MS)
                    motifElapsedMs += SEGMENT_MS
                    continue
                }
                if (goingDeep && exact > peak) goingDeep = false // shallower peak: come back
                val goal = if (goingDeep) peak else 0f
                val distance = abs(goal - exact)
                val maxStep = minOf(rate * SEGMENT_MS / 1000f, MAX_STEP.toFloat())
                val travel: Float
                if (distance <= maxStep) {
                    travel = distance
                    exact = goal
                    goingDeep = !goingDeep
                } else {
                    travel = maxStep
                    exact += if (goal > exact) maxStep else -maxStep
                }
                val duration = ceil(travel * 1000f / rate).toLong().coerceIn(MIN_SEGMENT_MS.toLong(), SEGMENT_MS)
                val next = exact.roundToInt().coerceIn(0, 100)
                if (next != position) {
                    position = next
                    emit(next, duration.toInt())
                }
                delay(duration)
                motifElapsedMs += duration
            }
        } finally {
            finish(self, completed = false)
        }
    }

    private fun finish(self: Job?, completed: Boolean) {
        if (job === self) {
            job = null
            moving = false
            moveTarget = null
            motif = null
            motifElapsedMs = 0L
            onFinished(completed)
        }
    }

    companion object {
        /** Target duration; below the ~250 ms the firmware allows per Live target. */
        const val SEGMENT_MS = 200L
        const val MIN_SEGMENT_MS = 40
        /** Largest logical step of one target. */
        const val MAX_STEP = 25

        /** Segments of a move: <= [SEGMENT_MS] each and <= [MAX_STEP] points each. */
        fun segmentCount(distance: Int, moveMs: Int): Int {
            val byTime = ceil(moveMs / SEGMENT_MS.toDouble()).toInt()
            val byStep = ceil(distance / MAX_STEP.toDouble()).toInt()
            return maxOf(1, byTime, byStep)
        }
    }
}
