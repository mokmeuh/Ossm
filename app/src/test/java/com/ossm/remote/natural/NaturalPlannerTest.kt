package com.ossm.remote.natural

import com.google.gson.JsonParser
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalPlannerTest {
    private val input = NaturalPlanInput(
        constraints = NaturalConstraints(20, 80, 35, seed = 7L),
        phraseCount = 2,
    )

    @Test
    fun `planner failure falls back to local plan`() = runBlocking {
        val fallback = LocalNaturalPlanner()
        val primary = object : NaturalPlanner {
            override suspend fun plan(input: NaturalPlanInput): NaturalPlan {
                error("simulated unavailable planner")
            }
        }

        val result = FallbackNaturalPlanner(primary, fallback).plan(input)

        assertEquals(fallback.plan(input), result)
    }

    @Test
    fun `cancellation propagates and never invokes fallback`() = runBlocking {
        val fallbackCalls = AtomicInteger(0)
        val primary = object : NaturalPlanner {
            override suspend fun plan(input: NaturalPlanInput): NaturalPlan {
                throw CancellationException("STOP")
            }
        }
        val fallback = object : NaturalPlanner {
            override suspend fun plan(input: NaturalPlanInput): NaturalPlan {
                fallbackCalls.incrementAndGet()
                return LocalNaturalPlanner().plan(input)
            }
        }

        try {
            FallbackNaturalPlanner(primary, fallback).plan(input)
        } catch (_: CancellationException) {
            // Expected: STOP must cancel the optional request, not create a new plan.
        }
        assertEquals(0, fallbackCalls.get())
    }

    @Test
    fun `ollama request is high level and uses the selected model contract`() = runBlocking {
        val transport = RecordingTransport()
        val result = OllamaNaturalPlanner(transport).plan(input)
        val request = JsonParser.parseString(transport.lastBody).asJsonObject

        assertEquals("/api/tags", transport.paths[0])
        assertEquals("/api/generate", transport.paths[1])
        assertEquals("qwen3:8b", request["model"].asString)
        assertFalse(request["stream"].asBoolean)
        assertFalse(request["think"].asBoolean)
        assertEquals("15m", request["keep_alive"].asString)
        assertEquals(2_048, request["options"].asJsonObject["num_ctx"].asInt)
        assertTrue(request["format"].isJsonObject)
        assertEquals(1, result.phrases.size)
        assertFalse(transport.lastBody.contains("positionPercent"))
        assertFalse(transport.lastBody.contains("durationMs"))
        assertFalse(transport.lastBody.contains("\"command\""))
    }

    @Test
    fun `missing ollama model falls back without producing raw targets`() = runBlocking {
        val transport = object : RecordingTransport() {
            override suspend fun get(path: String): String = "{\"models\":[{\"name\":\"other:tag\"}]}"
        }
        val planner = FallbackNaturalPlanner(
            primary = OllamaNaturalPlanner(transport),
            fallback = LocalNaturalPlanner(),
        )

        val result = planner.plan(input)

        assertEquals(input.phraseCount, result.phrases.size)
    }

    @Test
    fun `incomplete outer response falls back before nested parsing`() = runBlocking {
        val transport = RecordingTransport().apply { responseDone = false }
        val planner = FallbackNaturalPlanner(
            primary = OllamaNaturalPlanner(transport),
            fallback = LocalNaturalPlanner(),
        )

        val result = planner.plan(input)

        assertEquals(input.phraseCount, result.phrases.size)
    }

    private open class RecordingTransport : OllamaTransport {
        val paths = mutableListOf<String>()
        var lastBody: String = ""
        var responseModel: String = "qwen3:8b"
        var responseDone: Boolean = true

        open override suspend fun get(path: String): String {
            paths += path
            return "{\"models\":[{\"name\":\"qwen3:8b\"}]}"
        }

        open override suspend fun post(path: String, body: String): String {
            paths += path
            lastBody = body
            return """
                {"model":"$responseModel","done":$responseDone,"response":"{\"schemaVersion\":1,\"phrases\":[{\"motif\":\"wave\",\"intensity\":0.6,\"tempo\":0.3,\"variation\":0.7,\"rest\":0.1}]}"}
            """.trimIndent()
        }
    }
}
