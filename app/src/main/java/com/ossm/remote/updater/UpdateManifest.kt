package com.ossm.remote.updater

import java.net.URI

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String
)

object UpdateManifestParser {
    fun parse(json: String, manifestUrl: String): UpdateManifest? {
        val normalizedManifestUrl = normalizeManifestUrl(manifestUrl) ?: return null
        val manifestUri = URI(normalizedManifestUrl)
        val values = VALUE_REGEX.findAll(json).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }
        val versionCode = values["versionCode"]?.toIntOrNull() ?: return null
        val versionName = values["versionName"]?.takeIf(String::isNotBlank) ?: return null
        val apkPath = values["apkPath"]?.takeIf(String::isNotBlank) ?: return null
        val sha256 = values["sha256"]?.lowercase()?.takeIf { SHA256_REGEX.matches(it) } ?: return null
        val apkPathUri = runCatching { URI(apkPath) }.getOrNull() ?: return null
        if (apkPathUri.scheme != null || apkPathUri.authority != null ||
            apkPathUri.rawPath?.startsWith('/') != true || apkPathUri.rawQuery != null ||
            apkPathUri.rawFragment != null
        ) return null
        val apkUrl = manifestUri.resolve(apkPathUri).toString()
        return UpdateManifest(versionCode, versionName, apkUrl, sha256)
    }

    fun isNewer(manifest: UpdateManifest, installedVersionCode: Int): Boolean =
        manifest.versionCode > installedVersionCode

    private val VALUE_REGEX = Regex("\\\"(versionCode|versionName|apkPath|sha256)\\\"\\s*:\\s*\\\"?([^,}\\\"]+)\\\"?")
    private val SHA256_REGEX = Regex("[0-9a-f]{64}")
}

internal fun normalizeManifestUrl(value: String): String? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val isHttp = uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)
    if (!uri.isAbsolute || !isHttp || uri.host.isNullOrBlank() || uri.port !in -1..65535) return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    return normalized
}
