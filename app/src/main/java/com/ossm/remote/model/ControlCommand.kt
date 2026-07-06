package com.ossm.remote.model

data class StrokeEngineCommand(
    val speed: Float = 0f,
    val depthMin: Float = 0.2f,
    val depthMax: Float = 0.8f,
    val sensation: Float = 0.5f
)

sealed class OssmCommand {
    object Stop : OssmCommand()
    data class ActivatePattern(val pattern: OssmPattern) : OssmCommand()
    data class UpdateStrokeEngine(val command: StrokeEngineCommand) : OssmCommand()
    object EnterStreaming : OssmCommand()
    data class Stream(val positionPercent: Int, val timeMs: Int) : OssmCommand()
}
