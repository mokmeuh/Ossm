package com.ossm.remote.updater

import java.net.URI

private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

/** Canonical `scheme://host[:port]/` of the PC server, or null when the text is not usable. */
internal fun normalizePcServerUrl(value: String): String? {
    var text = value.trim()
    if (text.isEmpty()) return null
    if (!text.contains("://")) text = "http://$text"
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
    if (uri.port !in -1..65535) return null
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "$scheme://$host$port/"
}

/** Manifest of this app's own package; the server picks the highest versionCode in its folder. */
internal fun pcManifestUrl(serverUrl: String, packageName: String): String? {
    val base = normalizePcServerUrl(serverUrl) ?: return null
    if (!PACKAGE_NAME.matches(packageName)) return null
    return "${base}p/$packageName/update.json"
}

/**
 * Whether the listening loop should download [manifestVersionCode]. A version already handled
 * (refused by the checks, failed, or already offered) is not fetched again until a newer one
 * shows up, so a bad file cannot make the phone re-download it every poll.
 */
internal fun shouldDownloadPcUpdate(
    manifestVersionCode: Int,
    installedVersionCode: Int,
    handledVersionCode: Int?,
): Boolean = manifestVersionCode > installedVersionCode && manifestVersionCode != handledVersionCode
