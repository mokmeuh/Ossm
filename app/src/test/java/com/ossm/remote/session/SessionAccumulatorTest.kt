package com.ossm.remote.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAccumulatorTest {

    @Test
    fun `duration excludes pause time`() {
        val acc = SessionAccumulator(startTimeMillis = 0)
        acc.pause(5_000)
        acc.resume(8_000)
        acc.pause(12_000)
        assertEquals(9_000L, acc.durationMillis(12_000))

        acc.resume(15_000)
        assertEquals(10_000L, acc.durationMillis(16_000))
    }

    @Test
    fun `pause and resume are idempotent`() {
        val acc = SessionAccumulator(0)
        acc.pause(5_000)
        acc.pause(99_000)
        assertEquals(5_000L, acc.durationMillis(99_000))

        acc.resume(10_000)
        acc.resume(11_000)
        assertEquals(6_000L, acc.durationMillis(11_000))
    }

    @Test
    fun `samples produce average max and sensation percent`() {
        val acc = SessionAccumulator(0)
        acc.addSample(0.5f, 0.8f)
        acc.addSample(0.7f, 0.6f)
        acc.addSample(1f, 1f)
        assertEquals(73.333f, acc.avgSpeedPercent(), 0.01f)
        assertEquals(100f, acc.maxSpeedPercent(), 0.01f)
        assertEquals(80f, acc.avgSensationPercent(), 0.01f)
    }

    @Test
    fun `no samples yields zero percentages`() {
        val acc = SessionAccumulator(0)
        assertEquals(0f, acc.avgSpeedPercent(), 0f)
        assertEquals(0f, acc.maxSpeedPercent(), 0f)
        assertEquals(0f, acc.avgSensationPercent(), 0f)
        assertEquals(0L, acc.durationMillis(0))
    }

    @Test
    fun `isMoving reflects pause state`() {
        val acc = SessionAccumulator(0)
        assertTrue(acc.isMoving)
        acc.pause(100)
        assertFalse(acc.isMoving)
        acc.resume(200)
        assertTrue(acc.isMoving)
    }
}
