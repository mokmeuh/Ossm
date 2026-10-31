package com.ossm.remote.ui.modes

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * Ce que le cadre de l'écran de contrôle dit aux composants d'un mode : quel mode est actif, si la
 * machine répond, et si une session distante impose des plafonds. Les composants partagés (curseurs,
 * barres) le lisent eux-mêmes ; un mode n'a plus à repasser ces états à la main à chaque curseur,
 * ce qui évite d'oublier un plafond affiché. Lecture seule : aucune commande ne passe par ici.
 */
@Immutable
data class ModeContext(
    val mode: ModeDescriptor? = null,
    /** La machine est connectée. */
    val connected: Boolean = false,
    /** Connectée et libre : les réglages sont modifiables. */
    val interactive: Boolean = false,
    /** Une session distante impose des plafonds (cadenas et « limité » sur les curseurs). */
    val remoteLimited: Boolean = false
) {
    /** Faux seulement si le mode connu n'expose pas ce canal. Sans mode connu, rien n'est masqué. */
    fun exposes(channel: ModeChannel): Boolean = mode?.exposes(channel) ?: true

    companion object {
        val Idle = ModeContext()
    }
}

val LocalModeContext = compositionLocalOf { ModeContext.Idle }
