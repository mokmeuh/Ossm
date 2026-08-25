package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import com.ossm.remote.audio.AudioLevelMonitor
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.KnownStrokeEnginePattern
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.PatternControlMode
import com.ossm.remote.model.Preset
import com.ossm.remote.session.ControlSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

enum class GuardedControl {
    SPEED,
    DEPTH
}

/**
 * Cibles du mode aléatoire du pattern « Teasing & Pounding ». Chaque case activée
 * fait varier ce paramètre AUTOUR de la valeur réglée par l'utilisateur, sans jamais
 * dépasser son maximum (bornage de sécurité) :
 *  - SPEED          : vitesse aléatoire entre un plancher et la vitesse du slider.
 *  - DEPTH_MIN      : point de RETRAIT aléatoire (jamais plus reculé que réglé → course jamais rallongée).
 *  - DEPTH_MAX      : FOND aléatoire (jamais plus profond que réglé).
 */
enum class RandomTarget {
    SPEED,
    DEPTH_MIN,
    DEPTH_MAX
}

data class PendingManualChange(
    val control: GuardedControl,
    val speed: Float,
    val depthMin: Float,
    val depthMax: Float,
    val thresholdPercent: Int
)

data class SliderGuardUiState(
    val enabled: Boolean = true,
    val thresholdPercent: Int = 5
)

/**
 * Assistant de plage au toucher : la machine suit le doigt (mode streaming officiel,
 * `stream:pos:time`), l'utilisateur amène physiquement le chariot au FOND désiré puis
 * au point de RETRAIT ; l'app convertit en `depth = fond` / `stroke = fond - retrait`.
 * Aucune commande firmware non documentée (équivalent fonctionnel du "setup depth"
 * de la librairie StrokeEngine, qui n'est PAS exposé par le firmware OSSM).
 */
data class RangeWizardState(
    val step: Int = 1,                // 1 = définir le fond, 2 = définir le retrait
    val capturedMax: Float? = null,   // fond capturé (0..1)
    val returnPatternKey: String
)

