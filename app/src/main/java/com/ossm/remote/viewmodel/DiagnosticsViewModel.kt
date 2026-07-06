package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ossm.remote.data.repository.DiagnosticsRepository
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.ble.BleManager
import com.ossm.remote.model.DiagnosticsLog
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
                _logs.update { current ->
                    current + log
                }
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
