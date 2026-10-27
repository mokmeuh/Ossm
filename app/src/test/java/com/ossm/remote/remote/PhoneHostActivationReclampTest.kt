package com.ossm.remote.remote

import com.ossm.remote.model.StrokeEngineCommand
import com.ossm.remote.session.StrokeEngineActivationSpeedGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression for a retained command completed after the owner narrows its range. */
class PhoneHostActivationReclampTest {
    @Test
    fun `pending 20 to 80 activation is reclamped to current 20 to 40`() {
        val gate = StrokeEngineActivationSpeedGate()
        val token = gate.begin()
        assertTrue(
            gate.retainIfActivating(
                StrokeEngineCommand(speed = 0.4f, depthMin = 0.2f, depthMax = 0.8f)
            )
        )

        val retained = gate.finishResult(token, activationSucceeded = true).command
            ?: error("expected retained activation command")
        val currentHostLimits = PhoneHostMotionLimits(0.4f, 0.2f, 0.4f)
        val safeCommand = currentHostLimits.clampStrokeEngineCommand(retained)

        assertEquals(0.2f, safeCommand.depthMin, 0.0001f)
        assertEquals(0.4f, safeCommand.depthMax, 0.0001f)
    }

    @Test
    fun `recorded raw live target is clamped in physical space before BLE reconversion`() {
        val currentHostLimits = PhoneHostMotionLimits(0.4f, 0.2f, 0.4f)

        // Existing non-inverted Live raw 0 would physically target 100%; the
        // current owner range must convert that to the physical 40% endpoint.
        assertEquals(60, currentHostLimits.mapPhysicalPositionForBle(100, liveInvert = false))
    }
}
