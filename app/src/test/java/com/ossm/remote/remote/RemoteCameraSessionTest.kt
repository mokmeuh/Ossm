package com.ossm.remote.remote

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.io.File
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Camera invitation (`cameraUrl`, broker v2.0.7+): parsing, persistence, regeneration, secrecy. */
class RemoteCameraSessionTest {
    private val hostKey = "abcdefghijklmnopqrstuvwxyz012345"
    private val cameraKey = "CAMERAcameraCAMERAcamera_-0123456789"
    private val hostUrl = "https://remote.example/s/session_01#k=$hostKey"
    private val cameraUrl = "https://remote.example/s/session_01?role=camera#k=$cameraKey"

    private fun creation(url: String = hostUrl, camera: String? = cameraUrl): String =
        if (camera == null) """{"url":"$url","expiresAt":1}""" else """{"url":"$url","cameraUrl":"$camera","expiresAt":1}"""

    // --- parseCreationResponse ---------------------------------------------------------------

    @Test
    fun `creation response with cameraUrl exposes the camera invitation`() {
        val session = RemoteBrokerSession.parseCreationResponse(creation())

        assertNotNull(session)
        assertEquals(hostUrl, session?.shareUrl)
        assertEquals(cameraUrl, session?.cameraUrl)
        assertEquals(hostKey, session?.secret)
    }

    @Test
    fun `creation response without cameraUrl stays valid with no camera invitation`() {
        val session = RemoteBrokerSession.parseCreationResponse(creation(camera = null))

        assertNotNull(session)
        assertEquals(hostUrl, session?.shareUrl)
        assertNull(session?.cameraUrl)
    }

    @Test
    fun `a camera link that does not match the host session is ignored`() {
        listOf(
            "https://evil.example/s/session_01?role=camera#k=$cameraKey",
            "http://remote.example/s/session_01?role=camera#k=$cameraKey",
            "https://remote.example/s/session_99?role=camera#k=$cameraKey",
            "https://remote.example/s/session_01#k=$cameraKey",
            "https://remote.example/s/session_01?role=controller#k=$cameraKey",
            "https://remote.example/s/session_01?role=camera&x=1#k=$cameraKey",
            "https://remote.example/s/session_01?role=camera#k=short",
            "https://remote.example/s/session_01?role=camera#k=$hostKey",
            "not a url",
        ).forEach { invalid ->
            val session = RemoteBrokerSession.parseCreationResponse(creation(camera = invalid))
            assertNotNull("host link must stay usable for $invalid", session)
            assertNull("camera link must be refused: $invalid", session?.cameraUrl)
        }
        val numeric = RemoteBrokerSession.parseCreationResponse("""{"url":"$hostUrl","cameraUrl":42}""")
        assertNull(numeric?.cameraUrl)
        assertNull(RemoteBrokerSession.parseCreationResponse("""{"cameraUrl":"$cameraUrl"}"""))
    }

    // --- persistence -------------------------------------------------------------------------

    @Test
    fun `camera invitation is persisted with the host link and restored`() {
        val credentials = InMemoryCredentials()
        val lifecycle = RemoteSessionLifecycle(credentials)
        val session = RemoteBrokerSession.parseCreationResponse(creation()) ?: error("valid")

        lifecycle.persist(session)
        val restored = (lifecycle.restore() as StoredRemoteSession.Available).session

        assertEquals(hostUrl, restored.shareUrl)
        assertEquals(cameraUrl, restored.cameraUrl)
        assertEquals("https://remote.example/api/session/session_01", restored.sessionUrl)
    }

    @Test
    fun `sessions stored before 2_3_15 without cameraUrl still restore`() {
        val lifecycle = RemoteSessionLifecycle(InMemoryCredentials(hostUrl))

        val restored = (lifecycle.restore() as StoredRemoteSession.Available).session

        assertEquals(hostUrl, restored.shareUrl)
        assertNull(restored.cameraUrl)
    }

    @Test
    fun `a session without camera keeps the legacy stored format`() {
        val credentials = InMemoryCredentials()
        val session = RemoteBrokerSession.parse(hostUrl) ?: error("valid")

        RemoteSessionLifecycle(credentials).persist(session)

        assertEquals(hostUrl, credentials.value)
    }

    @Test
    fun `corrupted stored JSON is unreadable`() {
        assertTrue(RemoteSessionLifecycle(InMemoryCredentials("{\"url\":42}")).restore() is StoredRemoteSession.Unreadable)
        assertTrue(RemoteSessionLifecycle(InMemoryCredentials("{broken")).restore() is StoredRemoteSession.Unreadable)
    }

    // --- secrecy -----------------------------------------------------------------------------

    @Test
    fun `session and state descriptions never print a capability key`() {
        val session = RemoteBrokerSession.parseCreationResponse(creation()) ?: error("valid")
        val state = RemoteSessionState(active = true, shareUrl = session.shareUrl, cameraUrl = session.cameraUrl)

        listOf(session.toString(), state.toString()).forEach { text ->
            assertFalse(text, text.contains(hostKey))
            assertFalse(text, text.contains(cameraKey))
            assertFalse(text, text.contains("#k="))
        }
    }