data class ControlUiState(
    val availablePatterns: List<OssmPattern> = KnownFallbackPatterns,
    val activePatternKey: String = KnownStrokeEnginePattern.key,
    val speed: Float = 0f,
    val depthMin: Float = 0f,    // default full range 0-100%; user/preset adjustments persist
    val depthMax: Float = 1f,
    val depthLimitMin: Float = 0f,
    val depthLimitMax: Float = 1f,
    val sensation: Float = 0.5f,
    val progressiveMaxSpeed: Float = 1f,   // red ceiling for the Progressif ramp (0..1)
    val chaosAtMax: Boolean = false,       // when ramp hits max: random varied strokes
    // Mode aléatoire « Teasing & Pounding » : chaque flag fait varier son paramètre
    // dans les bornes réglées par l'utilisateur (voir RandomTarget). Les valeurs de
    // slider ci-dessus restent les MAX/ancres ; l'aléatoire est envoyé à la machine
    // sans écraser ces valeurs affichées.
    val randSpeed: Boolean = false,
    val randDepthMin: Boolean = false,
    val randDepthMax: Boolean = false,
    // Momentary values sent by the random ticker. The slider values above remain the
    // user-configured ceilings/anchors.
    val randomSpeedCurrent: Float? = null,
    val randomDepthMinCurrent: Float? = null,
    val randomDepthMaxCurrent: Float? = null,
    // Mode « à l'écoute » : le micro biaise le mode aléatoire vers le haut (plus
    // fort tu réagis, plus l'intensité monte et reste dans le haut des plages).
    val listeningMode: Boolean = false,
    val listeningCeiling: Float = 1f,
    val listeningTargetSpeed: Boolean = false,
    val listeningTargetDepth: Boolean = false,
    val listeningDepthCurrent: Float? = null,
    // Inversion du sens du mode Live (réglable par l'utilisateur).
    val liveInvert: Boolean = false,
    val liveAccelerationPercent: Int = 33,
    // Auto Random mix
    val autoSelectedKeys: Set<String> = setOf("simpleStroke", "teasingPounding", "roboStroke"),
    val autoMaxSpeed: Float = 0.7f,        // speed ceiling for the mix (never exceeded)
    val autoIntensityCap: Float = 0.8f,    // user-set max build-up intensity
    val autoRandomness: Float = 0.6f,      // how much variation/switching the mix is allowed
    val autoInitialIntensity: Float = 0f,   // pre-launch starting intensity (user-set slider)
    val autoRunning: Boolean = false,
    val autoIntensity: Float = 0f,         // 0→1 build-up, shown live to the user
    val pendingAutoStart: Boolean = false, // show the "set your limits" reminder popup
    val isPaused: Boolean = false,
    val isRunning: Boolean = false,
    // Vrai quand la machine a confirmé le mode streaming avec la bande pleine course
    // (stroke=100/depth=100) : le slider Live n'est actif qu'à ce moment-là.
    val streamingReady: Boolean = false,
    val streamPositionPercent: Int = 0,
    // Assistant de plage au toucher (non null = assistant en cours).
    val rangeWizard: RangeWizardState? = null,
    // Ordre personnalisé des patterns (keys) ; vide = ordre naturel.
    val patternOrder: List<String> = emptyList(),
    val speedGuard: SliderGuardUiState = SliderGuardUiState(enabled = true, thresholdPercent = 10),
    val depthGuard: SliderGuardUiState = SliderGuardUiState(enabled = true, thresholdPercent = 5),
    val pendingManualChange: PendingManualChange? = null
) {
    val activePattern: OssmPattern?
        get() = availablePatterns.firstOrNull { it.key == activePatternKey }

    /** Patterns dans l'ordre choisi par l'utilisateur (inconnus à la fin). */
    val orderedPatterns: List<OssmPattern>
        get() = if (patternOrder.isEmpty()) availablePatterns
        else availablePatterns.sortedBy { p ->
            patternOrder.indexOf(p.key).let { if (it < 0) Int.MAX_VALUE else it }
        }

    val activePatternSupportsControls: Boolean
        get() = activePattern != null && activePattern!!.mode != PatternControlMode.LAUNCH_ONLY

    val activePatternUsesStrokeEngine: Boolean
        get() = activePattern?.mode == PatternControlMode.STROKE_ENGINE

    /** Au moins une case du mode aléatoire est active. */
    val anyRandomActive: Boolean
        get() = randSpeed || randDepthMin || randDepthMax
}

/**
 * ViewModel de l'écran de contrôle. La source de vérité (état de session, garde
 * anti-saut, throttling) vit dans [ControlSessionStore] : ce ViewModel ne fait que
 * déléguer, afin qu'un futur overlay en ForegroundService partage le même état live.
 */
