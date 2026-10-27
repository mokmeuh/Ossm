package com.ossm.remote.updater

import java.net.Inet4Address
import java.net.InetAddress

/** Peer identity as announced in the NSD TXT record. */
internal data class LocalOtaPeerInfo(
    val packageName: String,
    val versionCode: Long?,
    val versionName: String?,
    val device: String?,
    val instanceId: String?,
    /** [LocalOtaProtocol.PROTOCOL_VERSION] or null for 2.3.31 and older receivers. */
    val protocol: Int? = null,
    /** Receiver public key (base64url X.509), pinned during the handshake. */
    val publicKey: String? = null,
)

internal fun localOtaPeerFromTxt(attributes: Map<String, String>): LocalOtaPeerInfo? {
    val packageName = attributes[LocalOtaProtocol.TXT_PACKAGE] ?: return null
    return LocalOtaPeerInfo(
        packageName = packageName,
        versionCode = attributes[LocalOtaProtocol.TXT_VERSION_CODE]?.toLongOrNull(),
        versionName = attributes[LocalOtaProtocol.TXT_VERSION_NAME],
        device = attributes[LocalOtaProtocol.TXT_DEVICE],
        instanceId = attributes[LocalOtaProtocol.TXT_INSTANCE],
        protocol = attributes[LocalOtaProtocol.TXT_PROTOCOL]?.toIntOrNull(),
        publicKey = attributes[LocalOtaProtocol.TXT_PUBLIC_KEY]?.takeIf { it.isNotBlank() },
    )
}

/** True when the receiver speaks the 2.3.32 handshake (ECDH + number comparison). */
internal fun LocalOtaPeerInfo.supportsPairing(): Boolean =
    (protocol ?: 0) >= LocalOtaProtocol.PROTOCOL_VERSION && publicKey != null

/** True when a discovered service is this phone's own receiver. */
internal fun isOwnLocalOtaReceiver(peer: LocalOtaPeerInfo, ownInstanceId: String): Boolean =
    peer.instanceId != null && peer.instanceId == ownInstanceId

internal enum class LocalOtaSendDecision { Send, PeerUpToDate, WrongPackage }

/**
 * Only send a strictly newer build. A peer without a version (legacy receiver) is
 * allowed; the receiver still validates the APK itself.
 */
internal fun localOtaSendDecision(
    peer: LocalOtaPeerInfo,
    ownPackageName: String,
    ownVersionCode: Long,
): LocalOtaSendDecision {
    if (peer.packageName != ownPackageName) return LocalOtaSendDecision.WrongPackage
    val remote = peer.versionCode ?: return LocalOtaSendDecision.Send
    return if (ownVersionCode > remote) LocalOtaSendDecision.Send else LocalOtaSendDecision.PeerUpToDate
}

/** Host + port typed by the user ("10.0.0.232", "10.0.0.232:47474", "[fd7a::1]:47474", "phone.ts.net"). */
internal data class LocalOtaTarget(val host: String, val port: Int) {
    val hostForUrl: String get() = if (host.contains(':')) "[$host]" else host
    val label: String get() = "$hostForUrl:$port"
}

internal fun parseLocalOtaAddress(input: String, defaultPort: Int = LocalOtaProtocol.PREFERRED_PORT): LocalOtaTarget? {
    var text = input.trim()
    text = text.removePrefix("http://").removePrefix("https://").trimEnd('/')
    if (text.isEmpty() || text.any { it.isWhitespace() || it == '/' || it == '@' }) return null
    val host: String
    val portText: String?
    if (text.startsWith("[")) {
        val end = text.indexOf(']')
        if (end <= 1) return null
        host = text.substring(1, end)
        val rest = text.substring(end + 1)
        portText = when {
            rest.isEmpty() -> null
            rest.startsWith(":") -> rest.substring(1)
            else -> return null
        }
        if (!host.all { it.isLetterOrDigit() || it == ':' || it == '.' }) return null
    } else {
        val parts = text.split(':')
        if (parts.size > 2) return null
        host = parts[0]
        portText = parts.getOrNull(1)
        if (!isValidHostName(host)) return null
    }
    val port = if (portText == null) defaultPort else portText.toIntOrNull() ?: return null
    if (port !in 1..65_535) return null
    return LocalOtaTarget(host, port)
}

private fun isValidHostName(host: String): Boolean {
    if (host.isEmpty() || host.length > 253) return false
    val labels = host.split('.')
    if (labels.all { label -> label.isNotEmpty() && label.all(Char::isDigit) }) {
        return labels.size == 4 && labels.all { it.length <= 3 && it.toInt() in 0..255 }
    }
    return labels.all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            label.all { it.isLetterOrDigit() || it == '-' } &&
            !label.startsWith('-') && !label.endsWith('-')
    }
}

internal enum class LocalOtaAddressKind { WiFi, Tailscale }

internal data class LocalOtaAddress(val address: String, val kind: LocalOtaAddressKind)

/**
 * Picks the receiver addresses worth showing: private IPv4 on Wi-Fi/Ethernet-like
 * interfaces and Tailscale 100.64.0.0/10. Excludes loopback, link-local (169.254/16,
 * IPv6), and cellular interfaces (rmnet/ccmni/...), whose carrier NAT can also use 100.64/10.
 */
internal fun selectLocalOtaAddresses(candidates: List<Pair<String, InetAddress>>): List<LocalOtaAddress> =
    candidates.mapNotNull { (interfaceName, address) ->
        if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) return@mapNotNull null
        val name = interfaceName.lowercase()
        if (CELLULAR_PREFIXES.any(name::startsWith)) return@mapNotNull null
        val octets = address.address.map { it.toInt() and 0xFF }
        val kind = when {
            octets[0] == 100 && octets[1] in 64..127 -> LocalOtaAddressKind.Tailscale
            octets[0] == 10 -> LocalOtaAddressKind.WiFi
            octets[0] == 172 && octets[1] in 16..31 -> LocalOtaAddressKind.WiFi
            octets[0] == 192 && octets[1] == 168 -> LocalOtaAddressKind.WiFi
            else -> null
        } ?: return@mapNotNull null
        LocalOtaAddress(address.hostAddress ?: return@mapNotNull null, kind)
    }
        .distinctBy { it.address }
        .sortedBy { it.kind.ordinal }

private val CELLULAR_PREFIXES = listOf("rmnet", "ccmni", "pdp", "radio", "v4-rmnet", "clat")
