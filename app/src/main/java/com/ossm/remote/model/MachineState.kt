package com.ossm.remote.model

data class MachineState(
    val state: String = "unknown",
    val speed: Int? = null,
    val stroke: Int? = null,
    val sensation: Int? = null,
    val depth: Int? = null,
    val pattern: Int? = null
) {
    val isHoming: Boolean
        get() = state.contains("homing", ignoreCase = true)

    val isPreflight: Boolean
        get() = state.contains("preflight", ignoreCase = true)

    val isError: Boolean
        get() = state.startsWith("error", ignoreCase = true)

    val isReady: Boolean
        get() = !isHoming && !isPreflight && !isError

    val displayLabel: String
        get() = when {
            isError -> "Erreur: $state"
            isHoming -> "Homing..."
            isPreflight -> "Préparation..."
            state.equals("idle", ignoreCase = true) -> "Inactif"
            state.contains("strokeEngine", ignoreCase = true) -> "Stroke Engine"
            state.contains("simplePenetration", ignoreCase = true) -> "Simple Penetration"
            state.equals("menu", ignoreCase = true) -> "Menu"
            else -> state
        }
}
