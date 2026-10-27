package com.ossm.remote.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceRuleParserTest {
    private fun s(target: Int, move: Int = VoiceRuleParser.MOVE_NORMAL, hold: Int = 0) = VoiceStep(target, move, hold)

    private fun steps(phrase: String, context: VoiceRuleContext? = null): List<VoiceStep> {
        val result = VoiceRuleParser.parse(phrase, context)
        assertNotNull("rules should understand: $phrase", result)
        return (result as VoiceInterpretResult.Steps).steps
    }

    private fun assertSteps(phrase: String, vararg expected: VoiceStep, context: VoiceRuleContext? = null) =
        assertEquals(phrase, expected.toList(), steps(phrase, context))

    private fun assertMotif(phrase: String, expected: VoiceMotif) =
        assertEquals(phrase, VoiceInterpretResult.Motif(expected), VoiceRuleParser.parse(phrase))

    private fun assertModulation(phrase: String, expected: VoiceModulation, context: VoiceRuleContext? = null) =
        assertEquals(phrase, VoiceInterpretResult.Modulate(expected), VoiceRuleParser.parse(phrase, context))

    private fun assertGemma(phrase: String, context: VoiceRuleContext? = null) =
        assertNull("should fall back to Gemma: $phrase", VoiceRuleParser.parse(phrase, context))

    // ---- positions
    @Test fun `au fond`() = assertSteps("va au fond", s(100))
    @Test fun `a fond`() = assertSteps("À fond !", s(100))
    @Test fun `tout au fond`() = assertSteps("descends tout au fond", s(100))
    @Test fun `a moitie`() = assertSteps("va à moitié", s(50))
    @Test fun `au milieu`() = assertSteps("Place-toi au milieu.", s(50))
    @Test fun `a mi-chemin`() = assertSteps("va à mi-chemin", s(50))
    @Test fun `pourcentage en chiffres`() = assertSteps("va à 30 %", s(30))
    @Test fun `pour cent en lettres`() = assertSteps("va à soixante-quinze pour cent", s(75))
    @Test fun `a N sans unite`() = assertSteps("va à 40", s(40))
    @Test fun `trois quarts`() = assertSteps("va aux trois quarts et attends 5 secondes", s(75, hold = 5_000))
    @Test fun `reviens`() = assertSteps("reviens", s(0))
    @Test fun `ressors`() = assertSteps("ressors doucement", s(0, VoiceRuleParser.MOVE_SLOW))
    @Test fun `remonte au debut`() = assertSteps("remonte au début", s(0))
    @Test fun `ressors a mi-chemin`() = assertSteps("ressors à mi-chemin", s(50))

    // ---- speeds
    @Test fun `tres lentement`() = assertSteps("très lentement jusqu'au fond", s(100, VoiceRuleParser.MOVE_VERY_SLOW))
    @Test fun `lentement`() = assertSteps("va au fond lentement", s(100, VoiceRuleParser.MOVE_SLOW))
    @Test fun `doucement`() = assertSteps("reviens doucement au début", s(0, VoiceRuleParser.MOVE_SLOW))
    @Test fun `normalement`() = assertSteps("va au fond normalement", s(100, VoiceRuleParser.MOVE_NORMAL))
    @Test fun `vite`() = assertSteps("reviens vite", s(0, VoiceRuleParser.MOVE_FAST))
    @Test fun `rapidement`() = assertSteps("va à la moitié rapidement", s(50, VoiceRuleParser.MOVE_FAST))
    @Test fun `tres vite`() = assertSteps("va au fond très vite", s(100, VoiceRuleParser.MOVE_VERY_FAST))
    @Test fun `d un coup`() = assertSteps("va au fond d'un coup", s(100, VoiceRuleParser.MOVE_SUDDEN))
    @Test fun `en une seconde`() = assertSteps("descends à la moitié en une seconde", s(50, 1_000))
    @Test fun `vitesse seule qualifie le mouvement precedent`() = assertSteps("va au fond, lentement", s(100, VoiceRuleParser.MOVE_SLOW))

    // ---- holds and chains
    @Test fun `exemple complet du prompt`() = assertSteps(
        "va au fond lentement et reste-y 2 secondes, puis reviens vite",
        s(100, VoiceRuleParser.MOVE_SLOW, 2_000), s(0, VoiceRuleParser.MOVE_FAST),
    )
    @Test fun `tiens puis ressors`() = assertSteps(
        "très lentement jusqu'au fond, tiens 3 secondes, puis ressors à mi-chemin",
        s(100, VoiceRuleParser.MOVE_VERY_SLOW, 3_000), s(50),
    )
    @Test fun `quelques secondes`() = assertSteps("va au fond et attends quelques secondes", s(100, hold = VoiceRuleParser.FEW_SECONDS_MS))
    @Test fun `une seconde et demie`() = assertSteps("va au fond, reste une seconde et demie", s(100, hold = 1_500))
    @Test fun `deux secondes et demie`() = assertSteps("va au fond et reste deux secondes et demie", s(100, hold = 2_500))
    @Test fun `decimale`() = assertSteps("va au fond et reste 1,5 seconde", s(100, hold = 1_500))
    @Test fun `demi seconde`() = assertSteps("va au fond et attends une demi-seconde", s(100, hold = 500))
    @Test fun `ensuite`() = assertSteps("va au milieu ensuite au fond", s(50), s(100))
    @Test fun `et puis`() = assertSteps("va au fond et puis reviens", s(100), s(0))
    @Test fun `politesse ignoree`() = assertSteps("Va au fond s'il te plaît.", s(100))
    @Test fun `nombre en lettres vingt et un`() = assertSteps("va à vingt et un pour cent", s(21))
    @Test fun `nombre en lettres quatre-vingt-dix`() = assertSteps("va à quatre-vingt-dix pour cent", s(90))
    @Test fun `cent pour cent`() = assertSteps("va à cent pour cent", s(100))

    // ---- repetitions
    @Test fun `allers-retours rapides`() {
        val trip = listOf(s(100, VoiceRuleParser.MOVE_FAST), s(0, VoiceRuleParser.MOVE_FAST))
        assertEquals(trip + trip + trip, steps("fais trois allers-retours rapides"))
    }
    @Test fun `allers-retours lents avec maintien au fond`() {
        val trip = listOf(s(100, VoiceRuleParser.MOVE_SLOW, 1_000), s(0, VoiceRuleParser.MOVE_SLOW))
        assertEquals(trip + trip, steps("deux allers-retours lents en restant une seconde au fond"))
    }
    @Test fun `N fois`() {
        val base = listOf(s(100), s(0))
        assertEquals(base + base + base, steps("va au fond et reviens, trois fois"))
    }
    @Test fun `encore dans la phrase`() {
        val base = listOf(s(100), s(0))
        assertEquals(base + base, steps("va au fond, reviens, encore"))
    }
    @Test fun `encore seul rejoue le dernier plan`() {
        val last = listOf(s(80, 2_500, 1_000), s(0, 600))
        assertSteps("encore", *last.toTypedArray(), context = VoiceRuleContext(lastSteps = last))
        assertGemma("encore", VoiceRuleContext())
    }
    @Test fun `encore deux fois`() {
        val last = listOf(s(100), s(0))
        assertEquals(last + last, steps("encore deux fois", VoiceRuleContext(lastSteps = last)))
    }

    // ---- relative
    @Test fun `un peu plus sans motif`() =
        assertSteps("un peu plus", s(55), context = VoiceRuleContext(positionPercent = 40))
    @Test fun `un peu moins sans motif`() =
        assertSteps("un peu moins", s(25), context = VoiceRuleContext(positionPercent = 40))
    @Test fun `un peu plus pendant un motif`() =
        assertModulation("un peu plus", VoiceModulation.DEEPER, VoiceRuleContext(motifRunning = true))

    // ---- motifs (values of the Gemma few-shots)
    @Test fun `commence lentement`() = assertMotif("commence lentement", VoiceRuleParser.MOTIF_SLOW)
    @Test fun `commence doucement poli`() = assertMotif("Commence doucement, s'il te plaît.", VoiceRuleParser.MOTIF_SLOW)
    @Test fun `demarre tout doucement`() = assertMotif("démarre tout doucement", VoiceRuleParser.MOTIF_VERY_SLOW)
    @Test fun `vas-y`() = assertMotif("vas-y", VoiceRuleParser.MOTIF_DEFAULT)
    @Test fun `vas-y doucement`() = assertMotif("vas-y doucement", VoiceRuleParser.MOTIF_SLOW)
    @Test fun `commence`() = assertMotif("Commence.", VoiceRuleParser.MOTIF_DEFAULT)
    @Test fun `commence et accelere`() =
        assertMotif("commence doucement et accélère petit à petit", VoiceRuleParser.MOTIF_RAMP)
    @Test fun `motif values match the Gemma few-shots`() {
        for ((phrase, json) in VoiceLlmPrompt.FEW_SHOTS) {
            val expected = VoiceLlmJsonParser.toInterpretResult(json)
            val ours = VoiceRuleParser.parse(phrase) ?: continue
            assertEquals(phrase, expected, ours)
        }
    }

    // ---- modulations
    @Test fun `plus vite`() = assertModulation("va plus vite", VoiceModulation.FASTER)
    @Test fun `un peu plus vite`() = assertModulation("un peu plus vite", VoiceModulation.FASTER)
    @Test fun `accelere`() = assertModulation("Accélère !", VoiceModulation.FASTER)
    @Test fun `plus lentement`() = assertModulation("plus lentement", VoiceModulation.SLOWER)
    @Test fun `ralentis`() = assertModulation("ralentis", VoiceModulation.SLOWER)
    @Test fun `plus profond`() = assertModulation("plus profond", VoiceModulation.DEEPER)
    @Test fun `va plus loin`() = assertModulation("va plus loin", VoiceModulation.DEEPER)
    @Test fun `moins profond`() = assertModulation("moins profond", VoiceModulation.SHALLOWER)
    @Test fun `garde ce rythme`() = assertModulation("garde ce rythme", VoiceModulation.HOLD)
    @Test fun `n augmente plus`() = assertModulation("n'augmente plus", VoiceModulation.HOLD)

    // ---- uncertain: Gemma decides
    @Test fun `phrase inconnue`() = assertGemma("quel temps fait-il demain")
    @Test fun `mot inconnu dans une consigne`() = assertGemma("va au fond comme une fusée")
    @Test fun `maintien sans mouvement`() = assertGemma("attends 2 secondes")
    @Test fun `nombre sans unite`() = assertGemma("va au fond 3")
    @Test fun `deux vitesses`() = assertGemma("va au fond lentement vite")
    @Test fun `pourcentage hors bornes`() = assertGemma("va à 150 %")
    @Test fun `un peu plus sans contexte`() = assertGemma("un peu plus")
    @Test fun `vide`() = assertGemma(" , . ")

    @Test fun `rules never produce out of bound targets`() {
        val phrases = listOf(
            "va au fond", "reviens", "va à 30 %", "un peu plus", "fais trois allers-retours rapides",
            "va au fond, reste une seconde et demie",
        )
        for (phrase in phrases) {
            val result = VoiceRuleParser.parse(phrase, VoiceRuleContext(positionPercent = 95)) as VoiceInterpretResult.Steps
            assertNotNull(phrase, VoiceStepValidator.validate(result.steps))
        }
    }

    @Test fun `numbers in words`() {
        fun n(text: String) = VoiceRuleParser.number(VoiceRuleParser.tokenize(text), 0)?.first?.toInt()
        assertEquals(17, n("dix-sept"))
        assertEquals(42, n("quarante-deux"))
        assertEquals(71, n("soixante-onze"))
        assertEquals(80, n("quatre-vingts"))
        assertEquals(99, n("quatre-vingt-dix-neuf"))
        assertEquals(100, n("cent"))
    }
}
