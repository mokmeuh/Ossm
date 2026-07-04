package com.ossm.remote.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Abstraction du canal réseau du contrôle à distance. Toute la logique de session
 * dépend de CETTE interface, pas d'une techno précise → on pourra brancher MQTT
 * public (MVP) puis WebRTC (P2P) sans rien changer d'autre. Voir REMOTE_CONTROL_DESIGN.md.
 *
 * NOTE PORT iOS : contrat commun ; seule l'implémentation concrète est spécifique
 * à la plateforme.
 */
interface RemoteTransport {
    /** Messages entrants (JSON déjà déchiffré). */
    val incoming: Flow<String>

    /** Ouvre/rejoint la session identifiée par [code] avec le rôle [role]. */
    suspend fun connect(code: String, role: RemoteRole)

    /** Envoie un message (JSON) au pair. */
    suspend fun send(message: String)

    /** Ferme la session. */
    suspend fun disconnect()
}

/**
 * Stub no-op : permet de compiler et de développer l'UI sans dépendance réseau.
 * Sera remplacé par `MqttRemoteTransport` à l'incrément 2 (voir design).
 */
class NoopRemoteTransport : RemoteTransport {
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val incoming: Flow<String> = _incoming.asSharedFlow()
    override suspend fun connect(code: String, role: RemoteRole) { /* TODO incrément 2 : MQTT */ }
    override suspend fun send(message: String) { /* TODO incrément 2 */ }
    override suspend fun disconnect() { /* TODO incrément 2 */ }
}
