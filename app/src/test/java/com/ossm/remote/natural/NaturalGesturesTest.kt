package com.ossm.remote.natural

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalGesturesTest {
    private val generator = NaturalGestureGenerator()
    private val settings = NaturalSettings(energy = 0.7f, variety = 0.8f)
    private val limits = NaturalLimits.of(
        speedMaximum = 0.6f,
        depthMinimum = 0.2f,
        depthMaximum = 0.8f,
        sensationMinimum = 0.2f,
        sensationMaximum = 0.8f,
    )

    private fun manyGestures(seed: Long, plans: Int = 30): List<NaturalGesture> {
        val random = Random(seed)
        return (0 until plans).flatMap { index ->
            localNaturalPlan(seed + index, 2).phrases.flatMap { generator.generate(it, settings, random) }
        }
    }

    @Test
    fun `same seed produces the same gestures and frames`() {
        fun run(seed: Long): List<NaturalFrame> {
            val engine = NaturalModeEngine(seed)
            engine.start(NaturalFrame(0f, 0.2f, 0.8f, 0.5f), limits, settings)
            return (0 until 400).map { engine.tick(200L, limits, settings) }
        }
        assertEquals(run(7L), run(7L))
        assertNotEquals(run(7L), run(8L))
    }

    @Test
    fun `gestures stay normalised and pauses are bounded`() {
        val gestures = manyGestures(11L)
        assertTrue(gestures.any { it.pause })
        gestures.forEach { gesture ->
            assertTrue(gesture.drive in 0f..1f && gesture.amplitude in 0f..1f && gesture.anchor in 0f..1f)
            assertTrue(gesture.asymmetry in -1f..1f)
            if (gesture.pause) {
                assertTrue(gesture.durationMs in NaturalGesture.MIN_PAUSE_MS..NaturalGesture.MAX_PAUSE_MS)
            } else {
                // No fixed ~2 s random switching: moving gestures last at least 2.5 s and vary.
                assertTrue(gesture.durationMs >= NaturalGesture.MIN_MOVING_DURATION_MS)
                assertTrue(gesture.durationMs <= NaturalGesture.MAX_DURATION_MS)
            }
        }
        val movingDurations = gestures.filterNot { it.pause }.map { it.durationMs }.toSet()
        assertTrue("durations must vary", movingDurations.size > 10)
    }

    @Test
    fun `cadence and amplitude are positively correlated`() {
        val moving = manyGestures(21L).filterNot { it.pause }
        val correlation = pearson(moving.map { it.drive }, moving.map { it.amplitude })
        assertTrue("correlation=$correlation", correlation > 0.5)
    }

    @Test
    fun `advance and retreat are asymmetric through bounded bipolar sensation`() {
        val moving = manyGestures(31L).filterNot { it.pause }
        assertTrue(moving.any { it.asymmetry > 0.1f })
        val targets = moving.map { NaturalFrameMapper.target(it, limits) }
        assertTrue(targets.any { abs(it.sensation - 0.5f) > 0.05f })
        assertTrue(targets.all { it.sensation in 0.2f..0.8f })
        // A neutral-only envelope never produces asymmetry.
        val neutral = limits.copy(sensationMinimum = 0.5f, sensationMaximum = 0.5f)
        assertTrue(moving.all { NaturalFrameMapper.target(it, neutral).sensation == 0.5f })
    }

    @Test
    fun `frames never exceed limits and intensity rises no faster than the ramp`() {
        val engine = NaturalModeEngine(5L)
        val rates = NaturalRates()
        engine.start(NaturalFrame(0f, 0.2f, 0.8f, 0.5f), limits, settings)
        var previous = engine.frame
        repeat(3_000) {
            val frame = engine.tick(200L, limits, settings)
            assertTrue(frame.speed in 0f..limits.speedMaximum)
            assertTrue(frame.depthMin >= limits.depthMinimum - 1e-6f)
            assertTrue(frame.depthMax <= limits.depthMaximum + 1e-6f)
            assertTrue(frame.depthMax - frame.depthMin >= NaturalLimits.MIN_DEPTH_SPAN - 1e-4f)
            assertTrue(frame.sensation in limits.sensationMinimum..limits.sensationMaximum)
            // Increases of speed, depth and stroke length are bounded by the slow ramp.
            assertTrue(frame.speed - previous.speed <= rates.speedUpPerSecond * 0.2f + 1e-5f)
            assertTrue(frame.depthMax - previous.depthMax <= rates.depthGrowPerSecond * 0.2f + 1e-5f)
            assertTrue(previous.depthMin - frame.depthMin <= rates.depthShrinkPerSecond * 0.2f + 1e-5f)
            assertTrue(abs(frame.sensation - previous.sensation) <= rates.sensationPerSecond * 0.2f + 1e-5f)
            previous = frame
        }
    }

    @Test
    fun `start is gradual from speed zero`() {
        val engine = NaturalModeEngine(3L)
        engine.start(NaturalFrame(0.5f, 0.2f, 0.8f, 0.5f), limits, settings)
        assertEquals(0f, engine.frame.speed)
        val first = engine.tick(200L, limits, settings)
        assertTrue(first.speed <= NaturalRates().speedUpPerSecond * 0.2f + 1e-6f)
        val afterTwoSeconds = (0 until 9).map { engine.tick(200L, limits, settings) }.last()
        assertTrue(afterTwoSeconds.speed <= 0.13f)
    }

    @Test
    fun `lowered limits apply immediately`() {
        val engine = NaturalModeEngine(9L)
        engine.start(NaturalFrame(0f, 0.2f, 0.8f, 0.5f), limits, settings)
        repeat(400) { engine.tick(200L, limits, settings) }
        val tight = NaturalLimits.of(0.1f, 0.4f, 0.5f, 0.45f, 0.55f)
        val frame = engine.tick(0L, tight, settings)
        assertTrue(frame.speed <= 0.1f)
        assertTrue(frame.depthMin >= 0.4f - 1e-6f && frame.depthMax <= 0.5f + 1e-6f)
        assertTrue(frame.sensation in 0.45f..0.55f)
    }

    @Test
    fun `a late tick never produces a catch up jump`() {
        val engine = NaturalModeEngine(13L)
        engine.start(NaturalFrame(0f, 0.2f, 0.8f, 0.5f), limits, settings)
        val frame = engine.tick(60_000L, limits, settings)
        assertTrue(frame.speed <= NaturalRates().speedUpPerSecond * NaturalMotionShaper.MAX_STEP_MS / 1000f + 1e-6f)
    }

    @Test
    fun `replacing the future keeps the gesture in progress`() {
        val engine = NaturalModeEngine(17L)
        engine.start(NaturalFrame(0f, 0.2f, 0.8f, 0.5f), limits, settings)
        engine.tick(200L, limits, settings)
        val current = engine.currentGesture
        val plan = NaturalPlan(
            NaturalPlan.SCHEMA_VERSION,
            listOf(NaturalPhrase(NaturalMotif.REST, NaturalStyle(0.2f, 0.2f, 0.2f, 0.9f))),
        )
        engine.replaceFuture(plan, settings)
        engine.tick(200L, limits, settings)
        assertEquals(current, engine.currentGesture)
        assertTrue(engine.queuedGestureCount in 1..3)
    }

    @Test
    fun `engine keeps moving with local material when no plan arrives`() {
        val engine = NaturalModeEngine(19L)
        engine.start(NaturalFrame(0f, 0.2f, 0.8f, 0.5f), limits, settings)
        val motifs = mutableSetOf<NaturalMotif>()
        repeat(5_000) {
            engine.tick(200L, limits, settings)
            engine.currentGesture?.let { motifs += it.motif }
        }
        assertTrue(engine.frame.speed > 0f)
        assertTrue(motifs.size >= 3)
    }

    @Test
    fun `pauses slow down but never send speed zero`() {
        val pause = NaturalGesture(NaturalMotif.REST, 0f, 0.2f, 0.3f, 0f, 2_000L, pause = true)
        val target = NaturalFrameMapper.target(pause, limits)
        assertTrue(target.speed > 0f)
        assertTrue(target.speed <= limits.speedMaximum * 0.2f)
    }

    private fun pearson(x: List<Float>, y: List<Float>): Double {
        val mx = x.average()
        val my = y.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        x.indices.forEach { i ->
            num += (x[i] - mx) * (y[i] - my)
            dx += (x[i] - mx) * (x[i] - mx)
            dy += (y[i] - my) * (y[i] - my)
        }
        return num / sqrt(dx * dy)
    }
}
