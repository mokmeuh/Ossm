package com.ossm.remote.model

data class ControlCommand(
    val speed: Float = 0f,       // 0.0 – 1.0
    val depth: Float = 1f,       // 0.0 – 1.0  (max position)
    val strokeLength: Float = 1f,// 0.0 – 1.0
    val sensation: Float = 0.5f, // 0.0 – 1.0
    val patternId: Int = 0       // 0 = manual, 1-3 = preset patterns
)

sealed class OssmCommand {
    object Stop : OssmCommand()
    data class Move(val command: ControlCommand) : OssmCommand()
    data class Pattern(val id: Int) : OssmCommand()
    data class Position(val position: Float, val speed: Float) : OssmCommand()
}
