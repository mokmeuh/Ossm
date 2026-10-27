package com.ossm.remote.remote

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RemoteSessionClientLifecycleRaceTest {
    private val httpClient = mockk<OkHttpClient>(relaxed = true)
    private val safety = mockk<RemoteSessionSafetyAdapter>(relaxed = true)
    private val credentials = mockk<RemoteSessionCredentialStore>(relaxed = true)

    @Before
    fun setUp() {
        mockkConstructor(OkHttpClient.Builder::class)
        every { anyConstructed<OkHttpClient.Builder>().pingInterval(any(), any()) } answers { self as OkHttpClient.Builder }
        every { anyConstructed<OkHttpClient.Builder>().build() } returns httpClient
        every { safety.beginSession() } returns PhoneHostMotionLimits(1f, 0f, 1f)
        every { safety.sessionView } returns kotlinx.coroutines.flow.MutableStateFlow(RemoteSessionView(active = true, speedMax = 1f))
        every { safety.hostReadiness() } returns PhoneHostReadiness(
            connected = true,
            machineReady = true,
            postSetupReady = true,
        )
    }

    @After
    fun tearDown() {
        unmockkConstructor(OkHttpClient.Builder::class)
    }

    @Test
    fun `stop invalidates a pending create response`() {
        val callback = slot<Callback>()
        val call = mockk<Call>()
        every { credentials.read() } returns null
        every { credentials.hasCredential() } returns false
        every { httpClient.newCall(any()) } returns call
        every { call.cancel() } just Runs
        every { call.enqueue(capture(callback)) } just Runs
        val client = RemoteSessionClient(safety, credentials)

        client.start()
        client.stop()
        callback.captured.onResponse(call, successfulCreationResponse())

        verify(exactly = 1) { call.cancel() }
        verify(exactly = 0) { credentials.save(any()) }
        verify(exactly = 0) { httpClient.newWebSocket(any(), any()) }
    }

    @Test
    fun `regenerate invalidates a pending renewal response`() {
        val renewalCallback = slot<Callback>()
        val renewalCall = mockk<Call>()
        val revokeCall = mockk<Call>(relaxed = true)
        val storedUrl = "https://remote.example/s/session_01#k=abcdefghijklmnopqrstuvwxyz012345"
        every { credentials.read() } returns storedUrl
        every { credentials.hasCredential() } returns true
        every { httpClient.newCall(match { it.method == "POST" }) } returns renewalCall
        every { renewalCall.cancel() } just Runs
        every { renewalCall.enqueue(capture(renewalCallback)) } just Runs
        every { httpClient.newCall(match { it.method == "DELETE" }) } returns revokeCall
        val client = RemoteSessionClient(safety, credentials)

        client.start()
        client.regenerate()
        renewalCallback.captured.onResponse(renewalCall, successfulEmptyResponse())

        verify(exactly = 1) { renewalCall.cancel() }
        verify(exactly = 0) { httpClient.newWebSocket(any(), any()) }
    }

    @Test
    fun `renewal answered 404 or 410 clears the vanished session and creates a new one`() {
        listOf(404, 410).forEach { goneCode ->
            val renewalCall = mockk<Call>(relaxed = true)
            val creationCall = mockk<Call>(relaxed = true)
            val renewalCallback = slot<Callback>()
            val creationCallback = slot<Callback>()
            val storedUrl = "https://remote.example/s/session_01#k=abcdefghijklmnopqrstuvwxyz012345"
            val gonePersistence = mockk<RemoteSessionCredentialStore>(relaxed = true)
            every { gonePersistence.read() } returns storedUrl
            every { gonePersistence.hasCredential() } returns true
            every { httpClient.newCall(match { it.url.encodedPath == "/api/session/session_01" }) } returns renewalCall
            every { httpClient.newCall(match { it.url.encodedPath == "/api/sessions" }) } returns creationCall
            every { renewalCall.enqueue(capture(renewalCallback)) } just Runs
            every { creationCall.enqueue(capture(creationCallback)) } just Runs
            val client = RemoteSessionClient(safety, gonePersistence)

            client.start()
            renewalCallback.captured.onResponse(renewalCall, responseWithCode(goneCode))

            verify(exactly = 1) { gonePersistence.clear() }
            verify(exactly = 1) { creationCall.enqueue(any()) }
            verify(exactly = 0) { httpClient.newWebSocket(any(), any()) }
            client.stop()
        }
    }

    @Test
    fun `renewal answered with another failure keeps the stored session and does not recreate`() {
        val renewalCall = mockk<Call>(relaxed = true)
        val renewalCallback = slot<Callback>()
        val storedUrl = "https://remote.example/s/session_01#k=abcdefghijklmnopqrstuvwxyz012345"
        every { credentials.read() } returns storedUrl
        every { credentials.hasCredential() } returns true
        every { httpClient.newCall(any()) } returns renewalCall
        every { renewalCall.enqueue(capture(renewalCallback)) } just Runs
        val client = RemoteSessionClient(safety, credentials)

        client.start()
        renewalCallback.captured.onResponse(renewalCall, responseWithCode(500))

        verify(exactly = 0) { credentials.clear() }
        verify(exactly = 1) { httpClient.newCall(any()) }
        client.stop()
    }

    @Test
    fun `every refusal reason is allowlisted by the broker and sent as a strict diagnostic`() {
        assertEquals(
            setOf(
                "remote_lease_invalid",
                "remote_session_not_started",
                "remote_machine_not_ready",
                "remote_sequence_stale",
                "remote_live_position_rejected",
            ),
            BROKER_ACCEPTED_REFUSAL_CODES,
        )
        RemoteRefusalReason.values().forEach { reason ->
            assertEquals(
                """{"type":"diagnostic","severity":"warning","code":"${reason.code}"}""",
                refusalDiagnosticPayload(reason),
            )
            assertNull(refusalDiagnosticPayload(reason, emptySet()))
        }
        assertEquals(
            """{"type":"diagnostic","severity":"warning","code":"remote_lease_invalid"}""",
            refusalDiagnosticPayload(RemoteRefusalReason.LeaseInvalid, setOf("remote_lease_invalid")),
        )
    }

    @Test
    fun `client records the last refusal code without any payload or secret`() {
        val client = RemoteSessionClient(safety, credentials)

        client.onCommandRefused(RemoteRefusalReason.MachineNotReady)

        assertEquals("remote_machine_not_ready", client.state.value.lastRefusal)
        assertFalse(client.state.value.toString().contains("#k="))
    }

    private fun responseWithCode(code: Int): Response = Response.Builder()
        .request(responseRequest())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("status")
        .body("".toResponseBody())
        .build()

    private fun successfulCreationResponse(): Response = Response.Builder()
        .request(responseRequest())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("""{"url":"https://remote.example/s/session_02#k=abcdefghijklmnopqrstuvwxyz012345"}""".toResponseBody())
        .build()

    private fun successfulEmptyResponse(): Response = Response.Builder()
        .request(responseRequest())
        .protocol(Protocol.HTTP_1_1)
        .code(204)
        .message("No Content")
        .body("".toResponseBody())
        .build()

    private fun responseRequest(): Request = Request.Builder().url("https://remote.example/").build()
}
