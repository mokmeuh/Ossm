package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.KnownStrokeEnginePattern
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.PatternControlMode
import com.ossm.remote.model.Preset
import com.ossm.remote.remote.RemoteSessionView
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
    DEPTH_MAX,
    SENSATION,
    SENSATION_MAX
}

enum class RandomPresetTarget {
    SPEED,
    DEPTH
}

data class RandomRangePreset(
    val minPercent: Int,
    val maxPercent: Int,
    val savedSpeedPercent: Int? = null
) {
    init {
        require(minPercent in 0..100)
        require(maxPercent in 0..100)
        require(minPercent <= maxPercent)
        require(savedSpeedPercent == null || savedSpeedPercent in 0..100)
    }

    val minFraction: Float get() = minPercent / 100f
    val maxFraction: Float get() = maxPercent / 100f
}

val DefaultRandomRangePresets: List<RandomRangePreset> = listOf(
    RandomRangePreset(0, 25),
    RandomRangePreset(25, 50),
    RandomRangePreset(50, 75),
    RandomRangePreset(75, 100)
)

/** Which planner currently feeds Naturel's future gestures. Motion never depends on it. */
enum class NaturalPlannerStatus {
    LOCAL,            // AI not in use: local generator only
    AI_WAITING_MODEL, // "Utiliser l'IA" chosen, model not loaded yet: local meanwhile
    AI_READY,         // model loaded, no plan received yet
    AI_CONNECTED,     // last AI plan validated and in use
    AI_UNAVAILABLE,   // AI failed/invalid/timeout: automatic fallback to local
}

enum class LiveRecordingState {
    IDLE,
    ARMED,
    RECORDING,
    RECORDED,
    PLAYING
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
    val randSensation: Boolean = false,
    // "Max" (Teasing Pounding only): top of the sensation range on advance, bottom on retract.
    val maxSensation: Boolean = false,
    // Bounds of the random sensation (0..1, 0.5 = neutral), set by the two range bars.
    val randomSensationMin: Float = 0.45f,
    val randomSensationMax: Float = 0.55f,
    val randomSpeedPresets: List<RandomRangePreset> = DefaultRandomRangePresets,
    val randomDepthPresets: List<RandomRangePreset> = DefaultRandomRangePresets,
    val selectedRandomSpeedPreset: Int = 0,
    val selectedRandomDepthPreset: Int = 0,
    // Momentary values sent by the random ticker. The slider values above remain the
    // user-configured ceilings/anchors.
    val randomSpeedCurrent: Float? = null,
    val randomDepthMinCurrent: Float? = null,
    val randomDepthMaxCurrent: Float? = null,
    val randomSensationCurrent: Float? = null,
    // Inversion du sens du mode Live (réglable par l'utilisateur).
    val liveInvert: Boolean = false, // false keeps the baseline inverse mapping (100 - raw).
    val liveAccelerationPercent: Int = 33,
    val precisionOverrides: Map<String, Boolean> = emptyMap(),
    val liveRecordingState: LiveRecordingState = LiveRecordingState.IDLE,
    // Auto Random mix
    val autoSelectedKeys: Set<String> = setOf("simpleStroke", "teasingPounding", "roboStroke"),
    val autoMaxSpeed: Float = 0.7f,        // speed ceiling for the mix (never exceeded)
    val autoIntensityCap: Float = 0.8f,    // user-set max build-up intensity
    val autoRandomness: Float = 0.6f,      // how much variation/switching the mix is allowed
    val autoInitialIntensity: Float = 0f,   // pre-launch starting intensity (user-set slider)
    val autoRunning: Boolean = false,
    val autoIntensity: Float = 0f,         // 0→1 build-up, shown live to the user
    val pendingAutoStart: Boolean = false, // show the "set your limits" reminder popup
    // Naturel (2.3.18): phone-generated coherent gestures, started only by an explicit action.
    val naturalRunning: Boolean = false,
    val naturalReady: Boolean = false,          // verified mode entry done (speed 0)
    val naturalStartPending: Boolean = false,   // explicit start waiting for mode entry
    val naturalEnergy: Float = 0.5f,
    val naturalVariety: Float = 0.5f,
    val naturalMaxSpeed: Float = 0.4f,          // local speed ceiling (never exceeded)
    val naturalAiEnabled: Boolean = false,      // optional Ollama planner, off by default
    val naturalAiAddress: String = "",
    val naturalAiReasoningRelay: Boolean = false, // 2.3.23: via the PC relay (11435, think:true)
    val naturalAiReserveSeconds: Int? = null,     // queued gesture time while AI plans are used
    val naturalAiLastLatencySeconds: Int? = null, // last successful plan request duration
    val naturalAiWaitingFirstPlan: Boolean = false, // AI-fed start: speed 0 until the first valid plan
    val naturalAiWaitSeconds: Int = 0,
    val naturalAiFallback: com.ossm.remote.natural.NaturalAiFallback? = null, // visible AI -> local cause
    val naturalPlannerStatus: NaturalPlannerStatus = NaturalPlannerStatus.LOCAL,
    // 2.3.20: model pre-load on the PC, decoupled from motion (never cancelled by STOP).
    val naturalAiModelState: com.ossm.remote.natural.NaturalAiModelState =
        com.ossm.remote.natural.NaturalAiModelState.IDLE,
    val naturalAiLoadSeconds: Int = 0,
    val naturalAiModelInstalled: Boolean? = null,  // /api/tags check (null = unknown)
    val naturalAiModelUnloaded: Boolean = false,   // was loaded, then gone from /api/ps
    val naturalAiProblem: com.ossm.remote.natural.NaturalAiProblem? = null,
    val naturalMotif: String? = null,           // current motif wire name (display only)
    val naturalSpeedCurrent: Float? = null,     // shaped values sent to the machine (display)
    val naturalDepthMinCurrent: Float? = null,
    val naturalDepthMaxCurrent: Float? = null,
    // ---- Voix (2.3.26, base): on-screen only, never persisted ----
    val voiceModelInstalled: Boolean = false,    // on-device voice model (none yet)
    val voiceInterpretation: com.ossm.remote.voice.VoiceInterpretation =
        com.ossm.remote.voice.VoiceInterpretation.NONE,
    val voicePlan: List<com.ossm.remote.voice.VoiceStep> = emptyList(),
    val voiceStepIndex: Int? = null,
    val voiceRunning: Boolean = false,
    val voiceMaxSpeed: Float = 0.5f,             // bounds the travel rate (never exceeded)
    // 2.3.28: persisted Voix depth range (fractions); 0 % -> min, 100 % -> max, always
    // intersected with depthLimitMin..depthLimitMax (see VoiceDepthRange.effective).
    val voiceDepthMin: Float = 0f,
    // 2.3.29: "STOP rapide par mot court" (persisted, on by default).
    val voiceShortWordStop: Boolean = true,
    // 2.3.28: running continuous motif (null for a step plan or idle).
    val voiceMotif: com.ossm.remote.voice.VoiceMotif? = null,
    val voiceDepthMax: Float = 1f,
    val isPaused: Boolean = false,
    val isRunning: Boolean = false,
    // Vrai quand la machine a confirmé le mode streaming avec la bande pleine course
    // (stroke=100/depth=100) : le slider Live n'est actif qu'à ce moment-là.
    val streamingReady: Boolean = false,
    val streamPositionPercent: Int = 0,
    // Monotonic key for cancelling a held native Live gesture after STOP/loss/mode exit.
    val liveGestureEpoch: Long = 0L,
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
        get() = randSpeed || randDepthMin || randDepthMax || randSensation || maxSensation

