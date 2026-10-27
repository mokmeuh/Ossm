package com.ossm.remote.voice

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal

/** What the on-device LLM answered, once parsed. Nothing here has been bounds-checked yet. */
sealed interface VoiceLlmParse {
    /** Well-formed list of steps (bounds are checked separately by [VoiceStepValidator]). */
    data class Steps(val steps: List<VoiceStep>) : VoiceLlmParse
    /** The model explicitly said it did not understand (`{"erreur": ...}` or `[]`). */
    data object NotUnderstood : VoiceLlmParse
    /** The model asked for a STOP (`{"stop": true}`). */
    data object Stop : VoiceLlmParse
    /** 2.3.28: continuous motif, well-formed (bounds checked by [VoiceMotifValidator]). */
    data class Motif(val motif: VoiceMotif) : VoiceLlmParse
    /** 2.3.28: modulation of the running motif. */
    data class Modulation(val modulation: VoiceModulation) : VoiceLlmParse
    /** Anything else: not JSON, wrong shape, non-integer values, unknown keys. */
    data class Malformed(val reason: String) : VoiceLlmParse
}

/**
 * Tolerant envelope, strict content: Markdown fences (```json … ```) and text around a single
 * JSON value are accepted, but the value itself is parsed strictly. Each step is
 * `{"cible": int, "duree": int, "maintien": int}` (maintien optional, 0 by default). Numbers must
 * be exact integers; unknown keys reject the whole answer. Nothing is clamped.
 */
object VoiceLlmJsonParser {
    const val KEY_TARGET = "cible"
    const val KEY_MOVE = "duree"
    const val KEY_HOLD = "maintien"
    private val STEP_KEYS = setOf(KEY_TARGET, KEY_MOVE, KEY_HOLD)

    // 2.3.28 continuous motif: every key is required, values are exact integers.
    const val KEY_MOTIF = "motif"
    const val KEY_DEPTH_START = "profondeur_debut"
    const val KEY_DEPTH_END = "profondeur_fin"
    const val KEY_SPEED_START = "vitesse_debut"
    const val KEY_SPEED_END = "vitesse_fin"
    const val KEY_RAMP_S = "montee_s"
    const val KEY_MODULATION = "modulation"
    private val MOTIF_KEYS = setOf(
        KEY_MOTIF, KEY_DEPTH_START, KEY_DEPTH_END, KEY_SPEED_START, KEY_SPEED_END, KEY_RAMP_S
    )

    /** Modulation words of the JSON schema. */
    val MODULATIONS: Map<String, VoiceModulation> = mapOf(
        "plus_vite" to VoiceModulation.FASTER,
        "plus_lent" to VoiceModulation.SLOWER,
        "plus_profond" to VoiceModulation.DEEPER,
        "moins_profond" to VoiceModulation.SHALLOWER,
        "garder" to VoiceModulation.HOLD,
    )

