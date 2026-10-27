package com.ossm.remote.natural

import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * User-entered address of the optional Ollama planner on the user's own PC. There is no
 * built-in endpoint: an empty field means "local only". Only private, loopback, Tailscale
 * (100.64.0.0/10, *.ts.net) or LAN-style names are accepted, never a public host.
 */
data class NaturalAiEndpoint(val url: HttpUrl, val reasoningRelay: Boolean = false) {
    /**
     * "Passer par le relais de raisonnement (PC)": same host, relay port 11435, plans with
     * think:true. Off: the address is used unchanged (11434 by default, think:false).
     */
    fun withReasoningRelay(enabled: Boolean): NaturalAiEndpoint =
        if (enabled) NaturalAiEndpoint(url.newBuilder().port(REASONING_RELAY_PORT).build(), true) else this

    companion object {
        const val DEFAULT_OLLAMA_PORT = 11434
        const val REASONING_RELAY_PORT = 11435

        fun parse(raw: String): NaturalAiEndpoint? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > 200) return null
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            val parsed = withScheme.toHttpUrlOrNull() ?: return null
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
            if (!isPrivateHost(parsed.host)) return null
            val authority = withScheme.substringAfter("://")
                .substringBefore('/').substringBefore('?').substringBefore('#')
            val explicitPort = Regex(":\\d+$").containsMatchIn(authority)
            val url = parsed.newBuilder()
                .apply { if (!explicitPort && parsed.scheme == "http") port(DEFAULT_OLLAMA_PORT) }
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
            return NaturalAiEndpoint(url)
        }

        internal fun isPrivateHost(host: String): Boolean {
            val lower = host.lowercase().trimEnd('.')
            if (lower == "localhost" || lower.endsWith(".localhost")) return true
            parseIpv4(lower)?.let { return isPrivateIpv4(it) }
            if (lower.contains(':')) return isPrivateIpv6(lower)
            if (!lower.contains('.')) return lower.isNotEmpty() // single-label LAN/MagicDNS name
            return PRIVATE_SUFFIXES.any { lower.endsWith(it) }
        }

        private val PRIVATE_SUFFIXES = listOf(".ts.net", ".local", ".lan", ".home.arpa", ".internal")

        private fun parseIpv4(host: String): IntArray? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val values = parts.map { part ->
                if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
                part.toInt().takeIf { it in 0..255 } ?: return null
            }
            return values.toIntArray()
        }

        private fun isPrivateIpv4(ip: IntArray): Boolean {
            val (a, b) = ip[0] to ip[1]
            return a == 10 ||
                a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127) // CGNAT range used by Tailscale
        }

        private fun isPrivateIpv6(host: String): Boolean {
            val address = host.removePrefix("[").removeSuffix("]")
            if (address == "::1") return true
            val first = address.substringBefore(':').ifEmpty { return false }
            val value = first.toIntOrNull(16) ?: return false
            return (value and 0xfe00) == 0xfc00 || // unique local fc00::/7 (Tailscale uses fd7a:)
                (value and 0xffc0) == 0xfe80 // link-local
        }
    }
}

/** Builds the optional planner for one endpoint; one shared client, 300 s cold-load budget. */
object NaturalAiPlannerFactory {
    const val COLD_LOAD_TIMEOUT_MS = 300_000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(COLD_LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(COLD_LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    fun create(endpoint: NaturalAiEndpoint): NaturalPlanner = OllamaNaturalPlanner(
        transport = OkHttpOllamaTransport(
            client = client,
            endpoint = endpoint.url,
            callTimeoutMillis = COLD_LOAD_TIMEOUT_MS,
        ),
        // qwen3:8b, keep_alive 15m; think:false + small budget, or think:true through the relay.
        config = plannerConfigFor(endpoint),
    )

    fun plannerConfigFor(endpoint: NaturalAiEndpoint): OllamaNaturalPlannerConfig =
        if (endpoint.reasoningRelay) OllamaNaturalPlannerConfig.reasoning() else OllamaNaturalPlannerConfig()

    /** Model pre-loader sharing the same client (300 s load budget). */
    fun createLoader(endpoint: NaturalAiEndpoint): OllamaModelLoader = OllamaModelLoader(
        transport = OkHttpOllamaTransport(
            client = client,
            endpoint = endpoint.url,
            callTimeoutMillis = COLD_LOAD_TIMEOUT_MS,
        ),
        model = OllamaNaturalPlannerConfig.DEFAULT_MODEL,
        keepAlive = OllamaNaturalPlannerConfig().keepAlive,
    )
}
