package com.ossm.remote.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcCheckGateTest {
    @Test
    fun `no check without a saved address`() {
        val gate = PcCheckGate()
        assertFalse(gate.tryBegin(""))
        assertFalse(gate.tryBegin("   "))
        assertTrue(gate.tryBegin("http://100.65.88.40:8091/"))
    }

    @Test
    fun `a second check cannot start while one is running`() {
        val gate = PcCheckGate()
        assertTrue(gate.tryBegin("http://10.0.0.1:8091/"))
        assertFalse(gate.tryBegin("http://10.0.0.1:8091/"))
        gate.end()
        assertTrue(gate.tryBegin("http://10.0.0.1:8091/"))
    }

    @Test
    fun `a blank address never takes the slot`() {
        val gate = PcCheckGate()
        assertFalse(gate.tryBegin(""))
        assertTrue(gate.tryBegin("http://10.0.0.1:8091/"))
    }

    @Test
    fun `a version already refused is not downloaded again, a newer one is`() {
        assertFalse(shouldDownloadPcUpdate(194, 193, 194))
        assertTrue(shouldDownloadPcUpdate(195, 193, 194))
    }
}
