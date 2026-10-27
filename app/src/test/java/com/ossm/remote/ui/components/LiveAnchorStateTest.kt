package com.ossm.remote.ui.components

import com.ossm.remote.session.LiveReturnRamp
import com.ossm.remote.session.liveReturnDurationMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAnchorStateTest {
    // 300 x 400 surface (y grows downward, top edge = 0).

    @Test
    fun `reach is the distance from the anchor to the farthest top corner`() {
        // Bottom-left anchor: farthest top corner is top-right (300, 0) -> 500 px.
        assertEquals(500f, liveAnchorReach(0f, 400f, 300f, 400f), 0.001f)
        // Centered anchor: hypot(150, 200) = 250.
        assertEquals(250f, liveAnchorReach(150f, 200f, 300f, 400f), 0.001f)
        // Off-center anchor picks the farther of the two top corners (top-right here).
        assertEquals(kotlin.math.hypot(250f, 100f), liveAnchorReach(50f, 100f, 300f, 400f), 0.001f)
        // Degenerate sizes never divide by zero.
        assertTrue(liveAnchorReach(0f, 0f, 0f, 0f) >= 1f)
    }

    @Test
    fun `anchor on the top edge uses the minimum reach`() {
        // Top-left corner anchor: the top corners are 0 and 300 px away -> 300 px.
        assertEquals(300f, liveAnchorReach(0f, 0f, 300f, 400f, minReachPx = 144f), 0.001f)
        // Narrow surface with a top anchor: the geometric reach would be tiny, the minimum wins.
        assertEquals(144f, liveAnchorReach(10f, 0f, 20f, 400f, minReachPx = 144f), 0.001f)
        val state = LiveAnchorState()
        state.onDown(0f, 10f, 0f, 20f, 400f, minReachPx = 144f)
        // Nothing is above a top-edge anchor: moving anywhere stays at the base, no NaN.
        assertNull(state.onMove(10f, 300f))
        assertEquals(0, state.desired)
    }

    @Test
    fun `above the anchor the radial distance counts and the farthest top corner is 100`() {
        val geometry = liveAnchorGeometry(150f, 200f, 300f, 400f)
        assertEquals(250f, geometry.reach, 0.001f)
        // Straight up 100 px -> 0.4.
        assertEquals(0.4f, liveAnchorFraction(150f, 100f, geometry), 0.0001f)
        // Diagonal up-right (dx 60, dy 80 -> 100 px) is the same radial distance -> 0.4:
        // sideways movement counts above the line.
        assertEquals(0.4f, liveAnchorFraction(210f, 120f, geometry), 0.0001f)
        // Farthest top corner = 100 %, beyond clamps.
        assertEquals(1f, liveAnchorFraction(0f, 0f, geometry), 0.0001f)
        assertEquals(1f, liveAnchorFraction(-2_000f, -2_000f, geometry), 0.0001f)
        assertEquals(100f, liveAnchorTargetPercent(0f, 1.5f), 0.0001f)
    }

    @Test
    fun `on or below the anchor line the target stays at the base`() {
        val geometry = liveAnchorGeometry(150f, 200f, 300f, 400f)
        // Exactly on the line, even far to the side: no depth.
        assertEquals(0f, liveAnchorFraction(300f, 200f, geometry), 0.0001f)
        assertEquals(0f, liveAnchorFraction(0f, 200f, geometry), 0.0001f)
        // Below the line, far away: still nothing, never a positive rebound.
        assertEquals(0f, liveAnchorFraction(150f, 400f, geometry), 0.0001f)
        assertEquals(0f, liveAnchorFraction(0f, 400f, geometry), 0.0001f)

        val state = LiveAnchorState()
        state.onDown(30f, 150f, 200f, 300f, 400f)
        // 100 px up of a 250 px reach -> 30 + 0.4 * 70 = 58.
        assertEquals(58f, state.onMove(150f, 100f)!!, 0.01f)
        // Then below the line, far to the side: back to exactly the base.
        assertEquals(30f, state.onMove(0f, 400f)!!, 0.01f)
        assertEquals(30, state.desired)
    }

    @Test
    fun `pointer down anywhere emits the current value without a jump`() {
        val state = LiveAnchorState()
        val atDown = state.onDown(currentPercent = 0f, pointerX = 150f, pointerY = 400f, widthPx = 300f, heightPx = 400f)
        assertEquals(0f, atDown, 0.0001f)
        assertEquals(0, state.desired)
        // Farthest top corner -> 100 %.
        assertEquals(100f, state.onMove(0f, 0f)!!, 0.001f)
        // Fast round trips are pure geometry, no rate limit: back on the anchor is 0 at once.
        assertEquals(0f, state.onMove(150f, 400f)!!, 0.001f)
        assertEquals(100f, state.onMove(300f, 0f)!!, 0.001f)
        // Dropping below the line goes straight back to the base, never negative.
        assertEquals(0f, state.onMove(150f, 500f)!!, 0.001f)
    }

    @Test
    fun `re-touch during the return anchors at the current position and adds depth beyond it`() {
        val state = LiveAnchorState()
        val atDown = state.onDown(currentPercent = 40f, pointerX = 150f, pointerY = 200f, widthPx = 300f, heightPx = 400f)
        assertEquals(40f, atDown, 0.0001f)
        assertEquals(40, state.desired)
        // Half the reach (125 px of 250 px) straight up -> 40 + 0.5 * 60 = 70.
        assertEquals(70f, state.onMove(150f, 75f)!!, 0.01f)
        // Farthest top corner still reaches 100.
        assertEquals(100f, state.onMove(300f, 0f)!!, 0.01f)
        // Below the anchor gives back the base, never less (no jump below it).
        assertEquals(40f, state.onMove(0f, 400f)!!, 0.01f)
    }

    @Test
    fun `cancelling a touch prevents later movement callbacks`() {
        val state = LiveAnchorState()
        state.onDown(0f, 10f, 300f, 300f, 400f)
        state.cancelTouch()
        assertNull(state.onMove(300f, 0f))
        assertNull(state.geometry)
    }

    @Test
    fun `full release return takes at least five seconds at the capped rate`() {
        assertTrue(liveReturnDurationMillis(100) >= 5_000L)
        val ramp = LiveReturnRamp(initialPercent = 100, startAtMillis = 0L)
        assertEquals(100, ramp.advance(0L))
        assertEquals(80, ramp.advance(1_000L))
        assertEquals(0, ramp.advance(5_000L))
        assertTrue(ramp.isComplete)
    }

    @Test
    fun `return ramp seeds a nonzero device uptime without an entry jump`() {
        val deviceUptime = 7_200_000L
        val ramp = LiveReturnRamp(initialPercent = 100, startAtMillis = deviceUptime)

        assertEquals(100, ramp.advance(deviceUptime))
        assertEquals(99, ramp.advance(deviceUptime + 50L))
    }

    @Test
    fun `accessibility steps stay bounded`() {
        assertEquals(10f, liveAnchorAccessibilityStep(0f, +1), 0.0001f)
        assertEquals(0f, liveAnchorAccessibilityStep(5f, -1), 0.0001f)
        assertEquals(100f, liveAnchorAccessibilityStep(95f, +1), 0.0001f)
    }
}
