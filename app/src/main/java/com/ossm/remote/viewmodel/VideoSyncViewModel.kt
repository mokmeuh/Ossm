package com.ossm.remote.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.gson.Gson
import com.ossm.remote.ble.BleManager
import com.ossm.remote.model.Funscript
import com.ossm.remote.model.FunscriptAction
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URL
import javax.inject.Inject
import kotlin.math.abs

data class VideoSyncUiState(
    val videoLabel: String = "",
    val hasVideo: Boolean = false,
    val funscriptName: String = "",
    val totalActions: Int = 0,
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val streamingActive: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    // Décalage de latence (ms) : positif = le funscript est envoyé en avance
    // par rapport à la vidéo (compense la latence BLE + transport firmware).
    val latencyOffsetMs: Int = 0,
    // Remappe la position brute 0-100 du script dans la plage utilisateur.
    val depthMin: Int = 0,
    val depthMax: Int = 100,
    val error: String? = null
)

/**
 * Lecture d'une vidéo (ExoPlayer, fichier local ou URL) synchronisée avec un
 * funscript qui pilote l'actionneur via le chemin streaming existant du BleManager.
 *
 * Tout est traité localement : la position de lecture d'ExoPlayer sert d'horloge,
 * on interpole la position funscript courante et on envoie `stream:pos:time`.
 * Réutilise les modèles Funscript/FunscriptAction et le gating streaming
 * (EnterStreaming + streamingReady) du BleManager.
 */
