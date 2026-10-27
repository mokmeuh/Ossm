package com.ossm.remote.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTargetSlewTest {
    @Test
    fun `far desired target is bounded and continues during a stationary hold`() {
        val slew = LiveTargetSlew(initialPercent = 0)
        slew.setDesired(100)

        assertEquals(5, slew.advance(1_000_000L))
        assertTrue(slew.advance(1_000_050L) <= 9)
        assertTrue(slew.advance(1_001_050L) <= 89)
        assertTrue(slew.current < slew.desired)
    }

    @Test
    fun `new desired target replaces the held target without resetting current position`() {
        val slew = LiveTargetSlew(initialPercent = 40)
        slew.setDesired(100)
        assertEquals(45, slew.advance(10_000L))

        slew.setDesired(0)
        assertEquals(41, slew.advance(10_050L))
        assertEquals(0, slew.desired)
    }

    @Test
    fun `descent keeps the slow rate while the climb keeps the normal rate`() {
        val slew = LiveTargetSlew(initialPercent = 50, descentRatePercentPerSecond = 20f)
        slew.setDesired(0)
        assertEquals(45, slew.advance(10_000L))
        // One second later the descent has covered exactly 20 points.
        assertEquals(25, slew.advance(11_000L))

        slew.setDesired(100)
        assertEquals(33, slew.advance(11_100L))
    }

    @Test
    fun `return rate cannot be configured above the twenty point safety cap`() {
        val ramp = LiveReturnRamp(initialPercent = 100, ratePercentPerSecond = 100f, startAtMillis = 4_000L)

        assertEquals(100, ramp.advance(4_000L))
        assertEquals(80, ramp.advance(5_000L))
        assertTrue(liveReturnDurationMillis(100, ratePercentPerSecond = 100f) >= 5_000L)
    }
}
