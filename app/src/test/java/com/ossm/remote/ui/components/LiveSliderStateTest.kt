package com.ossm.remote.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSliderStateTest {
    @Test
    fun `vertical mapping places Home at bottom and Fond at top`() {
        assertEquals(100f, livePositionForPointer(pointerY = 0f, trackHeightPx = 400f), 0.001f)
        assertEquals(75f, livePositionForPointer(pointerY = 100f, trackHeightPx = 400f), 0.001f)
        assertEquals(50f, livePositionForPointer(pointerY = 200f, trackHeightPx = 400f), 0.001f)
        assertEquals(0f, livePositionForPointer(pointerY = 400f, trackHeightPx = 400f), 0.001f)
        assertEquals(100f, livePositionForPointer(pointerY = -20f, trackHeightPx = 400f), 0.001f)
        assertEquals(0f, livePositionForPointer(pointerY = 420f, trackHeightPx = 400f), 0.001f)
    }

    @Test
    fun `finger down and every meaningful vertical move emit real-time positions`() {
        val state = LiveSliderState(movementThresholdPx = 8f)
        val emitted = mutableListOf<Int>()

        emitted += state.onDown(pointerY = 300f, trackHeightPx = 300f, uptimeMillis = 0).toInt()
        state.onMove(pointerY = 295f, uptimeMillis = 10)?.let { emitted += it.toInt() }
        state.onMove(pointerY = 225f, uptimeMillis = 20)?.let { emitted += it.toInt() }
        state.onMove(pointerY = 150f, uptimeMillis = 30)?.let { emitted += it.toInt() }
        state.onMove(pointerY = 75f, uptimeMillis = 40)?.let { emitted += it.toInt() }
        state.onMove(pointerY = 0f, uptimeMillis = 50)?.let { emitted += it.toInt() }

        assertEquals(listOf(0, 25, 50, 75, 100), emitted)
    }

    @Test
    fun `Live precision is disabled by default and never narrows automatically`() {
        val state = LiveSliderState()
        state.onDown(pointerY = 100f, trackHeightPx = 200f, uptimeMillis = 0)

        assertFalse(state.advanceTime(2_000))
        assertFalse(state.advanceTime(10_000))
        assertEquals(0f..100f, state.visibleRange)
    }

    @Test
    fun `precision narrows around current vertical target without changing it`() {
        val state = LiveSliderState(precisionEnabled = true)
        state.onDown(pointerY = 100f, trackHeightPx = 200f, uptimeMillis = 0)

        assertTrue(state.advanceTime(2_000))
        assertEquals(10f..90f, state.visibleRange)
        assertEquals(50f, state.valuePercent, 0.001f)

        assertEquals(60f, state.onMove(pointerY = 75f, uptimeMillis = 2_100)!!, 0.001f)
        assertTrue(state.advanceTime(4_100))
        assertEquals(30f..90f, state.visibleRange)
        assertEquals(60f, state.valuePercent, 0.001f)
    }

    @Test
    fun `release is stop-only with no final target callback`() {
        val state = LiveSliderState()
        val events = mutableListOf<String>()
        state.onDown(pointerY = 150f, trackHeightPx = 300f, uptimeMillis = 0)
        state.onMove(pointerY = 75f, uptimeMillis = 100)

        finishLiveTouchGesture(
            gestureState = state,
            onTouchEnd = { events += "stop" }
        )

        assertEquals(listOf("stop"), events)
        assertNull(state.onMove(pointerY = 0f, uptimeMillis = 200))
        assertEquals(0f..100f, state.visibleRange)
    }

    @Test
    fun `cancel is stop-only with no final target callback`() {
        val state = LiveSliderState(precisionEnabled = true)
        val events = mutableListOf<String>()
        state.onDown(pointerY = 150f, trackHeightPx = 300f, uptimeMillis = 0)
        state.advanceTime(2_000)

        finishLiveTouchGesture(
            gestureState = state,
            onTouchEnd = { events += "stop" }
        )

        assertEquals(listOf("stop"), events)
        assertNull(state.onMove(pointerY = 0f, uptimeMillis = 2_100))
        assertEquals(0f..100f, state.visibleRange)
    }
}
