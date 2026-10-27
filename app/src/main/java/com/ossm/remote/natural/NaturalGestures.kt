package com.ossm.remote.natural

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Phone-owned motion limits for one Naturel tick, as 0..1 fractions of the Store scales.
 * They are recomputed every tick (user maxima, local depth limits and the remote envelope),
 * so a lowered limit is honoured at once rather than at the next gesture.
 */
data class NaturalLimits(
    val speedMaximum: Float,
    val depthMinimum: Float,
    val depthMaximum: Float,
    val sensationMinimum: Float = NEUTRAL_SENSATION,
    val sensationMaximum: Float = NEUTRAL_SENSATION,
) {
    val depthSpan: Float get() = depthMaximum - depthMinimum

    /** No motion is allowed without a positive speed ceiling and the minimum stroke span. */
    val allowsMotion: Boolean
        get() = speedMaximum > 0f && depthSpan >= MIN_DEPTH_SPAN - SPAN_EPSILON

    companion object {
        const val NEUTRAL_SENSATION = 0.5f
        const val MIN_DEPTH_SPAN = 0.05f
        private const val SPAN_EPSILON = 0.0001f

        /** Sanitising factory: NaN and out-of-range values never reach the generator. */
        fun of(
            speedMaximum: Float,
            depthMinimum: Float,
            depthMaximum: Float,
            sensationMinimum: Float = NEUTRAL_SENSATION,
            sensationMaximum: Float = NEUTRAL_SENSATION,
        ): NaturalLimits {
            fun unit(value: Float, fallback: Float) =
                if (value.isNaN()) fallback else value.coerceIn(0f, 1f)
            val low = unit(min(depthMinimum, depthMaximum), 0f)
            val high = unit(max(depthMinimum, depthMaximum), 0f)
            val sensationLow = unit(min(sensationMinimum, sensationMaximum), NEUTRAL_SENSATION)
            val sensationHigh = unit(max(sensationMinimum, sensationMaximum), NEUTRAL_SENSATION)
            return NaturalLimits(
                speedMaximum = unit(speedMaximum, 0f),
                depthMinimum = low,
                depthMaximum = high,
                sensationMinimum = sensationLow,
                sensationMaximum = sensationHigh,
            )
        }
    }
}

/** User style controls. Neither value is a motor value; both are clamped to 0..1. */
data class NaturalSettings(
    val energy: Float = 0.5f,
    val variety: Float = 0.5f,
) {
    val safeEnergy: Float get() = if (energy.isNaN()) 0.5f else energy.coerceIn(0f, 1f)
    val safeVariety: Float get() = if (variety.isNaN()) 0.5f else variety.coerceIn(0f, 1f)
}

/**
 * One gesture of a phrase, normalised so that it stays meaningful when the limits change:
 * [drive] (cadence) and [amplitude] are positions inside the current ranges, [anchor] places
 * the deep end of the stroke (1 = deepest allowed), [asymmetry] is the signed sensation
 * offset (-1..1, advance vs retreat speed ratio on Teasing Pounding).
 */
data class NaturalGesture(
    val motif: NaturalMotif,
    val drive: Float,
    val amplitude: Float,
    val anchor: Float,
    val asymmetry: Float,
    val durationMs: Long,
    val pause: Boolean = false,
) {
    init {
        require(drive.isFinite() && drive in 0f..1f)
        require(amplitude.isFinite() && amplitude in 0f..1f)
        require(anchor.isFinite() && anchor in 0f..1f)
        require(asymmetry.isFinite() && asymmetry in -1f..1f)
        require(durationMs in MIN_DURATION_MS..MAX_DURATION_MS)
        if (pause) require(durationMs in MIN_PAUSE_MS..MAX_PAUSE_MS)
    }

    companion object {
        const val MIN_DURATION_MS = 800L
        const val MAX_DURATION_MS = 12_000L
        const val MIN_PAUSE_MS = 800L
        const val MAX_PAUSE_MS = 4_000L
        /** Gestures that move are never shorter than this: no fixed ~2 s switching. */
        const val MIN_MOVING_DURATION_MS = 2_500L
    }
}

/** Bounded command values (0..1 Store fractions) emitted by the shaper. */
data class NaturalFrame(
    val speed: Float,
    val depthMin: Float,
    val depthMax: Float,
    val sensation: Float,
)

/**
 * Expands one high-level phrase (local or AI) into a coherent gesture sequence. Pure and
 * seeded through the caller's [Random]: the same seed always produces the same gestures.
 */
class NaturalGestureGenerator {

    private data class Step(
        val drive: Float,
        val amplitudeScale: Float = 1f,
        val anchor: Float? = null,
        val holdFactor: Float = 1f,
        val pause: Boolean = false,
    )

