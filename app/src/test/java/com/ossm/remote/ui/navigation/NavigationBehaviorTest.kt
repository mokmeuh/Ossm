package com.ossm.remote.ui.navigation

import com.ossm.remote.model.BleConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationBehaviorTest {

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
}
