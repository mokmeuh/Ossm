package com.ossm.remote.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLlmJsonParserTest {
    private val example = listOf(VoiceStep(100, 2_500, 2_000), VoiceStep(0, 600, 0))
    private val exampleJson =
        """[{"cible":100,"duree":2500,"maintien":2000},{"cible":0,"duree":600,"maintien":0}]"""

    @Test
    fun `plain array is parsed`() {
        assertEquals(VoiceLlmParse.Steps(example), VoiceLlmJsonParser.parse(exampleJson))
    }

    @Test
    fun `markdown fences and surrounding text are tolerated`() {
        assertEquals(VoiceLlmParse.Steps(example), VoiceLlmJsonParser.parse("```json\n$exampleJson\n```"))
        assertEquals(VoiceLlmParse.Steps(example), VoiceLlmJsonParser.parse("```\n$exampleJson```"))
        assertEquals(VoiceLlmParse.Steps(example), VoiceLlmJsonParser.parse("Voici : $exampleJson"))
        assertEquals(
            VoiceLlmParse.Steps(example),
            VoiceLlmJsonParser.parse("""{"etapes":$exampleJson}"""),
        )
    }

    @Test
    fun `missing hold defaults to zero and integral decimals are accepted`() {
        assertEquals(
            VoiceLlmParse.Steps(listOf(VoiceStep(50, 1_000, 0))),
            VoiceLlmJsonParser.parse("""[{"cible":50,"duree":1000.0}]"""),
        )
    }

    @Test
    fun `explicit not understood and empty plan`() {
        assertEquals(VoiceLlmParse.NotUnderstood, VoiceLlmJsonParser.parse("""{"erreur":"non compris"}"""))
        assertEquals(VoiceLlmParse.NotUnderstood, VoiceLlmJsonParser.parse("[]"))
        assertEquals(VoiceLlmParse.Stop, VoiceLlmJsonParser.parse("""{"stop":true}"""))
    }

    @Test
    fun `invalid answers are malformed and become not understood`() {
        val invalid = listOf(
            "",
            "Je vais au fond.",
            """[{"cible":100,"duree":2500,"maintien":2000}""",       // truncated
            """[{"cible":"100","duree":2500}]""",                      // string number
            """[{"cible":50.5,"duree":1000}]""",                       // not an integer
            """[{"cible":50,"duree":1000,"vitesse":3}]""",             // unknown key
            """[{"cible":50}]""",                                      // missing duration
            """[{'cible':50,'duree':1000}]""",                          // not strict JSON
            """[{"cible":50,"duree":99999999999}]""",                  // overflow
            """{"stop":false}""",
            """[1, 2]""",
        )
        for (raw in invalid) {
            assertTrue(raw, VoiceLlmJsonParser.parse(raw) is VoiceLlmParse.Malformed)
            assertEquals(raw, VoiceInterpretResult.NotUnderstood, VoiceLlmJsonParser.toInterpretResult(raw))
        }
    }

    @Test
    fun `bounds are not clamped but rejected by the strict validator`() {
        val outOfBounds = listOf(
            """[{"cible":120,"duree":1000,"maintien":0}]""",
            """[{"cible":-1,"duree":1000,"maintien":0}]""",
            """[{"cible":50,"duree":100,"maintien":0}]""",
            """[{"cible":50,"duree":1000,"maintien":20000}]""",
        )
        for (raw in outOfBounds) {
            val parsed = VoiceLlmJsonParser.parse(raw) as VoiceLlmParse.Steps
            assertNull(raw, VoiceStepValidator.validate(parsed.steps))
        }
        val thirteen = (1..13).joinToString(",", "[", "]") { """{"cible":50,"duree":500,"maintien":0}""" }
        assertNull(VoiceStepValidator.validate((VoiceLlmJsonParser.parse(thirteen) as VoiceLlmParse.Steps).steps))
        assertEquals(example, VoiceStepValidator.validate((VoiceLlmJsonParser.parse(exampleJson) as VoiceLlmParse.Steps).steps))
    }

    @Test
    fun `few-shot answers of the prompt are all valid`() {
        for ((phrase, answer) in VoiceLlmPrompt.FEW_SHOTS) {
            when (val parsed = VoiceLlmJsonParser.parse(answer)) {
                is VoiceLlmParse.Steps -> assertTrue(phrase, VoiceStepValidator.validate(parsed.steps) != null)
                VoiceLlmParse.NotUnderstood -> Unit
                is VoiceLlmParse.Motif -> assertTrue(phrase, VoiceMotifValidator.isValid(parsed.motif))
                is VoiceLlmParse.Modulation -> Unit
                else -> throw AssertionError("$phrase -> $parsed")
            }
        }
        assertEquals(
            VoiceLlmParse.Steps(example),
            VoiceLlmJsonParser.parse(VoiceLlmPrompt.FEW_SHOTS.first().second),
        )
    }
}
