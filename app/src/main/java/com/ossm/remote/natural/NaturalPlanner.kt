package com.ossm.remote.natural

import kotlinx.coroutines.CancellationException

interface NaturalPlanner {
    suspend fun plan(input: NaturalPlanInput): NaturalPlan
}

/** Always-available planner. All work is pure; the caller chooses a background dispatcher. */
class LocalNaturalPlanner : NaturalPlanner {
    override suspend fun plan(input: NaturalPlanInput): NaturalPlan =
        localNaturalPlan(input.constraints.seed, input.phraseCount)
}

/** Optional planner wrapper: AI failure falls back locally, cancellation propagates. */
class FallbackNaturalPlanner(
    private val primary: NaturalPlanner,
    private val fallback: NaturalPlanner,
) : NaturalPlanner {
    override suspend fun plan(input: NaturalPlanInput): NaturalPlan = try {
        primary.plan(input)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fallback.plan(input)
    }
}

/** Pure seeded local plan; also used directly by the phone-side [NaturalModeEngine]. */
internal fun localNaturalPlan(seed: Long, phraseCount: Int): NaturalPlan {
    require(phraseCount in 1..NaturalPlan.MAX_PHRASES)
    val random = kotlin.random.Random(seed)
    val motifs = NaturalMotif.entries.toList().shuffled(random)
    val phrases = (0 until phraseCount).map { index ->
        NaturalPhrase(
            motif = motifs[index % motifs.size],
            style = NaturalStyle(
                intensity = (0.28f + random.nextFloat() * 0.62f).coerceIn(0f, 1f),
                tempo = random.nextFloat(),
                variation = (0.35f + random.nextFloat() * 0.65f).coerceIn(0f, 1f),
                rest = random.nextFloat() * 0.7f,
            ),
        )
    }
    return NaturalPlan(NaturalPlan.SCHEMA_VERSION, phrases)
}

