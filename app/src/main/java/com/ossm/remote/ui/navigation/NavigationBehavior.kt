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
