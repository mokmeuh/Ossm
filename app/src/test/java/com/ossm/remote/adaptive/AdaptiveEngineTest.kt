package com.ossm.remote.adaptive

import com.ossm.remote.data.repository.AdaptiveLearning
import com.ossm.remote.data.repository.AdaptiveSettings
import com.ossm.remote.model.KnownAdaptivePattern
import com.ossm.remote.model.KnownAutoRandomPattern
import com.ossm.remote.model.KnownProgressivePattern
import com.ossm.remote.model.KnownStreamingPattern
import com.ossm.remote.model.KnownStrokeEnginePatterns
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.PatternControlMode
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveEngineTest {

    // ---- Progression ----

    @Test
    fun `progression starts at zero`() {
        assertEquals(0, AdaptiveEngine.computeProgressionPercent(0, 0, 0, 0))
    }

    @Test
    fun `progression caps at 100`() {
        assertEquals(100, AdaptiveEngine.computeProgressionPercent(100, 100_000_000_000L, 100, 100))
        assertEquals(100, AdaptiveEngine.computeProgressionPercent(7, 0, 0, 0))
    }

    @Test
    fun `progression unlocks around four to six sessions`() {
        assertEquals(90, AdaptiveEngine.computeProgressionPercent(4, 0, 3, 0))
        assertEquals(100, AdaptiveEngine.computeProgressionPercent(5, 0, 3, 0))
        assertEquals(90, AdaptiveEngine.computeProgressionPercent(6, 0, 0, 0))
        assertEquals(100, AdaptiveEngine.computeProgressionPercent(7, 0, 0, 0))
    }

    @Test
    fun `progression grows with every counter`() {
        assertEquals(15, AdaptiveEngine.computeProgressionPercent(1, 0, 0, 0))
        assertEquals(25, AdaptiveEngine.computeProgressionPercent(1, 3_600_000L, 0, 0))
        assertEquals(25, AdaptiveEngine.computeProgressionPercent(1, 0, 1, 0))
        assertEquals(20, AdaptiveEngine.computeProgressionPercent(0, 0, 0, 4))
        assertEquals(20, AdaptiveEngine.computeProgressionPercent(0, 0, 0, 5))
    }

    // ---- Déblocage persistant ----

    @Test
    fun `unlock is persistent and never re-locks`() {
        assertFalse(AdaptiveEngine.isUnlocked(90, persistedUnlocked = false))
        assertTrue(AdaptiveEngine.isUnlocked(100, persistedUnlocked = false))
        assertTrue(AdaptiveEngine.isUnlocked(50, persistedUnlocked = true))
        assertTrue(AdaptiveEngine.isUnlocked(100, persistedUnlocked = true))
    }

    // ---- Confiance ----

    @Test
    fun `confidence grows with sessions and days`() {
        assertEquals(0, AdaptiveEngine.computeConfidencePercent(0, 0, emptyMap()))
        assertEquals(100, AdaptiveEngine.computeConfidencePercent(15, 10, mapOf("deeper" to 10)))
        val low = AdaptiveEngine.computeConfidencePercent(3, 2, mapOf("deeper" to 3, "insist" to 2))
        val high = AdaptiveEngine.computeConfidencePercent(15, 10, mapOf("deeper" to 10, "insist" to 2))
        assertTrue(low < high)
    }

    @Test
    fun `confidence plateaus beyond fifteen sessions`() {
        assertEquals(
            AdaptiveEngine.computeConfidencePercent(15, 10, mapOf("deeper" to 1)),
            AdaptiveEngine.computeConfidencePercent(50, 10, mapOf("deeper" to 1))
        )
    }

    @Test
    fun `preference consistency is full for one pattern and zero for an even spread`() {
        assertEquals(100, AdaptiveEngine.preferenceConsistencyPercent(mapOf("deeper" to 5)))
        assertEquals(0, AdaptiveEngine.preferenceConsistencyPercent(mapOf("a" to 1, "b" to 1, "c" to 1)))
        assertEquals(0, AdaptiveEngine.preferenceConsistencyPercent(emptyMap()))
    }

    // ---- Décision ----

    @Test
    fun `next pattern is always one of the 7 firmware patterns`() {
        val learning = AdaptiveLearning()
        val settings = AdaptiveSettings()
        repeat(200) { seed ->
            val pattern = AdaptiveEngine.nextPattern(null, 0.5f, learning, settings, Random(seed))
            assertTrue("id ${pattern.id} hors 0-6", pattern.id in 0..6)
            assertTrue(KnownStrokeEnginePatterns.any { it.key == pattern.key })
        }
    }

    @Test
    fun `next pattern never immediately repeats`() {
        val learning = AdaptiveLearning()
        val settings = AdaptiveSettings()
        repeat(200) { seed ->
            val last = KnownStrokeEnginePatterns[seed % 7].key
            val pattern = AdaptiveEngine.nextPattern(last, 0.5f, learning, settings, Random(seed))
            assertNotEquals(last, pattern.key)
        }
    }

    @Test
    fun `preference bias weights the favorite higher`() {
        val weights = AdaptiveEngine.weightPatterns(
            KnownStrokeEnginePatterns,
            intensity = 0.5f,
            favoriteKey = "deeper",
            usePreference = true,
            diversity = 0f
        )
        val favoriteWeight = weights.first { it.first.key == "deeper" }.second
        val otherWeight = weights.first { it.first.key == "simpleStroke" }.second
        assertTrue(favoriteWeight > otherWeight)
    }

    @Test
    fun `intense patterns are weighted higher at high intensity and gentle at low`() {
        val highWeights = AdaptiveEngine.weightPatterns(KnownStrokeEnginePatterns, 1f, null, false, 0.5f)
        assertTrue(
            highWeights.first { it.first.key == "deeper" }.second >
                highWeights.first { it.first.key == "stopNGo" }.second
        )
        val lowWeights = AdaptiveEngine.weightPatterns(KnownStrokeEnginePatterns, 0f, null, false, 0.5f)
        assertTrue(
            lowWeights.first { it.first.key == "stopNGo" }.second >
                lowWeights.first { it.first.key == "deeper" }.second
        )
    }

    @Test
    fun `decision keeps speed and depth inside the user bounds`() {
        repeat(100) { seed ->
            val decision = AdaptiveEngine.decide(
                lastPatternKey = "deeper",
                intensity = 0.7f,
                speedCeiling = 0.8f,
                depthMin = 0.2f,
                depthMax = 0.8f,
                learning = AdaptiveLearning(),
                settings = AdaptiveSettings(),
                random = Random(seed)
            )
            assertTrue(decision.speed in 0.05f..0.8f)
            assertTrue(decision.depthMin >= 0.2f - 1e-4f)
            assertTrue(decision.depthMax <= 0.8f + 1e-4f)
            assertTrue(decision.depthMax - decision.depthMin >= 0.05f - 1e-4f)
            assertTrue(decision.pattern.id in 0..6)
        }
    }

    // ---- Détection firmware ----

    @Test
    fun `seven known patterns report standard firmware`() {
        assertEquals(AdaptiveFirmwareLevel.STANDARD, AdaptiveEngine.detectFirmwareLevel(KnownStrokeEnginePatterns))
    }

    @Test
    fun `an extra stroke engine pattern reports advanced firmware`() {
        val extra = KnownStrokeEnginePatterns + OssmPattern("mystery", "Mystery", PatternControlMode.STROKE_ENGINE, id = 7)
        assertEquals(AdaptiveFirmwareLevel.ADVANCED, AdaptiveEngine.detectFirmwareLevel(extra))
    }

    @Test
    fun `app-only patterns do not change the firmware level`() {
        val withAppModes = KnownStrokeEnginePatterns +
            listOf(KnownAdaptivePattern, KnownAutoRandomPattern, KnownProgressivePattern, KnownStreamingPattern)
        assertEquals(AdaptiveFirmwareLevel.STANDARD, AdaptiveEngine.detectFirmwareLevel(withAppModes))
    }

    // ---- Transitions (rampe + garde) ----

    @Test
    fun `ramp moves monotonically to the target`() {
        val values = AdaptiveEngine.rampValues(0.1f, 0.6f, 0.05f)
        assertEquals(0.6f, values.last(), 1e-4f)
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `ramp steps never exceed the guard max step`() {
        val values = AdaptiveEngine.rampValues(0f, 1f, 0.1f)
        val maxDelta = values.zipWithNext().maxOf { (a, b) -> abs(b - a) }
        assertTrue(maxDelta <= 0.1f + 1e-4f)
    }

    @Test
    fun `ramp with no delta yields no steps`() {
        assertTrue(AdaptiveEngine.rampValues(0.5f, 0.5f, 0.1f).isEmpty())
    }

    @Test
    fun `ramp always ends exactly on the target`() {
        repeat(20) { seed ->
            val to = seed / 20f
            val values = AdaptiveEngine.rampValues(0f, to, 0.05f)
            if (to == 0f) {
                assertTrue(values.isEmpty())
            } else {
                assertEquals(to, values.last(), 1e-4f)
            }
        }
    }
}
