package com.ossm.remote.ui.screens

import com.ossm.remote.model.BleDevice
import com.ossm.remote.model.BleConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanScreenAutoConnectTest {
    @Test
    fun `unique OSSM address remains stable when the same device RSSI changes`() {
        val initialScan = listOf(BleDevice("OSSM", "AA:BB:CC:DD:EE:FF", -45, isOssm = true))
        val rssiUpdate = listOf(BleDevice("OSSM", "AA:BB:CC:DD:EE:FF", -72, isOssm = true))

        assertEquals("AA:BB:CC:DD:EE:FF", uniqueOssmAddress(initialScan))
        assertEquals(uniqueOssmAddress(initialScan), uniqueOssmAddress(rssiUpdate))
    }

    @Test
    fun `stale scan result cannot trigger auto connect outside an active scan`() {
        val staleResult = listOf(BleDevice("OSSM", "AA:BB:CC:DD:EE:FF", -45, isOssm = true))

        assertNull(scanAutoConnectAddress(staleResult, BleConnectionState.Disconnected))
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            scanAutoConnectAddress(staleResult, BleConnectionState.Scanning),
        )
    }
}
