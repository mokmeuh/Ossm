package com.ossm.remote.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.3.16: sensation is bipolar (0.5 = neutral); the phone allows a symmetric range 0.5 ± d. */
class PhoneHostSensationRangeTest {

    @Test
    fun `intensity is the distance to neutral on either side`() {
        assertEquals(0f, PhoneHostMotionLimits.sensationIntensityOf(0.5f), 0.0001f)
        assertEquals(0.3f, PhoneHostMotionLimits.sensationIntensityOf(0.2f), 0.0001f)
        assertEquals(0.3f, PhoneHostMotionLimits.sensationIntensityOf(0.8f), 0.0001f)
        assertEquals(0.5f, PhoneHostMotionLimits.sensationIntensityOf(0f), 0.0001f)
        assertEquals(0.5f, PhoneHostMotionLimits.sensationIntensityOf(1.4f), 0.0001f)
        assertEquals(0f, PhoneHostMotionLimits.sensationIntensityOf(Float.NaN), 0f)
    }

    @Test
    fun `range is symmetric and clamps both directions`() {
        val limits = PhoneHostMotionLimits(1f, 0f, 1f, sensationIntensity = 0.2f)
        assertEquals(0.3f, limits.sensationMinimum, 0.0001f)
        assertEquals(0.7f, limits.sensationMaximum, 0.0001f)
        assertEquals(0.7f, limits.clampSensation(0.95f), 0.0001f)
        assertEquals(0.3f, limits.clampSensation(0.02f), 0.0001f)
        assertEquals(0.45f, limits.clampSensation(0.45f), 0.0001f)
        assertEquals(0.5f, limits.clampSensation(Float.NaN), 0f)
    }

    @Test
    fun `zero intensity locks the browser to neutral and default allows the full range`() {
        val locked = PhoneHostMotionLimits(1f, 0f, 1f, sensationIntensity = 0f)
        assertEquals(0.5f, locked.clampSensation(0f), 0.0001f)
        assertEquals(0.5f, locked.clampSensation(1f), 0.0001f)

        val full = PhoneHostMotionLimits(1f, 0f, 1f)
        assertEquals(0f, full.sensationMinimum, 0f)
        assertEquals(1f, full.sensationMaximum, 0f)
    }

    @Test
    fun `clamped bounds never encode outside the range in BLE percent`() {
        // BLE encodes (value * 100).toInt(): the lower bound must not truncate below it.
        listOf(0.05f, 0.1f, 0.2f, 0.3f, 0.35f, 0.45f).forEach { d ->
            val limits = PhoneHostMotionLimits(1f, 0f, 1f, sensationIntensity = d)
            val minPercent = Math.round(limits.sensationMinimum * 100f)
            val maxPercent = Math.round(limits.sensationMaximum * 100f)
            val low = (limits.clampSensation(0f) * 100f).toInt()
            val high = (limits.clampSensation(1f) * 100f).toInt()
            assertTrue("d=$d low=$low min=$minPercent", low >= minPercent)
            assertTrue("d=$d high=$high max=$maxPercent", high <= maxPercent)
        }
    }

    @Test
    fun `invalid intensity is sanitized`() {
        val tooWide = PhoneHostMotionLimits(1f, 0f, 1f, sensationIntensity = 3f)
        assertEquals(0f, tooWide.sensationMinimum, 0.0001f)
        assertEquals(1f, tooWide.sensationMaximum, 0.0001f)
        val nan = PhoneHostMotionLimits(1f, 0f, 1f, sensationIntensity = Float.NaN)
        assertEquals(0.5f, nan.sensationMinimum, 0f)
        assertEquals(0.5f, nan.sensationMaximum, 0f)
    }
}
