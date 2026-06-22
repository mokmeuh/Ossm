package com.ossm.remote.model

data class OssmPattern(
    val id: Int,
    val name: String,
    val description: String,
    val baseSpeed: Float,
    val strokeLength: Float,
    val sensation: Float
)

val PredefinedPatterns = listOf(
    OssmPattern(1, "Pattern 1", "Régulier lent", 0.3f, 0.8f, 0.5f),
    OssmPattern(2, "Pattern 2", "Vagues progressives", 0.5f, 0.9f, 0.6f),
    OssmPattern(3, "Pattern 3", "Intensité maximale", 0.8f, 1.0f, 0.9f)
)
