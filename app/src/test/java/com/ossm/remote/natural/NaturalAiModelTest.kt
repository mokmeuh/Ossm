package com.ossm.remote.natural

import com.google.gson.JsonParser
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalAiModelTest {

    private class Recording(
        private val ps: String = "{\"models\":[]}",
        private val answer: String = "{\"model\":\"qwen3:8b\",\"done\":true,\"done_reason\":\"load\"}",
    ) : OllamaTransport {
        val paths = mutableListOf<String>()
        var body = ""
        override suspend fun get(path: String): String {
            paths += path
            return if (path == "/api/ps") ps else "{\"models\":[{\"name\":\"qwen3:8b\"}]}"
        }

        override suspend fun post(path: String, body: String): String {
            paths += path
            this.body = body
            return answer
        }
    }

    @Test
    fun `load request only loads the model, never generates`() = runBlocking {
        val transport = Recording()
        OllamaModelLoader(transport).load()
        val request = JsonParser.parseString(transport.body).asJsonObject

        assertEquals(listOf("/api/generate"), transport.paths)
        assertEquals("qwen3:8b", request["model"].asString)
        assertEquals("", request["prompt"].asString)
        assertEquals("15m", request["keep_alive"].asString)
        assertFalse(request["stream"].asBoolean)
        assertEquals(4, request.size())
    }

    @Test
    fun `ps and tags tell whether the model is loaded and installed`() = runBlocking {
        assertFalse(OllamaModelLoader(Recording()).isModelLoaded())
        assertTrue(OllamaModelLoader(Recording(ps = "{\"models\":[{\"name\":\"qwen3:8b\"}]}")).isModelLoaded())
        assertTrue(OllamaModelLoader(Recording()).isModelInstalled())
        assertFalse(OllamaModelLoader(Recording(), model = "other:1b").isModelInstalled())
    }

    @Test(expected = OllamaInvalidResponseException::class)
    fun `malformed load answer is rejected`() = runBlocking {
        OllamaModelLoader(Recording(answer = "not json")).load()
    }

    @Test
    fun `failures map to readable non sensitive causes`() {
        assertEquals(
            NaturalAiProblem(NaturalAiFailureKind.LOAD_TIMEOUT, seconds = 300),
            classifyNaturalAiFailure(NaturalAiLoadTimeoutException(), 300),
        )
        assertEquals(NaturalAiFailureKind.LOAD_TIMEOUT, classifyNaturalAiFailure(SocketTimeoutException("timeout"), 12).kind)
        assertEquals(NaturalAiFailureKind.UNREACHABLE, classifyNaturalAiFailure(ConnectException("refused"), 0).kind)
        assertEquals(NaturalAiFailureKind.UNREACHABLE, classifyNaturalAiFailure(IOException("reset"), 0).kind)
        assertEquals(NaturalAiFailureKind.MODEL_MISSING, classifyNaturalAiFailure(OllamaHttpException(404), 0).kind)
        assertEquals(NaturalAiFailureKind.MODEL_MISSING, classifyNaturalAiFailure(OllamaModelMissingException(), 0).kind)
        assertEquals(
            NaturalAiProblem(NaturalAiFailureKind.HTTP_ERROR, httpCode = 500),
            classifyNaturalAiFailure(OllamaHttpException(500), 0),
        )
        assertEquals(
            NaturalAiFailureKind.INVALID_JSON,
            classifyNaturalAiFailure(NaturalPlanParseException("bad"), 0).kind,
        )

        val texts = NaturalAiFailureKind.entries.map {
            NaturalAiProblem(it, seconds = 42, httpCode = 503).logText("qwen3:8b")
        }
        assertTrue(texts.contains("Delai depasse pendant le chargement (42 s)"))
        assertTrue(texts.contains("Erreur Ollama HTTP 503"))
        assertTrue(texts.none { it.contains("http://") || it.contains("100.") })
    }
}
