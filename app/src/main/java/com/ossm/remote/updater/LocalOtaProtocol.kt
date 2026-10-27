package com.ossm.remote.updater

import android.os.Build
import java.security.SecureRandom

internal object LocalOtaProtocol {
    /** 2 = ECDH + 2-digit comparison (2.3.32). Receivers without it are 2.3.31 or older. */
    const val PROTOCOL_VERSION = 2
    const val SERVICE_TYPE = "_ossm-ota._tcp."
    const val HELLO_PATH = "/ossm-ota/hello"
    const val CONFIRM_PATH = "/ossm-ota/confirm"
    const val UPLOAD_PATH = "/ossm-ota/upload"
    const val LEGACY_INFO_PATH = "/ossm-ota/info"
    const val LEGACY_UPLOAD_PATH = "/v1/apk"
    const val SESSION_HEADER = "x-ossm-session"
    const val MAC_HEADER = "x-ossm-mac"
    const val SOCKET_BACKLOG = 4
    const val SOCKET_TIMEOUT_MS = 90_000
    const val DISCOVERY_TIMEOUT_MS = 15_000L

    /** Oui/Non must be answered within 60 s; then the upload must start within 2 min. */
    const val CONFIRM_TIMEOUT_MS = 60_000L
    const val UPLOAD_WINDOW_MS = 120_000L

    /** Fixed port tried first so a bare IP works in the manual path; falls back to ephemeral. */
    const val PREFERRED_PORT = 47_474

    const val TXT_PACKAGE = "package"
    const val TXT_VERSION_CODE = "vc"
    const val TXT_VERSION_NAME = "vn"
    const val TXT_DEVICE = "dev"
    const val TXT_INSTANCE = "inst"
    const val TXT_PROTOCOL = "proto"
    const val TXT_PUBLIC_KEY = "pk"

    /** Random per process: lets a phone ignore its own receiver during discovery. */
    val INSTANCE_ID: String by lazy {
        val bytes = ByteArray(8).also(SecureRandom()::nextBytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    fun safeModel(): String = (Build.MODEL ?: "android").replace(Regex("[^A-Za-z0-9_-]"), "_").take(16)
}
