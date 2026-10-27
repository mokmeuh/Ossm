package com.ossm.remote.remote

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
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

class RemoteSessionClientReadinessTest {
    private val httpClient = mockk<OkHttpClient>(relaxed = true)
    private val safety = mockk<RemoteSessionSafetyAdapter>(relaxed = true)
    private val credentials = mockk<RemoteSessionCredentialStore>(relaxed = true)
    private val call = mockk<Call>(relaxed = true)
    private val webSocket = mockk<WebSocket>(relaxed = true)
    private val callback = slot<Callback>()
    private val webSocketListener = slot<WebSocketListener>()

    @Before
    fun setUp() {
        mockkConstructor(OkHttpClient.Builder::class)
        every { anyConstructed<OkHttpClient.Builder>().pingInterval(any(), any()) } answers { self as OkHttpClient.Builder }
        every { anyConstructed<OkHttpClient.Builder>().build() } returns httpClient
        every { credentials.read() } returns "https://remote.example/s/session_11#k=abcdefghijklmnopqrstuvwxyz012345"
        every { credentials.hasCredential() } returns true
        every { httpClient.newCall(any()) } returns call
        every { call.enqueue(capture(callback)) } just Runs
        every { httpClient.newWebSocket(any(), capture(webSocketListener)) } returns webSocket
        every { safety.beginSession() } returns PhoneHostMotionLimits(0.8f, 0.2f, 0.8f)
        every { safety.sessionView } returns kotlinx.coroutines.flow.MutableStateFlow(RemoteSessionView(active = true, speedMax = 1f))
        every { safety.hostReadiness() } returns PhoneHostReadiness(
            connected = true,
            machineReady = true,
            postSetupReady = true,
        )
    }

    @After
    fun tearDown() = unmockkConstructor(OkHttpClient.Builder::class)

    @Test
    fun `ordinary phone connection and post setup readiness are reflected in session state`() {
        val client = RemoteSessionClient(safety, credentials)
        client.start()
        callback.captured.onResponse(call, successfulRenewalResponse())
        webSocketListener.captured.onOpen(webSocket, successfulRenewalResponse())
        webSocketListener.captured.onMessage(
            webSocket,
            """{"type":"ready","participantId":"host_01","role":"host","expiresAt":${System.currentTimeMillis() + 60_000L}}""",
        )

        assertEquals(
            PhoneHostReadiness(connected = true, machineReady = true, postSetupReady = true),
            client.state.value.readiness,
        )
        client.stop()
    }

    private fun successfulRenewalResponse(): Response = Response.Builder()
        .request(Request.Builder().url("https://remote.example/api/session/session_11").build())
        .protocol(Protocol.HTTP_1_1)
        .code(204)
        .message("No Content")
        .body("".toResponseBody())
        .build()
}
