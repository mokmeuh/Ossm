package com.ossm.remote.natural

/** High-level motifs accepted from a local or optional AI planner. */
enum class NaturalMotif(val wireName: String) {
    APPROACH_HOLD_RELEASE("approach_hold_release"),
    TEASE_PAUSE("tease_pause"),
    WAVE("wave"),
    LADDER("ladder"),
    REST("rest");

    companion object {
        fun fromWire(value: String): NaturalMotif? = entries.firstOrNull { it.wireName == value }
    }
}

/** Bounded style controls. These are not motor values and never cross the BLE boundary. */
data class NaturalStyle(
    val intensity: Float,
    val tempo: Float,
    val variation: Float,
    val rest: Float,
) {
    init {
        require(intensity.isFinite() && intensity in 0f..1f)
        require(tempo.isFinite() && tempo in 0f..1f)
        require(variation.isFinite() && variation in 0f..1f)
        require(rest.isFinite() && rest in 0f..1f)
    }
}

data class NaturalPhrase(
    val motif: NaturalMotif,
    val style: NaturalStyle,
)

/** Strict, high-level plan. Positions and durations are intentionally absent. */
data class NaturalPlan(
    val schemaVersion: Int,
    val phrases: List<NaturalPhrase>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION)
        require(phrases.size in 1..MAX_PHRASES)
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_PHRASES = 6
    }
}

/** Phone-owned limits captured for one phrase-generation epoch. */
data class NaturalConstraints(
    val depthMinPercent: Int,
    val depthMaxPercent: Int,
    val speedMaxPercent: Int,
    val seed: Long,
    val minimumTargets: Int = 3,
    val maximumTargets: Int = 7,
) {
    init {
        require(depthMinPercent in 0..100)
        require(depthMaxPercent in 0..100)
        require(depthMaxPercent - depthMinPercent >= MINIMUM_DEPTH_SPAN_PERCENT)
        require(speedMaxPercent in 1..100)
        require(minimumTargets in 3..MAX_TARGETS)
        require(maximumTargets in minimumTargets..MAX_TARGETS)
    }

    companion object {
        const val MINIMUM_DEPTH_SPAN_PERCENT = 5
        const val MAX_TARGETS = 7
    }
}

/** Generated target data. This is still not an [OssmCommand] or a BLE payload. */
data class NaturalTarget(
    val positionPercent: Int,
    val durationMs: Int,
    val holdMs: Long,
) {
    init {
        require(positionPercent in 0..100)
        require(durationMs in MIN_DURATION_MS..MAX_DURATION_MS)
        require(holdMs in 0L..MAX_HOLD_MS)
    }

    companion object {
        const val MIN_DURATION_MS = 20
        const val MAX_DURATION_MS = 2_000
        const val MAX_HOLD_MS = 4_000L
    }
}

data class NaturalPlanInput(
    val constraints: NaturalConstraints,
    val phraseCount: Int = 2,
    /** Optional pacing snapshot, only forwarded to the PC reasoning relay journal. */
    val pacing: NaturalAiPacingInfo? = null,
) {
    init {
        require(phraseCount in 1..NaturalPlan.MAX_PHRASES)
    }
}

