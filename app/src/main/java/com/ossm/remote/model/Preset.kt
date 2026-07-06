package com.ossm.remote.model

data class Preset(
    val id: Long = 0,
    val name: String,
    val patternKey: String,
    val patternName: String,
    val speed: Float,
    val depthMin: Float,
    val depthMax: Float,
    val createdAt: Long = System.currentTimeMillis()
)
