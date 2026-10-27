package com.ossm.remote.remote

import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.util.Collections
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 2.3.31: automatic reconnection of the host WebSocket (same link, STOP first, no motion replay). */
class RemoteSessionClientReconnectTest {
    private val httpClient = mockk<OkHttpClient>(relaxed = true)
    private val safety = mockk<RemoteSessionSafetyAdapter>(relaxed = true)
    private val credentials = mockk<RemoteSessionCredentialStore>(relaxed = true)
    private val call = mockk<Call>(relaxed = true)
    private val sockets = Collections.synchronizedList(mutableListOf<WebSocket>())
    private val listeners = Collections.synchronizedList(mutableListOf<WebSocketListener>())
    private val delaysAsked = Collections.synchronizedList(mutableListOf<Int>())
    private lateinit var client: RemoteSessionClient
    private var renewal: Callback? = null

    @Before
    fun setUp() {
        mockkConstructor(OkHttpClient.Builder::class)
        every { anyConstructed<OkHttpClient.Builder>().pingInterval(any(), any()) } answers { self as OkHttpClient.Builder }
        every { anyConstructed<OkHttpClient.Builder>().build() } returns httpClient
        every { credentials.hasCredential() } returns true
        every { credentials.read() } returns SESSION_URL
        every { httpClient.newCall(any()) } returns call
        every { call.enqueue(any()) } answers { renewal = firstArg() }
        every { httpClient.newWebSocket(any(), any()) } answers {
            val socket = mockk<WebSocket>(relaxed = true)
            sockets += socket
            listeners += secondArg<WebSocketListener>()
            socket
        }
        every { safety.beginSession() } returns PhoneHostMotionLimits(1f, 0f, 1f)
        every { safety.sessionView } returns kotlinx.coroutines.flow.MutableStateFlow(RemoteSessionView(active = true, speedMax = 1f))
        every { safety.hostReadiness() } returns PhoneHostReadiness(connected = true, machineReady = true, postSetupReady = true)
        every { safety.acceptAction(any(), any(), any(), any()) } returns true
        every { credentials.clear() } just Runs
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) client.stop()
        unmockkConstructor(OkHttpClient.Builder::class)
    }

    private fun open(delays: (Int) -> Long? = { attempt -> delaysAsked += attempt; 20L }) {
        client = RemoteSessionClient(safety, credentials, delays)
        client.start()
        renewal!!.onResponse(call, response(204))
        ready(0)
        // Forget the set-up calls (connect() itself stops any previous socket first).
        clearMocks(safety, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true, exclusionRules = false)
    }

    private fun ready(index: Int, expiresIn: Long = 60_000L) {
        listeners[index].onOpen(sockets[index], response(101))
        listeners[index].onMessage(
            sockets[index],
            """{"type":"ready","participantId":"host_$index","role":"host","expiresAt":${System.currentTimeMillis() + expiresIn}}""",
        )
    }

    private fun waitSockets(count: Int) = runBlocking {
        withTimeout(3_000L) { while (sockets.size < count) kotlinx.coroutines.delay(5) }
    }

    @Test
    fun `backoff is 1 2 4 8 then 15 s, at most 20 attempts`() {
        val delays = (1..21).map { RemoteReconnectPolicy.delayMs(it) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L), delays.take(5))
        assertTrue(delays.subList(4, 20).all { it == 15_000L })
        assertNull(delays[20])
        assertNull(RemoteReconnectPolicy.delayMs(0))
    }

    @Test
    fun `heartbeat timeout stops, keeps the envelope and reconnects to the same link`() {
        open()
        listeners[0].onClosing(sockets[0], 4000, "heartbeat_timeout")

        assertTrue(client.state.value.reconnecting)
        assertTrue(client.state.value.active)
        assertFalse(client.state.value.connected)
        verify(exactly = 1) { safety.onTransportLost() }
        verify(exactly = 0) { safety.endSession() }

        waitSockets(2)
        ready(1)
        assertFalse(client.state.value.reconnecting)
        assertTrue(client.state.value.connected)
        // One Store session for the whole reconnection: the envelope is never re-captured.
        verify(exactly = 0) { safety.beginSession() }
        verify(exactly = 0) { safety.endSession() }
        verify(exactly = 2) { httpClient.newWebSocket(match { it.url.toString().endsWith("/api/session/session_rc/ws") }, any()) }
    }

    @Test
    fun `no motion resumes after reconnection and stale frames are ignored`() {
        open()
        listeners[0].onFailure(sockets[0], java.io.IOException("network"), null)
        verify(exactly = 1) { safety.onTransportLost() }
        waitSockets(2)
        // A late frame from the dead socket is ignored.
        listeners[0].onMessage(sockets[0], command(5, "{\"type\":\"set_speed\",\"value\":0.4}"))
        ready(1)
        Thread.sleep(100)
        verify(exactly = 0) { safety.acceptAction(any(), any(), any(), any()) }
        verify(exactly = 0) { safety.acceptAction(any(), any(), any()) }
        assertEquals("Reconnecté; attente d'une nouvelle commande du contrôleur", client.state.value.message)
        // Only a NEW browser command moves again.
        listeners[1].onMessage(sockets[1], command(6, "{\"type\":\"stop\"}"))
        verify(timeout = 2_000, exactly = 1) { safety.acceptAction("controller_01", 6, RemoteControlAction.Stop, any()) }
    }

    @Test
    fun `failed attempts follow the backoff then give up`() {
        open { attempt -> delaysAsked += attempt; if (attempt <= 3) 10L else null }
        listeners[0].onFailure(sockets[0], java.io.IOException("network"), null)
        for (index in 1..3) {
            waitSockets(index + 1)
            listeners[index].onFailure(sockets[index], java.io.IOException("still down"), null)
        }
        runBlocking { withTimeout(2_000L) { client.state.first { !it.active } } }
        assertEquals(listOf(1, 2, 3, 4), delaysAsked.toList())
        assertEquals("Reconnexion impossible; session arrêtée", client.state.value.message)
        assertFalse(client.state.value.reconnecting)
        verify(atLeast = 1) { safety.endSession() }
    }

    @Test
    fun `revocation ends the session without reconnecting`() {
        open()
        listeners[0].onMessage(sockets[0], """{"type":"error","code":"forbidden"}""")
        Thread.sleep(100)
        assertFalse(client.state.value.active)
        assertFalse(client.state.value.reconnecting)
        assertEquals(1, sockets.size)
        assertTrue(delaysAsked.isEmpty())
        verify(atLeast = 1) { safety.endSession() }
    }

    @Test
    fun `revoked close code ends the session without reconnecting`() {
        open()
        listeners[0].onClosed(sockets[0], 1008, "revoked")
        Thread.sleep(100)
        assertFalse(client.state.value.active)
        assertEquals(1, sockets.size)
        assertTrue(delaysAsked.isEmpty())
    }

    @Test
    fun `expired session is not reconnected`() {
        client = RemoteSessionClient(safety, credentials) { attempt -> delaysAsked += attempt; 20L }
        client.start()
        renewal!!.onResponse(call, response(204))
        ready(0, expiresIn = -1_000L)
        assertFalse(client.state.value.active)
        assertTrue(delaysAsked.isEmpty())
    }

    @Test
    fun `stop during the backoff cancels the pending attempt`() {
        open { attempt -> delaysAsked += attempt; 300L }
        listeners[0].onFailure(sockets[0], java.io.IOException("network"), null)
        assertTrue(client.state.value.reconnecting)
        client.stop()
        Thread.sleep(500)
        assertEquals(1, sockets.size)
        assertFalse(client.state.value.active)
        assertFalse(client.state.value.reconnecting)
    }

    @Test
    fun `gone session while reconnecting is recreated`() {
        open()
        listeners[0].onFailure(sockets[0], java.io.IOException("network"), null)
        waitSockets(2)
        listeners[1].onFailure(sockets[1], java.io.IOException("gone"), response(410))
        verify(exactly = 1) { credentials.clear() }
        assertFalse(client.state.value.reconnecting)
        verify(atLeast = 1) { safety.endSession() }
    }

    private fun command(sequence: Long, command: String): String =
        """{"type":"command","seq":$sequence,"command":"${command.replace("\"", "\\\"")}","from":"controller_01"}"""

    private fun response(code: Int): Response = Response.Builder()
        .request(Request.Builder().url("https://remote.example/api/session/session_rc").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("x")
        .body("".toResponseBody())
        .build()

    private companion object {
        const val SESSION_URL = "https://remote.example/s/session_rc#k=abcdefghijklmnopqrstuvwxyz012345"
    }
}
