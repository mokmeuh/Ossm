package com.ossm.remote.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveParameterCoalescerTest {
    @Test
    fun `a burst of drag ticks collapses to one write carrying the newest value`() {
        val coalescer = LiveParameterCoalescer()
        for (value in 0..60) coalescer.offer("speed", value)

        assertEquals(1, coalescer.size())
        assertEquals("speed" to 60, coalescer.poll())
        assertNull(coalescer.poll())
    }

    @Test
    fun `distinct parameters keep first-offer order with their latest values`() {
        val coalescer = LiveParameterCoalescer()
        coalescer.offer("speed", 10)
        coalescer.offer("sensation", 40)
        coalescer.offer("speed", 30)

        assertEquals("speed" to 30, coalescer.poll())
        assertEquals("sensation" to 40, coalescer.poll())
        assertNull(coalescer.poll())
    }

    @Test
    fun `a committed write supersedes and STOP clears every pending value`() {
        val coalescer = LiveParameterCoalescer()
        coalescer.offer("speed", 70)
        coalescer.offer("sensation", 20)
        coalescer.remove(liveParameterOf("set:sensation:25")!!)
        assertEquals(1, coalescer.size())

        coalescer.clear()
        assertNull("No stale slider value may be written after STOP", coalescer.poll())
    }

    @Test
    fun `parameter names are parsed only from set commands`() {
        assertEquals("speed", liveParameterOf("set:speed:0"))
        assertEquals("depth", liveParameterOf("set:depth:80"))
        assertNull(liveParameterOf("go:streaming"))
        assertNull(liveParameterOf("stream:50:60"))
        assertNull(liveParameterOf("set:"))
    }

    @Test
    fun `entry convergence duration is proportional and bounded`() {
        assertEquals(1_000L, liveEntryConvergenceDurationMs(30f))
        assertEquals(400L, liveEntryConvergenceDurationMs(1f))
        assertEquals(10_000L, liveEntryConvergenceDurationMs(1_000f))
        assertEquals(liveEntryConvergenceDurationMs(-60f), liveEntryConvergenceDurationMs(60f))
    }
}
