package com.ossm.remote.voice

import com.ossm.remote.data.repository.ControlSafetySettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDepthRangeTest {
    private val eps = 0.0001f

    @Test
    fun `0 percent is the minimum and 100 percent the maximum`() {
        val range = 0.2f to 0.8f
        assertEquals(20, VoiceDepthRange.scalarFor(0, range))
        assertEquals(50, VoiceDepthRange.scalarFor(50, range))
        assertEquals(80, VoiceDepthRange.scalarFor(100, range))
        assertEquals(80, VoiceDepthRange.scalarFor(150, range))
    }

    @Test
    fun `range is ordered and clamped into the hard limits`() {
        val (low, high) = VoiceDepthRange.effective(0.9f, 0.05f, 0.1f, 0.6f)
        assertEquals(0.1f, low, eps)
        assertEquals(0.6f, high, eps)
    }

    @Test
    fun `collapsed range is widened to the minimum span inside the limits`() {
        val (low, high) = VoiceDepthRange.effective(0.6f, 0.6f, 0f, 0.6f)
        assertEquals(0.55f, low, eps)
        assertEquals(0.6f, high, eps)
        val (l2, h2) = VoiceDepthRange.effective(0.3f, 0.31f, 0f, 1f)
        assertEquals(0.3f, l2, eps)
        assertEquals(0.35f, h2, eps)
    }

    @Test
    fun `inverse mapping is clamped and consistent`() {
        val range = 0.2f to 0.8f
        for (logical in 0..100 step 10) {
            val scalar = VoiceDepthRange.scalarFor(logical, range)
            assertTrue(kotlin.math.abs(VoiceDepthRange.logicalFor(scalar, range) - logical) <= 1)
        }
        assertEquals(0, VoiceDepthRange.logicalFor(5, range))
        assertEquals(100, VoiceDepthRange.logicalFor(95, range))
    }

    @Test
    fun `direct tolerance covers one maximal voice step`() {
        assertEquals(26, VoiceDepthRange.directTolerance(25, 0f to 1f))
        assertEquals(14, VoiceDepthRange.directTolerance(25, 0.25f to 0.75f))
    }

    @Test
    fun `stored range normalization`() {
        val (low, high) = ControlSafetySettingsRepository.normalizeVoiceDepthRange(1.4f, -0.2f)
        assertEquals(0f, low, eps)
        assertEquals(1f, high, eps)
    }
}
