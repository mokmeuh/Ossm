package com.ossm.remote.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbFallbackPolicyTest {
    @Test
    fun `cable removed lets Bluetooth take over`() {
        assertTrue(UsbFallbackPolicy.allowBle(false, reopening = false, reopenFailed = false, userRequested = false))
        assertTrue(UsbFallbackPolicy.allowBle(false, reopening = true, reopenFailed = false, userRequested = false))
    }

    @Test
    fun `cable present and reopening never allows Bluetooth`() {
        assertFalse(UsbFallbackPolicy.allowBle(true, reopening = true, reopenFailed = false, userRequested = false))
        assertFalse(UsbFallbackPolicy.allowBle(true, reopening = true, reopenFailed = false, userRequested = true))
    }

    @Test
    fun `cable present and healthy or connecting keeps Bluetooth idle`() {
        assertFalse(UsbFallbackPolicy.allowBle(true, reopening = false, reopenFailed = false, userRequested = false))
        assertFalse(UsbFallbackPolicy.allowBle(true, reopening = false, reopenFailed = false, userRequested = true))
    }

    @Test
    fun `failed reopen allows Bluetooth only on user request`() {
        assertFalse(UsbFallbackPolicy.allowBle(true, reopening = false, reopenFailed = true, userRequested = false))
        assertTrue(UsbFallbackPolicy.allowBle(true, reopening = false, reopenFailed = true, userRequested = true))
    }

    @Test
    fun `link state follows cable, readiness and recovery`() {
        assertEquals(UsbLinkState.Ready, UsbLinkPolicy.linkState(true, ready = true, reopening = false, failed = false))
        assertEquals(UsbLinkState.Absent, UsbLinkPolicy.linkState(false, ready = false, reopening = false, failed = false))
        assertEquals(UsbLinkState.Recovering, UsbLinkPolicy.linkState(true, ready = false, reopening = true, failed = false))
        assertEquals(UsbLinkState.Failed, UsbLinkPolicy.linkState(true, ready = false, reopening = false, failed = true))
        assertEquals(UsbLinkState.Connecting, UsbLinkPolicy.linkState(true, ready = false, reopening = false, failed = false))
    }
}
