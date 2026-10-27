package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ossm.remote.data.repository.DiagnosticsRepository
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.ble.BleManager
import com.ossm.remote.model.DiagnosticsLog
import com.ossm.remote.model.LogLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val repository: DiagnosticsRepository,
    private val bleManager: BleManager
) : ViewModel() {

    private val _logs = MutableStateFlow<List<DiagnosticsLog>>(emptyList())
    val logs: StateFlow<List<DiagnosticsLog>> = _logs.asStateFlow()

    val lastCommand: StateFlow<String> = repository.lastCommand
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
        .stateIn(viewModelScope, SharingStarted.Eagerly, BleConnectionState.Disconnected)

    init {
        viewModelScope.launch {
            repository.logs.collect { log ->
                _logs.update { current -> appendBoundedDiagnosticsLog(current, log) }
            }
        }
    }

    /** Camera share diagnostics (sanitized, tag CAMERA) into the same journal. */
    fun recordCamera(level: LogLevel, message: String) = repository.recordCamera(level, message)

    fun clearLogs() {
        _logs.value = emptyList()
    }
}

/**
 * 2.3.17: the journal was an UNBOUNDED list copied on the main thread for every log line
 * (`current + log`). Live logs one line per delivered target (~50/s), so after a session the
 * main thread spent its frames copying tens of thousands of entries and allocating garbage:
 * the whole UI, sliders included, progressively "froze". The journal now keeps the newest
 * [DIAGNOSTICS_LOG_LIMIT] lines.
 */
internal const val DIAGNOSTICS_LOG_LIMIT = 500

internal fun appendBoundedDiagnosticsLog(
    current: List<DiagnosticsLog>,
    log: DiagnosticsLog,
    limit: Int = DIAGNOSTICS_LOG_LIMIT,
): List<DiagnosticsLog> {
    val keep = limit.coerceAtLeast(1)
    if (current.size < keep) return current + log
    val result = ArrayList<DiagnosticsLog>(keep)
    for (index in current.size - keep + 1 until current.size) result += current[index]
    result += log
    return result
}
