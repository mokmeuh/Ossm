package com.ossm.remote.session

import com.ossm.remote.voice.VoiceModulation
import com.ossm.remote.voice.VoiceMotif
import com.ossm.remote.voice.VoiceStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSequenceRunnerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    /** (time, position, duration) */
    private val emitted = mutableListOf<Triple<Long, Int, Int>>()
    private var allowed = true
    private var finished: Boolean? = null
    private var rate = 100f

    private val runner = VoiceSequenceRunner(
        scope = scope,
        canRun = { allowed },
        emit = { p, d -> emitted += Triple(dispatcher.scheduler.currentTime, p, d) },
        onStep = {},
        onFinished = { finished = it },
        maxRate = { rate },
    )

    private fun advance(ms: Long) {
        dispatcher.scheduler.advanceTimeBy(ms)
        dispatcher.scheduler.runCurrent()
    }

    private fun positions() = emitted.map { it.second }

    @Test
    fun `executes move, hold and return then stays in place`() {
        runner.submit(listOf(VoiceStep(100, 2_000, 1_000), VoiceStep(0, 400, 0)), immediate = false)
        advance(2_000)
        assertEquals(100, runner.position)
        val atDeep = emitted.size
        advance(900)
        assertEquals("no target while holding", atDeep, emitted.size)
        advance(600)
        assertEquals(0, runner.position)
        assertEquals(true, finished)
        assertFalse(runner.isActive)
        val count = emitted.size
        advance(5_000)
        assertEquals("end of sequence stays in place", count, emitted.size)
    }

    @Test
    fun `steps are sized by the wanted speed, not 1 percent ticks`() {
        runner.submit(listOf(VoiceStep(100, 1_000, 0)), immediate = false)
        advance(1_200)
        assertEquals(listOf(20, 40, 60, 80, 100), positions())
        assertTrue(emitted.all { it.third == 200 })
        // Each target is sent at the start of its segment: 100 % in the planned 1 s.
        assertEquals(listOf(0L, 200L, 400L, 600L, 800L), emitted.map { it.first })
    }

    @Test
    fun `a fast move keeps its duration with steps of at most 25 points`() {
        runner.submit(listOf(VoiceStep(100, 250, 0)), immediate = false)
        advance(500)
        assertEquals(listOf(25, 50, 75, 100), positions())
        assertEquals(250, emitted.sumOf { it.third })
        assertEquals(4, VoiceSequenceRunner.segmentCount(100, 250))
        assertEquals(13, VoiceSequenceRunner.segmentCount(100, 2_500))
        assertEquals(1, VoiceSequenceRunner.segmentCount(3, 100))
    }

    @Test
    fun `new plan replaces the current one after the current move`() {
        runner.submit(listOf(VoiceStep(100, 1_000, 5_000)), immediate = false)
        advance(400)
        // A queued plan starts from the target of the move in progress.
        assertEquals(100, runner.nextStartPercent(immediate = false))
        runner.submit(listOf(VoiceStep(20, 400, 0)), immediate = false)
        advance(590)
        assertEquals(100, runner.position)
        advance(410)
        assertEquals(20, runner.position)
    }

    @Test
    fun `immediate plan replaces at once`() {
        runner.submit(listOf(VoiceStep(100, 1_000, 0)), immediate = false)
        advance(400)
        val here = runner.position
        runner.submit(listOf(VoiceStep(here, 200, 0)), immediate = true)
        advance(2_000)
        assertEquals(here, runner.position)
    }

    @Test
    fun `cancel and lost permission stop all targets`() {
        runner.submit(listOf(VoiceStep(100, 1_000, 0)), immediate = false)
        advance(200)
        runner.cancel()
        val count = emitted.size
        advance(2_000)
        assertEquals(count, emitted.size)

        runner.submit(listOf(VoiceStep(0, 1_000, 0)), immediate = false)
        advance(200)
        allowed = false
        val count2 = emitted.size
        advance(2_000)
        assertEquals(count2, emitted.size)
        assertEquals(false, finished)
    }

    // ---- continuous motif ----

    @Test
    fun `motif cadence is really reached - one round trip at the wanted speed`() {
        rate = 100f
        // 50 % of 100 %/s = 50 %/s; 0 -> 50 -> 0 = 100 points = 2 s per round trip.
        runner.submitMotif(VoiceMotif(50, 50, 50, 50, 1))
        advance(20_000)
        val atPeak = emitted.filter { it.second == 50 }.map { it.first }
        assertTrue("peaks $atPeak", atPeak.size >= 8)
        atPeak.zipWithNext().forEach { (a, b) -> assertTrue("round trip ${b - a} ms", (b - a) in 1_900L..2_100L) }
        // 10 points per 200 ms target, never 1 % ticks.
        assertTrue(positions().zipWithNext().all { (a, b) -> kotlin.math.abs(b - a) in 1..10 })
        assertTrue(emitted.all { it.third in VoiceSequenceRunner.MIN_SEGMENT_MS..VoiceSequenceRunner.SEGMENT_MS.toInt() })
    }

    @Test
    fun `motif peak rises linearly and turning points are always emitted`() {
        rate = 100f
        runner.submitMotif(VoiceMotif(20, 60, 40, 100, 20))
        advance(40_000)
        val p = positions()
        val peaks = p.indices.filter { i -> i in 1 until p.lastIndex && p[i] > p[i - 1] && p[i] > p[i + 1] }.map { p[it] }
        assertTrue("peaks $peaks", peaks.size >= 5)
        assertTrue(peaks.first() <= 30)
        assertEquals(60, peaks.last())
        assertTrue(peaks.zipWithNext().all { (a, b) -> b >= a - 1 })
        assertTrue(p.all { it in 0..60 })
        assertTrue("returns to 0", p.count { it == 0 } >= 5)
        assertTrue(p.zipWithNext().all { (a, b) -> kotlin.math.abs(b - a) <= VoiceSequenceRunner.MAX_STEP })
    }

    @Test
    fun `motif starts from the current position and cancel stops at once`() {
        rate = 100f
        runner.submit(listOf(VoiceStep(80, 800, 0)), immediate = false)
        advance(1_000)
        assertEquals(80, runner.position)
        emitted.clear()
        runner.submitMotif(VoiceMotif(30, 30, 50, 50, 1))
        advance(10)
        assertTrue("continuous from 80: ${emitted.first()}", emitted.first().second in 70..79)
        runner.cancel()
        val count = emitted.size
        advance(5_000)
        assertEquals(count, emitted.size)
        assertFalse(runner.motifActive)
    }

    @Test
    fun `modulation keeps strokes running without restarting`() {
        rate = 100f
        runner.submitMotif(VoiceMotif(40, 40, 20, 20, 1))
        advance(2_000)
        val next = runner.modulate(VoiceModulation.DEEPER)
        assertEquals(50, next?.depthEnd)
        advance(20_000)
        assertTrue(positions().contains(50))
        assertTrue(runner.motifActive)
        runner.cancel()
        assertEquals(null, runner.modulate(VoiceModulation.FASTER))
    }
}
