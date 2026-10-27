package com.ossm.remote.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeEngineActivationSpeedGateTest {
    @Test
    fun `latest user speed is retained until stroke engine activation finishes`() {
        val gate = StrokeEngineActivationSpeedGate()

        gate.begin()

        assertTrue(gate.retainIfActivating(2))
        assertTrue(gate.retainIfActivating(7))
        assertEquals(7, gate.finish(activationSucceeded = true))
        assertFalse(gate.retainIfActivating(9))
    }

    @Test
    fun `stale activation completion cannot consume newer pending speed`() {
        val gate = StrokeEngineActivationSpeedGate()

        val firstToken = gate.begin()
        assertTrue(gate.retainIfActivating(1))

        val secondToken = gate.begin()
        assertTrue(gate.retainIfActivating(7))

        assertEquals(null, gate.finish(firstToken, activationSucceeded = true))
        assertEquals(7, gate.finish(secondToken, activationSucceeded = true))
    }

    @Test
    fun `progressive completion distinguishes no override explicit zero and cancelled stop`() {
        val noOverrideToken = gate.begin()
        val noOverride = gate.finishResult(noOverrideToken, activationSucceeded = true)
        assertTrue(noOverride.accepted)
        assertEquals(null, noOverride.speedPercent)

        val zeroToken = gate.begin()
        assertTrue(gate.retainIfActivating(0))
        val explicitZero = gate.finishResult(zeroToken, activationSucceeded = true)
        assertTrue(explicitZero.accepted)
        assertEquals(0, explicitZero.speedPercent)

        val stoppedToken = gate.begin()
        assertTrue(gate.retainIfActivating(1))
        gate.cancel()
        val stale = gate.finishResult(stoppedToken, activationSucceeded = true)
        assertFalse(stale.accepted)
        assertEquals(null, stale.speedPercent)
    }

    private val gate = StrokeEngineActivationSpeedGate()
}
