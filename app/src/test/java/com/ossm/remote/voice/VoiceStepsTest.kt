package com.ossm.remote.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStepsTest {

    private val example = listOf(
        VoiceStep(targetPercent = 100, moveMs = 2_500, holdMs = 2_000),
        VoiceStep(targetPercent = 0, moveMs = 600, holdMs = 0),
    )

    @Test
    fun `valid plan is accepted unchanged`() {
        assertEquals(example, VoiceStepValidator.validate(example))
    }

    @Test
    fun `strict bounds reject the whole plan`() {
        val bad = listOf(
            VoiceStep(101, 1_000, 0),
            VoiceStep(-1, 1_000, 0),
            VoiceStep(50, 199, 0),
            VoiceStep(50, 10_001, 0),
            VoiceStep(50, 1_000, -1),
            VoiceStep(50, 1_000, 10_001),
        )
        bad.forEach { step ->
            assertNull("$step", VoiceStepValidator.validate(listOf(VoiceStep(10, 500, 0), step)))
        }
        assertNull(VoiceStepValidator.validate(emptyList()))
        assertNull(VoiceStepValidator.validate(List(13) { VoiceStep(50, 500, 0) }))
        assertEquals(12, VoiceStepValidator.validate(List(12) { VoiceStep(50, 500, 0) })?.size)
    }

    @Test
    fun `boundary values are accepted`() {
        val edge = listOf(VoiceStep(0, 200, 0), VoiceStep(100, 10_000, 10_000))
        assertEquals(edge, VoiceStepValidator.validate(edge))
    }

    @Test
    fun `speed maximum lengthens moves that would be too fast`() {
        // 10 % maximum -> 40 %/s -> 100 points need at least 2.5 s.
        val rate = VoicePlanner.maxRateFor(0.1f)
        val bounded = VoicePlanner.bound(example, startPercent = 0, maxRatePercentPerSecond = rate)
        assertEquals(2_500, bounded[0].moveMs)
        assertEquals(2_500, bounded[1].moveMs)
        assertEquals(2_000, bounded[0].holdMs)
        // Default maximum (50 % -> 200 %/s) keeps the requested durations.
        val normal = VoicePlanner.bound(example, 0, VoicePlanner.maxRateFor(0.5f))
        assertEquals(listOf(2_500, 600), normal.map { it.moveMs })
    }

    @Test
    fun `step at the current position only holds`() {
        val bounded = VoicePlanner.bound(listOf(VoiceStep(40, 800, 1_000)), 40, 200f)
        assertEquals(0, bounded.single().moveMs)
        assertEquals(1_000, bounded.single().holdMs)
    }

    @Test
    fun `default interpreter is not installed and fake is scriptable`() = runBlocking {
        assertFalse(NotInstalledVoiceCommandInterpreter.isInstalled)
        assertEquals(
            VoiceInterpretResult.NotUnderstood,
            NotInstalledVoiceCommandInterpreter.interpret(VoiceInput.Text("va au fond")),
        )
        val fake = FakeVoiceCommandInterpreter(VoiceInterpretResult.Stop)
        assertTrue(fake.isInstalled)
        assertEquals(VoiceInterpretResult.Stop, fake.interpret(VoiceInput.Audio(ShortArray(160))))
        assertEquals(1, fake.inputs.size)
    }
}
