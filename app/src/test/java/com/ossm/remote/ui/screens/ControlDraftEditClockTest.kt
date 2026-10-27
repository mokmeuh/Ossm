package com.ossm.remote.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.3.17 regression: a drag tick wrote the local draft while the previous tick's uiState was
 * still being delivered; the sync effect then overwrote the draft with the stale value and the
 * thumb jumped backwards. External values may only land after a quiet period.
 */
class ControlDraftEditClockTest {
    @Test
    fun `no local edit lets external state apply immediately`() {
        assertEquals(0L, ControlDraftEditClock().remainingQuietMs(1_000L))
    }

    @Test
    fun `a stale snapshot during a drag waits for the quiet period`() {
        val clock = ControlDraftEditClock(quietMs = 250L)
        clock.markLocalEdit(1_000L)
        assertEquals(250L, clock.remainingQuietMs(1_000L))
        clock.markLocalEdit(1_016L) // next drag tick restarts the window
        assertEquals(234L, clock.remainingQuietMs(1_032L))
        assertEquals(0L, clock.remainingQuietMs(1_300L))
    }

    @Test
    fun `remote session start and end are applied without waiting`() {
        val clock = ControlDraftEditClock()
        assertFalse(clock.consumeSessionChange(false))
        assertFalse(clock.consumeSessionChange(false))
        assertTrue(clock.consumeSessionChange(true))
        assertFalse(clock.consumeSessionChange(true))
        assertTrue(clock.consumeSessionChange(false))
    }
}
