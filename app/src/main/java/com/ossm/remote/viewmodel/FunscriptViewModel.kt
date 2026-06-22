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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class FunscriptUiState(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
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
        val startOffset = if (_uiState.value.isPaused) pausedAtMs else 0L
        val startTime = System.currentTimeMillis() - startOffset

        _uiState.value = _uiState.value.copy(isPlaying = true, isPaused = false, error = null)

        playJob = viewModelScope.launch {
            val actions = fs.actions.sortedBy { it.atMs }
            var actionIdx = actions.indexOfFirst { it.atMs >= startOffset }.coerceAtLeast(0)

            while (actionIdx < actions.size && isActive) {
                val action = actions[actionIdx]
                val targetTime = startTime + action.atMs
                val now = System.currentTimeMillis()
                val delay = targetTime - now

                if (delay > 0) delay(delay)

                val elapsed = System.currentTimeMillis() - startTime
                bleManager.sendCommand(
                    OssmCommand.Position(
                        position = action.pos / 100f,
                        speed = 1f
                    )
                )

                _uiState.value = _uiState.value.copy(
                    currentActionIndex = actionIdx,
                    elapsedMs = elapsed
                )
                actionIdx++
            }

            // Playback complete
            stop()
        }
    }

    fun pause() {
        pausedAtMs = _uiState.value.elapsedMs
        playJob?.cancel()
        _uiState.value = _uiState.value.copy(isPlaying = false, isPaused = true)
        bleManager.sendCommand(OssmCommand.Stop)
    }

    fun stop() {
        playJob?.cancel()
        pausedAtMs = 0L
        _uiState.value = _uiState.value.copy(
            isPlaying = false,
            isPaused = false,
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