    fun parse(raw: String): VoiceLlmParse {
        val candidate = extractJson(raw) ?: return VoiceLlmParse.Malformed("aucun JSON")
        val root = try {
            val reader = JsonReader(StringReader(candidate)).apply { strictness = Strictness.STRICT }
            val element = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) return VoiceLlmParse.Malformed("texte après le JSON")
            element
        } catch (_: Exception) {
            return VoiceLlmParse.Malformed("JSON invalide")
        }
        return when {
            root.isJsonArray -> parseSteps(root.asJsonArray)
            root.isJsonObject -> parseObject(root.asJsonObject)
            else -> VoiceLlmParse.Malformed("ni tableau ni objet")
        }
    }

    /**
     * Result handed to the store: a well-formed plan goes to the strict validator there (an
     * out-of-bounds plan is shown as rejected), everything else is "not understood".
     */
    fun toInterpretResult(raw: String): VoiceInterpretResult = when (val parsed = parse(raw)) {
        is VoiceLlmParse.Steps -> VoiceInterpretResult.Steps(parsed.steps)
        VoiceLlmParse.Stop -> VoiceInterpretResult.Stop
        is VoiceLlmParse.Motif -> VoiceInterpretResult.Motif(parsed.motif)
        is VoiceLlmParse.Modulation -> VoiceInterpretResult.Modulate(parsed.modulation)
        VoiceLlmParse.NotUnderstood, is VoiceLlmParse.Malformed -> VoiceInterpretResult.NotUnderstood
    }

    internal fun extractJson(raw: String): String? {
        var text = raw.trim()
        // Markdown fences, with or without a language tag.
        val fence = Regex("```[a-zA-Z]*\\s*([\\s\\S]*?)\\s*```").find(text)
        if (fence != null) text = fence.groupValues[1].trim()
        if (text.isEmpty()) return null
        if (text.first() == '[' || text.first() == '{') return text
        val start = text.indexOfFirst { it == '[' || it == '{' }
        if (start < 0) return null
        val close = if (text[start] == '[') ']' else '}'
        val end = text.lastIndexOf(close)
        if (end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun parseObject(obj: JsonObject): VoiceLlmParse {
        val keys = obj.keySet()
        return when {
            keys == setOf("stop") -> {
                val value = obj.get("stop")
                if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean && value.asBoolean) {
                    VoiceLlmParse.Stop
                } else VoiceLlmParse.Malformed("stop invalide")
            }
            keys == setOf("erreur") -> VoiceLlmParse.NotUnderstood
            keys == MOTIF_KEYS -> parseMotif(obj)
            keys == setOf(KEY_MODULATION) -> parseModulation(obj.get(KEY_MODULATION))
            keys == setOf("etapes") && obj.get("etapes").isJsonArray -> parseSteps(obj.getAsJsonArray("etapes"))
            else -> VoiceLlmParse.Malformed("objet inattendu")
        }
    }

    private fun parseMotif(obj: JsonObject): VoiceLlmParse {
        val kind = obj.get(KEY_MOTIF)
        if (kind == null || !kind.isJsonPrimitive || !kind.asJsonPrimitive.isString ||
            kind.asString != VoiceMotifValidator.KIND_BACK_AND_FORTH
        ) return VoiceLlmParse.Malformed("motif inconnu")
        val depthStart = exactInt(obj.get(KEY_DEPTH_START)) ?: return VoiceLlmParse.Malformed(KEY_DEPTH_START)
        val depthEnd = exactInt(obj.get(KEY_DEPTH_END)) ?: return VoiceLlmParse.Malformed(KEY_DEPTH_END)
        val speedStart = exactInt(obj.get(KEY_SPEED_START)) ?: return VoiceLlmParse.Malformed(KEY_SPEED_START)
        val speedEnd = exactInt(obj.get(KEY_SPEED_END)) ?: return VoiceLlmParse.Malformed(KEY_SPEED_END)
        val ramp = exactInt(obj.get(KEY_RAMP_S)) ?: return VoiceLlmParse.Malformed(KEY_RAMP_S)
        return VoiceLlmParse.Motif(VoiceMotif(depthStart, depthEnd, speedStart, speedEnd, ramp))
    }

    private fun parseModulation(value: JsonElement?): VoiceLlmParse {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            return VoiceLlmParse.Malformed("modulation invalide")
        }
        return MODULATIONS[value.asString]?.let { VoiceLlmParse.Modulation(it) }
            ?: VoiceLlmParse.Malformed("modulation inconnue")
    }

    private fun parseSteps(array: JsonArray): VoiceLlmParse {
        if (array.size() == 0) return VoiceLlmParse.NotUnderstood
        val steps = ArrayList<VoiceStep>(array.size())
        for (element in array) {
            if (!element.isJsonObject) return VoiceLlmParse.Malformed("étape non objet")
            val obj = element.asJsonObject
            if (!STEP_KEYS.containsAll(obj.keySet())) return VoiceLlmParse.Malformed("clé inconnue")
            val target = exactInt(obj.get(KEY_TARGET)) ?: return VoiceLlmParse.Malformed("cible")
            val move = exactInt(obj.get(KEY_MOVE)) ?: return VoiceLlmParse.Malformed("duree")
            val hold = if (obj.has(KEY_HOLD)) {
                exactInt(obj.get(KEY_HOLD)) ?: return VoiceLlmParse.Malformed("maintien")
            } else 0
            steps += VoiceStep(target, move, hold)
        }
        return VoiceLlmParse.Steps(steps)
    }

    private fun exactInt(element: JsonElement?): Int? {
        if (element == null || !element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        if (!primitive.isNumber) return null
        return try {
            BigDecimal(primitive.asString).intValueExact()
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }
}
