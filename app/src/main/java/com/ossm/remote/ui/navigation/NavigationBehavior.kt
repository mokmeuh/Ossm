package com.ossm.remote.ui.navigation

import com.ossm.remote.model.BleConnectionState

internal fun shouldReturnToScanAfterDisconnect(
    previous: BleConnectionState?,
    current: BleConnectionState
): Boolean {
    val isDisconnectedLike = current is BleConnectionState.Disconnected ||
        current is BleConnectionState.EmergencyStop
    val wasAlreadyDisconnected = previous == null ||
        previous is BleConnectionState.Disconnected ||
        previous is BleConnectionState.EmergencyStop
    return isDisconnectedLike && !wasAlreadyDisconnected
}

internal fun shouldOpenControlAfterConnect(
    previous: BleConnectionState?,
    current: BleConnectionState,
    currentRoute: String
): Boolean =
    currentRoute == Screen.Scan.route &&
        current is BleConnectionState.Connected &&
        previous !is BleConnectionState.Connected