    fun generate(
        phrase: NaturalPhrase,
        settings: NaturalSettings,
        random: Random,
    ): List<NaturalGesture> {
        val style = phrase.style
        val energyScale = 0.4f + 0.6f * settings.safeEnergy
        val varietyScale = 0.25f + 0.75f * settings.safeVariety
        val jitterWidth = 0.16f * style.variation * varietyScale
        // One dominant asymmetry direction per phrase keeps the phrase coherent.
        val asymmetrySign = if (random.nextFloat() < 0.7f) 1f else -1f
        val asymmetryBase = 0.2f + 0.6f * style.variation * varietyScale
        val baseDuration = 9_000f - 5_500f * style.tempo

        val gestures = stepsFor(phrase.motif).map { step ->
            if (step.pause) {
                pauseGesture(phrase.motif, style.rest, random)
            } else {
                val jitter = (random.nextFloat() * 2f - 1f) * jitterWidth
                val drive = (step.drive * (0.55f + 0.45f * style.intensity) * energyScale + jitter)
                    .coerceIn(0.05f, 1f)
                val amplitude = correlatedAmplitude(drive, step.amplitudeScale, jitterWidth, random)
                val anchor = ((step.anchor ?: (0.55f + 0.45f * drive)) +
                    (random.nextFloat() * 2f - 1f) * jitterWidth * 0.5f).coerceIn(0f, 1f)
                val asymmetry = (asymmetrySign * asymmetryBase * (0.4f + 0.6f * drive))
                    .coerceIn(-1f, 1f)
                val duration = (baseDuration * step.holdFactor * (0.75f + 0.5f * random.nextFloat()))
                    .toLong()
                    .coerceIn(NaturalGesture.MIN_MOVING_DURATION_MS, NaturalGesture.MAX_DURATION_MS)
                NaturalGesture(phrase.motif, drive, amplitude, anchor, asymmetry, duration)
            }
        }
        // Optional bounded breath after a restful phrase.
        val trailingPause = phrase.motif != NaturalMotif.REST &&
            random.nextFloat() < style.rest * 0.5f
        return if (trailingPause) gestures + pauseGesture(phrase.motif, style.rest, random) else gestures
    }

    /** Short calm opening so every run starts slowly, whatever the first phrase is. */
    fun warmUp(): NaturalGesture = NaturalGesture(
        motif = NaturalMotif.APPROACH_HOLD_RELEASE,
        drive = 0.12f,
        amplitude = 0.35f,
        anchor = 0.45f,
        asymmetry = 0f,
        durationMs = 4_000L,
    )

    /**
     * Cadence and amplitude move together (longer strokes when the drive rises) with a mild
     * shortening at the very top, as a hurried stroke naturally shortens.
     */
    private fun correlatedAmplitude(
        drive: Float,
        scale: Float,
        jitterWidth: Float,
        random: Random,
    ): Float {
        val base = 0.3f + 0.65f * drive
        val hurry = if (drive > 0.8f) (drive - 0.8f) / 0.2f * 0.2f else 0f
        val noise = (random.nextFloat() * 2f - 1f) * jitterWidth * 0.5f
        return ((base - hurry) * scale + noise).coerceIn(0.1f, 1f)
    }

    private fun pauseGesture(motif: NaturalMotif, rest: Float, random: Random): NaturalGesture {
        val duration = (NaturalGesture.MIN_PAUSE_MS +
            (NaturalGesture.MAX_PAUSE_MS - NaturalGesture.MIN_PAUSE_MS) * rest * random.nextFloat())
            .toLong()
            .coerceIn(NaturalGesture.MIN_PAUSE_MS, NaturalGesture.MAX_PAUSE_MS)
        return NaturalGesture(
            motif = motif,
            drive = 0f,
            amplitude = 0.2f,
            anchor = 0.3f,
            asymmetry = 0f,
            durationMs = duration,
            pause = true,
        )
    }

    private fun stepsFor(motif: NaturalMotif): List<Step> = when (motif) {
        NaturalMotif.APPROACH_HOLD_RELEASE -> listOf(
            Step(0.3f), Step(0.5f), Step(0.75f), Step(0.85f, holdFactor = 1.4f), Step(0.4f, amplitudeScale = 0.8f),
        )
        NaturalMotif.TEASE_PAUSE -> listOf(
            Step(0.3f, amplitudeScale = 0.5f, anchor = 0.25f),
            Step(0.4f, amplitudeScale = 0.45f, anchor = 0.3f),
            Step(0f, pause = true),
            Step(0.6f, amplitudeScale = 1.1f, anchor = 0.95f),
        )
        NaturalMotif.WAVE -> listOf(
            Step(0.35f), Step(0.65f), Step(0.45f), Step(0.75f), Step(0.4f),
        )
        NaturalMotif.LADDER -> listOf(
            Step(0.2f), Step(0.35f), Step(0.5f), Step(0.65f), Step(0.8f, holdFactor = 1.2f),
        )
        NaturalMotif.REST -> listOf(
            Step(0f, pause = true), Step(0.2f, amplitudeScale = 0.6f, anchor = 0.4f),
        )
    }
}

