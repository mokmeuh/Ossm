package com.ossm.remote.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleManagerLiveGenerationTest {
    @Test
    fun `invalidating Live rejects queued generation but keeps unrelated writes accepted`() {
        val gate = LiveWriteGenerationGate()
        val first = gate.current()

        assertTrue(gate.accepts(first))
        assertTrue(gate.accepts(null))

        gate.invalidate()

        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(gate.current()))
        assertTrue(gate.accepts(null))
    }
}
