package com.ossm.remote.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandTransportRouterTest {

    @Test
    fun `ready USB is preferred over BLE`() {
        assertEquals(
            CommandTransport.USB,
            chooseCommandTransport(usbReady = true, bleReady = true),
        )
    }

    @Test
    fun `BLE remains the fallback until USB handshake completes`() {
        assertEquals(
            CommandTransport.BLE,
            chooseCommandTransport(usbReady = false, bleReady = true),
        )
    }

    @Test
    fun `no transport is selected while both links are unavailable`() {
        assertEquals(
            CommandTransport.NONE,
            chooseCommandTransport(usbReady = false, bleReady = false),
        )
    }
}
