package com.ossm.remote.ui.navigation

import com.ossm.remote.model.BleConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationBehaviorTest {

    @Test
    fun `top-level navigation exposes only visible screens`() {
        assertEquals(
            listOf("scan", "control", "profiles", "diagnostics"),
            BottomNavScreens.map { it.route }
        )
    }

    @Test
    fun `unexpected disconnect from a live page returns to scan`() {
        assertTrue(
            shouldReturnToScanAfterDisconnect(
                previous = BleConnectionState.Connected("OSSM", "AA:BB"),
                current = BleConnectionState.Disconnected
            )
        )
    }

    @Test
    fun `first disconnected state does not force scan navigation`() {
        assertFalse(
            shouldReturnToScanAfterDisconnect(
                previous = null,
                current = BleConnectionState.Disconnected
            )
        )
    }

    @Test
    fun `successful connection from scan opens control`() {
        assertTrue(
            shouldOpenControlAfterConnect(
                previous = BleConnectionState.Connecting("AA:BB"),
                current = BleConnectionState.Connected("OSSM", "AA:BB"),
                currentRoute = Screen.Scan.route
            )
        )
    }

    @Test
    fun `successful connection does not steal navigation outside scan`() {
        assertFalse(
            shouldOpenControlAfterConnect(
                previous = BleConnectionState.Connecting("AA:BB"),
                current = BleConnectionState.Connected("OSSM", "AA:BB"),
                currentRoute = Screen.Profiles.route
            )
        )
    }
}
