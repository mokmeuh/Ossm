package com.ossm.remote.session

import com.ossm.remote.model.KnownStrokeEnginePatterns
import com.ossm.remote.ui.screens.shouldShowMaxSensationToggle
import com.ossm.remote.viewmodel.ControlUiState
import com.ossm.remote.viewmodel.RandomTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensationExtremeTest {
    @Test
    fun `advance takes the top and retract the bottom of the full scale`() {
        assertEquals(1f, sensationExtreme(true, 0f, 1f), 0f)
        assertEquals(0f, sensationExtreme(false, 0f, 1f), 0f)
    }

    @Test
    fun `reduced user range gives its own bounds`() {
        assertEquals(0.8f, sensationExtreme(true, 0.3f, 0.8f), 0f)
        assertEquals(0.3f, sensationExtreme(false, 0.3f, 0.8f), 0f)
    }

    @Test
    fun `reversed user bounds are ordered`() {
        assertEquals(0.8f, sensationExtreme(true, 0.8f, 0.3f), 0f)
        assertEquals(0.3f, sensationExtreme(false, 0.8f, 0.3f), 0f)
    }

    @Test
    fun `envelope narrows the extremes and is never exceeded`() {
        assertEquals(0.7f, sensationExtreme(true, 0f, 1f, 0.3f, 0.7f), 0f)
        assertEquals(0.3f, sensationExtreme(false, 0f, 1f, 0.3f, 0.7f), 0f)
        assertEquals(0.6f, sensationExtreme(true, 0.4f, 0.6f, 0.3f, 0.7f), 0f)
        assertEquals(0.4f, sensationExtreme(false, 0.4f, 0.6f, 0.3f, 0.7f), 0f)
    }

    @Test
    fun `empty or tiny range collapses to a single value inside the envelope`() {
        assertEquals(0.5f, sensationExtreme(true, 0.5f, 0.5f), 0f)
        assertEquals(0.5f, sensationExtreme(false, 0.5f, 0.5f), 0f)
        assertEquals(0.5f, sensationExtreme(true, 0f, 1f, 0.5f, 0.5f), 0f)
        assertEquals(0.5f, sensationExtreme(false, 0f, 1f, 0.5f, 0.5f), 0f)
    }

    @Test
    fun `range outside the envelope falls back to the nearest envelope bound`() {
        assertEquals(0.3f, sensationExtreme(true, 0f, 0.1f, 0.3f, 0.7f), 0f)
        assertEquals(0.7f, sensationExtreme(false, 0.9f, 1f, 0.3f, 0.7f), 0f)
    }

    @Test
    fun `max turns random off and random turns max off`() {
        val random = ControlUiState().withSensationToggle(RandomTarget.SENSATION, true)
        assertTrue(random.randSensation)
        val max = random.withSensationToggle(RandomTarget.SENSATION_MAX, true)
        assertTrue(max.maxSensation)
        assertFalse(max.randSensation)
        val backToRandom = max.withSensationToggle(RandomTarget.SENSATION, true)
        assertTrue(backToRandom.randSensation)
        assertFalse(backToRandom.maxSensation)
    }

    @Test
    fun `turning one off leaves the other untouched`() {
        val max = ControlUiState().withSensationToggle(RandomTarget.SENSATION_MAX, true)
        val stillMax = max.withSensationToggle(RandomTarget.SENSATION, false)
        assertTrue(stillMax.maxSensation)
        val off = max.withSensationToggle(RandomTarget.SENSATION_MAX, false)
        assertFalse(off.maxSensation)
        assertFalse(off.sensationTurnActive)
        assertFalse(off.anyRandomActive)
    }

    @Test
    fun `max counts as an active sensation mode`() {
        val max = ControlUiState().withSensationToggle(RandomTarget.SENSATION_MAX, true)
        assertTrue(max.sensationTurnActive)
        assertTrue(max.anyRandomActive)
    }

    @Test
    fun `max checkbox only for teasing pounding`() {
        val teasing = KnownStrokeEnginePatterns.first { it.key == "teasingPounding" }
        assertTrue(shouldShowMaxSensationToggle(teasing))
        KnownStrokeEnginePatterns.filter { it.key != "teasingPounding" }.forEach {
            assertFalse(it.key, shouldShowMaxSensationToggle(it))
        }
    }
}
