package com.ossm.remote.natural

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalPlanJsonParserTest {
    private val parser = NaturalPlanJsonParser()

    @Test
    fun `valid high level plan parses into immutable motifs`() {
        val plan = parser.parse(validPlanJson())

        assertEquals(NaturalPlan.SCHEMA_VERSION, plan.schemaVersion)
        assertEquals(2, plan.phrases.size)
        assertEquals(NaturalMotif.WAVE, plan.phrases[0].motif)
        assertEquals(0.6f, plan.phrases[0].style.intensity)
    }

    @Test
    fun `extra root and phrase fields are rejected`() {
        assertRejects(validPlanJson().replace("\"phrases\"", "\"unexpected\":true,\"phrases\""))
        assertRejects(validPlanJson().replace("\"rest\":0.1", "\"rest\":0.1,\"positionPercent\":50"))
        assertRejects(validPlanJson().replace("\"rest\":0.1", "\"rest\":0.1,\"durationMs\":300"))
    }

    @Test
    fun `unknown motifs and out of range styles are rejected`() {
        assertRejects(validPlanJson().replace("\"wave\"", "\"unknown\""))
        assertRejects(validPlanJson().replace("\"intensity\":0.6", "\"intensity\":1.1"))
        assertRejects(validPlanJson().replace("\"tempo\":0.3", "\"tempo\":NaN"))
    }

    @Test
    fun `missing fields and malformed JSON are rejected`() {
        assertRejects(validPlanJson().replace(",\"rest\":0.1", ""))
        assertRejects("{not-json}")
        assertRejects("[]")
    }

    @Test
    fun `schema advertises only bounded high level fields`() {
        val schema = NaturalPlanJsonSchema.create().toString()

        assertTrue(schema.contains("approach_hold_release"))
        assertTrue(schema.contains("additionalProperties"))
        assertTrue(schema.contains("maxItems"))
        assertTrue(!schema.contains("positionPercent"))
        assertTrue(!schema.contains("durationMs"))
        assertTrue(!schema.contains("ble"))
    }

    private fun assertRejects(json: String) {
        try {
            parser.parse(json)
        } catch (_: NaturalPlanParseException) {
            return
        }
        throw AssertionError("Expected the Naturel plan to be rejected")
    }

    private fun validPlanJson(): String = """
        {
          "schemaVersion": 1,
          "phrases": [
            {"motif":"wave","intensity":0.6,"tempo":0.3,"variation":0.7,"rest":0.1},
            {"motif":"approach_hold_release","intensity":0.5,"tempo":0.4,"variation":0.2,"rest":0.8}
          ]
        }
    """.trimIndent()
}
