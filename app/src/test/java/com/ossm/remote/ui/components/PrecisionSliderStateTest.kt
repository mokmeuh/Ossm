package com.ossm.remote.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecisionSliderStateTest {
    @Test
    fun `pointer down preserves the current value`() {
        val state = PrecisionSliderState(movementThresholdPx = 8f)

        state.onDown(valuePercent = 50f, pointerX = 180f, trackWidthPx = 300f, uptimeMillis = 0)

        assertEquals(50f, state.valuePercent, 0.001f)
        assertEquals(PrecisionLevel.Global, state.level)
    }

    @Test
    fun `horizontal drag is relative to the pointer down value`() {
        val state = PrecisionSliderState(movementThresholdPx = 8f)
        state.onDown(valuePercent = 50f, pointerX = 180f, trackWidthPx = 200f, uptimeMillis = 0)

        val changed = state.onMove(pointerX = 200f, uptimeMillis = 100)

        assertTrue(changed)
        assertEquals(60f, state.valuePercent, 0.001f)
    }

    @Test
    fun `small movements update continuously without an eight pixel staircase`() {
        val state = PrecisionSliderState(movementThresholdPx = 8f)
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)

        assertTrue(state.onMove(pointerX = 101f, uptimeMillis = 10))
        assertEquals(50.5f, state.valuePercent, 0.001f)
        assertTrue(state.onMove(pointerX = 102f, uptimeMillis = 20))
        assertEquals(51f, state.valuePercent, 0.001f)
    }

    @Test
    fun `each stationary interval advances through plus or minus thirty twenty ten and five`() {
        val state = PrecisionSliderState()
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)

        assertFalse(state.advanceTime(1499))
        assertTrue(state.advanceTime(1500))
        assertEquals(PrecisionLevel.Fine30, state.level)
        assertEquals(20f..80f, state.visibleRange)
        assertTrue(state.advanceTime(3000))
        assertEquals(PrecisionLevel.Fine20, state.level)
        assertEquals(30f..70f, state.visibleRange)
        assertTrue(state.advanceTime(4500))
        assertEquals(PrecisionLevel.Fine10, state.level)
        assertEquals(40f..60f, state.visibleRange)
        assertTrue(state.advanceTime(6000))
        assertEquals(PrecisionLevel.Fine5, state.level)
        assertEquals(45f, state.visibleRange.start, 0.001f)
        assertEquals(55f, state.visibleRange.endInclusive, 0.001f)
        assertFalse(state.advanceTime(7500))
    }

    @Test
    fun `fine ranges retain their full useful span at global edges`() {
        val low = PrecisionSliderState()
        low.onDown(valuePercent = 2f, pointerX = 0f, trackWidthPx = 200f, uptimeMillis = 0)
        low.advanceTime(1500)

        val high = PrecisionSliderState()
        high.onDown(valuePercent = 98f, pointerX = 200f, trackWidthPx = 200f, uptimeMillis = 0)
        high.advanceTime(1500)

        assertEquals(0f, low.visibleRange.start, 0.001f)
        assertEquals(60f, low.visibleRange.endInclusive, 0.001f)
        assertEquals(40f, high.visibleRange.start, 0.001f)
        assertEquals(100f, high.visibleRange.endInclusive, 0.001f)
    }

    @Test
    fun `meaningful movement resets the stationary timer`() {
        val state = PrecisionSliderState(movementThresholdPx = 8f)
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)

        assertTrue(state.onMove(pointerX = 104f, uptimeMillis = 1400))
        assertEquals(52f, state.valuePercent, 0.001f)
        // Four pixels are enough to update the scalar, but not enough to restart the
        // stationary precision clock.
        assertTrue(state.advanceTime(1500))
        assertEquals(PrecisionLevel.Fine30, state.level)

        state.onMove(pointerX = 120f, uptimeMillis = 2000)
        assertFalse(state.advanceTime(3499))
        assertEquals(PrecisionLevel.Fine30, state.level)
        assertTrue(state.advanceTime(3500))
        assertEquals(PrecisionLevel.Fine20, state.level)
    }

    @Test
    fun `a new gesture adopts an externally supplied value after cancellation`() {
        val state = PrecisionSliderState()
        state.onDown(valuePercent = 25f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)
        state.cancel()

        state.onDown(valuePercent = 75f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 100)

        assertEquals(75f, state.valuePercent, 0.001f)
        assertTrue(state.onMove(pointerX = 101f, uptimeMillis = 110))
        assertEquals(75.5f, state.valuePercent, 0.001f)
    }

    @Test
    fun `next level centers on value mapped after movement`() {
        val state = PrecisionSliderState(movementThresholdPx = 8f)
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)
        state.advanceTime(1500)

        state.onMove(pointerX = 120f, uptimeMillis = 2000)
        assertEquals(56f, state.valuePercent, 0.001f)
        state.advanceTime(3500)

        assertEquals(36f, state.visibleRange.start, 0.001f)
        assertEquals(76f, state.visibleRange.endInclusive, 0.001f)
    }

    @Test
    fun `cancel returns no commit and restores the global visual range`() {
        val state = PrecisionSliderState()
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)
        state.advanceTime(1500)

        state.cancel()

        assertNull(state.release())
        assertEquals(PrecisionLevel.Global, state.level)
        assertEquals(0f, state.visibleRange.start, 0.001f)
        assertEquals(100f, state.visibleRange.endInclusive, 0.001f)
    }

    @Test
    fun `successful release returns exactly one commit and restores global range`() {
        val state = PrecisionSliderState()
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)
        state.onMove(pointerX = 120f, uptimeMillis = 100)
        state.advanceTime(1600)

        assertEquals(60f, state.release()!!, 0.001f)
        assertNull(state.release())
        assertEquals(PrecisionLevel.Global, state.level)
        assertEquals(0f, state.visibleRange.start, 0.001f)
        assertEquals(100f, state.visibleRange.endInclusive, 0.001f)
    }
}
