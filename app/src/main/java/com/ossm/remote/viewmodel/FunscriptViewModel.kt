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
    val durationMs: Long = 0L,
    // Plage de profondeur appliquee au funscript (0 = home, 100 = fond).
    // pos brut 0-100 du script est remappe dans [depthMin, depthMax].
    val depthMin: Int = 0,
    val depthMax: Int = 100,
    // Multiplicateur de vitesse de lecture (0.5x .. 2.0x).
    val speedFactor: Float = 1.0f,
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

    fun setDepthRange(min: Int, max: Int) {
        val lo = min.coerceIn(0, 100)
        val hi = max.coerceIn(0, 100)
        if (hi <= lo) return
        _uiState.value = _uiState.value.copy(depthMin = lo, depthMax = hi)
    }

    fun setSpeedFactor(factor: Float) {
        _uiState.value = _uiState.value.copy(speedFactor = factor.coerceIn(0.5f, 2.0f))
    }

    /** Remappe une position brute 0-100 du script dans la plage utilisateur [depthMin, depthMax]. */
    private fun mapPosition(rawPos: Int): Int {
        val s = _uiState.value
        val clamped = rawPos.coerceIn(0, 100)
        return (s.depthMin + clamped * (s.depthMax - s.depthMin) / 100).coerceIn(0, 100)
    }

    fun loadFunscript(uri: Uri, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Stoppe une lecture en cours avant de charger un autre fichier.
            playJob?.cancel()
            pausedAtMs = 0L
            try {
                val json = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (json.isBlank()) {
                    _uiState.value = _uiState.value.copy(error = "Fichier vide ou illisible")
                    return@launch
                }
                val fs = gson.fromJson(json, Funscript::class.java)
                val actions = fs?.actions?.filter { it.atMs >= 0 && it.pos in 0..100 }
                    ?.sortedBy { it.atMs }
                    ?: emptyList()
                if (actions.isEmpty()) {
                    funscript = null
                    _uiState.value = _uiState.value.copy(
                        fileName = displayName,
                        totalActions = 0,
                        error = "Aucune action valide dans ce funscript"
                    )
                    return@launch
                }
                funscript = fs?.copy(actions = actions)
                // Conserve la plage de profondeur et la vitesse deja reglees par l'utilisateur.
                val prev = _uiState.value
                _uiState.value = FunscriptUiState(
                    fileName = displayName,
                    totalActions = actions.size,
                    durationMs = actions.last().atMs,
                    depthMin = prev.depthMin,
                    depthMax = prev.depthMax,
                    speedFactor = prev.speedFactor
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
                // Attend la confirmation du mode streaming + bande pleine course.
                waitForStreamingReady()
            } else {
                // La pause a envoyé set:speed:0 ; en streaming le firmware ignore tout
                // stream:pos:time si speed=0 — on restaure avant de reprendre.
                bleManager.liveSet("speed", 100)
            }

            _uiState.value = _uiState.value.copy(isPreparing = false)

            val actions = fs.actions.sortedBy { it.atMs }
            val speed = _uiState.value.speedFactor.coerceIn(0.5f, 2.0f)
            // Le temps de lecture est compresse/dilate par le facteur de vitesse.
            fun scaledAt(atMs: Long): Long = (atMs / speed).toLong()
            val startTime = System.currentTimeMillis() - startOffset
            var actionIdx = actions.indexOfFirst { scaledAt(it.atMs) >= startOffset }.coerceAtLeast(0)

            while (actionIdx < actions.size && isActive) {
                val action = actions[actionIdx]
                val targetTime = startTime + scaledAt(action.atMs)
                val waitMs = targetTime - System.currentTimeMillis()
                if (waitMs > 0) delay(waitMs)

                val durationMs = if (actionIdx == 0) {
                    150
                } else {
                    (scaledAt(action.atMs) - scaledAt(actions[actionIdx - 1].atMs))
                        .coerceIn(20L, 2000L).toInt()
                }

                bleManager.sendCommand(
                    OssmCommand.Stream(
                        positionPercent = mapPosition(action.pos),
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

    private suspend fun waitForStreamingReady(timeoutMs: Long = 15000) {
        // Attend la confirmation complète (état streaming + stroke/depth=100 vérifiés
        // par BleManager) : tant que streamingReady est faux, les stream:<pos>:<time>
        // sont ignorés et la bande de course serait imprévisible.
        withTimeoutOrNull(timeoutMs) {
            bleManager.streamingReady.first { it }
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
