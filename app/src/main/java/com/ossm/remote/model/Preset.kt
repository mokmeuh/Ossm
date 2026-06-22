package com.ossm.remote.model

data class Preset(
    val id: Long = 0,
    val name: String,
    val speed: Float,
    val depth: Float,
    val strokeLength: Float,
    val sensation: Float,
    val patternId: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
