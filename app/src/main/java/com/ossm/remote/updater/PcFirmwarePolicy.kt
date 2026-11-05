package com.ossm.remote.updater

/** Firmware image announced by the PC server (`/firmware/update.json`). */
data class PcFirmwareManifest(
    val sha256: String,
    val size: Int,
    val modifiedMs: Long,
    val label: String,
)

private val SHA256 = Regex("[0-9a-f]{64}")
private val STRING_FIELD = Regex("\"(sha256|label)\"\\s*:\\s*\"([^\"]*)\"")
private val NUMBER_FIELD = Regex("\"(size|modifiedMs)\"\\s*:\\s*(\\d+)")

/** Largest image the machine's application slot accepts (same bound as the USB flasher). */
internal const val PC_FIRMWARE_MAX_BYTES = 0x1E0000

/** Strict reading of the manifest: any missing or odd field means "no usable firmware". */
internal fun parsePcFirmwareManifest(json: String): PcFirmwareManifest? {
    val strings = STRING_FIELD.findAll(json).associate { it.groupValues[1] to it.groupValues[2] }
    val numbers = NUMBER_FIELD.findAll(json).associate { it.groupValues[1] to it.groupValues[2] }
    val sha256 = strings["sha256"]?.lowercase()?.takeIf { SHA256.matches(it) } ?: return null
    val size = numbers["size"]?.toIntOrNull()?.takeIf { it in 1..PC_FIRMWARE_MAX_BYTES } ?: return null
    val modifiedMs = numbers["modifiedMs"]?.toLongOrNull() ?: 0L
    val label = strings["label"]?.takeIf { it.isNotBlank() } ?: "firmware.bin"
    return PcFirmwareManifest(sha256, size, modifiedMs, label)
}

internal fun pcFirmwareManifestUrl(serverUrl: String): String? =
    normalizePcServerUrl(serverUrl)?.let { "${it}firmware/update.json" }

/** Download is by digest only, never by a file name. */
internal fun pcFirmwareDownloadUrl(serverUrl: String, sha256: String): String? {
    if (!SHA256.matches(sha256)) return null
    return normalizePcServerUrl(serverUrl)?.let { "${it}firmware/$sha256.bin" }
}

/**
 * Offer the PC image unless it is exactly the one last flashed from this phone. The app cannot
 * read the firmware version running on the machine, so "last flashed" is the only reference.
 */
internal fun shouldOfferPcFirmware(manifestSha256: String, lastFlashedSha256: String?): Boolean =
    !manifestSha256.equals(lastFlashedSha256.orEmpty(), ignoreCase = true)
