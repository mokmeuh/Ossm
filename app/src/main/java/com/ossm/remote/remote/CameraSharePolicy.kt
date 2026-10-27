package com.ossm.remote.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Pure rules for the in-app camera share WebView (no Android types, unit-tested on the JVM).
 *
 * The WebView only ever shows the broker's camera page: HTTPS, the exact broker origin
 * (scheme + host + port) of `BuildConfig.REMOTE_BROKER_BASE_URL`, and only camera/microphone
 * capture once the matching Android runtime permission is granted. Everything else is refused.
 */
internal object CameraSharePolicy {
    /** Values of `android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE` / `RESOURCE_AUDIO_CAPTURE`. */
    const val RESOURCE_VIDEO_CAPTURE = "android.webkit.resource.VIDEO_CAPTURE"
    const val RESOURCE_AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE"

    private fun httpsOrigin(value: String?): HttpUrl? =
        value?.toHttpUrlOrNull()?.takeIf { it.scheme == "https" && it.host.isNotEmpty() }

    /** True when [url] is HTTPS and has exactly the broker's origin. */
    fun isBrokerOrigin(url: String?, brokerBaseUrl: String): Boolean {
        val broker = httpsOrigin(brokerBaseUrl) ?: return false
        val candidate = httpsOrigin(url) ?: return false
        return candidate.host == broker.host && candidate.port == broker.port
    }

    /** The camera page may be loaded only from the broker origin and only as `/s/{id}?role=camera#k=...`. */
    fun isLoadableCameraUrl(url: String?, brokerBaseUrl: String): Boolean = cameraUrlProblem(url, brokerBaseUrl) == null

    /**
     * Why [url] is not loadable, as a short NON-sensitive French reason (never the URL, never
     * the key), or null when it is loadable. Used for the camera diagnostics journal.
     */
    fun cameraUrlProblem(url: String?, brokerBaseUrl: String): String? {
        if (url.isNullOrBlank()) return "lien caméra absent"
        if (httpsOrigin(brokerBaseUrl) == null) return "adresse du serveur de l'app invalide"
        val parsed = url.toHttpUrlOrNull() ?: return "lien caméra illisible"
        if (parsed.scheme != "https") return "lien caméra non HTTPS"
        if (!isBrokerOrigin(url, brokerBaseUrl)) return "origine du lien différente du serveur de l'app"
        if (parsed.pathSegments.size != 2 || parsed.pathSegments[0] != "s" || parsed.pathSegments[1].isEmpty()) {
            return "chemin du lien inattendu (attendu /s/{id})"
        }
        if (parsed.queryParameter("role") != "camera") return "paramètre role=camera absent"
        if (parsed.fragment?.startsWith("k=") != true || parsed.fragment == "k=") return "clé de fragment absente du lien"
        return null
    }

    /** `shouldOverrideUrlLoading`: true = block. Anything outside the HTTPS broker origin is blocked. */
    fun shouldBlockNavigation(url: String?, brokerBaseUrl: String): Boolean = !isBrokerOrigin(url, brokerBaseUrl)

    /**
     * Resources to grant for a WebView `PermissionRequest` from [origin]. Empty means deny.
     * Only video/audio capture, only for the broker origin, and each only if the matching Android
     * runtime permission ([cameraGranted] / [microphoneGranted]) has already been granted.
     */
    fun grantableResources(
        origin: String?,
        requested: Array<String>,
        brokerBaseUrl: String,
        cameraGranted: Boolean,
        microphoneGranted: Boolean,
    ): Array<String> {
        if (!isBrokerOrigin(origin, brokerBaseUrl)) return emptyArray()
        return requested.filter { resource ->
            when (resource) {
                RESOURCE_VIDEO_CAPTURE -> cameraGranted
                RESOURCE_AUDIO_CAPTURE -> microphoneGranted
                else -> false
            }
        }.distinct().toTypedArray()
    }
}
