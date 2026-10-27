package com.ossm.remote.natural

/** Snapshot sent with a plan request (relay journal only) and shown in the app. */
data class NaturalAiPacingInfo(
    val reserveSeconds: Int,
    val lastLatencySeconds: Int?,
    val thresholdSeconds: Int,
)

/** Why an AI-fed Naturel run handed over to the local generator (shown and logged). */
enum class NaturalAiFallbackKind { RESERVE_EXHAUSTED, AI_ERROR, MODEL_UNLOADED }

data class NaturalAiFallback(
    val kind: NaturalAiFallbackKind,
    val problem: NaturalAiProblem? = null,
    val latencySeconds: Int = 0,
    val reserveSeconds: Int = 0,
) {
    /** French text for the NATURAL_AI journal (no URL, no content). */
    fun logText(model: String): String = "IA defaillante -> generateur local (" + when (kind) {
        NaturalAiFallbackKind.RESERVE_EXHAUSTED ->
            "reserve epuisee : latence $latencySeconds s > reserve $reserveSeconds s"
        NaturalAiFallbackKind.AI_ERROR -> problem?.logText(model) ?: "erreur"
        NaturalAiFallbackKind.MODEL_UNLOADED -> "modele decharge"
    } + ")"
}

/**
 * Adaptive look-ahead for the optional AI planner. The next plan is requested as soon as the
 * queued gesture time (reserve) drops below the target `3 x measured latency + 20 s`, the latency being
 * the max of the recent successful requests (a slow reasoning answer raises it at once, a
 * single fast one does not lower it too early). One request at a time is the caller's job;
 * the local generator keeps moving whenever the AI is late.
 */
class NaturalAiPacing(private val defaultLatencyMs: Long = DEFAULT_LATENCY_MS) {
    private val samples = ArrayDeque<Long>()

    var lastLatencyMs: Long? = null
        private set

    /** Latency used for the threshold: max of the recent window, default before any sample. */
    val latencyEstimateMs: Long
        get() = samples.maxOrNull() ?: defaultLatencyMs

    val thresholdMs: Long
        get() = (3L * latencyEstimateMs + MARGIN_MS).coerceIn(MIN_THRESHOLD_MS, MAX_THRESHOLD_MS)

    fun record(latencyMs: Long) {
        val value = latencyMs.coerceAtLeast(0L)
        lastLatencyMs = value
        samples.addLast(value)
        while (samples.size > WINDOW) samples.removeFirst()
    }

    fun shouldRequest(reserveMs: Long): Boolean = reserveMs < thresholdMs

    /**
     * Phrases per AI plan: longer plans through the reasoning relay, and more phrases when the
     * measured latency grows (about one phrase per 6 s of latency), within the strict parser bound.
     */
    fun phrasesPerPlan(reasoningRelay: Boolean): Int {
        val base = if (reasoningRelay) REASONING_PHRASES_PER_PLAN else PHRASES_PER_PLAN
        val byLatency = ((latencyEstimateMs + PHRASE_PER_LATENCY_MS - 1) / PHRASE_PER_LATENCY_MS).toInt()
        return maxOf(base, byLatency).coerceIn(1, NaturalPlan.MAX_PHRASES)
    }

    fun info(reserveMs: Long): NaturalAiPacingInfo = NaturalAiPacingInfo(
        reserveSeconds = (reserveMs.coerceAtLeast(0L) / 1_000L).toInt(),
        lastLatencySeconds = lastLatencyMs?.let { ((it + 500L) / 1_000L).toInt() },
        thresholdSeconds = (thresholdMs / 1_000L).toInt(),
    )

    companion object {
        const val MARGIN_MS = 20_000L
        const val WINDOW = 4
        const val MIN_THRESHOLD_MS = 30_000L
        const val MAX_THRESHOLD_MS = 300_000L
        const val DEFAULT_LATENCY_MS = 15_000L
        const val DEFAULT_REASONING_LATENCY_MS = 60_000L

        /** Phrases per AI plan: longer plans through the reasoning relay amortize its latency. */
        const val PHRASES_PER_PLAN = 2
        const val REASONING_PHRASES_PER_PLAN = NaturalPlan.MAX_PHRASES
        const val PHRASE_PER_LATENCY_MS = 6_000L

        fun forEndpoint(reasoningRelay: Boolean): NaturalAiPacing =
            NaturalAiPacing(if (reasoningRelay) DEFAULT_REASONING_LATENCY_MS else DEFAULT_LATENCY_MS)

    }
}
