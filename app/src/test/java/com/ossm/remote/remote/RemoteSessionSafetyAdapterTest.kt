package com.ossm.remote.remote

import com.ossm.remote.session.ControlSessionStore
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class RemoteSessionSafetyAdapterTest {
    private var now = 1_000L
    private val store = mockk<ControlSessionStore>(relaxed = true)
    private val adapter = RemoteSessionSafetyAdapter(store) { now }

    private fun beginSafeSession() {
        val limits = PhoneHostMotionLimits(0.8f, 0.2f, 0.8f)
        every { store.beginRemoteSession(any(), any(), any()) } returns limits
        every { store.remoteSessionLimits() } returns limits
        every { store.hostReadiness() } returns PhoneHostReadiness(true, true, true)
        every { store.setRemoteStreamPosition(any()) } returns true
        adapter.beginSession(0.8f, 0.2f, 0.8f)
        adapter.updateReadiness(PhoneHostReadiness(true, true, true))
    }

    @Test fun `lease expiry triggers emergency stop`() {
        adapter.grantLease("a", 1_001L); now = 1_001L; adapter.expireIfNeeded()
        verify(exactly = 1) { store.emergencyStop() }
    }
    @Test fun `stale and out of order commands are rejected`() {
        beginSafeSession(); adapter.grantLease("a", 2_000L)
        assertTrue(adapter.accept(RemoteSessionCommand.Motion("a", 2, 30)))
        assertFalse(adapter.accept(RemoteSessionCommand.Motion("a", 2, 40)))
        assertFalse(adapter.accept(RemoteSessionCommand.Motion("a", 1, 40)))
    }
    @Test fun `renewing the same broker controller does not reset its sequence`() {
        beginSafeSession(); adapter.grantLease("a", 2_000L)
        assertTrue(adapter.accept(RemoteSessionCommand.Motion("a", 2, 30)))
        adapter.grantLease("a", 3_000L)
        assertFalse(adapter.accept(RemoteSessionCommand.Motion("a", 2, 40)))
    }
    @Test fun `transport loss prevents replay after new lease`() {
        beginSafeSession(); adapter.grantLease("a", 2_000L); adapter.onTransportLost(); adapter.grantLease("b", 2_000L)
        assertFalse(adapter.accept(RemoteSessionCommand.Motion("a", 1, 20)))
    }
    @Test fun `broker lease loss stops only the active controller`() {
        adapter.grantLease("controller", 2_000L)
        adapter.loseLease("camera")
        verify(exactly = 0) { store.emergencyStop() }
        adapter.loseLease("controller")
        verify(exactly = 1) { store.emergencyStop() }
    }
    @Test fun `motion is gated on every required safe state`() {
        beginSafeSession()
        adapter.grantLease("a", 2_000L)
        adapter.updateReadiness(PhoneHostReadiness(false, false, false))
        assertFalse(adapter.accept(RemoteSessionCommand.Motion("a", 1, 20)))
        adapter.updateReadiness(PhoneHostReadiness(true, true, true))
        assertTrue(adapter.accept(RemoteSessionCommand.Motion("a", 2, 20)))
    }

    @Test fun `safe active session applies ordinary remote controls`() {
        beginSafeSession(); adapter.grantLease("a", 2_000L)
        assertTrue(adapter.acceptAction("a", 1, RemoteControlAction.SetSpeed(0.4f)))
        assertTrue(adapter.acceptAction("a", 2, RemoteControlAction.SetDepth(0.2f, 0.8f)))
        assertTrue(adapter.acceptAction("a", 3, RemoteControlAction.SetSensation(0.6f)))
        assertTrue(adapter.acceptAction("a", 4, RemoteControlAction.SelectPattern("simpleStroke")))
        verify { store.requestRemoteSpeedChange(0.4f); store.requestRemoteDepthRangeChange(0.2f, 0.8f); store.requestRemoteSensationChange(0.6f); store.activateRemotePattern("simpleStroke") }
    }

    @Test fun `remote sensation goes through the clamped remote entry point, never the local one`() {
        beginSafeSession(); adapter.grantLease("a", 2_000L)
        assertTrue(adapter.acceptAction("a", 1, RemoteControlAction.SetSensation(1f)))
        verify(exactly = 1) { store.requestRemoteSensationChange(1f) }
        verify(exactly = 0) { store.setSensationLive(any()) }
        verify(exactly = 0) { store.requestSensationChange(any()) }
    }

    @Test fun `remote EndLiveTouch starts the phone-owned release instead of STOP`() {
        beginSafeSession(); adapter.grantLease("a", 2_000L)

        assertTrue(adapter.acceptAction("a", 1, RemoteControlAction.EndLiveTouch))

        verify(exactly = 1) { store.endLiveTouch() }
        verify(exactly = 0) { store.stopLiveTouch() }
    }

    @Test fun `end of a started session always ends the store session`() {
        beginSafeSession()
        every { store.endRemoteSession() } returns true
        adapter.endSession()
        verify(exactly = 1) { store.endRemoteSession() }
        verify(exactly = 0) { store.stop() }
    }

    @Test fun `end of a started session stops even if the store envelope was already gone`() {
        beginSafeSession()
        every { store.endRemoteSession() } returns false
        adapter.endSession()
        verify(exactly = 1) { store.stop() }
    }

    @Test fun `transport loss, timeout and service destruction stop a started session without lease`() {
        listOf<(RemoteSessionSafetyAdapter) -> Unit>(
            { it.onTransportLost() },
            { it.onSessionTimeout() },
            { it.onServiceDestroyed() },
        ).forEach { end ->
            val localStore = mockk<ControlSessionStore>(relaxed = true)
            every { localStore.beginRemoteSession(any(), any(), any()) } returns PhoneHostMotionLimits(1f, 0f, 1f)
            val localAdapter = RemoteSessionSafetyAdapter(localStore) { now }
            localAdapter.beginSession(1f, 0f, 1f)
            end(localAdapter)
            verify(exactly = 1) { localStore.stop() }
            verify(exactly = 0) { localStore.emergencyStop() }
        }
    }

    @Test fun `transport loss with a controller lease emergency stops`() {
        beginSafeSession(); adapter.grantLease("a", 2_000L)
        adapter.onTransportLost()
        verify(exactly = 1) { store.emergencyStop() }
    }

    @Test fun `ending without any session sends no stop`() {
        adapter.onTransportLost()
        adapter.endSession()
        verify(exactly = 0) { store.emergencyStop() }
        verify(exactly = 0) { store.stop() }
    }

    private fun collectRefusals(): MutableList<RemoteRefusalReason> =
        mutableListOf<RemoteRefusalReason>().also { adapter.refusalListener = it::add }

    @Test fun `refused actions report a short reason for each refusal cause`() {
        val refusals = collectRefusals()
        beginSafeSession()

        // No valid lease yet.
        assertFalse(adapter.acceptAction("ghost", 1, RemoteControlAction.SetSpeed(0.4f)))
        assertEquals(listOf(RemoteRefusalReason.LeaseInvalid), refusals)

        // Stale sequence.
        adapter.grantLease("a", 2_000L)
        assertTrue(adapter.acceptAction("a", 5, RemoteControlAction.SetSpeed(0.4f)))
        assertFalse(adapter.acceptAction("a", 5, RemoteControlAction.SetSpeed(0.5f)))
        assertEquals(RemoteRefusalReason.StaleSequence, refusals.last())

        // Machine not ready (BLE not connected / post-setup not ready).
        adapter.updateReadiness(PhoneHostReadiness(false, false, false))
        assertFalse(adapter.acceptAction("a", 6, RemoteControlAction.SetSpeed(0.5f)))
        assertEquals(RemoteRefusalReason.MachineNotReady, refusals.last())
        assertFalse(adapter.acceptAction("a", 7, RemoteControlAction.Home))
        assertEquals(RemoteRefusalReason.MachineNotReady, refusals.last())

        // Live position refused by the store.
        adapter.updateReadiness(PhoneHostReadiness(true, true, true))
        every { store.setRemoteStreamPosition(any()) } returns false
        assertFalse(adapter.acceptAction("a", 8, RemoteControlAction.SetLivePosition(50)))
        assertEquals(RemoteRefusalReason.LivePositionRejected, refusals.last())
        assertEquals(5, refusals.size)
    }

    @Test fun `session not started is reported and interrupts stay allowed`() {
        val refusals = collectRefusals()
        adapter.grantLease("a", 2_000L)
        adapter.updateReadiness(PhoneHostReadiness(true, true, true))

        assertFalse(adapter.acceptAction("a", 1, RemoteControlAction.SetSpeed(0.4f)))
        assertEquals(listOf(RemoteRefusalReason.SessionNotStarted), refusals)
        assertTrue(adapter.acceptAction("a", 2, RemoteControlAction.Stop))
        assertEquals(1, refusals.size)
    }

    @Test fun `refused motion commands report lease, sequence and readiness reasons`() {
        val refusals = collectRefusals()
        beginSafeSession()

        assertFalse(adapter.accept(RemoteSessionCommand.Motion("ghost", 1, 20)))
        adapter.grantLease("a", 2_000L)
        assertTrue(adapter.accept(RemoteSessionCommand.Motion("a", 2, 20)))
        assertFalse(adapter.accept(RemoteSessionCommand.Motion("a", 2, 30)))
        adapter.updateReadiness(PhoneHostReadiness(false, false, false))
        assertFalse(adapter.accept(RemoteSessionCommand.Motion("a", 3, 30)))

        assertEquals(
            listOf(
                RemoteRefusalReason.LeaseInvalid,
                RemoteRefusalReason.StaleSequence,
                RemoteRefusalReason.MachineNotReady,
            ),
            refusals,
        )
    }

    @Test fun `accepted commands and emergency stop never report a refusal`() {
        val refusals = collectRefusals()
        beginSafeSession()
        adapter.grantLease("a", 2_000L)

        assertTrue(adapter.acceptAction("a", 1, RemoteControlAction.SetSpeed(0.4f)))
        assertTrue(adapter.acceptAction("ignored", 0, RemoteControlAction.EmergencyStop))
        assertTrue(adapter.accept(RemoteSessionCommand.EmergencyStop()))

        assertTrue(refusals.isEmpty())
    }

    @Test fun `refusal reason codes are fixed non sensitive identifiers`() {
        RemoteRefusalReason.values().forEach { reason ->
            assertTrue(reason.code.matches(Regex("remote_[a-z_]+")))
            assertFalse(reason.code.contains("#k"))
        }
        assertEquals(RemoteRefusalReason.values().size, RemoteRefusalReason.values().map { it.code }.toSet().size)
    }

    @Test fun `rejected interrupt does not invalidate the pending-command generation`() {
        adapter.grantLease("a", 2_000L)
        assertTrue(adapter.acceptAction("a", 1, RemoteControlAction.Stop))
        var invalidated = false

        assertFalse(adapter.acceptAction("a", 1, RemoteControlAction.EndLiveTouch) { invalidated = true })
        assertFalse(invalidated)
    }
}
