package com.ossm.remote.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMotifTest {
    private val motif = VoiceMotif(20, 60, 10, 50, 40)

    @Test
    fun `depth and cadence rise linearly then keep the final level`() {
        assertEquals(VoiceMotifLevels(20f, 10f), VoiceMotifMath.levelsAt(motif, 0))
        assertEquals(VoiceMotifLevels(40f, 30f), VoiceMotifMath.levelsAt(motif, 20_000))
        assertEquals(VoiceMotifLevels(60f, 50f), VoiceMotifMath.levelsAt(motif, 40_000))
        assertEquals(VoiceMotifLevels(60f, 50f), VoiceMotifMath.levelsAt(motif, 400_000))
    }

    @Test
    fun `strict bounds`() {
        assertTrue(VoiceMotifValidator.isValid(motif))
        assertFalse(VoiceMotifValidator.isValid(motif.copy(depthEnd = 101)))
        assertFalse(VoiceMotifValidator.isValid(motif.copy(depthStart = -1)))
        assertFalse(VoiceMotifValidator.isValid(motif.copy(speedStart = 0)))
        assertFalse(VoiceMotifValidator.isValid(motif.copy(speedEnd = 101)))
        assertFalse(VoiceMotifValidator.isValid(motif.copy(rampSeconds = 0)))
        assertFalse(VoiceMotifValidator.isValid(motif.copy(rampSeconds = 301)))
    }

    @Test
    fun `modulations start from the current levels`() {
        // Half-way: depth 40, speed 30, 20 s left.
        assertEquals(VoiceMotif(40, 60, 45, 65, 20), VoiceMotifMath.modulate(motif, 20_000, VoiceModulation.FASTER))
        assertEquals(VoiceMotif(40, 60, 15, 35, 20), VoiceMotifMath.modulate(motif, 20_000, VoiceModulation.SLOWER))
        assertEquals(VoiceMotif(50, 70, 30, 50, 20), VoiceMotifMath.modulate(motif, 20_000, VoiceModulation.DEEPER))
        assertEquals(VoiceMotif(30, 50, 30, 50, 20), VoiceMotifMath.modulate(motif, 20_000, VoiceModulation.SHALLOWER))
        assertEquals(VoiceMotif(40, 40, 30, 30, 1), VoiceMotifMath.modulate(motif, 20_000, VoiceModulation.HOLD))
        // After the ramp: steady, bounded.
        assertEquals(VoiceMotif(60, 60, 50, 50, 1), VoiceMotifMath.modulate(motif, 60_000, VoiceModulation.HOLD))
        val fast = VoiceMotif(90, 95, 95, 100, 1)
        val faster = VoiceMotifMath.modulate(fast, 5_000, VoiceModulation.FASTER)
        assertEquals(100, faster.speedStart)
        assertTrue(VoiceMotifValidator.isValid(VoiceMotifMath.modulate(fast, 5_000, VoiceModulation.DEEPER)))
        assertTrue(VoiceMotifValidator.isValid(VoiceMotifMath.modulate(VoiceMotif(0, 0, 1, 1, 1), 5_000, VoiceModulation.SLOWER)))
    }

    @Test
    fun `parser accepts the motif and modulations strictly`() {
        val json = """{"motif":"va-et-vient","profondeur_debut":20,"profondeur_fin":60,"vitesse_debut":10,"vitesse_fin":50,"montee_s":40}"""
        assertEquals(VoiceLlmParse.Motif(motif), VoiceLlmJsonParser.parse(json))
        assertEquals(VoiceInterpretResult.Motif(motif), VoiceLlmJsonParser.toInterpretResult("```json\n$json\n```"))
        assertEquals(
            VoiceInterpretResult.Modulate(VoiceModulation.FASTER),
            VoiceLlmJsonParser.toInterpretResult("""{"modulation":"plus_vite"}"""),
        )
        assertEquals(
            VoiceInterpretResult.Modulate(VoiceModulation.HOLD),
            VoiceLlmJsonParser.toInterpretResult("""{"modulation":"garder"}"""),
        )
        listOf(
            """{"motif":"cercle","profondeur_debut":20,"profondeur_fin":60,"vitesse_debut":10,"vitesse_fin":50,"montee_s":40}""",
            """{"motif":"va-et-vient","profondeur_debut":20.5,"profondeur_fin":60,"vitesse_debut":10,"vitesse_fin":50,"montee_s":40}""",
            """{"motif":"va-et-vient","profondeur_debut":20,"profondeur_fin":60,"vitesse_debut":10,"vitesse_fin":50}""",
            """{"motif":"va-et-vient","profondeur_debut":20,"profondeur_fin":60,"vitesse_debut":10,"vitesse_fin":50,"montee_s":40,"x":1}""",
            """{"modulation":"plus_fort"}""",
            """{"modulation":3}""",
        ).forEach { raw ->
            assertEquals(raw, VoiceInterpretResult.NotUnderstood, VoiceLlmJsonParser.toInterpretResult(raw))
        }
        // Well-formed but out of bounds: parsed, then refused by the validator in the store.
        val outOfBounds = VoiceLlmJsonParser.parse(json.replace("\"montee_s\":40", "\"montee_s\":900"))
        assertFalse(VoiceMotifValidator.isValid((outOfBounds as VoiceLlmParse.Motif).motif))
    }

    @Test
    fun `few-shot covers the continuous rhythm phrases`() {
        val phrases = VoiceLlmPrompt.FEW_SHOTS.map { it.first }
        listOf("commence lentement", "commence doucement et accélère petit à petit", "va plus vite", "plus profond", "garde ce rythme")
            .forEach { assertTrue(it, it in phrases) }
        // None of the rhythm phrases may be swallowed by the STOP keyword before the LLM.
        VoiceLlmPrompt.FEW_SHOTS.filter { it.second.contains("motif") || it.second.contains("modulation") }
            .forEach { (phrase, _) -> assertFalse(phrase, VoiceStopKeywords.isStop(phrase)) }
    }

    @Test
    fun `commence lentement gives a valid motif, also in its robust variants`() {
        val shots = VoiceLlmPrompt.FEW_SHOTS.toMap()
        for (phrase in listOf("commence lentement", "commence lentement s'il te plaît", "Commence lentement.", "vas-y doucement", "commence")) {
            val parsed = VoiceLlmJsonParser.parse(shots.getValue(phrase))
            assertTrue(phrase, parsed is VoiceLlmParse.Motif)
            assertTrue(phrase, VoiceMotifValidator.isValid((parsed as VoiceLlmParse.Motif).motif))
            assertFalse(phrase, VoiceStopKeywords.isStop(phrase))
        }
        val result = VoiceLlmJsonParser.toInterpretResult(shots.getValue("commence lentement"))
        assertTrue(result.toString(), result is VoiceInterpretResult.Motif)
    }
}