/** Maps a normalised gesture into absolute bounded targets for the current limits. */
object NaturalFrameMapper {
    /** A pause is a slow, short, shallow movement: the producer itself never sends speed 0. */
    const val PAUSE_SPEED_FRACTION = 0.1f
    const val MIN_RUNNING_SPEED = 0.02f
    const val SPEED_FLOOR_FRACTION = 0.2f

    fun target(gesture: NaturalGesture, limits: NaturalLimits): NaturalFrame {
        val speedMax = limits.speedMaximum
        val speed = if (gesture.pause) {
            max(speedMax * PAUSE_SPEED_FRACTION, min(MIN_RUNNING_SPEED, speedMax))
        } else {
            val floor = speedMax * SPEED_FLOOR_FRACTION
            floor + gesture.drive * (speedMax - floor)
        }.coerceIn(0f, speedMax)

        val range = limits.depthSpan.coerceAtLeast(0f)
        val span = max(NaturalLimits.MIN_DEPTH_SPAN, range * (0.2f + 0.8f * gesture.amplitude))
            .coerceAtMost(range)
        val deep = (limits.depthMinimum + span + gesture.anchor * (range - span))
            .coerceIn(limits.depthMinimum, limits.depthMaximum)
        val shallow = (deep - span).coerceIn(limits.depthMinimum, deep)

        val neutral = NaturalLimits.NEUTRAL_SENSATION
        val sensation = if (gesture.asymmetry >= 0f) {
            neutral + gesture.asymmetry * (limits.sensationMaximum - neutral).coerceAtLeast(0f)
        } else {
            neutral + gesture.asymmetry * (neutral - limits.sensationMinimum).coerceAtLeast(0f)
        }
        return NaturalFrame(speed, shallow, deep, clampSensation(sensation, limits))
    }

    fun clampSensation(value: Float, limits: NaturalLimits): Float =
        if (limits.sensationMinimum <= limits.sensationMaximum) {
            value.coerceIn(limits.sensationMinimum, limits.sensationMaximum)
        } else {
            NaturalLimits.NEUTRAL_SENSATION
        }
}

/**
 * Per-second slew rates. Increases of intensity (faster, deeper, longer stroke) are slow and
 * progressive; decreases are quicker but still smooth. Lowered limits are applied at once.
 */
data class NaturalRates(
    val speedUpPerSecond: Float = 0.06f,
    val speedDownPerSecond: Float = 0.2f,
    val depthGrowPerSecond: Float = 0.08f,
    val depthShrinkPerSecond: Float = 0.2f,
    val sensationPerSecond: Float = 0.15f,
)

class NaturalMotionShaper(val rates: NaturalRates = NaturalRates()) {

    fun step(current: NaturalFrame, target: NaturalFrame, dtMs: Long, limits: NaturalLimits): NaturalFrame {
        val dt = dtMs.coerceIn(0L, MAX_STEP_MS) / 1000f
        val speed = approach(
            current.speed, target.speed,
            up = rates.speedUpPerSecond * dt, down = rates.speedDownPerSecond * dt,
        )
        // Deep end: deeper (more intense) is slow, shallower is quicker.
        val deep = approach(
            current.depthMax, target.depthMax,
            up = rates.depthGrowPerSecond * dt, down = rates.depthShrinkPerSecond * dt,
        )
        // Shallow end: lowering it lengthens the stroke (slow); raising it is quicker.
        val shallow = approach(
            current.depthMin, target.depthMin,
            up = rates.depthShrinkPerSecond * dt, down = rates.depthGrowPerSecond * dt,
        )
        val sensation = approach(
            current.sensation, target.sensation,
            up = rates.sensationPerSecond * dt, down = rates.sensationPerSecond * dt,
        )
        return clampToLimits(NaturalFrame(speed, shallow, deep, sensation), limits)
    }

