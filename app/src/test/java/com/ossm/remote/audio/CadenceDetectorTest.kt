package com.ossm.remote.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceDetectorTest {

    @Test
    fun `peak requires threshold crossing and low-level rearm`() {
        val detector = CadenceDetector()

        assertFalse(detector.consume(0.34f, 0L).peakDetected)
        assertTrue(detector.consume(0.35f, 100L).peakDetected)
        assertFalse(detector.consume(0.80f, 200L).peakDetected)
        assertFalse(detector.consume(0.20f, 300L).peakDetected)
        assertTrue(detector.consume(0.35f, 400L).peakDetected)
    }

    @Test
    fun `peaks within the refractory interval are suppressed`() {
        val detector = CadenceDetector()

        assertTrue(detector.consume(0.35f, 0L).peakDetected)
        detector.consume(0.20f, 100L)
        assertFalse(detector.consume(0.35f, 249L).peakDetected)
        detector.consume(0.20f, 300L)
        assertTrue(detector.consume(0.35f, 350L).peakDetected)
    }

    @Test
    fun `peaks every 500 milliseconds reach 120 ppm and full speed`() {
        val detector = CadenceDetector()
        var reading = detector.consume(0f, 0L)

        repeat(8) { index ->
            val peakTimestampMs = index * 500L
            reading = detector.consume(0.35f, peakTimestampMs)
            detector.consume(0.20f, peakTimestampMs + 1L)
        }

        assertEquals(120f, reading.peaksPerMinute, 0.001f)
        assertEquals(1f, reading.speedFraction, 0.001f)
    }

    @Test
    fun `three seconds without a peak sets speed fraction to zero`() {
        val detector = CadenceDetector()

        detector.consume(0.35f, 0L)
        val reading = detector.consume(0.20f, 3_000L)

        assertEquals(0f, reading.speedFraction, 0.001f)
    }

    @Test
    fun `out of range levels are clamped before peak detection`() {
        val detector = CadenceDetector()

        assertFalse(detector.consume(-1f, 0L).peakDetected)
        assertTrue(detector.consume(2f, 1L).peakDetected)
        assertFalse(detector.consume(-1f, 300L).peakDetected)
        assertTrue(detector.consume(2f, 500L).peakDetected)
    }
}
