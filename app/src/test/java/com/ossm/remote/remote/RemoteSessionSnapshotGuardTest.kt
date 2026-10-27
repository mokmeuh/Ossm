package com.ossm.remote.remote

import com.ossm.remote.session.ControlSessionStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteSessionSnapshotGuardTest {
    @Test
    fun `remote controls are rejected when the host snapshot is absent`() {
        val store = mockk<ControlSessionStore>(relaxed = true)
        every { store.beginRemoteSession(any(), any(), any()) } returns PhoneHostMotionLimits(0.8f, 0.2f, 0.8f)
        every { store.remoteSessionLimits() } returns null
        every { store.hostReadiness() } returns PhoneHostReadiness(true, true, true)
        val adapter = RemoteSessionSafetyAdapter(store) { 1_000L }
        adapter.beginSession(0.8f, 0.2f, 0.8f)
        adapter.updateReadiness(PhoneHostReadiness(true, true, true))
        adapter.grantLease("a", 2_000L)

        assertFalse(adapter.acceptAction("a", 1, RemoteControlAction.SetSpeed(0.4f)))
    }
}
