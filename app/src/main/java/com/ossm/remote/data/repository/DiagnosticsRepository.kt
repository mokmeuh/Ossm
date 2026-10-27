package com.ossm.remote.data.repository

import com.ossm.remote.ble.BleManager
import com.ossm.remote.model.DiagnosticsLog
import com.ossm.remote.model.LogLevel
import com.ossm.remote.remote.CameraShareDiagnostics
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticsRepository @Inject constructor(
    private val bleManager: BleManager
) {
    val logs: SharedFlow<DiagnosticsLog> = bleManager.logs
    val lastCommand = bleManager.lastCommand

    /** Camera share event, tag CAMERA. Sanitized again here: never a link nor a key. */
    fun recordCamera(level: LogLevel, message: String) {
        val clean = CameraShareDiagnostics.sanitize(message)
        if (clean.isNotEmpty()) bleManager.appendExternalDiagnostic(level, CameraShareDiagnostics.TAG, clean)
    }
}
