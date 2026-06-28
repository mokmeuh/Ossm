package com.ossm.remote.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.ossm.remote.ble.BleManager
import com.ossm.remote.model.Funscript
import com.ossm.remote.model.OssmCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FunscriptUiState(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val isPreparing: Boolean = false,
    val fileName: String = "",
    val totalActions: Int = 0,
    val currentActionIndex: Int = 0,
    val elapsedMs: Long = 0L,
    val error: String? = null
)

@HiltViewModel
class FunscriptViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: BleManager
) : ViewModel() {

    private val gson = Gson()
    private val _uiState = MutableStateFlow(FunscriptUiState())
    val uiState: StateFlow<FunscriptUiState> = _uiState.asStateFlow()

    private var funscript: Funscript? = null
    private var playJob: Job? = null
    private var pausedAtMs: Long = 0L

    fun loadFunscript(uri: Uri, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val fs = gson.fromJson(json, Funscript::class.java)
                funscript = fs
                _uiState.value = FunscriptUiState(
                    fileName = displayName,
                    totalActions = fs.actions.size
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Erreur chargement: ${e.message}")
            }
        }
    }

    fun play() {
        val fs = funscript ?: return
        if (_uiState.value.isPlaying) return

        playJob?.cancel()
        val resumeFromPause = _uiState.value.isPaused
        val startOffset = if (resumeFromPause) pausedAtMs else 0L

        _uiState.value = _uiState.value.copy(
            isPlaying = true,
            isPaused = false,
            isPreparing = !resumeFromPause,
            error = null
        )

        playJob = viewModelScope.launch {
            if (!resumeFromPause) {
                bleManager.sendCommand(OssmCommand.EnterStreaming)
                // Wait for homing/preflight to complete before sending stream commands
                waitForStreamingReady()
            }

            _uiState.value = _uiState.value.copy(isPreparing = false)

            val actions = fs.actions.sortedBy { it.atMs }
            val startTime = System.currentTimeMillis() - startOffset
            var actionIdx = actions.indexOfFirst { it.atMs >= startOffset }.coerceAtLeast(0)

            while (actionIdx < actions.size && isActive) {
                val action = actions[actionIdx]
                val targetTime = startTime + action.atMs
                val waitMs = targetTime - System.currentTimeMillis()
                if (waitMs > 0) delay(waitMs)

                val durationMs = if (actionIdx == 0) {
                    150
                } else {
                    (action.atMs - actions[actionIdx - 1].atMs).coerceIn(20L, 2000L).toInt()
                }

                bleManager.sendCommand(
                    OssmCommand.Stream(
                        positionPercent = action.pos.coerceIn(0, 100),
                        timeMs = durationMs
                    )
                )

                _uiState.value = _uiState.value.copy(
                    currentActionIndex = actionIdx,
                    elapsedMs = System.currentTimeMillis() - startTime
                )
                actionIdx++
            }

            stop()
        }
    }

    private suspend fun waitForStreamingReady(timeoutMs: Long = 8000) {
        withTimeoutOrNull(timeoutMs) {
            bleManager.machineState.first { state ->
                state.state.contains("streaming", ignoreCase = true) &&
                    !state.isHoming &&
                    !state.isPreflight
            }
        }
    }

    fun pause() {
        pausedAtMs = _uiState.value.elapsedMs
        playJob?.cancel()
        _uiState.value = _uiState.value.copy(isPlaying = false, isPaused = true, isPreparing = false)
        bleManager.sendCommand(OssmCommand.Stop)
    }

    fun stop() {
        playJob?.cancel()
        pausedAtMs = 0L
        _uiState.value = _uiState.value.copy(
            isPlaying = false,
            isPaused = false,
            isPreparing = false,
            currentActionIndex = 0,
            elapsedMs = 0L
        )
        bleManager.sendCommand(OssmCommand.Stop)
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}
