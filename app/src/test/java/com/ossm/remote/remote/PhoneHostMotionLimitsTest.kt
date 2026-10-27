package com.ossm.remote.remote

import com.ossm.remote.model.StrokeEngineCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneHostMotionLimitsTest {
    @Test fun `remote speed and stroke command never exceed captured phone envelope`() {
        val limits = PhoneHostMotionLimits(0.4f, 0.2f, 0.4f)

        assertEquals(0.4f, limits.clampSpeed(0.9f), 0.0001f)
        assertEquals(
            StrokeEngineCommand(speed = 0.4f, depthMin = 0.2f, depthMax = 0.4f, sensation = 0.5f),
            limits.clampStrokeEngineCommand(
                StrokeEngineCommand(speed = 0.9f, depthMin = 0f, depthMax = 1f)
            )
        )
    }

    @Test fun `physical live mapping stays inside selected interval in both directions`() {
        val limits = PhoneHostMotionLimits(0.8f, 0.2f, 0.4f)

        assertEquals(40, limits.mapLiveDevicePositionPercent(0, liveInvert = false))
        assertEquals(20, limits.mapLiveDevicePositionPercent(100, liveInvert = false))
        assertEquals(20, limits.mapLiveDevicePositionPercent(0, liveInvert = true))
        assertEquals(40, limits.mapLiveDevicePositionPercent(100, liveInvert = true))

        // Existing BLE mapping receives the inverse raw argument, so the
        // device still receives the physical endpoint selected above.
        assertEquals(60, limits.mapLiveUiPositionForBle(0, liveInvert = false))
        assertEquals(20, limits.mapLiveUiPositionForBle(0, liveInvert = true))
    }

    @Test fun `incompatible local range rejects stale captured envelope`() {
        val captured = PhoneHostMotionLimits(0.4f, 0.2f, 0.4f)

        assertNull(captured.intersectLocalDepthRange(0.8f, 1f))
    }

    @Test fun `local lowering intersects captured envelope without widening it`() {
        val captured = PhoneHostMotionLimits(0.4f, 0.2f, 0.8f)

        assertEquals(
            PhoneHostMotionLimits(0.4f, 0.3f, 0.6f),
            captured.intersectLocalDepthRange(0.3f, 0.6f)
        )
    }
}

