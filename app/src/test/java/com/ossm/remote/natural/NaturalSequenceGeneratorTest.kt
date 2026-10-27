package com.ossm.remote.natural

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalSequenceGeneratorTest {
    private val generator = NaturalSequenceGenerator()
    private val constraints = NaturalConstraints(
        depthMinPercent = 20,
        depthMaxPercent = 80,
        speedMaxPercent = 35,
        seed = 42L,
    )
    private val phrase = NaturalPhrase(
        motif = NaturalMotif.WAVE,
        style = NaturalStyle(
            intensity = 0.9f,
            tempo = 0.6f,
            variation = 0.85f,
            rest = 0.95f,
        ),
    )

    @Test
    fun `same seed and phrase produce the same sequence`() {
        // Given the same phone limits and phrase epoch
        val first = generator.generate(constraints, phrase, phraseIndex = 0)

        // When the phrase is expanded again
        val second = generator.generate(constraints, phrase, phraseIndex = 0)

        // Then no hidden wall-clock state affects the targets
        assertEquals(first, second)
    }

    @Test
    fun `targets stay inside limits and include bounded holds`() {
        val targets = generator.generate(constraints, phrase, phraseIndex = 0)

        assertTrue(targets.size in constraints.minimumTargets..constraints.maximumTargets)
        assertTrue(targets.any { it.holdMs > 0L })
        assertTrue(targets.all { it.positionPercent in 20..80 })
        assertTrue(targets.all { it.durationMs in 20..2_000 })
        assertTrue(targets.all { it.holdMs in 0L..4_000L })

        var previous = (constraints.depthMinPercent + constraints.depthMaxPercent) / 2
        targets.forEach { target ->
            val distance = abs(target.positionPercent - previous)
            assertTrue(distance * 1_000 <= target.durationMs * constraints.speedMaxPercent)
            previous = target.positionPercent
        }
    }

    @Test
    fun `seed and phrase changes vary the generated path`() {
        val first = generator.generate(constraints, phrase, phraseIndex = 0)
        val second = generator.generate(constraints.copy(seed = 43L), phrase, phraseIndex = 0)
        val third = generator.generate(constraints, phrase.copy(motif = NaturalMotif.LADDER), phraseIndex = 1)

        assertNotEquals(first, second)
        assertNotEquals(first, third)
    }

    @Test
    fun `coherent motifs include both advance and return directions`() {
        val paths = (0L..12L).flatMap { seed ->
            generator.generate(constraints.copy(seed = seed), phrase, phraseIndex = 0)
        }
        val directions = paths.zipWithNext().map { (from, to) -> to.positionPercent - from.positionPercent }

        assertTrue(directions.any { it > 0 })
        assertTrue(directions.any { it < 0 })
    }

    @Test
    fun `next phrase starts from the prior target within the low speed envelope`() {
        val lowSpeed = constraints.copy(speedMaxPercent = 1, seed = 901L)
        val first = generator.generate(lowSpeed, phrase, phraseIndex = 0, initialPositionPercent = 50)
        val second = generator.generate(
            lowSpeed,
            phrase,
            phraseIndex = 1,
            initialPositionPercent = first.last().positionPercent,
        )
        val actualBoundaryDelta = abs(second.first().positionPercent - first.last().positionPercent)

        assertTrue(actualBoundaryDelta * 1_000 <= second.first().durationMs * lowSpeed.speedMaxPercent)
        assertTrue(second.first().positionPercent in lowSpeed.depthMinPercent..lowSpeed.depthMaxPercent)
    }

    @Test
    fun `rest motif holds the current logical position`() {
        val rest = phrase.copy(motif = NaturalMotif.REST)
        val targets = generator.generate(constraints, rest, phraseIndex = 0, initialPositionPercent = 63)

        assertTrue(targets.isNotEmpty())
        assertTrue(targets.all { it.positionPercent == 63 })
        assertTrue(targets.any { it.holdMs > 0L })
    }
}
