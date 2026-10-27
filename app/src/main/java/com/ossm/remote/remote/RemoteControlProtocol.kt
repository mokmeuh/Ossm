package com.ossm.remote.remote

import com.google.gson.JsonParser

internal sealed interface RemoteControlAction {
    data class SetSpeed(val value: Float) : RemoteControlAction
    data class SetDepth(val minimum: Float, val maximum: Float) : RemoteControlAction
    data class SetSensation(val value: Float) : RemoteControlAction
    data class SelectPattern(val key: String) : RemoteControlAction
    data class SetLivePosition(val percent: Int) : RemoteControlAction
    data object EndLiveTouch : RemoteControlAction
    data object Stop : RemoteControlAction
    data object Home : RemoteControlAction
    data object Pause : RemoteControlAction
    data object Resume : RemoteControlAction
    data object EmergencyStop : RemoteControlAction
}

internal object RemoteControlProtocol {
    private const val MAX_BODY_BYTES = 4_096

    fun parse(body: String): RemoteControlAction? {
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) return null
        return runCatching {
            val json = JsonParser.parseString(body).asJsonObject
            when (json.get("type")?.asString) {
                "set_speed" -> RemoteControlAction.SetSpeed(json.unitFloat("value"))
                "set_depth" -> {
                    val minimum = json.unitFloat("minimum")
                    val maximum = json.unitFloat("maximum")
                    if (minimum > maximum) null else RemoteControlAction.SetDepth(minimum, maximum)
                }
                "set_sensation" -> RemoteControlAction.SetSensation(json.unitFloat("value"))
                "select_pattern" -> json.get("key")?.asString
                    ?.takeIf { it.length in 1..80 }
                    ?.let(RemoteControlAction::SelectPattern)
                "set_live_position" -> RemoteControlAction.SetLivePosition(
                    json.get("percent")?.asInt?.coerceIn(0, 100) ?: return null,
                )
                "end_live_touch" -> RemoteControlAction.EndLiveTouch
                "stop" -> RemoteControlAction.Stop
                "home" -> RemoteControlAction.Home
                "pause" -> RemoteControlAction.Pause
                "resume" -> RemoteControlAction.Resume
                "emergency_stop" -> RemoteControlAction.EmergencyStop
                else -> null
            }
        }.getOrNull()
    }

    private fun com.google.gson.JsonObject.unitFloat(name: String): Float {
        val value = get(name)?.asFloat ?: error("Missing $name")
        require(value.isFinite() && value in 0f..1f)
        return value
    }
}
