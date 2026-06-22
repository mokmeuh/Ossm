package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import com.ossm.remote.ble.BleManager
import com.ossm.remote.model.ControlCommand
import com.ossm.remote.model.OssmCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ControlUiState(
    val speed: Float = 0f,
    val depth: Float = 1f,
    val strokeLength: Float = 1f,
    val sensation: Float = 0.5f,
    val activePatternId: Int = 0,
    val isRunning: Boolean = false
)

@HiltViewModel
class ControlViewModel @Inject constructor(
    private val bleManager: BleManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ControlUiState())
    val uiState: StateFlow<ControlUiState> = _uiState.asStateFlow()

    fun setSpeed(value: Float) {
        _uiState.update { it.copy(speed = value, activePatternId = 0) }
        sendCurrentCommand()
    }

    fun setDepth(value: Float) {
        _uiState.update { it.copy(depth = value, activePatternId = 0) }
        sendCurrentCommand()
    }

    fun setStrokeLength(value: Float) {
        _uiState.update { it.copy(strokeLength = value, activePatternId = 0) }
        sendCurrentCommand()
    }

    fun setSensation(value: Float) {
        _uiState.update { it.copy(sensation = value, activePatternId = 0) }
        sendCurrentCommand()
    }

    fun activatePattern(patternId: Int) {
        _uiState.update { it.copy(activePatternId = patternId, isRunning = true) }
        bleManager.sendCommand(OssmCommand.Pattern(patternId))
    }

    fun stop() {
        _uiState.update { it.copy(isRunning = false, speed = 0f, activePatternId = 0) }
        bleManager.emergencyStop()
    }

    fun applyPreset(speed: Float, depth: Float, strokeLength: Float, sensation: Float) {
        _uiState.update { it.copy(speed = speed, depth = depth, strokeLength = strokeLength, sensation = sensation, activePatternId = 0) }
        sendCurrentCommand()
    }

    private fun sendCurrentCommand() {
        val s = _uiState.value
        if (s.speed > 0f) {
            _uiState.update { it.copy(isRunning = true) }
        }
        bleManager.sendCommand(
            OssmCommand.Move(
                ControlCommand(
                    speed = s.speed,
                    depth = s.depth,
                    strokeLength = s.strokeLength,
                    sensation = s.sensation
                )
            )
        )
    }
}
