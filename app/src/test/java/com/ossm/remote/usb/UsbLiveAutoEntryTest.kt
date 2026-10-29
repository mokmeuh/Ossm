package com.ossm.remote.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbLiveAutoEntryTest {
    @Test
    fun `no attach means no auto entry`() {
        val entry = UsbLiveAutoEntry()
        assertFalse(entry.tryConsume(0, linkReady = true, machineReady = true))
    }

    @Test
    fun `waits for link and machine then fires once`() {
        val entry = UsbLiveAutoEntry()
        assertTrue(entry.onAttach(0))
        assertFalse(entry.tryConsume(10, linkReady = false, machineReady = false))
        assertFalse(entry.tryConsume(20, linkReady = true, machineReady = false))
        assertTrue(entry.tryConsume(30, linkReady = true, machineReady = true))
        assertFalse(entry.tryConsume(40, linkReady = true, machineReady = true))
    }

    @Test
    fun `intent and broadcast for the same plug arm only once`() {
        val entry = UsbLiveAutoEntry()
        assertTrue(entry.onAttach(0))
        assertTrue(entry.tryConsume(5, true, true))
        assertFalse(entry.onAttach(6))
        assertFalse(entry.tryConsume(7, true, true))
    }

    @Test
    fun `a new plug after detach arms again`() {
        val entry = UsbLiveAutoEntry()
        entry.onAttach(0)
        entry.tryConsume(1, true, true)
        entry.onDetach()
        assertTrue(entry.onAttach(100))
        assertTrue(entry.tryConsume(101, true, true))
    }

    @Test
    fun `detach before ready cancels the pending entry`() {
        val entry = UsbLiveAutoEntry()
        entry.onAttach(0)
        entry.onDetach()
        assertFalse(entry.tryConsume(1, true, true))
    }

    @Test
    fun `stale arming expires`() {
        val entry = UsbLiveAutoEntry(ttlMs = 1_000)
        entry.onAttach(0)
        assertFalse(entry.tryConsume(1_001, true, true))
    }

    @Test
    fun `permission accepted late still fires within the window`() {
        val entry = UsbLiveAutoEntry()
        entry.onAttach(0)
        assertFalse(entry.tryConsume(30_000, linkReady = false, machineReady = false))
        assertTrue(entry.tryConsume(60_000, linkReady = true, machineReady = true))
    }
}