@HiltViewModel
class VideoSyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: BleManager
) : ViewModel() {

    private val gson = Gson()
    private val _uiState = MutableStateFlow(VideoSyncUiState())
    val uiState: StateFlow<VideoSyncUiState> = _uiState.asStateFlow()

    private var actions: List<FunscriptAction> = emptyList()
    private var player: ExoPlayer? = null
    private var syncJob: Job? = null
    private var progressJob: Job? = null

    /** Fournit (en créant si besoin) l'ExoPlayer pour le PlayerView. Thread principal. */
    fun getOrCreatePlayer(): ExoPlayer {
        val existing = player
        if (existing != null) return existing
        val p = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                    if (isPlaying) startSyncLoop() else pauseStreaming()
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) stop()
                }
            })
        }
        player = p
        startProgressLoop()
        return p
    }

    fun setVideoUri(uri: Uri, displayName: String) {
        val p = getOrCreatePlayer()
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        _uiState.value = _uiState.value.copy(
            videoLabel = displayName,
            hasVideo = true,
            error = null
        )
    }

    fun setVideoUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        val p = getOrCreatePlayer()
        p.setMediaItem(MediaItem.fromUri(trimmed))
        p.prepare()
        _uiState.value = _uiState.value.copy(
            videoLabel = trimmed,
            hasVideo = true,
            error = null
        )
    }

    fun setLatencyOffset(ms: Int) {
        _uiState.value = _uiState.value.copy(latencyOffsetMs = ms.coerceIn(-2000, 2000))
    }

    fun setDepthRange(min: Int, max: Int) {
        val lo = min.coerceIn(0, 100)
        val hi = max.coerceIn(0, 100)
        if (hi <= lo) return
        _uiState.value = _uiState.value.copy(depthMin = lo, depthMax = hi)
    }

    fun loadFunscriptFromUri(uri: Uri, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                applyFunscriptJson(json, displayName)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Erreur chargement funscript: ${e.message}")
            }
        }
    }

    fun loadFunscriptFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = URL(trimmed).openStream().bufferedReader().use { it.readText() }
                val name = trimmed.substringAfterLast('/').ifBlank { "funscript" }
                applyFunscriptJson(json, name)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Erreur téléchargement funscript: ${e.message}")
            }
        }
    }

    private fun applyFunscriptJson(json: String, displayName: String) {
        if (json.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Fichier funscript vide ou illisible")
            return
        }
        val fs = gson.fromJson(json, Funscript::class.java)
        val parsed = fs?.actions
            ?.filter { it.atMs >= 0 && it.pos in 0..100 }
            ?.sortedBy { it.atMs }
            ?: emptyList()
        if (parsed.isEmpty()) {
            actions = emptyList()
            _uiState.value = _uiState.value.copy(
                funscriptName = displayName,
                totalActions = 0,
                error = "Aucune action valide dans ce funscript"
            )
            return
        }
        actions = parsed
        _uiState.value = _uiState.value.copy(
            funscriptName = displayName,
            totalActions = parsed.size,
            error = null
        )
    }

    /** Remappe une position brute 0-100 du script dans la plage utilisateur [depthMin, depthMax]. */
    private fun mapPosition(rawPos: Int): Int {
        val s = _uiState.value
        val clamped = rawPos.coerceIn(0, 100)
        return (s.depthMin + clamped * (s.depthMax - s.depthMin) / 100).coerceIn(0, 100)
    }

    /** Play/pause : pilote ExoPlayer ; le streaming BLE suit l'état de lecture. */
    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            // Entre en streaming AVANT de lancer la vidéo pour aligner le départ.
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isPreparing = true, error = null)
                bleManager.sendCommand(OssmCommand.EnterStreaming)
                val ready = withTimeoutOrNull(15000) { bleManager.streamingReady.first { it } } != null
                _uiState.value = _uiState.value.copy(isPreparing = false, streamingActive = ready)
                if (!ready) {
                    _uiState.value = _uiState.value.copy(
                        error = "Mode streaming non confirmé — baisse le bouton de vitesse physique à 0."
                    )
                }
                // On lance quand même la vidéo ; le loop n'enverra rien tant que
                // streamingReady est faux (gate côté BleManager).
                p.play()
            }
        }
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0))
    }

    fun stop() {
        syncJob?.cancel()
        player?.pause()
        player?.seekTo(0)
        bleManager.sendCommand(OssmCommand.Stop)
        _uiState.value = _uiState.value.copy(
            isPlaying = false,
            isPreparing = false,
            streamingActive = false,
            positionMs = 0L
        )
    }

    private fun pauseStreaming() {
        syncJob?.cancel()
        bleManager.sendCommand(OssmCommand.Stop)
    }

    /**
     * Boucle de synchronisation : la position d'ExoPlayer est l'horloge maîtresse.
     * À chaque tick, on calcule le temps vidéo (+ offset de latence), on trouve la
     * prochaine action funscript et on envoie stream:pos:time pour l'atteindre pile
     * à son instant. Un saut de lecture (seek) force un renvoi immédiat.
     */
    private fun startSyncLoop() {
        syncJob?.cancel()
        val p = player ?: return
        syncJob = viewModelScope.launch(Dispatchers.Main) {
            var lastIdx = -1
            var lastNow = Long.MIN_VALUE
            while (isActive) {
                if (!p.isPlaying) break
                val offset = _uiState.value.latencyOffsetMs
                val now = p.currentPosition + offset
                val script = actions
                if (script.isEmpty()) { delay(120); continue }

                val seeked = lastNow != Long.MIN_VALUE && abs(now - lastNow) > 400
                lastNow = now

                var idx = script.indexOfFirst { it.atMs > now }
                if (idx < 0) {
                    // Au-delà de la dernière action : plus rien à envoyer.
                    lastIdx = -1
                    delay(120)
                    continue
                }
                if (idx != lastIdx || seeked) {
                    lastIdx = idx
                    val action = script[idx]
                    val dt = (action.atMs - now).coerceIn(20L, 2000L).toInt()
                    bleManager.sendCommand(
                        OssmCommand.Stream(
                            positionPercent = mapPosition(action.pos),
                            timeMs = dt
                        )
                    )
                }
                delay(60)
            }
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                val p = player
                if (p != null) {
                    _uiState.value = _uiState.value.copy(
                        positionMs = p.currentPosition.coerceAtLeast(0),
                        durationMs = p.duration.takeIf { it > 0 } ?: _uiState.value.durationMs
                    )
                }
                delay(200)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        syncJob?.cancel()
        progressJob?.cancel()
        bleManager.sendCommand(OssmCommand.Stop)
        player?.release()
        player = null
    }
}
