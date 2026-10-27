package com.ossm.remote.updater

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Outcome of the receiver-side "Le nombre 47 est-il bien celui affiché… ?" question. */
internal enum class LocalOtaDecision { Accepted, Declined, TimedOut, Disconnected, Cancelled }

/** Shown on the receiver while it waits for Oui/Non. */
data class LocalOtaIncomingRequest(
    val sessionId: String,
    val number: String,
    val peerName: String,
    val peerVersionName: String?,
)

internal data class LocalOtaInstallResult(val status: Int, val message: String)

/** Android side of the receiver (version, UI, APK validation, installer); faked in JVM tests. */
internal interface LocalOtaReceiverHost {
    val packageName: String
    val deviceName: String
    fun installedVersionCode(): Long
    fun installedVersionName(): String
    fun onConfirmationRequested(request: LocalOtaIncomingRequest)
    fun onConfirmationClosed(sessionId: String, decision: LocalOtaDecision)
    fun newStagingFile(): File
    /** Validates (package, signer, versionCode) and launches the Android installer. */
    fun onApkReceived(file: File): LocalOtaInstallResult
    fun onUploadRejected(reason: String)
}

/**
 * Receiver of protocol 2 (2.3.32). Routes, all `key=value` text bodies:
 * - `POST /ossm-ota/hello` {c=commit(pkS,nS), vc, vn, name, pkg} -> 200 {pk, n, sid, vc, vn, name}
 *   (409 error=not_newer when vc <= installed, 409 error=busy, 422 error=wrong_package);
 * - `POST /ossm-ota/confirm` {sid, pk, n} -> verifies the commitment, shows the number and
 *   waits (long poll, [confirmTimeoutMs]) for Oui/Non -> 200 {accepted, reason};
 *   a closed connection cancels;
 * - `POST /ossm-ota/upload` headers X-OSSM-Session + X-OSSM-Mac (HMAC-SHA256 of the body,
 *   key derived from the ECDH secret) -> the APK is installed only when the MAC matches.
 * Legacy routes (`/ossm-ota/info`, `/v1/apk`) answer 426 so old senders show an error.
 */
