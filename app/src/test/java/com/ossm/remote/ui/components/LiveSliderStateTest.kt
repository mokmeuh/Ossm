package com.ossm.remote.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class LiveSliderStateTest {
    @Test
    fun `first touch jitter emits nothing until displayed integer target changes`() {
        val state = LiveSliderState()
        state.onDown(valuePercent = 0f, pointerY = 390f, trackHeightPx = 400f, uptimeMillis = 0)

        assertNull(state.onMove(pointerY = 390f, uptimeMillis = 10))
        assertNull(state.onMove(pointerY = 389f, uptimeMillis = 20))
        assertEquals(1f, state.onMove(pointerY = 386f, uptimeMillis = 30)!!, 0.001f)
        assertNull(state.onMove(pointerY = 385f, uptimeMillis = 40))
        state.endTouch()
        state.onDown(valuePercent = 1f, pointerY = 380f, trackHeightPx = 400f, uptimeMillis = 50)
        assertNull(state.onMove(pointerY = 379f, uptimeMillis = 60))
    }

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
    fun `finger down and every vertical move emit real-time positions even with the stationary threshold`() {
        // The stationary threshold only drives the Precision clock: it never gates emission.
        val state = LiveSliderState(stationaryThresholdPx = LIVE_STATIONARY_THRESHOLD_PX)
        val emitted = mutableListOf<Int>()

        emitted += state.onDown(pointerY = 300f, trackHeightPx = 300f, uptimeMillis = 0).toInt()
        state.onMove(pointerY = 295f, uptimeMillis = 10)?.let { emitted += it.toInt() }
        state.onMove(pointerY = 225f, uptimeMillis = 20)?.let { emitted += it.toInt() }
        state.onMove(pointerY = 150f, uptimeMillis = 30)?.let { emitted += it.toInt() }
        state.onMove(pointerY = 75f, uptimeMillis = 40)?.let { emitted += it.toInt() }
        state.onMove(pointerY = 0f, uptimeMillis = 50)?.let { emitted += it.toInt() }

        assertEquals(listOf(0, 1, 25, 50, 75, 100), emitted)
    }

    @Test
    fun `stationary threshold is 8px and decoupled from emission`() {
        assertEquals(8f, LIVE_STATIONARY_THRESHOLD_PX, 0f)
        val state = LiveSliderState(stationaryThresholdPx = LIVE_STATIONARY_THRESHOLD_PX)
        state.onDown(pointerY = 400f, trackHeightPx = 400f, uptimeMillis = 0)

        // A 4px move (1%) is below the clock threshold yet still emits as soon as the integer changes.
        assertEquals(1f, state.onMove(pointerY = 396f, uptimeMillis = 10)!!, 0.001f)
    }

    @Test
    fun `jitter within the stationary threshold does not keep precision from narrowing`() {
        val state = LiveSliderState(
            stationaryThresholdPx = LIVE_STATIONARY_THRESHOLD_PX,
            precisionEnabled = true
        )
        state.onDown(pointerY = 100f, trackHeightPx = 200f, uptimeMillis = 0)

        state.onMove(pointerY = 103f, uptimeMillis = 500)
        state.onMove(pointerY = 98f, uptimeMillis = 1_000)
        state.onMove(pointerY = 101f, uptimeMillis = 1_500)

        assertTrue(state.advanceTime(2_000))
        assertEquals(10f..90f, state.visibleRange)
    }

    @Test
    fun `movement beyond the stationary threshold restarts the precision clock`() {
        val state = LiveSliderState(
            stationaryThresholdPx = LIVE_STATIONARY_THRESHOLD_PX,
            precisionEnabled = true
        )
        state.onDown(pointerY = 100f, trackHeightPx = 200f, uptimeMillis = 0)

        state.onMove(pointerY = 120f, uptimeMillis = 1_500)

        assertFalse(state.advanceTime(2_000))
        assertTrue(state.advanceTime(3_500))
    }

    @Test
    fun `without a stationary threshold any pixel of jitter restarts the clock`() {
        val state = LiveSliderState(precisionEnabled = true)
        state.onDown(pointerY = 100f, trackHeightPx = 200f, uptimeMillis = 0)

        state.onMove(pointerY = 101f, uptimeMillis = 1_500)

        assertFalse(state.advanceTime(2_000))
    }

    @Test
    fun `accessibility step sends the position then always stops`() {
        val events = mutableListOf<String>()

        val next = commitLiveAccessibilityPosition(
            target = 130f,
            onPosition = { events += "position:${it.roundToInt()}" },
            onTouchEnd = { events += "stop" }
        )

        assertEquals(100f, next, 0f)
        assertEquals(listOf("position:100", "stop"), events)
    }

    @Test
    fun `accessibility step still stops when the position callback fails`() {
        val events = mutableListOf<String>()

        try {
            commitLiveAccessibilityPosition(
                target = 40f,
                onPosition = { throw IllegalStateException("ble") },
                onTouchEnd = { events += "stop" }
            )
        } catch (_: IllegalStateException) {
            events += "failed"
        }

        assertEquals(listOf("stop", "failed"), events)
    }

    @Test
    fun `slow one pixel moves emit continuously instead of accumulating into a jump`() {
        val state = LiveSliderState()
        val emitted = mutableListOf<Int>()

        state.onDown(pointerY = 100f, trackHeightPx = 100f, uptimeMillis = 0)
        for (pointerY in 99 downTo 95) {
            state.onMove(pointerY = pointerY.toFloat(), uptimeMillis = (100 - pointerY).toLong())
                ?.let { emitted += it.roundToInt() }
        }

        assertEquals(listOf(1, 2, 3, 4, 5), emitted)
    }

    @Test
    fun `thumb capture accepts current value and rejects distant values`() {
        val thumbY = liveThumbCenterY(
            valuePercent = 20f,
            trackHeightPx = 400f,
            trackInsetPx = 40f
        )

        assertTrue(isLiveThumbHit(pointerY = thumbY + 20f, thumbY = thumbY, hitRadiusPx = 24f))
        assertFalse(isLiveThumbHit(pointerY = 40f, thumbY = thumbY, hitRadiusPx = 24f))
    }

    @Test
    fun `thumb geometry at fifty percent is selectable at the visual grip`() {
        val geometry = liveTrackGeometry(
            valuePercent = 50f,
            visibleRange = 0f..100f,
            canvasHeightPx = 500f,
            trackInsetPx = 50f
        )

        assertEquals(250f, geometry.thumbY, 0.001f)
        assertTrue(isLiveThumbHit(pointerY = 250f, thumbY = geometry.thumbY, hitRadiusPx = 48f))
        assertFalse(isLiveThumbHit(pointerY = 450f, thumbY = geometry.thumbY, hitRadiusPx = 48f))
    }

    @Test
    fun `captured drag is relative and never jumps to initial pointer position`() {
        val state = LiveSliderState()

        assertEquals(
            20f,
            state.onDown(
                valuePercent = 20f,
                pointerY = 310f,
                trackHeightPx = 400f,
                uptimeMillis = 0
            ),
            0.001f
        )
        assertEquals(25f, state.onMove(pointerY = 290f, uptimeMillis = 20)!!, 0.001f)
    }

    @Test
    fun `captured fifty percent drag stays relative and never jumps to zero`() {
        val state = LiveSliderState()
        val geometry = liveTrackGeometry(
            valuePercent = 50f,
            visibleRange = 0f..100f,
            canvasHeightPx = 500f,
            trackInsetPx = 50f
        )

        assertEquals(
            50f,
            state.onDown(
                valuePercent = 50f,
                pointerY = geometry.thumbY,
                trackHeightPx = geometry.height,
                uptimeMillis = 0
            ),
            0.001f
        )
        assertNull(state.onMove(pointerY = geometry.thumbY, uptimeMillis = 20))
        assertEquals(51f, state.onMove(pointerY = geometry.thumbY - 4f, uptimeMillis = 40)!!, 0.001f)
    }

    @Test
    fun `captured grip reaches Fond and Home from the canvas edges`() {
        val state = LiveSliderState()
        val geometry = liveTrackGeometry(
            valuePercent = 50f,
            visibleRange = 0f..100f,
            canvasHeightPx = 500f,
            trackInsetPx = 50f
        )

        state.onDown(
            valuePercent = 50f,
            pointerY = geometry.thumbY,
            trackHeightPx = geometry.height,
            uptimeMillis = 0
        )

        assertEquals(100f, state.onMove(pointerY = 0f, uptimeMillis = 20)!!, 0.001f)
        assertEquals(0f, state.onMove(pointerY = 500f, uptimeMillis = 40)!!, 0.001f)
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
