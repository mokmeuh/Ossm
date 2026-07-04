package com.ossm.remote.remote

import kotlin.random.Random

/**
 * Contrôle à distance (onglet Remote) — modèles de base. WIP, branche
 * `claude/remote-control`. Voir REMOTE_CONTROL_DESIGN.md.
 *
 * NOTE PORT iOS : ce fichier est pur Kotlin/logique (aucune dépendance Android),
 * donc directement réutilisable côté KMP/iOS.
 */

/** Rôle de l'instance dans une session distante. */
enum class RemoteRole {
    /** A : possède la machine (BLE local), diffuse l'état, exécute les commandes reçues. */
    HOST,
    /** B : distant, envoie des commandes, affiche l'état de la machine de A. */
    REMOTE
}

/** Qui a le contrôle des consignes pendant une session. STOP/fin de session restent TOUJOURS à l'hôte. */
enum class RemoteControlOwner {
    /** L'hôte (A) pilote. */
    LOCAL,
    /** Le distant (B) pilote (contrôle exclusif). */
    REMOTE
}

/** État de la connexion distante. */
sealed class RemoteConnectionState {
    object Idle : RemoteConnectionState()
    /** L'hôte partage son code et attend un pair. */
    data class Hosting(val code: String) : RemoteConnectionState()
    /** Tentative de connexion (B a collé un code). */
    data class Connecting(val code: String) : RemoteConnectionState()
    /** Session active. */
    data class Connected(val code: String, val role: RemoteRole) : RemoteConnectionState()
    data class Error(val message: String) : RemoteConnectionState()
}

/** Génération du code d'appairage (9 chiffres, affiché à l'utilisateur). */
object RemoteCode {
    const val LENGTH = 9

    /** Code lisible à 9 chiffres. Un secret plus long sera dérivé de ce code (voir design, sécurité). */
    fun generate(): String = buildString {
        repeat(LENGTH) { append(Random.nextInt(0, 10)) }
    }

    /** Normalise une saisie (retire espaces/tirets). */
    fun sanitize(input: String): String = input.filter { it.isDigit() }.take(LENGTH)

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it.isDigit() }
}
