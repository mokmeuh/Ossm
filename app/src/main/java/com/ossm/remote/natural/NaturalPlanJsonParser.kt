package com.ossm.remote.natural

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/** Raised when an optional planner response is not the strict Naturel plan shape. */
class NaturalPlanParseException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Parses only high-level Naturel motifs. Motor positions, durations, BLE text and
 * unknown fields are deliberately rejected at this boundary.
 */
class NaturalPlanJsonParser {
    fun parse(json: String): NaturalPlan {
        val root = try {
            JsonParser.parseString(json)
        } catch (error: RuntimeException) {
            throw NaturalPlanParseException("Malformed Naturel plan JSON", error)
        }
        return parseRoot(root)
    }

    private fun parseRoot(root: JsonElement): NaturalPlan {
        val objectRoot = requireObject(root, "root")
        requireExactKeys(objectRoot, ROOT_KEYS, "root")
        val schemaVersion = parseInteger(objectRoot, "schemaVersion")
        if (schemaVersion != NaturalPlan.SCHEMA_VERSION) {
            invalid("Unsupported Naturel schemaVersion: $schemaVersion")
        }
        val phraseArray = requireArray(objectRoot["phrases"], "phrases")
        if (phraseArray.size() !in 1..NaturalPlan.MAX_PHRASES) {
            invalid("phrases must contain 1..${NaturalPlan.MAX_PHRASES} items")
        }
        val phrases = phraseArray.mapIndexed { index, element ->
            parsePhrase(element, index)
        }
        return NaturalPlan(schemaVersion, phrases)
    }

    private fun parsePhrase(element: JsonElement, index: Int): NaturalPhrase {
        val phrase = requireObject(element, "phrases[$index]")
        requireExactKeys(phrase, PHRASE_KEYS, "phrases[$index]")
        val motifName = parseString(phrase, "motif")
        val motif = NaturalMotif.fromWire(motifName)
            ?: invalid("Unknown Naturel motif: $motifName")
        return NaturalPhrase(
            motif = motif,
            style = NaturalStyle(
                intensity = parseUnit(phrase, "intensity"),
                tempo = parseUnit(phrase, "tempo"),
                variation = parseUnit(phrase, "variation"),
                rest = parseUnit(phrase, "rest"),
            ),
        )
    }

    private fun requireObject(element: JsonElement?, label: String): JsonObject {
        if (element == null || !element.isJsonObject) {
            invalid("$label must be an object")
        }
        return element.asJsonObject
    }

    private fun requireArray(element: JsonElement?, label: String): JsonArray {
        if (element == null || !element.isJsonArray) {
            invalid("$label must be an array")
        }
        return element.asJsonArray
    }

    private fun requireExactKeys(objectValue: JsonObject, expected: Set<String>, label: String) {
        val actual = objectValue.keySet()
        val missing = expected - actual
        val unknown = actual - expected
        if (missing.isNotEmpty() || unknown.isNotEmpty()) {
            invalid(
                "$label keys are not exact; missing=$missing unknown=$unknown",
            )
        }
    }

    private fun parseString(objectValue: JsonObject, key: String): String {
        val primitive = objectValue[key]?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        if (primitive == null || !primitive.isString) {
            invalid("$key must be a string")
        }
        return primitive.asString
    }

    private fun parseInteger(objectValue: JsonObject, key: String): Int {
        val number = parseNumber(objectValue, key)
        if (number < Int.MIN_VALUE || number > Int.MAX_VALUE || number != number.toInt().toDouble()) {
            invalid("$key must be an integer")
        }
        return number.toInt()
    }

    private fun parseUnit(objectValue: JsonObject, key: String): Float {
        val number = parseNumber(objectValue, key)
        if (number !in 0.0..1.0) {
            invalid("$key must be between 0 and 1")
        }
        return number.toFloat()
    }

    private fun parseNumber(objectValue: JsonObject, key: String): Double {
        val primitive: JsonPrimitive = objectValue[key]
            ?.takeIf { it.isJsonPrimitive }
            ?.asJsonPrimitive
            ?: invalid("$key must be a number")
        if (!primitive.isNumber) {
            invalid("$key must be a number")
        }
        val number = try {
            primitive.asDouble
        } catch (error: RuntimeException) {
            throw NaturalPlanParseException("$key must be a number", error)
        }
        if (!number.isFinite()) {
            invalid("$key must be finite")
        }
        return number
    }

    private companion object {
        val ROOT_KEYS = setOf("schemaVersion", "phrases")
        val PHRASE_KEYS = setOf("motif", "intensity", "tempo", "variation", "rest")

        fun invalid(message: String): Nothing = throw NaturalPlanParseException(message)
    }
}

/** JSON schema supplied to Ollama; it contains no executable motor fields. */
object NaturalPlanJsonSchema {
    fun create(): JsonObject {
        val phraseProperties = JsonObject().apply {
            add("motif", JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply {
                    NaturalMotif.entries.forEach { add(it.wireName) }
                })
            })
            add("intensity", unitNumberSchema())
            add("tempo", unitNumberSchema())
            add("variation", unitNumberSchema())
            add("rest", unitNumberSchema())
        }
        val phraseSchema = JsonObject().apply {
            addProperty("type", "object")
            addProperty("additionalProperties", false)
            add("required", JsonArray().apply {
                add("motif")
                add("intensity")
                add("tempo")
                add("variation")
                add("rest")
            })
            add("properties", phraseProperties)
        }
        return JsonObject().apply {
            addProperty("type", "object")
            addProperty("additionalProperties", false)
            add("required", JsonArray().apply {
                add("schemaVersion")
                add("phrases")
            })
            add("properties", JsonObject().apply {
                add("schemaVersion", JsonObject().apply {
                    addProperty("type", "integer")
                    addProperty("const", NaturalPlan.SCHEMA_VERSION)
                })
                add("phrases", JsonObject().apply {
                    addProperty("type", "array")
                    addProperty("minItems", 1)
                    addProperty("maxItems", NaturalPlan.MAX_PHRASES)
                    add("items", phraseSchema)
                })
            })
        }
    }

    private fun unitNumberSchema(): JsonObject = JsonObject().apply {
        addProperty("type", "number")
        addProperty("minimum", 0)
        addProperty("maximum", 1)
    }
}
