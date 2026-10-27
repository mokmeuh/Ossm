package com.ossm.remote.updater

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

internal const val LOCAL_OTA_APK_MIME_TYPE = "application/vnd.android.package-archive"
/**
 * Upper bound of an uploaded APK. 2.3.25..2.3.31 used 64 MiB while the debug APK is
 * ~77 MB since the on-device voice models (2.3.27): the receiver answered 400
 * "Bad request" to every upload. Keep a wide margin.
 */
internal const val LOCAL_OTA_MAX_APK_BYTES = 256L * 1024L * 1024L

/** Pre-2.3.32 limit, kept for the regression test of the 400. */
internal const val LOCAL_OTA_LEGACY_MAX_APK_BYTES = 64L * 1024L * 1024L

/** Handshake messages (hello/confirm) are tiny key=value bodies. */
internal const val LOCAL_OTA_MAX_MESSAGE_BYTES = 4L * 1024L

internal data class LocalOtaHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val contentLength: Long,
)

internal fun readLocalOtaHttpRequest(
    input: BufferedInputStream,
    maxBodyBytes: Long = LOCAL_OTA_MAX_APK_BYTES,
): LocalOtaHttpRequest? {
    val requestLine = readAsciiLine(input) ?: return null
    val parts = requestLine.split(' ', limit = 3)
    if (parts.size != 3) return null
    val headers = linkedMapOf<String, String>()
    while (true) {
        val line = readAsciiLine(input) ?: return null
        if (line.isEmpty()) break
        val separator = line.indexOf(':')
        if (separator <= 0) return null
        headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
    }
    val declaredLength = headers["content-length"]
    val contentLength = when {
        declaredLength != null -> declaredLength.toLongOrNull() ?: return null
        parts[0] == "GET" -> 0L
        else -> return null
    }
    if (contentLength < 0L || contentLength > maxBodyBytes) return null
    return LocalOtaHttpRequest(parts[0], parts[1].substringBefore('?'), headers, contentLength)
}

internal fun copyLocalOtaBody(
    input: BufferedInputStream,
    destination: File,
    contentLength: Long,
): Boolean {
    var remaining = contentLength
    FileOutputStream(destination).use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) return false
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
    return true
}

internal fun writeLocalOtaResponse(
    output: BufferedOutputStream,
    statusCode: Int,
    body: String,
) {
    val bodyBytes = body.toByteArray(Charsets.UTF_8)
    val reason = when (statusCode) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        422 -> "Unprocessable Entity"
        426 -> "Upgrade Required"
        429 -> "Too Many Requests"
        else -> "Internal Server Error"
    }
    output.write(
        "HTTP/1.1 $statusCode $reason\r\n".toByteArray(Charsets.US_ASCII)
    )
    output.write("Content-Type: text/plain; charset=utf-8\r\n".toByteArray(Charsets.US_ASCII))
    output.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray(Charsets.US_ASCII))
    output.write("Cache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
    output.write(bodyBytes)
    output.flush()
}

/** Reads a small body fully (handshake messages). Null when truncated or too large. */
internal fun readLocalOtaSmallBody(input: BufferedInputStream, contentLength: Long): String? {
    if (contentLength > LOCAL_OTA_MAX_MESSAGE_BYTES) return null
    val bytes = ByteArray(contentLength.toInt())
    var offset = 0
    while (offset < bytes.size) {
        val read = input.read(bytes, offset, bytes.size - offset)
        if (read < 0) return null
        offset += read
    }
    return bytes.toString(Charsets.UTF_8)
}

/** `key=value` lines, the format of every handshake message. */
internal fun formatLocalOtaFields(vararg fields: Pair<String, Any?>): String = buildString {
    fields.forEach { (key, value) ->
        if (value != null) append(key).append('=').append(sanitizeLocalOtaValue(value.toString())).append('\n')
    }
}

internal fun parseLocalOtaFields(body: String): Map<String, String> = body.lineSequence()
    .take(32)
    .mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
    }
    .toMap()

internal fun sanitizeLocalOtaValue(value: String): String = value.replace(Regex("[\r\n=]"), " ").take(160)

/**
 * After an early error response the peer may still be sending its body: half-close and
 * drain briefly so the client reads our status instead of a TCP reset.
 */
internal fun lingerLocalOtaSocket(socket: java.net.Socket, input: BufferedInputStream, maxMillis: Long = 2_000L) {
    runCatching {
        socket.shutdownOutput()
        socket.soTimeout = 250
        val deadline = System.currentTimeMillis() + maxMillis
        val buffer = ByteArray(16 * 1024)
        while (System.currentTimeMillis() < deadline) {
            val read = try {
                input.read(buffer)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            }
            if (read < 0) break
        }
    }
}

private fun readAsciiLine(input: BufferedInputStream): String? {
    val bytes = ArrayList<Byte>(128)
    while (bytes.size < MAX_HEADER_LINE_BYTES) {
        val value = input.read()
        if (value == -1) return null
        if (value == '\n'.code) return bytes.toByteArray().toString(Charsets.US_ASCII)
        if (value != '\r'.code) bytes += value.toByte()
    }
    return null
}

private const val MAX_HEADER_LINE_BYTES = 2_048
