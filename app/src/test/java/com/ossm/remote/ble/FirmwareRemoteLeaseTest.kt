package com.ossm.remote.ble

import com.google.gson.JsonParser
import com.ossm.remote.model.BleConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareRemoteLeaseTest {
    @Test
    fun `BLE lease is claimable only from a homed local-safe machine`() {
        val readiness = parseFirmwareRemoteLeaseReadiness(
            JsonParser.parseString(
                """{"homed":true,"remoteControlSafe":true,"watchdogMs":250,"transport":"ble-local"}""",
            ).asJsonObject,
            BleConnectionState.Connected("OSSM", "AA:BB"),
        )

        assertTrue(readiness.canClaim)
        assertFalse(readiness.ownsLease)
    }

    @Test
    fun `lease heartbeat stays below the firmware watchdog`() {
        assertEquals(100L, firmwareLeaseHeartbeatIntervalMillis(250L))
        assertTrue(firmwareLeaseHeartbeatIntervalMillis(250L) < 250L)
    }

    @Test
    fun `USB state cannot claim a BLE remote lease`() {
        val readiness = parseFirmwareRemoteLeaseReadiness(
            JsonParser.parseString(
                """{"homed":true,"remoteControlSafe":true,"watchdogMs":250,"transport":"ble-local"}""",
            ).asJsonObject,
            BleConnectionState.Connected("OSSM", "USB-C"),
        )

        assertFalse(readiness.canClaim)
    }

    @Test
    fun `only an auth-required claim acknowledgement maps to the fixed diagnostic code`() {
        assertEquals(
            FirmwareRemoteLeaseDiagnostic.RemoteAuthRequired,
            firmwareRemoteLeaseDiagnosticFor("remote:claim", "fail:remote-auth-required"),
        )
        assertEquals(
            null,
            firmwareRemoteLeaseDiagnosticFor("remote:heartbeat", "fail:remote-auth-required"),
        )
        assertEquals(
            null,
            firmwareRemoteLeaseDiagnosticFor("remote:claim", "fail:remote-busy"),
        )
        assertEquals(null, firmwareRemoteLeaseDiagnosticFor("remote:claim", null))
    }
}