@HiltViewModel
class ControlViewModel @Inject constructor(
    private val controlSessionStore: ControlSessionStore,
    private val audioLevelMonitor: AudioLevelMonitor
) : ViewModel() {

    val uiState: StateFlow<ControlUiState> = controlSessionStore.uiState

    /** Niveau sonore live (0..1) pour le témoin visuel du mode à l'écoute. */
    val listeningLevel: StateFlow<Float> = audioLevelMonitor.level

    fun activatePattern(patternKey: String) = controlSessionStore.activatePattern(patternKey)

    fun requestSpeedChange(value: Float) = controlSessionStore.requestSpeedChange(value)

    fun requestDepthRangeChange(min: Float, max: Float) = controlSessionStore.requestDepthRangeChange(min, max)

    fun setSpeedLive(value: Float) = controlSessionStore.setSpeedLive(value)

    fun setSensationLive(value: Float) = controlSessionStore.setSensationLive(value)

    fun setDepthLive(min: Float, max: Float) = controlSessionStore.setDepthLive(min, max)

    fun confirmPendingManualChange(skipForSession: Boolean, neverAskAgain: Boolean) =
        controlSessionStore.confirmPendingManualChange(skipForSession, neverAskAgain)

    fun dismissPendingManualChange() = controlSessionStore.dismissPendingManualChange()

    fun setSpeedGuardEnabled(enabled: Boolean) = controlSessionStore.setSpeedGuardEnabled(enabled)

    fun setDepthGuardEnabled(enabled: Boolean) = controlSessionStore.setDepthGuardEnabled(enabled)

    fun setDepthLimits(min: Float, max: Float) = controlSessionStore.setDepthLimits(min, max)

    fun stop() = controlSessionStore.stop()

    fun home() = controlSessionStore.home()

    fun setProgressiveMaxSpeed(value: Float) = controlSessionStore.setProgressiveMaxSpeed(value)

    fun setChaosAtMax(enabled: Boolean) = controlSessionStore.setChaosAtMax(enabled)

    fun setRandomMode(target: RandomTarget, enabled: Boolean) =
        controlSessionStore.setRandomMode(target, enabled)

    fun setDepthRandom(enabled: Boolean) = controlSessionStore.setDepthRandom(enabled)

    fun setLiveInvert(enabled: Boolean) = controlSessionStore.setLiveInvert(enabled)

    fun setLiveAcceleration(percent: Int) = controlSessionStore.setLiveAcceleration(percent)

    fun setListeningMode(enabled: Boolean) = controlSessionStore.setListeningMode(enabled)

    fun setListeningCeiling(value: Float) = controlSessionStore.setListeningCeiling(value)

    fun setListeningTargetSpeed(enabled: Boolean) = controlSessionStore.setListeningTargetSpeed(enabled)

    fun setListeningTargetDepth(enabled: Boolean) = controlSessionStore.setListeningTargetDepth(enabled)

    fun setStreamPosition(positionPercent: Int) = controlSessionStore.setStreamPosition(positionPercent)

    fun setStreamPositionDrag(positionPercent: Int) = controlSessionStore.setStreamPositionDrag(positionPercent)

    fun stopLiveTouch() = controlSessionStore.stopLiveTouch()

    fun startRangeWizard() = controlSessionStore.startRangeWizard()

    fun captureRangePoint() = controlSessionStore.captureRangePoint()

    fun cancelRangeWizard() = controlSessionStore.cancelRangeWizard()

    fun savePatternOrder(keys: List<String>) = controlSessionStore.savePatternOrder(keys)

    fun applyPreset(preset: Preset) = controlSessionStore.applyPreset(preset)

    fun requestSensationChange(value: Float) = controlSessionStore.requestSensationChange(value)

    fun toggleAutoSelected(key: String) = controlSessionStore.toggleAutoSelected(key)

    fun setAutoMaxSpeed(value: Float) = controlSessionStore.setAutoMaxSpeed(value)

    fun setAutoIntensityCap(value: Float) = controlSessionStore.setAutoIntensityCap(value)

    fun setAutoRandomness(value: Float) = controlSessionStore.setAutoRandomness(value)

    fun setAutoInitialIntensity(value: Float) = controlSessionStore.setAutoInitialIntensity(value)

    fun requestAutoStart() = controlSessionStore.requestAutoStart()

    fun cancelAutoStart() = controlSessionStore.cancelAutoStart()

    fun confirmAutoStart() = controlSessionStore.confirmAutoStart()

    fun stopAuto() = controlSessionStore.stopAuto()

    fun pause() = controlSessionStore.pause()

    fun resume() = controlSessionStore.resume()

    override fun onCleared() {
        super.onCleared()
        // L'état et les tickers vivent dans le store (partagé avec le futur overlay) ;
        // on coupe seulement le micro ici, comme avant.
        controlSessionStore.onUiCleared()
    }
}
