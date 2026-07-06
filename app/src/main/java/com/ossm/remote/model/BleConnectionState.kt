package com.ossm.remote.model

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Scanning : BleConnectionState()
    data class Connecting(val deviceName: String) : BleConnectionState()
    data class Connected(val deviceName: String, val address: String) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
    object EmergencyStop : BleConnectionState()
}

fun BleConnectionState.label(): String = when (this) {
    is BleConnectionState.Disconnected -> "Déconnecté"
    is BleConnectionState.Scanning -> "Scan..."
    is BleConnectionState.Connecting -> "Connexion à $deviceName"
    is BleConnectionState.Connected -> "Connecté : $deviceName"
    is BleConnectionState.Error -> "Erreur : $message"
    is BleConnectionState.EmergencyStop -> "ARRÊT D'URGENCE"
}

fun BleConnectionState.isConnected() = this is BleConnectionState.Connected
fun BleConnectionState.isBusy() = this is BleConnectionState.Scanning || this is BleConnectionState.Connecting
