package com.ossm.remote.ble

internal fun mapLiveStreamPositionPercent(
    sliderPercent: Int,
    liveInvert: Boolean
): Int {
    val clamped = sliderPercent.coerceIn(0, 100)
    // Firmware wire positions run opposite to the Live pad's Home-to-Fond axis.
    val mapped = if (liveInvert) clamped else 100 - clamped
    return mapped.coerceIn(2, 98)
}

// Doc officielle OSSM (BLE §latency compensation) : NE PAS activer la compensation
// de latence quand l'intervalle entre commandes ne correspond PAS au champ `time`.
// Le mode Live suit le doigt : le timing est irrégulier par nature, donc la
// compensation ferait des corrections de vitesse erratiques → saccades. Désactivée.
internal fun isLatencyCompensationEnabledForLive(): Boolean = false
