package com.ossm.remote.remote

import com.ossm.remote.model.LogLevel

/**
 * Pure, JVM-tested helpers for the camera share diagnostics (2.3.16).
 *
 * Every line produced here is shown on the camera screen and written to the app Diagnostics
 * journal (tag [TAG]). None may contain a link or a capability key: [sanitize] removes any URL,
 * any `k=` fragment value and any long opaque token, whatever the caller passes in.
 */
internal object CameraShareDiagnostics {
    const val TAG = "CAMERA"
    private const val MAX_LENGTH = 180

    private val urlPattern = Regex("""(?i)\b[a-z][a-z0-9+.\-]*://\S*""")
    private val keyPattern = Regex("""(?i)(#|\b)k=[^\s&#]*""")
    private val opaqueTokenPattern = Regex("""[A-Za-z0-9_\-]{20,}""")
    private val whitespace = Regex("""\s+""")

    /** Remove links, `k=` values and long opaque tokens; collapse whitespace; cap the length. */
    fun sanitize(message: String): String {
        var text = urlPattern.replace(message, "<lien>")
        text = keyPattern.replace(text, "<clé>")
        // Opaque random tokens (keys, ids) mix case or carry digits; constant names such as
        // net::ERR_NAME_NOT_RESOLVED are single-case without digits and stay readable.
        text = opaqueTokenPattern.replace(text) { match ->
            val token = match.value
            val looksRandom = token.any(Char::isDigit) ||
                (token.any(Char::isLowerCase) && token.any(Char::isUpperCase))
            if (looksRandom) "<masqué>" else token
        }
        text = whitespace.replace(text, " ").trim()
        return if (text.length > MAX_LENGTH) text.take(MAX_LENGTH - 1) + "…" else text
    }

    private fun yesNo(value: Boolean) = if (value) "oui" else "non"

    /** Shape of the link actually handed to `loadUrl`, without the link itself. */
    fun linkCheck(url: String?, brokerBaseUrl: String): String {
        val problem = CameraSharePolicy.cameraUrlProblem(url, brokerBaseUrl)
        return if (problem == null) {
            "lien caméra valide (HTTPS, origine du serveur, /s/{id}, role=camera, clé de fragment présente)"
        } else {
            "lien caméra refusé : $problem"
        }
    }

    fun webViewSettings(javaScript: Boolean, domStorage: Boolean, mediaNeedsGesture: Boolean): String =
        "réglages WebView : JavaScript=${yesNo(javaScript)}, stockage DOM=${yesNo(domStorage)}, " +
            "média sans geste=${yesNo(!mediaNeedsGesture)}"

    fun webViewPackage(packageName: String?, versionName: String?): String =
        if (packageName == null) "moteur WebView : inconnu" else "moteur WebView : $packageName ${versionName ?: "?"}"

    fun androidPermissions(camera: Boolean, microphone: Boolean): String =
        "permissions Android : caméra=${if (camera) "accordée" else "refusée"}, micro=${if (microphone) "accordé" else "refusé"}"

    fun pageStarted(): String = "page caméra : chargement…"

    /** [fragmentKept]: the WebView's current URL still carries a `k=` fragment (never printed). */
    fun pageFinished(fragmentKept: Boolean): String =
        "page caméra chargée (clé de fragment conservée=${yesNo(fragmentKept)})"

    fun loadError(errorCode: Int, description: CharSequence?, mainFrame: Boolean): String =
        "erreur de chargement ${if (mainFrame) "de la page" else "d'une ressource"} : code $errorCode" +
            (description?.toString()?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")

    fun httpError(statusCode: Int, mainFrame: Boolean): String =
        "réponse HTTP $statusCode ${if (mainFrame) "pour la page" else "pour une ressource"}"

    fun sslError(primaryError: Int): String = "erreur de certificat TLS (code $primaryError) : chargement annulé"

    fun navigationBlocked(): String = "navigation hors du serveur bloquée"

    private fun resourceName(resource: String): String = when (resource) {
        CameraSharePolicy.RESOURCE_VIDEO_CAPTURE -> "caméra"
        CameraSharePolicy.RESOURCE_AUDIO_CAPTURE -> "micro"
        else -> resource.substringAfterLast('.').lowercase()
    }

    /** WebView `onPermissionRequest` outcome: requested vs granted resources. */
    fun webPermission(originAllowed: Boolean, requested: Array<String>, granted: Array<String>): String {
        val asked = requested.joinToString(", ") { resourceName(it) }.ifEmpty { "aucune" }
        return when {
            !originAllowed -> "permission WebView refusée (origine non autorisée) pour : $asked"
            granted.isEmpty() -> "permission WebView refusée pour : $asked (permission Android manquante ?)"
            granted.size < requested.size ->
                "permission WebView partielle : accordée=${granted.joinToString(", ") { resourceName(it) }} ; demandée=$asked"
            else -> "permission WebView accordée : $asked"
        }
    }

    fun webPermissionCanceled(): String = "demande de permission WebView annulée par la page"

    fun renderProcessGone(didCrash: Boolean): String =
        if (didCrash) {
            "moteur de rendu WebView planté : partage arrêté"
        } else {
            "moteur de rendu WebView arrêté par le système (mémoire) : partage arrêté"
        }

    fun console(level: String, message: String): String = "console $level : $message"

    /** Severity used in the Diagnostics journal for a given console level name. */
    fun consoleLevel(level: String): LogLevel = when (level.uppercase()) {
        "ERROR" -> LogLevel.ERROR
        "WARNING" -> LogLevel.WARNING
        else -> LogLevel.INFO
    }
}

/** One line of the short, non-sensitive camera journal. */
internal data class CameraJournalEntry(val level: LogLevel, val message: String)

/**
 * Short rolling journal shown on the camera screen; every entry is sanitized and mirrored to the
 * app Diagnostics journal through [sink]. [entries] is any mutable list (a Compose snapshot list
 * on device, a plain list in tests).
 */
internal class CameraShareJournal(
    private val entries: MutableList<CameraJournalEntry>,
    private val sink: (LogLevel, String) -> Unit,
    private val capacity: Int = 14,
) {
    fun record(level: LogLevel, message: String) {
        val clean = CameraShareDiagnostics.sanitize(message)
        if (clean.isEmpty()) return
        entries.add(CameraJournalEntry(level, clean))
        while (entries.size > capacity) entries.removeAt(0)
        sink(level, clean)
    }
}
