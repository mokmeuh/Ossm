package com.ossm.remote.ui.screens

import com.ossm.remote.remote.RemoteSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCameraSectionTest {
    private val host = "https://remote.example/s/session_01#k=abcdefghijklmnopqrstuvwxyz012345"
    private val camera = "https://remote.example/s/session_01?role=camera#k=CAMERAcameraCAMERAcamera_0001"

    @Test
    fun `without an active session the section stays visible and offers to start one`() {
        assertEquals(RemoteCameraSection.NoSession, remoteCameraSection(RemoteSessionState()))
        assertEquals(
            RemoteCameraSection.NoSession,
            remoteCameraSection(RemoteSessionState(active = false, shareUrl = host, cameraUrl = camera)),
        )
    }

    @Test
    fun `active session without host link yet is starting`() {
        assertEquals(RemoteCameraSection.Starting, remoteCameraSection(RemoteSessionState(active = true)))
    }

    @Test
    fun `active session without camera link asks to regenerate`() {
        assertEquals(RemoteCameraSection.Unavailable, remoteCameraSection(RemoteSessionState(active = true, shareUrl = host)))
    }

    @Test
    fun `active session with camera link offers it without printing it`() {
        val section = remoteCameraSection(RemoteSessionState(active = true, shareUrl = host, cameraUrl = camera))

        assertEquals(RemoteCameraSection.Available(camera), section)
        assertFalse(section.toString().contains("#k="))
    }

    @Test
    fun `an open camera share closes when the session ends or the link changes`() {
        assertTrue(cameraShareStillAllowed(camera, RemoteCameraSection.Available(camera)))
        assertFalse(cameraShareStillAllowed(camera, RemoteCameraSection.Available(camera.replace("0001", "0002"))))
        assertFalse(cameraShareStillAllowed(camera, RemoteCameraSection.Unavailable))
        assertFalse(cameraShareStillAllowed(camera, RemoteCameraSection.NoSession))
        assertFalse(cameraShareStillAllowed(camera, RemoteCameraSection.Starting))
    }
}
