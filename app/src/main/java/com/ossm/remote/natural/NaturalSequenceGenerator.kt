package com.ossm.remote.natural

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/** Pure seeded phrase expansion. It knows no Android, Store, BLE, or command type. */
class NaturalSequenceGenerator {
    /**
     * [initialPositionPercent] should be the prior phrase's final target when the
     * coordinator chains phrases. The midpoint default is only for standalone use.
     */
    fun generate(
        constraints: NaturalConstraints,
        phrase: NaturalPhrase,
        phraseIndex: Int,
        initialPositionPercent: Int? = null,
    ): List<NaturalTarget> {
        require(phraseIndex >= 0)
        val startingPosition = initialPositionPercent ?: (
            constraints.depthMinPercent +
                ((constraints.depthMaxPercent - constraints.depthMinPercent) * 0.5f).roundToInt()
            )
        require(startingPosition in constraints.depthMinPercent..constraints.depthMaxPercent)
        val random = Random(constraints.seed xor (phraseIndex.toLong() * SEED_MIX))
        val targetCount = random.nextInt(constraints.minimumTargets, constraints.maximumTargets + 1)
        val anchors = resample(anchorsFor(phrase.motif), targetCount)
        val amplitude = 0.22f + 0.78f * phrase.style.intensity
        var previous = startingPosition

        return anchors.mapIndexed { index, anchor ->
            val jitter = (random.nextFloat() * 2f - 1f) * 0.12f * phrase.style.variation
            val normalized = (0.5f + (anchor - 0.5f) * amplitude + jitter).coerceIn(0f, 1f)
            val desired = if (phrase.motif == NaturalMotif.REST) {
                previous
            } else {
                (constraints.depthMinPercent +
                    normalized * (constraints.depthMaxPercent - constraints.depthMinPercent)).roundToInt()
            }
            val position = boundStep(previous, desired, constraints.speedMaxPercent)
            val distance = abs(position - previous)
            val movingForward = position >= previous
            val tempoDuration = 1_500f - 1_150f * phrase.style.tempo
            val asymmetry = if (movingForward) 0.86f else 1.18f
            val durationNoise = 0.85f + random.nextFloat() * 0.3f
            val requestedDuration = (tempoDuration * asymmetry * durationNoise).roundToInt()
            val speedBound = ceil(distance * 1_000f / constraints.speedMaxPercent).toInt()
            val duration = maxOf(requestedDuration, speedBound, 60)
                .coerceIn(NaturalTarget.MIN_DURATION_MS, NaturalTarget.MAX_DURATION_MS)
            val hold = holdFor(phrase, index, targetCount, random)
            previous = position
            NaturalTarget(position, duration, hold)
        }
    }

    private fun boundStep(previous: Int, desired: Int, speedMaxPercent: Int): Int {
        val maximumSafeStep = (speedMaxPercent * NaturalTarget.MAX_DURATION_MS / 1_000)
            .coerceAtLeast(1)
        val delta = (desired - previous).coerceIn(-maximumSafeStep, maximumSafeStep)
        return (previous + delta).coerceIn(0, 100)
    }

    private fun holdFor(
        phrase: NaturalPhrase,
        index: Int,
        targetCount: Int,
        random: Random,
    ): Long {
        val motifBoost = if (phrase.motif == NaturalMotif.REST) 900L else 0L
        val edgeBoost = if (index == targetCount - 1) 180L else 0L
        val maximum = (180L + phrase.style.rest * 1_600f).roundToInt().toLong() + motifBoost + edgeBoost
        val shouldHold = phrase.motif == NaturalMotif.REST ||
            (index == targetCount - 1 && phrase.style.rest >= 0.8f)
        if (maximum <= 0L || (!shouldHold && random.nextFloat() > 0.35f + phrase.style.rest * 0.5f)) {
            return 0L
        }
        val boundedMaximum = maximum.coerceAtMost(NaturalTarget.MAX_HOLD_MS)
        val hold = random.nextLong(0L, boundedMaximum + 1L)
        return if (shouldHold) hold.coerceAtLeast(1L) else hold
    }

    private fun anchorsFor(motif: NaturalMotif): List<Float> = when (motif) {
        NaturalMotif.APPROACH_HOLD_RELEASE -> listOf(0.12f, 0.42f, 0.88f, 0.78f, 0.32f)
        NaturalMotif.TEASE_PAUSE -> listOf(0.2f, 0.36f, 0.31f, 0.76f, 0.5f)
        NaturalMotif.WAVE -> listOf(0.34f, 0.76f, 0.46f, 0.84f, 0.38f, 0.64f)
        NaturalMotif.LADDER -> listOf(0.2f, 0.36f, 0.54f, 0.7f, 0.9f, 0.58f)
        NaturalMotif.REST -> listOf(0.5f)
    }

    private fun resample(source: List<Float>, count: Int): List<Float> {
        if (source.size == count) return source
        val scale = source.lastIndex.toFloat() / (count - 1).coerceAtLeast(1)
        return (0 until count).map { index ->
            val location = index * scale
            val lower = floor(location).toInt().coerceIn(0, source.lastIndex)
            val upper = ceil(location).toInt().coerceIn(0, source.lastIndex)
            val fraction = location - lower
            source[lower] + (source[upper] - source[lower]) * fraction
        }
    }

    private companion object {
        const val SEED_MIX = -7046029254386353131L
    }
}

