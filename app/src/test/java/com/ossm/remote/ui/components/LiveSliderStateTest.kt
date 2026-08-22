package com.ossm.remote.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSliderStateTest {
    @Test
    fun `down and near-track tap preserve current Live position`() {
        val state = LiveSliderState(movementThresholdPx = 8f)

        state.onDown(valuePercent = 42f, pointerX = 190f, trackWidthPx = 300f, uptimeMillis = 0)

        assertEquals(42f, state.valuePercent, 0.001f)
        assertEquals(0f..100f, state.visibleRange)
    }

    @Test
    fun `moving finger previews linearly without narrowing`() {
        val state = LiveSliderState(movementThresholdPx = 8f)
        state.onDown(valuePercent = 40f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)

        assertTrue(state.onMove(pointerX = 120f, uptimeMillis = 1_900))
        assertFalse(state.advanceTime(2_000))

        assertEquals(50f, state.valuePercent, 0.001f)
        assertEquals(0f..100f, state.visibleRange)
    }

    @Test
    fun `first stationary level is always ten to ninety`() {
        val state = LiveSliderState()
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)

        assertFalse(state.advanceTime(1_999))
        assertTrue(state.advanceTime(2_000))
        assertEquals(10f..90f, state.visibleRange)

    }

    @Test
    fun `later Live levels reduce half span and recenter on current value`() {
        val state = LiveSliderState()
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)
        state.advanceTime(2_000)

        state.onMove(pointerX = 75f, uptimeMillis = 2_500)
        assertEquals(40f, state.valuePercent, 0.001f)
        state.advanceTime(4_500)
        assertEquals(10f..70f, state.visibleRange)
        state.advanceTime(6_500)
        assertEquals(20f..60f, state.visibleRange)
        state.advanceTime(8_500)
        assertEquals(30f..50f, state.visibleRange)
        state.advanceTime(10_500)
        assertEquals(35f..45f, state.visibleRange)
        assertFalse(state.advanceTime(12_500))
    }

    @Test
    fun `Live recentered levels preserve span near global edges`() {
        val state = LiveSliderState()
        state.onDown(valuePercent = 95f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)
        state.advanceTime(2_000)
        state.advanceTime(4_000)

        assertEquals(40f..100f, state.visibleRange)
    }

    @Test
    fun `movement restarts timer while retaining narrowed range`() {
        val state = LiveSliderState(movementThresholdPx = 8f)
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)
        state.advanceTime(2_000)

        state.onMove(pointerX = 120f, uptimeMillis = 2_500)

        assertEquals(10f..90f, state.visibleRange)
        assertFalse(state.advanceTime(4_499))
        assertTrue(state.advanceTime(4_500))
        assertEquals(28f..88f, state.visibleRange)
    }

    @Test
    fun `drag after narrowing uses current range span without transition jump`() {
        val state = LiveSliderState(movementThresholdPx = 8f)
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)
        state.advanceTime(2_000)

        assertEquals(50f, state.valuePercent, 0.001f)
        state.onMove(pointerX = 120f, uptimeMillis = 2_100)

        assertEquals(58f, state.valuePercent, 0.001f)
    }

    @Test
    fun `cancel does not commit and resets Live range`() {
        val state = LiveSliderState()
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)
        state.advanceTime(2_000)

        state.cancel()

        assertNull(state.release())
        assertEquals(0f..100f, state.visibleRange)
    }

    @Test
    fun `release commits once and resets Live range`() {
        val state = LiveSliderState()
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)
        state.onMove(pointerX = 120f, uptimeMillis = 100)

        assertEquals(60f, state.release()!!, 0.001f)
        assertNull(state.release())
        assertEquals(0f..100f, state.visibleRange)
    }

    @Test
    fun `pointer up commits final position then requests Live stop`() {
        val state = LiveSliderState()
        val events = mutableListOf<String>()
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)
        state.onMove(pointerX = 120f, uptimeMillis = 100)

        finishLiveTouchGesture(
            released = true,
            gestureState = state,
            onCommit = { events += "commit:${it.toInt()}" },
            onTouchEnd = { events += "stop" }
        )

        assertEquals(listOf("commit:60", "stop"), events)
        assertNull(state.release())
    }

    @Test
    fun `pointer cancel skips commit and immediately requests Live stop`() {
        val state = LiveSliderState()
        val events = mutableListOf<String>()
        state.onDown(valuePercent = 50f, pointerX = 100f, trackWidthPx = 200f, uptimeMillis = 0)
        state.onMove(pointerX = 120f, uptimeMillis = 100)

        finishLiveTouchGesture(
            released = false,
            gestureState = state,
            onCommit = { events += "commit" },
            onTouchEnd = { events += "stop" }
        )

        assertEquals(listOf("stop"), events)
        assertNull(state.release())
    }
}
