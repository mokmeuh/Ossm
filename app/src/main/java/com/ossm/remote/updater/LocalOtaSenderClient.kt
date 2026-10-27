package com.ossm.remote.updater

import java.io.File
import java.io.IOException
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

internal data class LocalOtaOwnVersion(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val deviceName: String,
)

/** Handshake state kept by the sender between hello, confirm and upload. Never logged. */
internal class LocalOtaSenderSession(
    val target: LocalOtaTarget,
    val sessionId: String,
    val number: String,
    val peerName: String?,
    val peerVersionCode: Long,
    val peerVersionName: String?,
    internal val senderPublic: ByteArray,
    internal val senderNonce: ByteArray,
    internal val macKey: ByteArray,
)

internal sealed interface LocalOtaHelloOutcome {
    data class Ready(val session: LocalOtaSenderSession) : LocalOtaHelloOutcome
    /** 400/404 on /ossm-ota/hello: a receiver older than 2.3.32 (code/token protocol). */
    data class Legacy(val status: Int) : LocalOtaHelloOutcome
    data class PeerUpToDate(val versionName: String?) : LocalOtaHelloOutcome
    data object Busy : LocalOtaHelloOutcome
    data object WrongPackage : LocalOtaHelloOutcome
    /** The receiver key differs from the one in the QR code / TXT record. */
    data object KeyMismatch : LocalOtaHelloOutcome
    data class Http(val status: Int) : LocalOtaHelloOutcome
    data class Unreachable(val targets: List<LocalOtaTarget>) : LocalOtaHelloOutcome
}

internal sealed interface LocalOtaConfirmOutcome {
    data object Accepted : LocalOtaConfirmOutcome
    data object Declined : LocalOtaConfirmOutcome
    data object TimedOut : LocalOtaConfirmOutcome
    data class Failed(val status: Int?) : LocalOtaConfirmOutcome
}

internal sealed interface LocalOtaUploadOutcome {
    data object Sent : LocalOtaUploadOutcome
    data object PeerUpToDate : LocalOtaUploadOutcome
    data object MacRejected : LocalOtaUploadOutcome
    data object SessionRejected : LocalOtaUploadOutcome
    data class Refused(val status: Int, val message: String) : LocalOtaUploadOutcome
    data object Unreachable : LocalOtaUploadOutcome
}

/**
 * Sender side of protocol 2. Blocking calls (run them on Dispatchers.IO); [cancel]
 * aborts the call in flight, which the receiver sees as a disconnection (= Non).
 */