internal class LocalOtaReceiverServer(
    private val keyPair: KeyPair,
    private val host: LocalOtaReceiverHost,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val confirmTimeoutMs: Long = LocalOtaProtocol.CONFIRM_TIMEOUT_MS,
    private val uploadWindowMs: Long = LocalOtaProtocol.UPLOAD_WINDOW_MS,
    private val maxApkBytes: Long = LOCAL_OTA_MAX_APK_BYTES,
) {
    val publicKey: ByteArray = LocalOtaCrypto.encodePublicKey(keyPair.public)

    /** Secret carried only by the QR code on this phone's screen: whoever scanned it is authenticated. */
    val qrSecret: ByteArray = ByteArray(16).also(random::nextBytes)

    private class Session(
        val id: String,
        val commit: ByteArray,
        val receiverNonce: ByteArray,
        val peerName: String,
        val peerVersionName: String?,
        val createdAtMs: Long,
    ) {
        @Volatile var macKey: ByteArray? = null
        @Volatile var decision: LocalOtaDecision? = null
        @Volatile var decidedAtMs: Long = 0L
        @Volatile var prompted = false
        @Volatile var used = false
        val latch = CountDownLatch(1)
    }

    private val lock = Any()
    private var session: Session? = null

    /** UI answer (Oui/Non) for the request currently shown. */
    fun decide(sessionId: String, accepted: Boolean) {
        val current = synchronized(lock) { session?.takeIf { it.id == sessionId } } ?: return
        finish(current, if (accepted) LocalOtaDecision.Accepted else LocalOtaDecision.Declined)
    }

    fun cancelAll() {
        val current = synchronized(lock) { session.also { session = null } } ?: return
        finish(current, LocalOtaDecision.Cancelled)
    }

    fun handle(socket: Socket) {
        socket.use {
            socket.soTimeout = LocalOtaProtocol.SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = readLocalOtaHttpRequest(input, maxApkBytes)
            if (request == null) {
                respondAndLinger(socket, input, output, 400, formatLocalOtaFields("error" to "bad_request"))
                return
            }
            when {
                request.method == "POST" && request.path == LocalOtaProtocol.HELLO_PATH -> hello(input, output, request)
                request.method == "POST" && request.path == LocalOtaProtocol.CONFIRM_PATH -> confirm(socket, input, output, request)
                request.method == "POST" && request.path == LocalOtaProtocol.UPLOAD_PATH -> upload(socket, input, output, request)
                request.path == LocalOtaProtocol.LEGACY_INFO_PATH || request.path == LocalOtaProtocol.LEGACY_UPLOAD_PATH ->
                    respondAndLinger(socket, input, output, 426, "Mettez à jour l’application de ce téléphone (2.3.32+).")
                else -> respondAndLinger(socket, input, output, 404, formatLocalOtaFields("error" to "not_found"))
            }
        }
    }

    private fun hello(input: BufferedInputStream, output: BufferedOutputStream, request: LocalOtaHttpRequest) {
        val fields = readLocalOtaSmallBody(input, request.contentLength)?.let(::parseLocalOtaFields)
        val commit = LocalOtaCrypto.unb64(fields?.get("c"))
        val senderVersion = fields?.get("vc")?.toLongOrNull()
        if (fields == null || commit == null || commit.size != 32 || senderVersion == null) {
            writeLocalOtaResponse(output, 400, formatLocalOtaFields("error" to "bad_hello"))
            return
        }
        if (fields["pkg"] != null && fields["pkg"] != host.packageName) {
            writeLocalOtaResponse(output, 422, formatLocalOtaFields("error" to "wrong_package"))
            return
        }
        val installedCode = host.installedVersionCode()
        val installedName = host.installedVersionName()
        if (senderVersion <= installedCode) {
            writeLocalOtaResponse(
                output, 409,
                formatLocalOtaFields("error" to "not_newer", "vc" to installedCode, "vn" to installedName),
            )
            return
        }
        val created = synchronized(lock) {
            val current = session
            val now = clock()
            // A request already shown to the user keeps priority until answered or expired.
            if (current != null && current.prompted && current.decision == null &&
                now - current.createdAtMs < confirmTimeoutMs * 2
            ) {
                return@synchronized null
            }
            Session(
                id = LocalOtaCrypto.b64(ByteArray(18).also(random::nextBytes)),
                commit = commit,
                receiverNonce = LocalOtaCrypto.nonce(random),
                peerName = sanitizeLocalOtaValue(fields["name"].orEmpty()).take(32).ifBlank { "?" },
                peerVersionName = fields["vn"]?.let(::sanitizeLocalOtaValue)?.take(32),
                createdAtMs = now,
            ).also { session = it }
        }
        if (created == null) {
            writeLocalOtaResponse(output, 409, formatLocalOtaFields("error" to "busy"))
            return
        }
        writeLocalOtaResponse(
            output, 200,
            formatLocalOtaFields(
                "pk" to LocalOtaCrypto.b64(publicKey),
                "n" to LocalOtaCrypto.b64(created.receiverNonce),
                "sid" to created.id,
                "vc" to installedCode,
                "vn" to installedName,
                "name" to host.deviceName,
                "proto" to LocalOtaProtocol.PROTOCOL_VERSION,
            ),
        )
    }

    private fun confirm(socket: Socket, input: BufferedInputStream, output: BufferedOutputStream, request: LocalOtaHttpRequest) {
        val fields = readLocalOtaSmallBody(input, request.contentLength)?.let(::parseLocalOtaFields)
        val current = fields?.get("sid")?.let(::findSession)
        if (fields == null || current == null || current.prompted || current.decision != null ||
            clock() - current.createdAtMs > confirmTimeoutMs
        ) {
            writeLocalOtaResponse(output, 404, formatLocalOtaFields("error" to "session"))
            return
        }
        val senderPublic = LocalOtaCrypto.unb64(fields["pk"])
        val senderNonce = LocalOtaCrypto.unb64(fields["n"])
        val shared = senderPublic?.let { LocalOtaCrypto.sharedSecret(keyPair, it) }
        if (senderPublic == null || senderNonce == null || shared == null ||
            !LocalOtaCrypto.commitmentMatches(current.commit, senderPublic, senderNonce)
        ) {
            finish(current, LocalOtaDecision.Cancelled)
            writeLocalOtaResponse(output, 400, formatLocalOtaFields("error" to "commitment"))
            return
        }
        val transcript = LocalOtaTranscript(shared, senderPublic, publicKey, senderNonce, current.receiverNonce)
        current.macKey = LocalOtaCrypto.macKey(transcript)
        val scannedSecret = LocalOtaCrypto.unb64(fields["qs"])
        if (scannedSecret != null && MessageDigest.isEqual(scannedSecret, qrSecret)) {
            // The sender scanned this phone's QR code: that is the confirmation, nothing to compare.
            finish(current, LocalOtaDecision.Accepted)
            runCatching {
                writeLocalOtaResponse(
                    output, 200,
                    formatLocalOtaFields("accepted" to true, "reason" to LocalOtaDecision.Accepted.name.lowercase()),
                )
            }
            return
        }
        current.prompted = true
        host.onConfirmationRequested(
            LocalOtaIncomingRequest(current.id, LocalOtaCrypto.comparisonNumber(transcript), current.peerName, current.peerVersionName),
        )
        val decision = awaitDecision(socket, input, current)
        val accepted = decision == LocalOtaDecision.Accepted
        runCatching {
            writeLocalOtaResponse(
                output, 200,
                formatLocalOtaFields("accepted" to accepted, "reason" to decision.name.lowercase()),
            )
        }
    }

    /** Waits for Oui/Non, the timeout, or the sender closing its connection (= cancel). */
    private fun awaitDecision(socket: Socket, input: BufferedInputStream, current: Session): LocalOtaDecision {
        val deadline = clock() + confirmTimeoutMs
        val previousTimeout = socket.soTimeout
        socket.soTimeout = DISCONNECT_PROBE_MS
        try {
            while (true) {
                current.decision?.let { return it }
                if (current.latch.await(DISCONNECT_PROBE_MS.toLong(), TimeUnit.MILLISECONDS)) continue
                if (clock() >= deadline) {
                    finish(current, LocalOtaDecision.TimedOut)
                    continue
                }
                val closed = try {
                    input.read() < 0
                } catch (_: SocketTimeoutException) {
                    false
                } catch (_: Exception) {
                    true
                }
                if (closed) finish(current, LocalOtaDecision.Disconnected)
            }
        } finally {
            runCatching { socket.soTimeout = previousTimeout }
        }
    }

    private fun upload(socket: Socket, input: BufferedInputStream, output: BufferedOutputStream, request: LocalOtaHttpRequest) {
        val current = request.headers[LocalOtaProtocol.SESSION_HEADER]?.let(::findSession)
        val suppliedMac = LocalOtaCrypto.unb64(request.headers[LocalOtaProtocol.MAC_HEADER])
        val macKey = current?.macKey
        val allowed = current != null && macKey != null && suppliedMac != null &&
            current.decision == LocalOtaDecision.Accepted && !current.used &&
            clock() - current.decidedAtMs <= uploadWindowMs
        if (!allowed) {
            respondAndLinger(socket, input, output, 403, formatLocalOtaFields("error" to "session"))
            return
        }
        current!!.used = true
        val staging = host.newStagingFile()
        try {
            val mac = LocalOtaCrypto.newMac(macKey!!, current.id)
            if (!copyBodyWithMac(input, staging, request.contentLength, mac)) {
                writeLocalOtaResponse(output, 400, formatLocalOtaFields("error" to "truncated"))
                return
            }
            if (!MessageDigest.isEqual(mac.doFinal(), suppliedMac)) {
                host.onUploadRejected("mac")
                writeLocalOtaResponse(output, 401, formatLocalOtaFields("error" to "mac"))
                return
            }
            val result = host.onApkReceived(staging)
            writeLocalOtaResponse(output, result.status, result.message)
        } catch (error: Exception) {
            runCatching { writeLocalOtaResponse(output, 500, formatLocalOtaFields("error" to "receive")) }
        } finally {
            staging.delete()
            synchronized(lock) { if (session === current) session = null }
        }
    }

    private fun copyBodyWithMac(input: BufferedInputStream, destination: File, length: Long, mac: javax.crypto.Mac): Boolean {
        var remaining = length
        FileOutputStream(destination).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) return false
                mac.update(buffer, 0, read)
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
        return true
    }

    private fun findSession(id: String): Session? = synchronized(lock) {
        session?.takeIf { MessageDigest.isEqual(it.id.toByteArray(), id.trim().toByteArray()) }
    }

    private fun finish(current: Session, decision: LocalOtaDecision) {
        synchronized(current) {
            if (current.decision != null) return
            current.decision = decision
            current.decidedAtMs = clock()
        }
        current.latch.countDown()
        if (decision != LocalOtaDecision.Accepted) {
            synchronized(lock) { if (session === current) session = null }
        }
        if (current.prompted) host.onConfirmationClosed(current.id, decision)
    }

    private fun respondAndLinger(
        socket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        status: Int,
        body: String,
    ) {
        writeLocalOtaResponse(output, status, body)
        lingerLocalOtaSocket(socket, input)
    }

    private companion object {
        const val DISCONNECT_PROBE_MS = 250
    }
}
