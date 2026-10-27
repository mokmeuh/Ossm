package com.ossm.remote.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSharePolicyTest {
    private val broker = "https://ossm-remote.example.dev"
    private val video = CameraSharePolicy.RESOURCE_VIDEO_CAPTURE
    private val audio = CameraSharePolicy.RESOURCE_AUDIO_CAPTURE

    @Test
    fun `resource names match the Android WebView constants`() {
        assertTrue(video == "android.webkit.resource.VIDEO_CAPTURE")
        assertTrue(audio == "android.webkit.resource.AUDIO_CAPTURE")
    }

    @Test
    fun `broker origin gets only video and audio capture once runtime permissions are granted`() {
        val granted = CameraSharePolicy.grantableResources(
            origin = "https://ossm-remote.example.dev/",
            requested = arrayOf(video, audio, "android.webkit.resource.PROTECTED_MEDIA_ID", "android.webkit.resource.MIDI_SYSEX"),
            brokerBaseUrl = broker,
            cameraGranted = true,
            microphoneGranted = true,
        )

        assertArrayEquals(arrayOf(video, audio), granted)
    }

    @Test
    fun `nothing is granted before the matching runtime permission`() {
        val requested = arrayOf(video, audio)

        assertArrayEquals(emptyArray<String>(), CameraSharePolicy.grantableResources("https://ossm-remote.example.dev", requested, broker, false, false))
        assertArrayEquals(arrayOf(video), CameraSharePolicy.grantableResources("https://ossm-remote.example.dev", requested, broker, true, false))
        assertArrayEquals(arrayOf(audio), CameraSharePolicy.grantableResources("https://ossm-remote.example.dev", requested, broker, false, true))
    }

    @Test
    fun `any other origin is refused`() {
        listOf(
            "https://evil.example",
            "http://ossm-remote.example.dev",
            "https://ossm-remote.example.dev:8443",
            "https://sub.ossm-remote.example.dev",
            "file:///sdcard/x.html",
            "null",
            "",
            null,
        ).forEach { origin ->
            assertArrayEquals(
                "origin $origin",
                emptyArray<String>(),
                CameraSharePolicy.grantableResources(origin, arrayOf(video, audio), broker, true, true),
            )
        }
    }

    @Test
    fun `a non https broker never grants anything`() {
        assertArrayEquals(
            emptyArray<String>(),
            CameraSharePolicy.grantableResources("http://localhost:8787", arrayOf(video), "http://localhost:8787", true, true),
        )
    }

    @Test
    fun `navigation outside the https broker origin is blocked`() {
        assertFalse(CameraSharePolicy.shouldBlockNavigation("https://ossm-remote.example.dev/s/session_01?role=camera#k=x", broker))
        assertTrue(CameraSharePolicy.shouldBlockNavigation("https://evil.example/", broker))
        assertTrue(CameraSharePolicy.shouldBlockNavigation("http://ossm-remote.example.dev/", broker))
        assertTrue(CameraSharePolicy.shouldBlockNavigation("intent://scan/#Intent;scheme=zxing;end", broker))
        assertTrue(CameraSharePolicy.shouldBlockNavigation("javascript:alert(1)", broker))
        assertTrue(CameraSharePolicy.shouldBlockNavigation(null, broker))
    }

    @Test
    fun `only the broker camera page is loadable`() {
        assertTrue(CameraSharePolicy.isLoadableCameraUrl("https://ossm-remote.example.dev/s/session_01?role=camera#k=abcdefghijklmnopqrstuvwxyz", broker))
        assertFalse(CameraSharePolicy.isLoadableCameraUrl("https://ossm-remote.example.dev/s/session_01#k=abcdefghijklmnopqrstuvwxyz", broker))
        assertFalse(CameraSharePolicy.isLoadableCameraUrl("https://other.example/s/session_01?role=camera#k=abcdefghijklmnopqrstuvwxyz", broker))
        assertFalse(CameraSharePolicy.isLoadableCameraUrl("https://ossm-remote.example.dev/s/session_01?role=camera", broker))
    }
}
