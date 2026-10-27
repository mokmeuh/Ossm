package com.ossm.remote.updater

import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Local OTA pairing crypto (protocol 2, 2.3.32). Pure JVM: ECDH on secp256r1
 * (java.security, available from API 26; X25519 would need API 33), SHA-256 and
 * HMAC-SHA256. Keys are ephemeral (one receiver activation / one send attempt).
 *
 * Numeric comparison: the sender commits to (pkS, nS) in hello before it sees the
 * receiver key, so a man-in-the-middle cannot grind keys to force equal numbers;
 * the two phones then show the same 2-digit number only if they share the secret.
 */
internal object LocalOtaCrypto {
    const val NONCE_BYTES = 16
    private const val CURVE = "secp256r1"
    private val LABEL_COMMIT = "OSSM-OTA-v2|commit".toByteArray(Charsets.US_ASCII)
    private val LABEL_SAS = "OSSM-OTA-v2|sas".toByteArray(Charsets.US_ASCII)
    private val LABEL_MAC = "OSSM-OTA-v2|mac".toByteArray(Charsets.US_ASCII)

    fun newKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE), random) }.generateKeyPair()

    fun nonce(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)

    /** X.509 SubjectPublicKeyInfo bytes, as carried in the QR code, the TXT record and the handshake. */
    fun encodePublicKey(key: PublicKey): ByteArray = key.encoded

    /** Null when the bytes are not a valid P-256 public key. */
    fun decodePublicKey(bytes: ByteArray): PublicKey? = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }.getOrNull()

    fun sharedSecret(own: KeyPair, peerPublic: ByteArray): ByteArray? {
        val peer = decodePublicKey(peerPublic) ?: return null
        return runCatching {
            KeyAgreement.getInstance("ECDH").run {
                init(own.private)
                doPhase(peer, true)
                generateSecret()
            }
        }.getOrNull()
    }

    fun commitment(senderPublic: ByteArray, senderNonce: ByteArray): ByteArray =
        sha256(LABEL_COMMIT, senderPublic, senderNonce)

    fun commitmentMatches(commit: ByteArray, senderPublic: ByteArray, senderNonce: ByteArray): Boolean =
        MessageDigest.isEqual(commit, commitment(senderPublic, senderNonce))

    /** Two decimal digits ("00".."99") shown on both phones. */
    fun comparisonNumber(transcript: LocalOtaTranscript): String {
        val digest = sha256(LABEL_SAS, *transcript.parts())
        val value = ((digest[0].toLong() and 0xFF) shl 24) or ((digest[1].toLong() and 0xFF) shl 16) or
            ((digest[2].toLong() and 0xFF) shl 8) or (digest[3].toLong() and 0xFF)
        return "%02d".format(value % 100)
    }

    fun macKey(transcript: LocalOtaTranscript): ByteArray = sha256(LABEL_MAC, *transcript.parts())

    fun newMac(key: ByteArray, sessionId: String): Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key, "HmacSHA256"))
        update(sessionId.toByteArray(Charsets.US_ASCII))
    }

    fun macOf(key: ByteArray, sessionId: String, input: InputStream): ByteArray {
        val mac = newMac(key, sessionId)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            mac.update(buffer, 0, read)
        }
        return mac.doFinal()
    }

    fun macOf(key: ByteArray, sessionId: String, file: File): ByteArray = file.inputStream().use { macOf(key, sessionId, it) }

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun unb64(text: String?): ByteArray? = text?.let { runCatching { Base64.getUrlDecoder().decode(it.trim()) }.getOrNull() }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            // Length-prefixed so concatenations cannot collide.
            digest.update(byteArrayOf((part.size ushr 8).toByte(), part.size.toByte()))
            digest.update(part)
        }
        return digest.digest()
    }
}

/** Everything both phones must agree on: the ECDH secret, both public keys and both nonces. */
internal class LocalOtaTranscript(
    val shared: ByteArray,
    val senderPublic: ByteArray,
    val receiverPublic: ByteArray,
    val senderNonce: ByteArray,
    val receiverNonce: ByteArray,
) {
    fun parts(): Array<ByteArray> = arrayOf(shared, senderPublic, receiverPublic, senderNonce, receiverNonce)
}
