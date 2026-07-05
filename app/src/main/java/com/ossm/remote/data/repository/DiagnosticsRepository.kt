package com.ossm.remote.data.repository

import com.ossm.remote.ble.BleManager
import com.ossm.remote.model.DiagnosticsLog
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticsRepository @Inject constructor(
    bleManager: BleManager
) {
    val logs: SharedFlow<DiagnosticsLog> = bleManager.logs
    val lastCommand = bleManager.lastCommand
}
