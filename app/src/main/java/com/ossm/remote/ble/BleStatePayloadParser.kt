package com.ossm.remote.ble

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ossm.remote.model.MachineState

private val COMPLETE_STRING_FIELD = Regex("\\\"(state)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
private val COMPLETE_INTEGER_FIELD = Regex(
    "\\\"(speed|stroke|sensation|depth|pattern)\\\"\\s*:\\s*(-?\\d+)"
)
private val COMPLETE_POSITION_FIELD = Regex(
    "\\\"position\\\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))"
)
private val COMPLETE_BOOLEAN_FIELD = Regex("\\\"(homed)\\\"\\s*:\\s*(true|false)")

internal fun parseMachineStatePayload(payload: String): MachineState? {
    val trimmed = payload.trim()
    if (trimmed.isEmpty()) return null

    val complete = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull()
    if (complete != null) return machineStateFromJson(complete)

    val state = COMPLETE_STRING_FIELD.find(trimmed)?.groupValues?.getOrNull(2) ?: return null
    val integers = COMPLETE_INTEGER_FIELD.findAll(trimmed).associate { match ->
        match.groupValues[1] to match.groupValues[2].toIntOrNull()
    }
    val position = COMPLETE_POSITION_FIELD.find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.toFloatOrNull()
    val homed = COMPLETE_BOOLEAN_FIELD.find(trimmed)
        ?.groupValues
        ?.getOrNull(2)
        ?.toBooleanStrictOrNull()

    return MachineState(
        state = state,
        speed = integers["speed"],
        stroke = integers["stroke"],
        sensation = integers["sensation"],
        depth = integers["depth"],
        pattern = integers["pattern"],
        positionMm = position,
        homed = homed,
    )
}

private fun machineStateFromJson(json: JsonObject): MachineState = MachineState(
    state = json.get("state")?.takeIf { !it.isJsonNull }?.asString ?: "unknown",
    speed = json.get("speed")?.takeIf { !it.isJsonNull }?.asInt,
    stroke = json.get("stroke")?.takeIf { !it.isJsonNull }?.asInt,
    sensation = json.get("sensation")?.takeIf { !it.isJsonNull }?.asInt,
    depth = json.get("depth")?.takeIf { !it.isJsonNull }?.asInt,
    pattern = json.get("pattern")?.takeIf { !it.isJsonNull }?.asInt,
    positionMm = json.get("position")?.takeIf { !it.isJsonNull }?.asFloat,
    homed = json.get("homed")?.takeIf { !it.isJsonNull }?.asBoolean,
)
