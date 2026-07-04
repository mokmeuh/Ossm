package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ossm.remote.remote.NoopRemoteTransport
import com.ossm.remote.remote.RemoteCode
import com.ossm.remote.remote.RemoteConnectionState
import com.ossm.remote.remote.RemoteControlOwner
import com.ossm.remote.remote.RemoteRole
import com.ossm.remote.remote.RemoteTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Session de contrôle à distance. WIP (branche `claude/remote-control`) : gère le
 * code, l'état de connexion et la propriété du contrôle. Le RÉSEAU est encore un
 * stub (NoopRemoteTransport) — l'appairage réel arrive à l'incrément 2 (voir
 * REMOTE_CONTROL_DESIGN.md). Aucune commande machine n'est encore relayée.
 */
data class RemoteUiState(
    val connection: RemoteConnectionState = RemoteConnectionState.Idle,
    val myCode: String = "",
    val controlOwner: RemoteControlOwner = RemoteControlOwner.LOCAL,
    /** Saisie en cours du code à coller (côté B). */
    val codeInput: String = ""
) {
    val isConnected: Boolean get() = connection is RemoteConnectionState.Connected
    val isHosting: Boolean get() = connection is RemoteConnectionState.Hosting
}

@HiltViewModel
class RemoteViewModel @Inject constructor() : ViewModel() {

    // TODO incrément 2 : injecter un vrai RemoteTransport (MQTT) via Hilt.
    private val transport: RemoteTransport = NoopRemoteTransport()

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    /** Côté A (hôte) : génère un code et se met en attente d'un pair. */
    fun startHosting() {
        val code = RemoteCode.generate()
        _uiState.update {
            it.copy(myCode = code, connection = RemoteConnectionState.Hosting(code))
        }
        viewModelScope.launch { transport.connect(code, RemoteRole.HOST) }
    }

    fun regenerateCode() {
        if (_uiState.value.connection is RemoteConnectionState.Connected) return
        startHosting()
    }

    fun onCodeInputChange(input: String) {
        _uiState.update { it.copy(codeInput = RemoteCode.sanitize(input)) }
    }

    /** Côté B (distant) : colle le code de A et se connecte. */
    fun connectToPeer() {
        val code = _uiState.value.codeInput
        if (!RemoteCode.isValid(code)) {
            _uiState.update { it.copy(connection = RemoteConnectionState.Error("Code à 9 chiffres attendu")) }
            return
        }
        _uiState.update { it.copy(connection = RemoteConnectionState.Connecting(code)) }
        viewModelScope.launch {
            transport.connect(code, RemoteRole.REMOTE)
            // TODO incrément 2 : attendre le hello de l'hôte avant de passer Connected.
            _uiState.update {
                it.copy(connection = RemoteConnectionState.Connected(code, RemoteRole.REMOTE))
            }
        }
    }

    /** Contrôle exclusif (case côté B). N'affecte JAMAIS le STOP / la fin de session de A. */
    fun setExclusiveControl(exclusive: Boolean) {
        _uiState.update {
            it.copy(controlOwner = if (exclusive) RemoteControlOwner.REMOTE else RemoteControlOwner.LOCAL)
        }
        // TODO incrément 5 : diffuser le transfert de propriété au pair.
    }

    fun endSession() {
        viewModelScope.launch { transport.disconnect() }
        _uiState.update {
            RemoteUiState()
        }
    }
}
