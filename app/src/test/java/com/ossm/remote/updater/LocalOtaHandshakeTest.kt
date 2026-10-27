package com.ossm.remote.updater

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalOtaHandshakeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val random = SecureRandom()
    private val http = OkHttpClient()
    private val sockets = CopyOnWriteArrayList<ServerSocket>()
    private val own = LocalOtaOwnVersion(PACKAGE, 180, "2.3.32", "SM-S938W")

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
    }

    // ------------------------------------------------------------ crypto

    @Test
    fun `ECDH gives the same two-digit number on both phones`() {
        val sender = LocalOtaCrypto.newKeyPair(random)
        val receiver = LocalOtaCrypto.newKeyPair(random)
        val pkS = LocalOtaCrypto.encodePublicKey(sender.public)
        val pkR = LocalOtaCrypto.encodePublicKey(receiver.public)
        val nS = LocalOtaCrypto.nonce(random)
        val nR = LocalOtaCrypto.nonce(random)

        val senderSide = LocalOtaTranscript(LocalOtaCrypto.sharedSecret(sender, pkR)!!, pkS, pkR, nS, nR)
        val receiverSide = LocalOtaTranscript(LocalOtaCrypto.sharedSecret(receiver, pkS)!!, pkS, pkR, nS, nR)
        val number = LocalOtaCrypto.comparisonNumber(senderSide)

        assertEquals(number, LocalOtaCrypto.comparisonNumber(receiverSide))
        assertTrue(number.matches(Regex("\\d{2}")))
        assertArrayEquals(LocalOtaCrypto.macKey(senderSide), LocalOtaCrypto.macKey(receiverSide))
        assertTrue(LocalOtaCrypto.commitmentMatches(LocalOtaCrypto.commitment(pkS, nS), pkS, nS))
    }

    @Test
    fun `a substituted key changes the number (checked over many attackers)`() {
        val sender = LocalOtaCrypto.newKeyPair(random)
        val receiver = LocalOtaCrypto.newKeyPair(random)
        val pkS = LocalOtaCrypto.encodePublicKey(sender.public)
        val pkR = LocalOtaCrypto.encodePublicKey(receiver.public)
        val nS = LocalOtaCrypto.nonce(random)
        val nR = LocalOtaCrypto.nonce(random)
        val honest = LocalOtaCrypto.comparisonNumber(
            LocalOtaTranscript(LocalOtaCrypto.sharedSecret(receiver, pkS)!!, pkS, pkR, nS, nR),
        )
        // The 2-digit number is a 1/100 check per attempt: count collisions instead of asserting each.
        val collisions = (1..50).count {
            val attacker = LocalOtaCrypto.newKeyPair(random)
            val pkM = LocalOtaCrypto.encodePublicKey(attacker.public)
            val senderSees = LocalOtaTranscript(LocalOtaCrypto.sharedSecret(sender, pkM)!!, pkS, pkM, nS, nR)
            LocalOtaCrypto.comparisonNumber(senderSees) == honest
        }
        assertTrue("collisions=$collisions", collisions <= 5)
        val altered = pkS.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertTrue(!LocalOtaCrypto.commitmentMatches(LocalOtaCrypto.commitment(pkS, nS), altered, nS))
        assertNull(LocalOtaCrypto.decodePublicKey(byteArrayOf(1, 2, 3)))
    }

    // ------------------------------------------------------------ QR

    @Test
    fun `QR URI round trips and is parsed in order`() {
        val key = LocalOtaCrypto.encodePublicKey(LocalOtaCrypto.newKeyPair(random).public)
        val payload = LocalOtaQrPayload(listOf("10.0.0.232", "100.101.102.103"), 47474, key, 180, "moto g")
        val uri = buildLocalOtaQrUri(payload)

        assertTrue(uri.startsWith("ossm-ota://pair?h=10.0.0.232,100.101.102.103&p=47474&k="))
        assertEquals(payload, parseLocalOtaQrUri(uri))
        assertEquals(
            listOf(LocalOtaTarget("10.0.0.232", 47474), LocalOtaTarget("100.101.102.103", 47474)),
            parseLocalOtaQrUri(uri)!!.targets,
        )
    }

    @Test
    fun `QR parser rejects foreign or malformed codes`() {
        val key = LocalOtaCrypto.b64(LocalOtaCrypto.encodePublicKey(LocalOtaCrypto.newKeyPair(random).public))
        listOf(
            null, "", "https://example.com", "WIFI:S:home;;",
            "ossm-ota://pair?p=47474&k=$key",
            "ossm-ota://pair?h=10.0.0.232&k=$key",
            "ossm-ota://pair?h=10.0.0.232&p=99999&k=$key",
            "ossm-ota://pair?h=10.0.0.232&p=47474",
            "ossm-ota://pair?h=10.0.0.232&p=47474&k=AAAA",
            "ossm-ota://pair?h=10.0.0.999&p=47474&k=$key",
            "ossm-ota://pair?h=a%20b&p=47474&k=$key",
            "ossm-ota://pair?h=10.0.0.232&p=47474&k=$key&v=abc",
        ).forEach { assertNull(it, parseLocalOtaQrUri(it)) }
    }

    // ------------------------------------------------------------ full handshake over a real socket

    @Test
    fun `accepted pairing uploads an APK with a valid HMAC`() {
        val host = FakeHost(answer = true)
        val server = newServer(host)
        val target = serve(server)
        val client = LocalOtaSenderClient(http, own, random)
        val apk = apkFile(300_000)

        val session = (client.hello(listOf(target), server.publicKey) as LocalOtaHelloOutcome.Ready).session
        assertEquals(LocalOtaConfirmOutcome.Accepted, client.awaitConfirmation(session))
        assertEquals(session.number, host.requests.single().number)
        assertEquals("SM-S938W", host.requests.single().peerName)
        assertEquals(LocalOtaUploadOutcome.Sent, client.upload(session, apk))
        assertArrayEquals(apk.readBytes(), host.received.single())
        // Single use: the same session cannot upload twice.
        assertEquals(LocalOtaUploadOutcome.SessionRejected, client.upload(session, apk))
    }

    @Test
    fun `scanned QR secret skips the comparison and the receiver prompt`() {
        val host = FakeHost(answer = false)
        val server = newServer(host)
        val target = serve(server)
        val client = LocalOtaSenderClient(http, own, random)
        val apk = apkFile(2_048)

        val session = (client.hello(listOf(target), server.publicKey) as LocalOtaHelloOutcome.Ready).session
        assertEquals(LocalOtaConfirmOutcome.Accepted, client.awaitConfirmation(session, server.qrSecret))
        assertTrue(host.requests.isEmpty())
        assertEquals(LocalOtaUploadOutcome.Sent, client.upload(session, apk))
    }

    @Test
    fun `wrong QR secret falls back to the receiver prompt`() {
        val host = FakeHost(answer = false)
        val server = newServer(host)
        val target = serve(server)
        val client = LocalOtaSenderClient(http, own, random)

        val session = (client.hello(listOf(target), server.publicKey) as LocalOtaHelloOutcome.Ready).session
        assertEquals(LocalOtaConfirmOutcome.Declined, client.awaitConfirmation(session, ByteArray(16)))
        assertEquals(1, host.requests.size)
    }

    @Test
    fun `tampered body or MAC is refused before installing`() {
        val host = FakeHost(answer = true)
        val server = newServer(host)
        val target = serve(server)
        val client = LocalOtaSenderClient(http, own, random)
        val session = (client.hello(listOf(target)) as LocalOtaHelloOutcome.Ready).session
        assertEquals(LocalOtaConfirmOutcome.Accepted, client.awaitConfirmation(session))
        val apk = apkFile(4_096)
        val goodMac = LocalOtaCrypto.macOf(session.macKey, session.sessionId, apk)
        val tampered = apk.readBytes().also { it[100] = (it[100].toInt() xor 0x55).toByte() }

        val response = http.newCall(
            Request.Builder()
                .url("http://${target.label}${LocalOtaProtocol.UPLOAD_PATH}")
                .header(LocalOtaProtocol.SESSION_HEADER, session.sessionId)
                .header(LocalOtaProtocol.MAC_HEADER, LocalOtaCrypto.b64(goodMac))
                .post(tampered.toRequestBody("application/octet-stream".toMediaType()))
                .build(),
        ).execute()

        assertEquals(401, response.code)
        response.close()
        assertTrue(host.received.isEmpty())
        assertEquals(listOf("mac"), host.rejections)
    }

    @Test
    fun `upload without a confirmed session is refused`() {
        val host = FakeHost(answer = true)
        val server = newServer(host)
        val target = serve(server)
        val client = LocalOtaSenderClient(http, own, random)
        val session = (client.hello(listOf(target)) as LocalOtaHelloOutcome.Ready).session

        assertEquals(LocalOtaUploadOutcome.SessionRejected, client.upload(session, apkFile(1_000)))
        assertTrue(host.received.isEmpty())
    }

    @Test
    fun `receiver refuses a version that is not newer before any transfer`() {
        val host = FakeHost(answer = true, installedCode = 180)
        val server = newServer(host)
        val outcome = LocalOtaSenderClient(http, own, random).hello(listOf(serve(server)))

        assertEquals(LocalOtaHelloOutcome.PeerUpToDate("2.3.x"), outcome)
        assertTrue(host.requests.isEmpty())
    }

    @Test
    fun `Non cancels the transfer`() {
        val host = FakeHost(answer = false)
        val server = newServer(host)
        val client = LocalOtaSenderClient(http, own, random)
        val session = (client.hello(listOf(serve(server))) as LocalOtaHelloOutcome.Ready).session

        assertEquals(LocalOtaConfirmOutcome.Declined, client.awaitConfirmation(session))
        assertEquals(LocalOtaDecision.Declined, host.awaitClosed())
        assertEquals(LocalOtaUploadOutcome.SessionRejected, client.upload(session, apkFile(1_000)))
    }

    @Test
    fun `no answer within the delay cancels the transfer`() {
        val host = FakeHost(answer = null)
        val server = newServer(host, confirmTimeoutMs = 700)
        val client = LocalOtaSenderClient(http, own, random)
        val session = (client.hello(listOf(serve(server))) as LocalOtaHelloOutcome.Ready).session

        assertEquals(LocalOtaConfirmOutcome.TimedOut, client.awaitConfirmation(session))
        assertEquals(LocalOtaDecision.TimedOut, host.awaitClosed())
    }

    @Test
    fun `sender disconnection while waiting cancels on the receiver`() {
        val host = FakeHost(answer = null)
        val server = newServer(host)
        val client = LocalOtaSenderClient(http, own, random)
        val session = (client.hello(listOf(serve(server))) as LocalOtaHelloOutcome.Ready).session
        host.onPrompt = { thread { Thread.sleep(300); client.cancel() } }

        assertEquals(LocalOtaConfirmOutcome.Failed(null), client.awaitConfirmation(session))
        assertEquals(LocalOtaDecision.Disconnected, host.awaitClosed())
    }

    @Test
    fun `a QR key that does not match the receiver blocks the transfer`() {
        val host = FakeHost(answer = true)
        val server = newServer(host)
        val otherKey = LocalOtaCrypto.encodePublicKey(LocalOtaCrypto.newKeyPair(random).public)

        val outcome = LocalOtaSenderClient(http, own, random).hello(listOf(serve(server)), otherKey)

        assertEquals(LocalOtaHelloOutcome.KeyMismatch, outcome)
        assertTrue(host.requests.isEmpty())
    }

    @Test
    fun `tries the QR addresses in order and uses the first that answers`() {
        val host = FakeHost(answer = true)
        val server = newServer(host)
        val reachable = serve(server)
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).let { socket ->
            LocalOtaTarget("127.0.0.1", socket.localPort).also { socket.close() }
        }
        val outcome = LocalOtaSenderClient(http, own, random, helloConnectTimeoutMs = 500)
            .hello(listOf(dead, reachable), server.publicKey)

        assertEquals(reachable, (outcome as LocalOtaHelloOutcome.Ready).session.target)
    }

    // ------------------------------------------------------------ the 400 of 2.3.25..2.3.31

    @Test
    fun `bug 400 reproduced - the 64 MiB limit rejects the 77 MB APK, the new limit accepts it`() {
        val legacy = serveRaw { socket -> drainingReceiver(socket, LOCAL_OTA_LEGACY_MAX_APK_BYTES) }
        val fixed = serveRaw { socket -> drainingReceiver(socket, LOCAL_OTA_MAX_APK_BYTES) }

        val before = http.newCall(bigUpload(legacy)).execute().use { it.code to it.body!!.string() }
        val after = http.newCall(bigUpload(fixed)).execute().use { it.code to it.body!!.string() }

        assertEquals(400 to "Bad request", before)
        assertEquals(200 to "OK $APK_SIZE_2_3_31", after)
    }

    @Test
    fun `new sender against a 2_3_31 receiver reports a too old version`() {
        val legacyReceiver = serveRaw { socket ->
            // handleReceiverClient of 2.3.25..2.3.31: only GET /ossm-ota/info and POST /v1/apk.
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = readLocalOtaHttpRequest(input, LOCAL_OTA_LEGACY_MAX_APK_BYTES)
            val known = request != null && (
                (request.method == "GET" && request.path == "/ossm-ota/info") ||
                    (request.method == "POST" && request.path == "/v1/apk")
                )
            writeLocalOtaResponse(output, if (known) 401 else 400, if (known) "Unauthorized" else "Bad request")
        }

        assertEquals(LocalOtaHelloOutcome.Legacy(400), LocalOtaSenderClient(http, own, random).hello(listOf(legacyReceiver)))
    }

    @Test
    fun `old senders get an explicit 426 from the new receiver`() {
        val server = LocalOtaReceiverServer(LocalOtaCrypto.newKeyPair(random), FakeHost(answer = true), random)
        val target = serve(server)
        val code = http.newCall(
            Request.Builder().url("http://${target.label}/ossm-ota/info").header("X-OSSM-Code", "123456").get().build(),
        ).execute().use { it.code }

        assertEquals(426, code)
    }

    // ------------------------------------------------------------ helpers

    private fun drainingReceiver(socket: Socket, limit: Long) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val request = readLocalOtaHttpRequest(input, limit)
        if (request == null) {
            writeLocalOtaResponse(output, 400, "Bad request")
            lingerLocalOtaSocket(socket, input)
            return
        }
        var remaining = request.contentLength
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            remaining -= read
        }
        writeLocalOtaResponse(output, 200, "OK ${request.contentLength - remaining}")
    }

    private fun bigUpload(target: LocalOtaTarget): Request = Request.Builder()
        .url("http://${target.label}/v1/apk")
        .post(object : RequestBody() {
            override fun contentType() = LOCAL_OTA_APK_MIME_TYPE.toMediaType()
            override fun contentLength() = APK_SIZE_2_3_31
            override fun writeTo(sink: BufferedSink) {
                val chunk = ByteArray(64 * 1024)
                var remaining = APK_SIZE_2_3_31
                while (remaining > 0) {
                    val size = minOf(chunk.size.toLong(), remaining).toInt()
                    sink.write(chunk, 0, size)
                    remaining -= size
                }
            }
        })
        .build()

    private fun serve(server: LocalOtaReceiverServer): LocalOtaTarget = serveRaw(server::handle)

    private fun serveRaw(handler: (Socket) -> Unit): LocalOtaTarget {
        val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        sockets += socket
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use { runCatching { handler(it) } } }
            }
        }
        return LocalOtaTarget("127.0.0.1", socket.localPort)
    }

    private fun apkFile(size: Int): File = temporaryFolder.newFile().apply {
        writeBytes(ByteArray(size).also(random::nextBytes))
    }

    /** answer: true = Oui, false = Non, null = no answer. */
    private inner class FakeHost(
        private val answer: Boolean?,
        private val installedCode: Long = 179,
    ) : LocalOtaReceiverHost {
        val requests = CopyOnWriteArrayList<LocalOtaIncomingRequest>()
        val received = CopyOnWriteArrayList<ByteArray>()
        val rejections = CopyOnWriteArrayList<String>()
        var onPrompt: (() -> Unit)? = null
        private val closed = CopyOnWriteArrayList<LocalOtaDecision>()
        private val closedLatch = CountDownLatch(1)
        private var decide: ((String, Boolean) -> Unit)? = null

        override val packageName = PACKAGE
        override val deviceName = "moto_g"
        override fun installedVersionCode() = installedCode
        override fun installedVersionName() = "2.3.x"

        override fun onConfirmationRequested(request: LocalOtaIncomingRequest) {
            requests += request
            onPrompt?.invoke()
            answer?.let { accepted -> decide?.invoke(request.sessionId, accepted) }
        }

        override fun onConfirmationClosed(sessionId: String, decision: LocalOtaDecision) {
            closed += decision
            closedLatch.countDown()
        }

        override fun newStagingFile(): File = temporaryFolder.newFile()

        override fun onApkReceived(file: File): LocalOtaInstallResult {
            received += file.readBytes()
            return LocalOtaInstallResult(200, "OK")
        }

        override fun onUploadRejected(reason: String) {
            rejections += reason
        }

        fun bind(server: LocalOtaReceiverServer) {
            decide = server::decide
        }

        fun awaitClosed(): LocalOtaDecision {
            assertTrue("receiver never closed the request", closedLatch.await(5, TimeUnit.SECONDS))
            return closed.first()
        }
    }

    private fun newServer(host: FakeHost, confirmTimeoutMs: Long = LocalOtaProtocol.CONFIRM_TIMEOUT_MS) =
        LocalOtaReceiverServer(LocalOtaCrypto.newKeyPair(random), host, random, confirmTimeoutMs = confirmTimeoutMs)
            .also(host::bind)

    private companion object {
        const val PACKAGE = "com.ossm.remote.debug"
        /** Size of OSSM-Remote-v2.3.31-debug.apk. */
        const val APK_SIZE_2_3_31 = 76_688_238L
    }
}
