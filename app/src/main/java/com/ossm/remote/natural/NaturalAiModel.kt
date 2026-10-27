package com.ossm.remote.natural

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException

/** Non-2xx Ollama answer; only the status code is kept (never the body). */
class OllamaHttpException(val code: Int) : IOException("Ollama HTTP $code")

/** The configured model is not listed by `/api/tags` (or Ollama answered 404 for it). */
class OllamaModelMissingException : IllegalStateException("Configured Ollama model is not installed")

/** Malformed or unexpected Ollama JSON (outer envelope or plan). */
class OllamaInvalidResponseException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Model load exceeded the phone-side budget. */
class NaturalAiLoadTimeoutException : IllegalStateException("Ollama model load timed out")

/** Lifecycle of the optional model on the PC. Motion never depends on it. */
enum class NaturalAiModelState {
    IDLE,    // not loaded (button "Charger Qwen3")
    LOADING, // load request / ps follow-up in progress
    LOADED,  // confirmed in memory (/api/ps or load success)
    FAILED,  // cause shown with a Retry button
}

enum class NaturalAiFailureKind {
    INVALID_ADDRESS,
    UNREACHABLE,
    LOAD_TIMEOUT,
    MODEL_MISSING,
    INVALID_JSON,
    HTTP_ERROR,
}

/** Readable, non-sensitive cause: no URL, no response content. */
data class NaturalAiProblem(
    val kind: NaturalAiFailureKind,
    val seconds: Int = 0,
    val httpCode: Int = 0,
    val detail: String = "",
) {
    /** French text for the Diagnostic journal (tag NATURAL_AI). */
    fun logText(model: String): String = baseLogText(model) + if (detail.isBlank()) "" else " [$detail]"

    private fun baseLogText(model: String): String = when (kind) {
        NaturalAiFailureKind.INVALID_ADDRESS -> "Adresse invalide"
        NaturalAiFailureKind.UNREACHABLE -> "PC injoignable (reseau/pare-feu)"
        NaturalAiFailureKind.LOAD_TIMEOUT -> "Delai depasse pendant le chargement (${seconds} s)"
        NaturalAiFailureKind.MODEL_MISSING -> "Modele $model absent sur le PC"
        NaturalAiFailureKind.INVALID_JSON -> "Reponse refusee (JSON invalide)"
        NaturalAiFailureKind.HTTP_ERROR -> "Erreur Ollama HTTP $httpCode"
    }
}

/** Exception type + short message for diagnosis; IP addresses and URLs are masked. */
internal fun naturalAiErrorDetail(error: Throwable): String {
    val message = (error.message ?: "")
        .replace(Regex("https?://\\S+"), "<url>")
        .replace(Regex("\\[?[0-9a-fA-F:]*:[0-9a-fA-F:]+]?"), "<ip6>")
        .replace(Regex("\\d{1,3}(\\.\\d{1,3}){3}"), "<ip>")
        .take(100)
    val root = error.cause?.let { " <- ${it::class.java.simpleName}" } ?: ""
    return "${error::class.java.simpleName}$root" + if (message.isBlank()) "" else ": $message"
}

/** Maps any planner/loader failure to a readable cause. Cancellation must be handled first. */
fun classifyNaturalAiFailure(error: Throwable, elapsedSeconds: Int): NaturalAiProblem = when (error) {
    is NaturalAiLoadTimeoutException,
    is TimeoutCancellationException,
    is SocketTimeoutException -> NaturalAiProblem(NaturalAiFailureKind.LOAD_TIMEOUT, seconds = elapsedSeconds)
    is OllamaModelMissingException -> NaturalAiProblem(NaturalAiFailureKind.MODEL_MISSING)
    is OllamaHttpException -> if (error.code == 404) {
        NaturalAiProblem(NaturalAiFailureKind.MODEL_MISSING)
    } else {
        NaturalAiProblem(NaturalAiFailureKind.HTTP_ERROR, httpCode = error.code)
    }
    is ConnectException, is UnknownHostException, is NoRouteToHostException ->
        NaturalAiProblem(NaturalAiFailureKind.UNREACHABLE, detail = naturalAiErrorDetail(error))
    is InterruptedIOException -> if (error.message?.contains("timeout", ignoreCase = true) == true) {
        NaturalAiProblem(NaturalAiFailureKind.LOAD_TIMEOUT, seconds = elapsedSeconds)
    } else {
        NaturalAiProblem(NaturalAiFailureKind.UNREACHABLE, detail = naturalAiErrorDetail(error))
    }
    is IOException -> NaturalAiProblem(NaturalAiFailureKind.UNREACHABLE, detail = naturalAiErrorDetail(error))
    else -> NaturalAiProblem(NaturalAiFailureKind.INVALID_JSON)
}

/** Model names listed by `/api/tags` or `/api/ps` (`models[].name|model`). */
internal fun ollamaModelNames(json: String): Set<String> {
    val parsed = try {
        JsonParser.parseString(json)
    } catch (error: RuntimeException) {
        throw OllamaInvalidResponseException("Malformed Ollama model list", error)
    }
    if (!parsed.isJsonObject) throw OllamaInvalidResponseException("Ollama model list is not an object")
    val models = parsed.asJsonObject["models"]
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?: throw OllamaInvalidResponseException("Ollama model list has no models array")
    return models.flatMap { element ->
        if (!element.isJsonObject) throw OllamaInvalidResponseException("Ollama model entry is not an object")
        val model = element.asJsonObject
        listOf("name", "model").mapNotNull { key ->
            model[key]
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.takeIf { it.isNotBlank() }
        }
    }.toSet()
}

/**
 * Pre-loads the model on the PC without generating anything: `POST /api/generate` with an
 * empty prompt and `keep_alive`, followed by read-only `/api/ps` checks. It never commands
 * the machine, so STOP/pause/mode exit do not concern it.
 */
class OllamaModelLoader(
    private val transport: OllamaTransport,
    val model: String = OllamaNaturalPlannerConfig.DEFAULT_MODEL,
    private val keepAlive: String = "15m",
    private val gson: Gson = Gson(),
) {
    suspend fun isModelInstalled(): Boolean = model in ollamaModelNames(transport.get("/api/tags"))

    suspend fun isModelLoaded(): Boolean = model in ollamaModelNames(transport.get("/api/ps"))

    suspend fun load() {
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", "")
            addProperty("keep_alive", keepAlive)
            addProperty("stream", false)
        }
        val response = transport.post("/api/generate", gson.toJson(body))
        val parsed = try {
            JsonParser.parseString(response)
        } catch (error: RuntimeException) {
            throw OllamaInvalidResponseException("Malformed Ollama load response", error)
        }
        if (!parsed.isJsonObject) throw OllamaInvalidResponseException("Ollama load response is not an object")
        val done = parsed.asJsonObject["done"]
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean == true
        if (!done) throw OllamaInvalidResponseException("Ollama load response is incomplete")
    }
}
