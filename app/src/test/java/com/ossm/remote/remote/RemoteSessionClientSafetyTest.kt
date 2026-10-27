package com.ossm.remote.remote

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RemoteSessionClientSafetyTest {
    private val httpClient = mockk<OkHttpClient>(relaxed = true)
    private val safety = mockk<RemoteSessionSafetyAdapter>(relaxed = true)
    private val credentials = mockk<RemoteSessionCredentialStore>(relaxed = true)
    private val call = mockk<Call>(relaxed = true)
    private val webSocket = mockk<WebSocket>(relaxed = true)
    private val callback = slot<Callback>()
    private val webSocketListener = slot<WebSocketListener>()
    private lateinit var client: RemoteSessionClient

    @Before
    fun setUp() {
        mockkConstructor(OkHttpClient.Builder::class)
        every { anyConstructed<OkHttpClient.Builder>().pingInterval(any(), any()) } answers { self as OkHttpClient.Builder }
        every { anyConstructed<OkHttpClient.Builder>().build() } returns httpClient
        every { credentials.hasCredential() } returns true
        every { credentials.read() } returns sessionUrl("session_05")
        every { httpClient.newCall(any()) } returns call
        every { call.enqueue(capture(callback)) } just Runs
        every { httpClient.newWebSocket(any(), capture(webSocketListener)) } returns webSocket
        every { safety.beginSession() } returns PhoneHostMotionLimits(1f, 0f, 1f)
        every { safety.sessionView } returns kotlinx.coroutines.flow.MutableStateFlow(RemoteSessionView(active = true, speedMax = 1f))
        every { safety.hostReadiness() } returns PhoneHostReadiness(
            connected = true,
            machineReady = true,
            postSetupReady = true,
        )
        every { safety.acceptAction(any(), any(), any(), any()) } returns true
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) client.stop()
        unmockkConstructor(OkHttpClient.Builder::class)
    }

    @Test
    fun `remote stop reaches the phone safety adapter`() {
        openSession("session_05")

        webSocketListener.captured.onMessage(
            webSocket,
            commandFrame(1, "controller_01", "{\"type\":\"stop\"}"),
        )

        verify(timeout = 2_000, exactly = 1) {
            safety.acceptAction("controller_01", 1, RemoteControlAction.Stop, any())
        }
    }

    @Test
    fun `remote end live touch reaches the phone safety adapter`() {
        openSession("session_06")

        webSocketListener.captured.onMessage(
            webSocket,
            commandFrame(1, "controller_01", "{\"type\":\"end_live_touch\"}"),
        )

        verify(timeout = 2_000, exactly = 1) {
            safety.acceptAction("controller_01", 1, RemoteControlAction.EndLiveTouch, any())
        }
    }

    @Test
    fun `ordinary motion is dispatched after ordinary phone readiness`() {
        openSession("session_07")

        webSocketListener.captured.onMessage(
            webSocket,
            commandFrame(1, "controller_01", "{\"type\":\"set_speed\",\"value\":0.4}"),
        )

        verify(timeout = 2_000, exactly = 1) {
            safety.acceptAction("controller_01", 1, RemoteControlAction.SetSpeed(0.4f))
        }
    }

    @Test
    fun `presence updates replace stale waiting controller status`() {
        openSession("session_08")

        webSocketListener.captured.onMessage(
            webSocket,
            """{"type":"presence","hostOnline":true,"controllerOnline":true,"cameras":0}""",
        )
        assertEquals("Hôte authentifié; contrôleur connecté", client.state.value.message)

        webSocketListener.captured.onMessage(
            webSocket,
            """{"type":"presence","hostOnline":true,"controllerOnline":false,"cameras":0}""",
        )
        assertEquals("Hôte authentifié; attente du contrôleur", client.state.value.message)
    }

    @Test
    fun `stop tears down the phone session and rejects a late websocket callback`() {
        openSession("session_09")
        client.stop()

        webSocketListener.captured.onMessage(
            webSocket,
            commandFrame(2, "controller_01", "{\"type\":\"set_speed\",\"value\":0.4}"),
        )

        verify(timeout = 2_000, atLeast = 1) { safety.endSession() }
        verify(exactly = 0) { safety.acceptAction(any(), any(), any(), any()) }
    }

    @Test
    fun `transport loss clears the socket and rejects a late callback from the old session`() {
        openSession("session_09_lost")
        webSocketListener.captured.onMessage(webSocket, """{"type":"error","code":"expired"}""")

        assertEquals(false, client.state.value.active)
        webSocketListener.captured.onMessage(
            webSocket,
            commandFrame(2, "controller_01", "{\"type\":\"set_speed\",\"value\":0.4}"),
        )

        verify(atLeast = 1) { safety.endSession() }
        verify(exactly = 0) { safety.acceptAction(any(), any(), any(), any()) }
    }

    @Test
    fun `local broker heartbeat expires the session at the ready deadline`() = runBlocking {
        openSession("session_expiry", expiryMillis = 700L)

        withTimeout(2_000L) { client.state.first { !it.active } }

        assertEquals("Session expirée", client.state.value.message)
    }

    @Test
    fun `service teardown closes the socket and tears down safety`() {
        openSession("session_10")
        client.onServiceDestroyed()

        verify(timeout = 2_000, atLeast = 1) { webSocket.close(1000, "user_stop") }
        verify(exactly = 1) { safety.onServiceDestroyed() }
        verify(atLeast = 1) { safety.endSession() }
        assertEquals(false, client.state.value.active)
    }

    private fun openSession(id: String, expiryMillis: Long = 60_000L) {
        every { credentials.read() } returns sessionUrl(id)
        client = RemoteSessionClient(safety, credentials)
        client.start()
        callback.captured.onResponse(call, successfulRenewalResponse(id))
        webSocketListener.captured.onOpen(webSocket, successfulRenewalResponse(id))
        webSocketListener.captured.onMessage(
            webSocket,
            """{"type":"ready","participantId":"host_01","role":"host","expiresAt":${System.currentTimeMillis() + expiryMillis}}""",
        )
    }

    private fun commandFrame(sequence: Long, controllerId: String, command: String): String =
        """{"type":"command","seq":$sequence,"command":${jsonString(command)},"from":"$controllerId"}"""

    private fun jsonString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun sessionUrl(id: String): String =
        "https://remote.example/s/$id#k=abcdefghijklmnopqrstuvwxyz012345"

    private fun successfulRenewalResponse(id: String): Response = Response.Builder()
        .request(Request.Builder().url("https://remote.example/api/session/$id").build())
        .protocol(Protocol.HTTP_1_1)
        .code(204)
        .message("No Content")
        .body("".toResponseBody())
        .build()
}