    /** Never exceed the limits; lowering is always immediate. */
    fun clampToLimits(frame: NaturalFrame, limits: NaturalLimits): NaturalFrame {
        val speed = frame.speed.coerceIn(0f, limits.speedMaximum)
        val minDeep = min(limits.depthMinimum + NaturalLimits.MIN_DEPTH_SPAN, limits.depthMaximum)
        val deep = frame.depthMax.coerceIn(minDeep, limits.depthMaximum)
        val shallow = frame.depthMin
            .coerceAtMost(deep - NaturalLimits.MIN_DEPTH_SPAN)
            .coerceIn(limits.depthMinimum, deep)
        return NaturalFrame(
            speed = speed,
            depthMin = shallow,
            depthMax = deep,
            sensation = NaturalFrameMapper.clampSensation(frame.sensation, limits),
        )
    }

    private fun approach(current: Float, target: Float, up: Float, down: Float): Float =
        if (target >= current) min(target, current + up) else max(target, current - down)

    companion object {
        /** A late tick never produces a bigger step than this (no catch-up jump). */
        const val MAX_STEP_MS = 500L
    }
}

/**
 * Pure Naturel coordinator: a queue of future gestures, the current gesture and the shaped
 * frame. A validated plan (local or AI) only replaces the FUTURE gestures; the shaper keeps
 * moving with local material when the queue runs dry, so motion never waits for a planner.
 */
class NaturalModeEngine(
    seed: Long,
    private val generator: NaturalGestureGenerator = NaturalGestureGenerator(),
    private val shaper: NaturalMotionShaper = NaturalMotionShaper(),
) {
    private val random = Random(seed)
    private val queue = ArrayDeque<NaturalGesture>()
    private var remainingMs = 0L

    var currentGesture: NaturalGesture? = null
        private set
    var frame: NaturalFrame = NaturalFrame(0f, 0f, 1f, NaturalLimits.NEUTRAL_SENSATION)
        private set

    val queuedGestureCount: Int get() = queue.size

    /** Times the queue ran dry and local material was appended (an AI "gap", motion continues). */
    var localRefillCount: Int = 0
        private set

    /** Planned motion time ahead: rest of the current gesture + every queued gesture. */
    val reserveMs: Long
        get() = remainingMs.coerceAtLeast(0L) + queue.sumOf { it.durationMs }

    /**
     * AI plan: appended while the AI feeds the queue (the look-ahead accumulates); when
     * [replaceLocalFuture] (return from local fallback) the queued local material is replaced.
     * Never touches the gesture in progress.
     */
    fun acceptAiPlan(plan: NaturalPlan, settings: NaturalSettings, replaceLocalFuture: Boolean) {
        if (replaceLocalFuture) replaceFuture(plan, settings) else appendPlan(plan, settings)
    }

    /** Starts from the machine's actual parameters (speed 0 after verified mode entry). */
    /** False until [start]: an AI-fed run waits (speed 0) for its first validated plan. */
    var started: Boolean = false
        private set

    /**
     * Starts from the machine's actual parameters (speed 0 after verified mode entry). With
     * [firstAiPlan] the queue holds only that AI plan (no local warm-up, no local material);
     * the shaper's bounded rates still ramp up from speed 0.
     */
    fun start(
        initial: NaturalFrame,
        limits: NaturalLimits,
        settings: NaturalSettings,
        firstAiPlan: NaturalPlan? = null,
    ) {
        queue.clear()
        currentGesture = null
        remainingMs = 0L
        frame = shaper.clampToLimits(initial.copy(speed = 0f), limits)
        if (firstAiPlan != null) {
            appendPlan(firstAiPlan, settings)
        } else {
            queue.addLast(generator.warmUp())
            appendLocalPlan(settings)
        }
        started = true
    }

    fun nextPlanSeed(): Long = random.nextLong()

    fun appendLocalPlan(settings: NaturalSettings) {
        appendPlan(localNaturalPlan(random.nextLong(), LOCAL_PHRASES_PER_PLAN), settings)
    }

    fun appendPlan(plan: NaturalPlan, settings: NaturalSettings) {
        plan.phrases.forEach { phrase -> queue.addAll(generator.generate(phrase, settings, random)) }
    }

    /** Validated plan: replaces the future, never the gesture in progress. */
    fun replaceFuture(plan: NaturalPlan, settings: NaturalSettings) {
        queue.clear()
        appendPlan(plan, settings)
    }

    fun tick(dtMs: Long, limits: NaturalLimits, settings: NaturalSettings): NaturalFrame {
        remainingMs -= dtMs.coerceAtLeast(0L)
        if (currentGesture == null || remainingMs <= 0L) {
            if (queue.isEmpty()) {
                localRefillCount++
                appendLocalPlan(settings)
            }
            val next = queue.removeFirst()
            currentGesture = next
            remainingMs = next.durationMs
        }
        val target = NaturalFrameMapper.target(currentGesture!!, limits)
        frame = shaper.step(frame, target, dtMs, limits)
        return frame
    }

    companion object {
        const val LOCAL_PHRASES_PER_PLAN = 2
    }
}