internal class LocalOtaSenderClient(
    baseClient: OkHttpClient,
    private val own: LocalOtaOwnVersion,
    private val random: SecureRandom = SecureRandom(),
    helloConnectTimeoutMs: Long = 3_000L,
    confirmTimeoutMs: Long = LocalOtaProtocol.CONFIRM_TIMEOUT_MS,
) {
    private val helloClient = baseClient.newBuilder()
        .connectTimeout(helloConnectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(helloConnectTimeoutMs + 8_000L, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val confirmClient = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(confirmTimeoutMs + 15_000L, TimeUnit.MILLISECONDS)
        .callTimeout(confirmTimeoutMs + 20_000L, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val uploadClient = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val calls = Collections.synchronizedSet(mutableSetOf<Call>())
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        synchronized(calls) { calls.toList() }.forEach(Call::cancel)
    }

    /** Tries each address in order (QR: Wi-Fi then Tailscale); the first one that answers wins. */
    fun hello(targets: List<LocalOtaTarget>, expectedReceiverKey: ByteArray? = null): LocalOtaHelloOutcome {
        for (target in targets) {
            if (cancelled) break
            val outcome = helloOne(target, expectedReceiverKey)
            if (outcome !is LocalOtaHelloOutcome.Unreachable) return outcome
        }
        return LocalOtaHelloOutcome.Unreachable(targets)
    }

    private fun helloOne(target: LocalOtaTarget, expectedReceiverKey: ByteArray?): LocalOtaHelloOutcome {
        val keyPair: KeyPair = LocalOtaCrypto.newKeyPair(random)
        val senderPublic = LocalOtaCrypto.encodePublicKey(keyPair.public)
        val senderNonce = LocalOtaCrypto.nonce(random)
        val body = formatLocalOtaFields(
            "c" to LocalOtaCrypto.b64(LocalOtaCrypto.commitment(senderPublic, senderNonce)),
            "vc" to own.versionCode,
            "vn" to own.versionName,
            "name" to own.deviceName,
            "pkg" to own.packageName,
            "proto" to LocalOtaProtocol.PROTOCOL_VERSION,
        )
        val (status, text) = execute(helloClient, post(target, LocalOtaProtocol.HELLO_PATH, body))
            ?: return LocalOtaHelloOutcome.Unreachable(listOf(target))
        val fields = parseLocalOtaFields(text)
        return when (status) {
            200 -> {
                val receiverPublic = LocalOtaCrypto.unb64(fields["pk"]) ?: return LocalOtaHelloOutcome.Http(status)
                val receiverNonce = LocalOtaCrypto.unb64(fields["n"]) ?: return LocalOtaHelloOutcome.Http(status)
                val sessionId = fields["sid"]?.takeIf { it.isNotBlank() } ?: return LocalOtaHelloOutcome.Http(status)
                val peerCode = fields["vc"]?.toLongOrNull() ?: return LocalOtaHelloOutcome.Http(status)
                if (expectedReceiverKey != null && !MessageDigest.isEqual(expectedReceiverKey, receiverPublic)) {
                    return LocalOtaHelloOutcome.KeyMismatch
                }
                if (peerCode >= own.versionCode) return LocalOtaHelloOutcome.PeerUpToDate(fields["vn"])
                val shared = LocalOtaCrypto.sharedSecret(keyPair, receiverPublic) ?: return LocalOtaHelloOutcome.Http(status)
                val transcript = LocalOtaTranscript(shared, senderPublic, receiverPublic, senderNonce, receiverNonce)
                LocalOtaHelloOutcome.Ready(
                    LocalOtaSenderSession(
                        target = target,
                        sessionId = sessionId,
                        number = LocalOtaCrypto.comparisonNumber(transcript),
                        peerName = fields["name"],
                        peerVersionCode = peerCode,
                        peerVersionName = fields["vn"],
                        senderPublic = senderPublic,
                        senderNonce = senderNonce,
                        macKey = LocalOtaCrypto.macKey(transcript),
                    ),
                )
            }
            400, 404 -> LocalOtaHelloOutcome.Legacy(status)
            409 -> if (fields["error"] == "busy") LocalOtaHelloOutcome.Busy else LocalOtaHelloOutcome.PeerUpToDate(fields["vn"])
            422 -> LocalOtaHelloOutcome.WrongPackage
            else -> LocalOtaHelloOutcome.Http(status)
        }
    }

    /** Reveals the key (opening the commitment) and waits for Oui/Non on the receiver. */
    fun awaitConfirmation(session: LocalOtaSenderSession, qrSecret: ByteArray? = null): LocalOtaConfirmOutcome {
        val body = formatLocalOtaFields(
            "sid" to session.sessionId,
            "pk" to LocalOtaCrypto.b64(session.senderPublic),
            "n" to LocalOtaCrypto.b64(session.senderNonce),
            *listOfNotNull(qrSecret?.let { "qs" to LocalOtaCrypto.b64(it) }).toTypedArray(),
        )
        val (status, text) = execute(confirmClient, post(session.target, LocalOtaProtocol.CONFIRM_PATH, body))
            ?: return LocalOtaConfirmOutcome.Failed(null)
        if (status != 200) return LocalOtaConfirmOutcome.Failed(status)
        val fields = parseLocalOtaFields(text)
        return when {
            fields["accepted"] == "true" -> LocalOtaConfirmOutcome.Accepted
            fields["reason"] == "timedout" -> LocalOtaConfirmOutcome.TimedOut
            else -> LocalOtaConfirmOutcome.Declined
        }
    }

    fun upload(session: LocalOtaSenderSession, apk: File): LocalOtaUploadOutcome {
        val mac = LocalOtaCrypto.macOf(session.macKey, session.sessionId, apk)
        val request = Request.Builder()
            .url(url(session.target, LocalOtaProtocol.UPLOAD_PATH))
            .header(LocalOtaProtocol.SESSION_HEADER, session.sessionId)
            .header(LocalOtaProtocol.MAC_HEADER, LocalOtaCrypto.b64(mac))
            .post(apk.asRequestBody(LOCAL_OTA_APK_MIME_TYPE.toMediaType()))
            .build()
        val (status, text) = execute(uploadClient, request) ?: return LocalOtaUploadOutcome.Unreachable
        return when (status) {
            in 200..299 -> LocalOtaUploadOutcome.Sent
            401 -> LocalOtaUploadOutcome.MacRejected
            403 -> LocalOtaUploadOutcome.SessionRejected
            409 -> LocalOtaUploadOutcome.PeerUpToDate
            else -> LocalOtaUploadOutcome.Refused(status, text.lineSequence().firstOrNull().orEmpty().take(160))
        }
    }

    private fun post(target: LocalOtaTarget, path: String, body: String): Request = Request.Builder()
        .url(url(target, path))
        .post(body.toRequestBody(TEXT_TYPE))
        .build()

    private fun url(target: LocalOtaTarget, path: String) = "http://${target.hostForUrl}:${target.port}$path"

    /** (status, body) or null when unreachable/cancelled. */
    private fun execute(client: OkHttpClient, request: Request): Pair<Int, String>? {
        if (cancelled) return null
        val call = client.newCall(request)
        calls += call
        return try {
            call.execute().use { response ->
                response.code to response.body?.string().orEmpty().take(MAX_RESPONSE_CHARS)
            }
        } catch (_: IOException) {
            null
        } finally {
            calls -= call
        }
    }

    private companion object {
        val TEXT_TYPE = "text/plain; charset=utf-8".toMediaType()
        const val MAX_RESPONSE_CHARS = 2_048
    }
}