    @Test
    fun `no main source logs or sends a capability link to diagnostics`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first(File::isDirectory)
        val sinks = Regex("""\bLog\.[a-z]+\(|println\(|addLog\(|addDiagnostic|DiagnosticsRepository|logDebug\(""")
        val secrets = Regex("""cameraUrl|shareUrl|#k=""")
        val offending = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().mapIndexed { index, line -> "${file.name}:${index + 1}: $line" } }
            .filter { line -> sinks.containsMatchIn(line) && secrets.containsMatchIn(line) }
            .toList()

        assertEquals(emptyList<String>(), offending)
    }

    private class InMemoryCredentials(initial: String? = null) : RemoteSessionCredentialPersistence {
        var value = initial
        override fun save(value: String) { this.value = value }
        override fun read(): String? = value
        override fun hasCredential(): Boolean = value != null
        override fun clear() { value = null }
    }
}

/** Regeneration revokes the old session and forgets its camera link everywhere. */
class RemoteCameraRegenerationTest {
    private val httpClient = mockk<OkHttpClient>(relaxed = true)
    private val safety = mockk<RemoteSessionSafetyAdapter>(relaxed = true)
    private val credentials = mockk<RemoteSessionCredentialStore>(relaxed = true)
    private val oldCameraKey = "OLDcameraOLDcameraOLDcamera_0001"
    private val newCameraKey = "NEWcameraNEWcameraNEWcamera_0002"
    private val oldStored =
        """{"url":"https://remote.example/s/session_01#k=abcdefghijklmnopqrstuvwxyz012345","cameraUrl":"https://remote.example/s/session_01?role=camera#k=$oldCameraKey"}"""
    private val newCameraUrl = "https://remote.example/s/session_02?role=camera#k=$newCameraKey"

    @Before
    fun setUp() {
        mockkConstructor(OkHttpClient.Builder::class)
        every { anyConstructed<OkHttpClient.Builder>().pingInterval(any(), any()) } answers { self as OkHttpClient.Builder }
        every { anyConstructed<OkHttpClient.Builder>().build() } returns httpClient
        every { safety.beginSession() } returns PhoneHostMotionLimits(1f, 0f, 1f)
        every { safety.sessionView } returns kotlinx.coroutines.flow.MutableStateFlow(RemoteSessionView(active = true, speedMax = 1f))
        every { safety.hostReadiness() } returns PhoneHostReadiness(connected = true, machineReady = true, postSetupReady = true)
    }

    @After
    fun tearDown() {
        unmockkConstructor(OkHttpClient.Builder::class)
    }

    @Test
    fun `renewal exposes the stored camera link and regeneration replaces it`() {
        val renewalCall = mockk<Call>(relaxed = true)
        val revokeCall = mockk<Call>(relaxed = true)
        val creationCall = mockk<Call>(relaxed = true)
        val revokeCallback = slot<Callback>()
        val creationCallback = slot<Callback>()
        val saved = mutableListOf<String>()
        every { credentials.read() } returns oldStored
        every { credentials.hasCredential() } returns true
        every { credentials.save(capture(saved)) } answers { }
        every { httpClient.newCall(match { it.method == "POST" && it.url.encodedPath == "/api/session/session_01" }) } returns renewalCall
        every { httpClient.newCall(match { it.method == "DELETE" }) } returns revokeCall
        every { httpClient.newCall(match { it.url.encodedPath == "/api/sessions" }) } returns creationCall
        every { revokeCall.enqueue(capture(revokeCallback)) } answers { }
        every { creationCall.enqueue(capture(creationCallback)) } answers { }
        val client = RemoteSessionClient(safety, credentials)

        client.start()
        assertTrue(client.state.value.cameraUrl!!.endsWith(oldCameraKey))

        client.regenerate()
        assertNull("old camera link is forgotten as soon as regeneration starts", client.state.value.cameraUrl)

        revokeCallback.captured.onResponse(revokeCall, response(204, ""))
        verify(exactly = 1) { credentials.clear() }
        creationCallback.captured.onResponse(
            creationCall,
            response(
                200,
                """{"url":"https://remote.example/s/session_02#k=zyxwvutsrqponmlkjihgfedcba543210","cameraUrl":"$newCameraUrl","expiresAt":1}""",
            ),
        )

        assertEquals(newCameraUrl, client.state.value.cameraUrl)
        assertEquals(1, saved.size)
        assertTrue(saved.single().contains(newCameraKey))
        assertFalse(saved.single().contains(oldCameraKey))
        client.stop()
    }

    private fun response(code: Int, body: String): Response = Response.Builder()
        .request(Request.Builder().url("https://remote.example/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("status")
        .body(body.toResponseBody())
        .build()
}
