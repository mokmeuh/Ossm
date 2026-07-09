package com.ossm.remote.ble

internal fun mapLiveStreamPositionPercent(
    sliderPercent: Int,
    liveInvert: Boolean
): Int {
    val clamped = sliderPercent.coerceIn(0, 100)
    // Calibration empirique (test appareil 2026-07-09) : sur ce firmware,
    // stream HAUT = HOME, stream BAS = FOND. Le pad Live a 100 % (doigt en haut)
    // DOIT aller au FOND -> on INVERSE par defaut (slider 100 -> stream 2 = fond ;
    // slider 0 -> stream 98 = home). C'est le mapping "baseline validee appareil".
    // liveInvert (flag utilisateur, false par defaut) permet de re-basculer en direct.
    val mapped = if (liveInvert) clamped else 100 - clamped
    return mapped.coerceIn(2, 98)
}

// Doc officielle OSSM (BLE §latency compensation) : NE PAS activer la compensation
// de latence quand l'intervalle entre commandes ne correspond PAS au champ `time`.
// Le mode Live suit le doigt : le timing est irrégulier par nature, donc la
// compensation ferait des corrections de vitesse erratiques → saccades. Désactivée.
internal fun isLatencyCompensationEnabledForLive(): Boolean = false
