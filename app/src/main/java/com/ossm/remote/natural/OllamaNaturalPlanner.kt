package com.ossm.remote.natural

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Configuration for an explicitly enabled, optional Ollama planner. */
data class OllamaNaturalPlannerConfig(
    val model: String = DEFAULT_MODEL,
    val keepAlive: String = "15m",
    val maxResponseCharacters: Int = 16_384,
    val maxOutputTokens: Int = 640, // up to 6 phrases of strict JSON
    val contextSize: Int = 2_048,
    val temperature: Double = 0.2,
    /** true only through the PC reasoning relay (which logs the thinking). */
    val think: Boolean = false,
) {
    init {
        require(model.isNotBlank())
        require(keepAlive.isNotBlank())
        require(maxResponseCharacters in 1_024..65_536)
        require(maxOutputTokens in 32..4_096)
        require(contextSize in 512..8_192)
        require(temperature.isFinite() && temperature in 0.0..2.0)
    }

    companion object {
        const val DEFAULT_MODEL = "qwen3:8b"

        /** Relay mode: thinking enabled, room for the reasoning tokens before the JSON plan. */
        fun reasoning(): OllamaNaturalPlannerConfig = OllamaNaturalPlannerConfig(
            maxOutputTokens = 3_072,
            contextSize = 4_096,
            think = true,
        )
    }
}

/** Small transport boundary that keeps the planner independent of Android and OkHttp. */
interface OllamaTransport {
    suspend fun get(path: String): String

    suspend fun post(path: String, body: String): String
}

/**
 * Optional high-level planner. The local planner remains the caller's fallback and
 * the generated response is accepted only after strict motif parsing.
 */
class OllamaNaturalPlanner(
    private val transport: OllamaTransport,
    private val config: OllamaNaturalPlannerConfig = OllamaNaturalPlannerConfig(),
    private val parser: NaturalPlanJsonParser = NaturalPlanJsonParser(),
    private val gson: Gson = Gson(),
) : NaturalPlanner {
    private val requestMutex = Mutex()

    override suspend fun plan(input: NaturalPlanInput): NaturalPlan = requestMutex.withLock {
        withContext(Dispatchers.IO) {
            requireModelIsInstalled(transport.get("/api/tags"))
            val request = buildGenerateRequest(input)
            val responseBody = transport.post("/api/generate", gson.toJson(request))
            val response = extractGeneratedResponse(responseBody)
            parser.parse(response)
        }
    }

    private fun requireModelIsInstalled(tagsJson: String) {
        if (config.model !in ollamaModelNames(tagsJson)) throw OllamaModelMissingException()
    }

    private fun buildGenerateRequest(input: NaturalPlanInput): JsonObject {
        val settings = JsonObject().apply {
            addProperty("schemaVersion", NaturalPlan.SCHEMA_VERSION)
            addProperty("depthMinPercent", input.constraints.depthMinPercent)
            addProperty("depthMaxPercent", input.constraints.depthMaxPercent)
            addProperty("speedMaxPercent", input.constraints.speedMaxPercent)
            addProperty("phraseCount", input.phraseCount)
            addProperty("seed", input.constraints.seed)
        }
        val prompt = """
            Choose ${input.phraseCount} short coherent Naturel phrases from the allowed motifs.
            Return only the requested JSON schema. Motifs and bounded style values are hints;
            the phone will generate all positions, durations, holds, and safety limits locally.
            Never include motor positions, timings, BLE text, commands, or extra JSON fields.
            Every style value must be an ordinary decimal number from 0.0 through 1.0 (never NaN).
            Example shape: {"schemaVersion":1,"phrases":[{"motif":"wave","intensity":0.6,"tempo":0.3,"variation":0.7,"rest":0.1}]}.
            Phone settings: ${gson.toJson(settings)}
        """.trimIndent()
        return JsonObject().apply {
            addProperty("model", config.model)
            addProperty("prompt", prompt)
            addProperty("stream", false)
            addProperty("think", config.think)
            addProperty("keep_alive", config.keepAlive)
            add("format", NaturalPlanJsonSchema.create())
            // Relay-only pacing info (logged then stripped by the relay, never sent to Ollama).
            input.pacing?.takeIf { config.think }?.let { pacing ->
                add("ossm_pacing", JsonObject().apply {
                    addProperty("reserve_s", pacing.reserveSeconds)
                    pacing.lastLatencySeconds?.let { addProperty("last_latency_s", it) }
                    addProperty("threshold_s", pacing.thresholdSeconds)
                })
            }
            add("options", JsonObject().apply {
                addProperty("temperature", config.temperature)
                addProperty("num_predict", config.maxOutputTokens)
                addProperty("num_ctx", config.contextSize)
            })
        }
    }

    private fun extractGeneratedResponse(generateJson: String): String {
        val root = parseObject(generateJson, "generate")
        val model = root["model"]
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?: throw IllegalStateException("Ollama response has no model name")
        val done = root["done"]
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean == true
        if (model != config.model || !done) {
            throw IllegalStateException("Ollama response is incomplete or from an unexpected model")
        }
        val response = root["response"]
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?: throw IllegalStateException("Ollama response has no JSON response string")
        if (response.isBlank() || response.length > config.maxResponseCharacters) {
            throw IllegalStateException("Ollama response is empty or oversized")
        }
        return response
    }

    private fun parseObject(json: String, label: String): JsonObject {
        val parsed = try {
            JsonParser.parseString(json)
        } catch (error: RuntimeException) {
            throw IllegalStateException("Malformed Ollama $label response", error)
        }
        if (!parsed.isJsonObject) {
            throw IllegalStateException("Ollama $label response is not an object")
        }
        return parsed.asJsonObject
    }
}
