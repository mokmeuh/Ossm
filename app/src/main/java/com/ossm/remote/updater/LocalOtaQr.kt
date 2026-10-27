package com.ossm.remote.updater

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Content of the receiver QR code:
 * `ossm-ota://pair?h=<ip1>,<ip2>&p=<port>&k=<public key base64url>&v=<versionCode>&n=<device>`.
 * Hosts are tried in order (Wi-Fi first, then Tailscale). The key pins the receiver.
 */
internal data class LocalOtaQrPayload(
    val hosts: List<String>,
    val port: Int,
    val receiverPublicKey: ByteArray,
    val versionCode: Long?,
    val deviceName: String?,
    /** One-time proof shown only in the QR: scanning it replaces the 2-digit comparison. */
    val secret: ByteArray? = null,
) {
    val targets: List<LocalOtaTarget> get() = hosts.map { LocalOtaTarget(it, port) }

    override fun equals(other: Any?): Boolean = other is LocalOtaQrPayload &&
        hosts == other.hosts && port == other.port && versionCode == other.versionCode &&
        deviceName == other.deviceName && receiverPublicKey.contentEquals(other.receiverPublicKey) &&
        (secret ?: ByteArray(0)).contentEquals(other.secret ?: ByteArray(0))

    override fun hashCode(): Int = hosts.hashCode() * 31 + port

    companion object {
        const val SCHEME_PREFIX = "ossm-ota://pair?"
    }
}

internal fun buildLocalOtaQrUri(payload: LocalOtaQrPayload): String = buildString {
    append(LocalOtaQrPayload.SCHEME_PREFIX)
    append("h=").append(payload.hosts.joinToString(",") { encode(it) })
    append("&p=").append(payload.port)
    append("&k=").append(LocalOtaCrypto.b64(payload.receiverPublicKey))
    payload.secret?.let { append("&s=").append(LocalOtaCrypto.b64(it)) }
    payload.versionCode?.let { append("&v=").append(it) }
    payload.deviceName?.takeIf { it.isNotBlank() }?.let { append("&n=").append(encode(it.take(32))) }
}

/** Null for anything that is not a well-formed OSSM OTA pairing QR (other QR codes, URLs, garbage). */
internal fun parseLocalOtaQrUri(text: String?): LocalOtaQrPayload? {
    val raw = text?.trim() ?: return null
    if (raw.length > 1_024 || !raw.startsWith(LocalOtaQrPayload.SCHEME_PREFIX, ignoreCase = true)) return null
    val params = raw.substring(LocalOtaQrPayload.SCHEME_PREFIX.length)
        .split('&')
        .mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) null else part.substring(0, separator) to part.substring(separator + 1)
        }
        .toMap()
    val hosts = params["h"]?.split(',')
        ?.map { decode(it) ?: return null }
        ?.filter { it.isNotBlank() }
        ?.take(4)
        .orEmpty()
    if (hosts.isEmpty()) return null
    val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
    // Each host must be a plain address/hostname accepted by the manual parser.
    if (hosts.any { parseLocalOtaAddress(it, port)?.host != it }) return null
    val key = LocalOtaCrypto.unb64(params["k"]) ?: return null
    if (LocalOtaCrypto.decodePublicKey(key) == null) return null
    val version = params["v"]?.let { it.toLongOrNull() ?: return null }
    val name = params["n"]?.let { decode(it) }?.replace(Regex("[\\r\\n=]"), " ")?.take(32)?.takeIf { it.isNotBlank() }
    val secret = params["s"]?.let { LocalOtaCrypto.unb64(it) ?: return null }
    return LocalOtaQrPayload(hosts, port, key, version, name, secret)
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