    /** Sensation follows the stroke turnarounds (random draw or Max extremes). */
    val sensationTurnActive: Boolean
        get() = randSensation || maxSensation
}

/**
 * ViewModel de l'écran de contrôle. La source de vérité (état de session, garde
 * anti-saut, throttling) vit dans [ControlSessionStore] : ce ViewModel ne fait que
 * déléguer, afin qu'un futur overlay en ForegroundService partage le même état live.
 */
@HiltViewModel
class ControlViewModel @Inject constructor(
    private val controlSessionStore: ControlSessionStore
) : ViewModel() {

    val uiState: StateFlow<ControlUiState> = controlSessionStore.uiState

    val remoteSessionView: StateFlow<RemoteSessionView> = controlSessionStore.remoteSessionView

    fun activatePattern(patternKey: String) = controlSessionStore.activatePattern(patternKey)

    fun enterLiveFromUsb() = controlSessionStore.enterLiveFromUsb()

    fun requestSpeedChange(value: Float) = controlSessionStore.requestSpeedChange(value)

    fun requestSpeedChangeAtEpoch(value: Float, gestureEpoch: Long) =
        controlSessionStore.requestSpeedChangeAtEpoch(value, gestureEpoch)

    fun requestDepthRangeChange(min: Float, max: Float) = controlSessionStore.requestDepthRangeChange(min, max)

    fun setSpeedLive(value: Float) = controlSessionStore.setSpeedLive(value)

    fun setSpeedLiveAtEpoch(value: Float, gestureEpoch: Long) =
        controlSessionStore.setSpeedLive(value, gestureEpoch)

    fun setSensationLive(value: Float) = controlSessionStore.setSensationLive(value)

    fun setSensationLiveAtEpoch(value: Float, gestureEpoch: Long) =
        controlSessionStore.setSensationLive(value, gestureEpoch)

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

    fun setRandomSensationRange(min: Float, max: Float) =
        controlSessionStore.setRandomSensationRange(min, max)

    fun saveRandomPreset(target: RandomPresetTarget, index: Int) =
        controlSessionStore.saveRandomPreset(target, index)

    fun applyRandomPreset(target: RandomPresetTarget, index: Int) =
        controlSessionStore.applyRandomPreset(target, index)

    fun editRandomPreset(
        target: RandomPresetTarget,
        index: Int,
        minPercent: Int,
        maxPercent: Int,
        saveSpeedToo: Boolean
    ) = controlSessionStore.editRandomPreset(target, index, minPercent, maxPercent, saveSpeedToo)

    fun setLiveInvert(enabled: Boolean) = controlSessionStore.setLiveInvert(enabled)

    fun setLiveAcceleration(percent: Int) = controlSessionStore.setLiveAcceleration(percent)

    fun setPrecisionEnabled(patternKey: String, enabled: Boolean) =
        controlSessionStore.setPrecisionEnabled(patternKey, enabled)

    fun setStreamPosition(positionPercent: Int) = controlSessionStore.setStreamPosition(positionPercent)

    fun setStreamPositionDrag(positionPercent: Int) = controlSessionStore.setStreamPositionDrag(positionPercent)

    /** Normal finger/remote release: the phone owns the bounded return to logical 0. */
    fun endLiveTouch() = controlSessionStore.endLiveTouch()

    /** Explicit STOP/cancel path: cancel all pending Live return work immediately. */
    fun stopLiveTouch() = controlSessionStore.stopLiveTouch()

    fun toggleLiveRecording() = controlSessionStore.toggleLiveRecording()

    fun startRangeWizard() = controlSessionStore.startRangeWizard()

    fun captureRangePoint() = controlSessionStore.captureRangePoint()

    fun cancelRangeWizard() = controlSessionStore.cancelRangeWizard()

    fun savePatternOrder(keys: List<String>) = controlSessionStore.savePatternOrder(keys)

    fun applyPreset(preset: Preset) = controlSessionStore.applyPreset(preset)

    fun requestSensationChange(value: Float) = controlSessionStore.requestSensationChange(value)

    fun requestSensationChangeAtEpoch(value: Float, gestureEpoch: Long) =
        controlSessionStore.requestSensationChange(value, gestureEpoch)

    fun toggleAutoSelected(key: String) = controlSessionStore.toggleAutoSelected(key)

    fun setAutoMaxSpeed(value: Float) = controlSessionStore.setAutoMaxSpeed(value)

    fun setAutoIntensityCap(value: Float) = controlSessionStore.setAutoIntensityCap(value)

    fun setAutoRandomness(value: Float) = controlSessionStore.setAutoRandomness(value)

    fun setAutoInitialIntensity(value: Float) = controlSessionStore.setAutoInitialIntensity(value)

    fun requestAutoStart() = controlSessionStore.requestAutoStart()

    fun cancelAutoStart() = controlSessionStore.cancelAutoStart()

    fun confirmAutoStart() = controlSessionStore.confirmAutoStart()

    fun stopAuto() = controlSessionStore.stopAuto()

    fun startNatural() = controlSessionStore.startNatural()

    fun stopNatural() = controlSessionStore.stopNatural()

    fun setNaturalEnergy(value: Float) = controlSessionStore.setNaturalEnergy(value)

    fun setNaturalVariety(value: Float) = controlSessionStore.setNaturalVariety(value)

    fun setNaturalMaxSpeed(value: Float) = controlSessionStore.setNaturalMaxSpeed(value)

    fun setNaturalAiEnabled(enabled: Boolean) = controlSessionStore.setNaturalAiEnabled(enabled)

    fun setNaturalAiAddress(address: String) = controlSessionStore.setNaturalAiAddress(address)

    fun loadNaturalAiModel() = controlSessionStore.loadNaturalAiModel()

    fun setNaturalAiReasoningRelay(enabled: Boolean) = controlSessionStore.setNaturalAiReasoningRelay(enabled)

    // ---- Voix (2.3.26) ----
    /** Priority STOP of the Voix mode (also the API the future on-device model calls). */
    fun voiceStopNow() = controlSessionStore.voiceStopNow()

    fun setVoiceMaxSpeed(value: Float) = controlSessionStore.setVoiceMaxSpeed(value)

    fun setVoiceDepthRange(min: Float, max: Float) = controlSessionStore.setVoiceDepthRange(min, max)

    fun setVoiceShortWordStop(enabled: Boolean) = controlSessionStore.setVoiceShortWordStop(enabled)

    /** 2.3.29: app in background / screen off: cancel phone producers with STOP, no resume. */
    fun onAppBackground() = controlSessionStore.onAppBackground()

    /** 2.3.29: volume keys on the Control screen. */
    fun isMotionModeActive(): Boolean = controlSessionStore.isMotionModeActive()

    fun hardwareStop() = controlSessionStore.hardwareStop()

    fun pause() = controlSessionStore.pause()

    fun resume() = controlSessionStore.resume()
}
