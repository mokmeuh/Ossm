package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ossm.remote.audio.AudioLevelMonitor
import com.ossm.remote.ble.BleManager
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.data.repository.ControlSafetySettingsRepository
import com.ossm.remote.data.repository.UserHabitsRepository
import com.ossm.remote.model.AutoRandomMixablePatterns
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.KnownSimplePenetrationPattern
import com.ossm.remote.model.KnownStreamingPattern
import com.ossm.remote.model.KnownStrokeEnginePattern
import com.ossm.remote.model.OssmCommand
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.PatternControlMode
import com.ossm.remote.model.Preset
import com.ossm.remote.model.StrokeEngineCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlinx.coroutines.launch

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
 *  - SENSATION_LOW  : sensation aléatoire dans la moitié « retour » (0–50 %).
 *  - SENSATION_HIGH : sensation aléatoire dans la moitié « aller » (50–100 %).
 * SENSATION_LOW + SENSATION_HIGH cochées ensemble = plage complète 0–100 %.
 */
enum class RandomTarget {
    SPEED,
    DEPTH_MIN,
    DEPTH_MAX,
    SENSATION_LOW,
    SENSATION_HIGH
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
/** Enregistreur de mouvement Live : un seul bouton qui cycle. */
enum class LiveRecState { IDLE, ARMED, RECORDING, PLAYING }

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
    val sensation: Float = 0f,
    val progressiveMaxSpeed: Float = 1f,   // red ceiling for the Progressif ramp (0..1)
    val chaosAtMax: Boolean = false,       // when ramp hits max: random varied strokes
    // Mode aléatoire « Teasing & Pounding » : chaque flag fait varier son paramètre
    // dans les bornes réglées par l'utilisateur (voir RandomTarget). Les valeurs de
    // slider ci-dessus restent les MAX/ancres ; l'aléatoire est envoyé à la machine
    // sans écraser ces valeurs affichées.
    val randSpeed: Boolean = false,
    val randDepthMin: Boolean = false,
    val randDepthMax: Boolean = false,
    val randSensationLow: Boolean = false,   // sensation 0–50 %
    val randSensationHigh: Boolean = false,  // sensation 50–100 %
    // Mode « à l'écoute » : le micro biaise le mode aléatoire vers le haut (plus
    // fort tu réagis, plus l'intensité monte et reste dans le haut des plages).
    val listeningMode: Boolean = false,
    // Inversion du sens du mode Live (réglable par l'utilisateur).
    val liveInvert: Boolean = false,
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
    // (stroke=100/depth=100) : le pad Live n'est actif qu'à ce moment-là.
    val streamingReady: Boolean = false,
    // Assistant de plage au toucher (non null = assistant en cours).
    val rangeWizard: RangeWizardState? = null,
    // Ordre personnalisé des patterns (keys) ; vide = ordre naturel.
    val patternOrder: List<String> = emptyList(),
    // Enregistreur de mouvement du mode Live.
    val liveRec: LiveRecState = LiveRecState.IDLE,
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
        get() = randSpeed || randDepthMin || randDepthMax || randSensationLow || randSensationHigh
}

/** Patterns qui exposent le mode aléatoire par cases. */
private const val TEASING_POUNDING_KEY = "teasingPounding"
private const val SIMPLE_STROKE_KEY = "simpleStroke"
/** Patterns sur lesquels le mode aléatoire (piston) est disponible. */
private val RANDOM_CAPABLE_KEYS = setOf(TEASING_POUNDING_KEY, SIMPLE_STROKE_KEY)

@HiltViewModel
class ControlViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val safetySettingsRepository: ControlSafetySettingsRepository,
    private val userHabitsRepository: UserHabitsRepository,
    private val audioLevelMonitor: AudioLevelMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(ControlUiState())
    val uiState: StateFlow<ControlUiState> = _uiState.asStateFlow()

    /** Niveau sonore live (0..1) pour le témoin visuel du mode à l'écoute. */
    val listeningLevel: StateFlow<Float> = audioLevelMonitor.level

    private val sessionBypass = mutableSetOf<GuardedControl>()

    init {
        viewModelScope.launch {
            safetySettingsRepository.settings.collect { settings ->
                _uiState.update { current ->
                    current.copy(
                        speedGuard = SliderGuardUiState(
                            enabled = settings.speed.enabled,
                            thresholdPercent = settings.speed.thresholdPercent
                        ),
                        depthGuard = SliderGuardUiState(
                            enabled = settings.depth.enabled,
                            thresholdPercent = settings.depth.thresholdPercent
                        )
                    )
                }
            }
        }

        viewModelScope.launch {
            bleManager.availablePatterns.collect { patterns ->
                _uiState.update { current ->
                    val safePatterns = patterns.ifEmpty { listOf(KnownStrokeEnginePattern) }
                    val mergedPatterns = if (safePatterns.isEmpty()) KnownFallbackPatterns else safePatterns
                    val selectedKey = when {
                        mergedPatterns.any { it.key == current.activePatternKey } -> current.activePatternKey
                        mergedPatterns.any { it.key == KnownStrokeEnginePattern.key } -> KnownStrokeEnginePattern.key
                        else -> mergedPatterns.first().key
                    }
                    current.copy(
                        availablePatterns = mergedPatterns,
                        activePatternKey = selectedKey
                    )
                }
            }
        }

        // À la connexion, la machine est généralement au MENU : aucun set:speed ne
        // fonctionne tant qu'un mode n'est pas actif (d'où l'ancien rituel « Stop +
        // re-cliquer le pattern »). On entre automatiquement dans le mode du pattern
        // sélectionné, vitesse forcée à 0 (aucun mouvement tant que l'utilisateur
        // ne monte pas la vitesse lui-même).
        viewModelScope.launch {
            var wasConnected = false
            bleManager.connectionState.collect { conn ->
                val connected = conn is BleConnectionState.Connected
                if (connected && !wasConnected) {
                    _uiState.update { it.copy(speed = 0f, isRunning = false, isPaused = false) }
                    autoEnterModeOnConnect()
                }
                wasConnected = connected
            }
        }

        viewModelScope.launch {
            userHabitsRepository.patternOrder.collect { order ->
                _uiState.update { it.copy(patternOrder = order) }
            }
        }

        viewModelScope.launch {
            safetySettingsRepository.listeningModeEnabled.collect { enabled ->
                _uiState.update { it.copy(listeningMode = enabled) }
                updateListeningMonitor()
            }
        }

        viewModelScope.launch {
            safetySettingsRepository.liveInvertEnabled.collect { enabled ->
                bleManager.liveInvert = enabled
                _uiState.update { it.copy(liveInvert = enabled) }
            }
        }

        viewModelScope.launch {
            bleManager.streamingReady.collect { ready ->
                if (ready) {
                    // Le firmware vient de se replacer en position 0 (home) : on
                    // resynchronise le suivi local pour que slider et machine repartent
                    // du même point (pad au repos = home). ON N'ENVOIE AUCUNE COMMANDE
                    // ICI (comportement baseline v1.20.5) : le firmware est déjà au home,
                    // et tout stream: envoyé maintenant risquerait de le déplacer.
                    streamTarget = 0f
                    streamSent = 0f
                }
                _uiState.update { it.copy(streamingReady = ready) }
            }
        }

        // Learned habits: pre-select the pattern/depth range the user typically starts with,
        // based on their first manual choice of each of the last few days.
        viewModelScope.launch {
            val defaults = userHabitsRepository.sessionDefaults.first()
            if (defaults.patternKey != null || defaults.depthMin != null) {
                _uiState.update { current ->
                    current.copy(
                        activePatternKey = defaults.patternKey
                            ?.takeIf { key -> current.availablePatterns.any { it.key == key } }
                            ?: current.activePatternKey,
                        depthMin = defaults.depthMin ?: current.depthMin,
                        depthMax = defaults.depthMax ?: current.depthMax
                    )
                }
            }
        }
    }

    private suspend fun autoEnterModeOnConnect() {
        // Attend le premier état réel notifié par la machine.
        val st = withTimeoutOrNull(6_000) {
            bleManager.machineState.first { it.state != "unknown" }
        } ?: return
        val pattern = _uiState.value.activePattern ?: return
        // Uniquement stroke engine : Progressif lancerait sa rampe tout seul, et
        // streaming/auto ne doivent jamais démarrer sans geste explicite.
        if (pattern.mode != PatternControlMode.STROKE_ENGINE) return
        if (st.state.contains("menu", ignoreCase = true)) {
            activatePattern(pattern.key)
        }
    }

    fun activatePattern(patternKey: String) {
        val pattern = _uiState.value.availablePatterns.firstOrNull { it.key == patternKey } ?: return
        viewModelScope.launch { userHabitsRepository.recordPatternHabit(pattern.key) }
        // Leaving any previous live/progressive session: stop the tickers.
        liveLoopJob?.cancel(); liveLoopJob = null
        streamJob?.cancel(); streamJob = null
        streamTarget = 0f; streamSent = 0f
        progressiveJob?.cancel(); progressiveJob = null
        chaosJob?.cancel(); chaosJob = null
        teasingRandomJob?.cancel(); teasingRandomJob = null
        audioLevelMonitor.stop()
        autoJob?.cancel(); autoJob = null; autoFirmwareMode = null
        // Repart sur des cases décochées à chaque changement de pattern (jamais
        // d'aléatoire surprise en revenant sur Teasing & Pounding).
        _uiState.update {
            it.copy(
                randSpeed = false, randDepthMin = false, randDepthMax = false,
                randSensationLow = false, randSensationHigh = false
            )
        }
        _uiState.update { it.copy(autoRunning = false, autoIntensity = 0f, pendingAutoStart = false, liveRec = LiveRecState.IDLE) }
        // Sensation always starts at minimum, except Teasing/Pounding where neutral (50%) is the base.
        val defaultSensation = if (pattern.key == "teasingPounding") 0.5f else 0f
        _uiState.update {
            it.copy(
                activePatternKey = pattern.key,
                sensation = defaultSensation,
                progressiveMaxSpeed = 1f,
                pendingManualChange = null,
                isRunning = false
            )
        }
        if (pattern.mode == PatternControlMode.AUTO_RANDOM) return
        when (pattern.mode) {
            PatternControlMode.AUTO_RANDOM -> {
                // Just select it. The user picks modes + limits, then presses start (with a
                // limits-reminder popup). The mix engine handles its own firmware mode switches.
            }
            PatternControlMode.STREAMING -> {
                bleManager.sendCommand(OssmCommand.ActivatePattern(pattern))
            }
            PatternControlMode.PROGRESSIVE -> {
                bleManager.sendCommand(OssmCommand.ActivatePattern(pattern))
                viewModelScope.launch {
                    // Attend l'état strokeEngine RÉEL avant de lancer la rampe
                    // (remplace l'ancien délai aveugle de 1200 ms).
                    withTimeoutOrNull(8_000) {
                        bleManager.machineState.first {
                            it.state.contains("strokeEngine", ignoreCase = true) && !it.isPreflight
                        }
                    }
                    startProgressiveRamp(1)
                }
            }
            else -> {
                bleManager.sendCommand(OssmCommand.ActivatePattern(pattern))
                // Le firmware réinitialise ses réglages à l'entrée du mode : application
                // VÉRIFIÉE par l'état réel (remplace l'ancien délai aveugle de 1200 ms
                // dont l'envoi pouvait être perdu → plage réelle ≠ plage affichée).
                val st = _uiState.value
                bleManager.applyStrokeEngineVerified(
                    StrokeEngineCommand(
                        speed = st.speed,
                        depthMin = st.depthMin,
                        depthMax = st.depthMax,
                        sensation = st.sensation
                    )
                )
            }
        }
    }

    fun requestSpeedChange(value: Float) {
        val current = _uiState.value
        if (!current.activePatternSupportsControls) return
        // In Progressif, setting the speed (re)starts the ramp from that value up to 100%.
        if (current.activePattern?.mode == PatternControlMode.PROGRESSIVE) {
            startProgressiveRamp((value * 100f).toInt().coerceIn(1, 100))
            return
        }
        maybeApplyManualChange(
            control = GuardedControl.SPEED,
            speed = value,
            depthMin = current.depthMin,
            depthMax = current.depthMax
        )
    }

    fun requestDepthRangeChange(min: Float, max: Float) {
        val current = _uiState.value
        if (!current.activePatternSupportsControls) return
        val lo = min.coerceIn(0f, 1f)
        val hi = max.coerceIn(0f, 1f)
        viewModelScope.launch { userHabitsRepository.recordDepthHabit(lo, hi) }
        maybeApplyManualChange(
            control = GuardedControl.DEPTH,
            speed = current.speed,
            depthMin = lo,
            depthMax = hi
        )
    }

    // ---- Real-time sliders: apply WHILE dragging, not only on release ----
    // Single-parameter writes, throttled, so the machine follows the finger live.
    private var lastLiveSendMs = 0L
    private fun liveThrottleOk(): Boolean {
        val now = System.currentTimeMillis()
        // 30ms ≈ 33 updates/s — feasible now that the BLE connection is HIGH priority.
        return if (now - lastLiveSendMs >= 30) { lastLiveSendMs = now; true } else false
    }

    fun setSpeedLive(value: Float) {
        val current = _uiState.value
        val mode = current.activePattern?.mode ?: return
        if (mode == PatternControlMode.PROGRESSIVE || mode == PatternControlMode.STREAMING) return
        // Frozen while a guard popup is open: the state/machine must NOT move until resolved,
        // otherwise "Annuler" would have nothing correct to fall back to.
        if (current.pendingManualChange != null) return
        val v = value.coerceIn(0f, 1f)
        // Garde ASYMÉTRIQUE : ralentir passe toujours sans confirmation.
        if (current.speedGuard.enabled && GuardedControl.SPEED !in sessionBypass &&
            (v - current.speed) > current.speedGuard.thresholdPercent / 100f
        ) {
            _uiState.update {
                it.copy(
                    pendingManualChange = PendingManualChange(
                        control = GuardedControl.SPEED,
                        speed = v,
                        depthMin = current.depthMin,
                        depthMax = current.depthMax,
                        thresholdPercent = current.speedGuard.thresholdPercent
                    )
                )
            }
            return
        }
        _uiState.update { it.copy(speed = v, isRunning = v > 0f) }
        if (liveThrottleOk()) bleManager.liveSet("speed", (v * 100f).toInt())
    }

    fun setSensationLive(value: Float) {
        val mode = _uiState.value.activePattern?.mode ?: return
        val v = value.coerceIn(0f, 1f)
        _uiState.update { it.copy(sensation = v) }
        // Progressif: sensation = ramp rate (no firmware write). Stroke engine: live set:sensation.
        if (mode == PatternControlMode.STROKE_ENGINE && liveThrottleOk()) {
            bleManager.liveSet("sensation", (v * 100f).toInt())
        }
    }

    fun setDepthLive(min: Float, max: Float) {
        val current = _uiState.value
        val mode = current.activePattern?.mode ?: return
        if (mode == PatternControlMode.STREAMING) return
        if (current.pendingManualChange != null) return
        val lo = min.coerceIn(0f, 1f)
        val hi = max.coerceIn(lo, 1f)
        // Garde ASYMÉTRIQUE (comme le commit) : seulement si l'intensité AUGMENTE.
        if (current.depthGuard.enabled && GuardedControl.DEPTH !in sessionBypass &&
            maxOf(
                hi - current.depthMax,       // fond plus profond
                current.depthMin - lo        // retrait reculé = course allongée
            ) > current.depthGuard.thresholdPercent / 100f
        ) {
            _uiState.update {
                it.copy(
                    pendingManualChange = PendingManualChange(
                        control = GuardedControl.DEPTH,
                        speed = current.speed,
                        depthMin = lo,
                        depthMax = hi,
                        thresholdPercent = current.depthGuard.thresholdPercent
                    )
                )
            }
            return
        }
        _uiState.update { it.copy(depthMin = lo, depthMax = hi) }
        if (liveThrottleOk()) {
            bleManager.liveSet("depth", (hi * 100f).toInt())
            bleManager.liveSet("stroke", ((hi - lo) * 100f).toInt())
        }
    }

    fun confirmPendingManualChange(skipForSession: Boolean, neverAskAgain: Boolean) {
        val pending = _uiState.value.pendingManualChange ?: return
        if (skipForSession) {
            sessionBypass += pending.control
        }
        viewModelScope.launch {
            if (neverAskAgain) {
                when (pending.control) {
                    GuardedControl.SPEED -> safetySettingsRepository.setSpeedGuardEnabled(false)
                    GuardedControl.DEPTH -> safetySettingsRepository.setDepthGuardEnabled(false)
                }
            }
            applyManualChange(pending.speed, pending.depthMin, pending.depthMax)
        }
    }

    fun dismissPendingManualChange() {
        _uiState.update { it.copy(pendingManualChange = null) }
    }

    fun setSpeedGuardEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                sessionBypass.remove(GuardedControl.SPEED)
            }
            safetySettingsRepository.setSpeedGuardEnabled(enabled)
        }
    }

    fun setDepthGuardEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                sessionBypass.remove(GuardedControl.DEPTH)
            }
            safetySettingsRepository.setDepthGuardEnabled(enabled)
        }
    }

    fun stop() {
        liveLoopJob?.cancel(); liveLoopJob = null
        _uiState.update { it.copy(liveRec = LiveRecState.IDLE) }
        streamJob?.cancel(); streamJob = null
        progressiveJob?.cancel(); progressiveJob = null
        chaosJob?.cancel(); chaosJob = null
        teasingRandomJob?.cancel(); teasingRandomJob = null
        audioLevelMonitor.stop()
        autoJob?.cancel(); autoJob = null
        autoFirmwareMode = null
        _uiState.update { it.copy(isRunning = false, isPaused = false, autoRunning = false, autoIntensity = 0f, speed = 0f, pendingManualChange = null) }
        bleManager.sendCommand(OssmCommand.Stop)
    }

    fun home() {
        val pattern = _uiState.value.activePattern
        _uiState.update { it.copy(isRunning = false, speed = 0f, pendingManualChange = null) }
        when (pattern?.mode) {
            PatternControlMode.SIMPLE_PENETRATION -> bleManager.home("simplePenetration", 0)
            PatternControlMode.STREAMING -> bleManager.sendCommand(OssmCommand.EnterStreaming)
            else -> bleManager.home("strokeEngine", pattern?.id ?: 0)
        }
    }

    // ---- Progressif: auto speed-ramp on full strokes ----
    private var progressiveJob: Job? = null

    /** User drags the RED ceiling: the purple speed ramps only up to this. */
    fun setProgressiveMaxSpeed(value: Float) {
        if (_uiState.value.activePattern?.mode != PatternControlMode.PROGRESSIVE) return
        val max = value.coerceIn(0.01f, 1f)
        _uiState.update {
            it.copy(progressiveMaxSpeed = max, speed = it.speed.coerceAtMost(max))
        }
        // Don't restart the ramp — just cap. If it was already capped/finished, resume toward new max.
        if (progressiveJob?.isActive != true && _uiState.value.speed < max) {
            startProgressiveRamp((_uiState.value.speed * 100f).toInt().coerceAtLeast(1))
        }
    }

    /** Per-stroke speed increment, driven by the sensation slider (min → +1, max → +10). */
    private fun progressiveIncrement(): Int {
        val sensation = _uiState.value.sensation.coerceIn(0f, 1f)
        return (1 + (sensation * (PROGRESSIVE_MAX_INCREMENT - 1))).toInt().coerceIn(1, PROGRESSIVE_MAX_INCREMENT)
    }

    /** (Re)start the speed ramp from [fromPercent], +increment per stroke up to the red ceiling. */
    private fun startProgressiveRamp(fromPercent: Int) {
        progressiveJob?.cancel()
        chaosJob?.cancel(); chaosJob = null
        var speedPercent = fromPercent.coerceIn(1, 100)
        _uiState.update {
            val cap = (it.progressiveMaxSpeed * 100f).toInt()
            it.copy(speed = speedPercent.coerceAtMost(cap) / 100f, isRunning = true)
        }
        sendCurrentStrokeEngineCommand()
        progressiveJob = viewModelScope.launch {
            while (isActive) {
                val cap = (_uiState.value.progressiveMaxSpeed * 100f).toInt().coerceIn(1, 100)
                if (speedPercent >= cap) break
                delay(progressiveStrokeMs(speedPercent))
                if (!isActive) break
                speedPercent = (speedPercent + progressiveIncrement()).coerceAtMost(cap)
                _uiState.update { it.copy(speed = speedPercent / 100f) }
                sendCurrentStrokeEngineCommand()
            }
            // Reached the red ceiling: optionally switch into random "chaos" strokes.
            if (isActive && _uiState.value.chaosAtMax) startChaos()
        }
    }

    fun setChaosAtMax(enabled: Boolean) {
        _uiState.update { it.copy(chaosAtMax = enabled) }
        if (_uiState.value.activePattern?.mode != PatternControlMode.PROGRESSIVE) return
        if (!enabled) {
            chaosJob?.cancel(); chaosJob = null
            // Settle back to a steady speed at the ceiling.
            _uiState.update { it.copy(speed = it.progressiveMaxSpeed) }
            sendCurrentStrokeEngineCommand()
        } else {
            // If the ramp already finished (we're at the ceiling), start chaos now.
            val st = _uiState.value
            if (progressiveJob?.isActive != true && st.speed >= st.progressiveMaxSpeed - 0.005f) {
                startChaos()
            }
        }
    }

    // Random varied strokes once the ramp tops out: switches pattern/speed/sensation at
    // random intervals. Uses the stroke engine (oscillates from home → no slam risk); depth
    // stays within the user's range. "The more random the better."
    private var chaosJob: Job? = null
    private fun startChaos() {
        chaosJob?.cancel()
        chaosJob = viewModelScope.launch {
            while (isActive) {
                val max = _uiState.value.progressiveMaxSpeed.coerceIn(0.1f, 1f)
                // ~55% of the time, jump to a different stroke-engine pattern for a new feel.
                if (Random.nextFloat() < 0.55f) {
                    bleManager.setStrokeEnginePattern(Random.nextInt(0, 7))
                    delay(80)
                }
                // Random speed between 35% and 100% of the ceiling, random sensation.
                val newSpeed = ((0.35f + Random.nextFloat() * 0.65f) * max).coerceIn(0.05f, max)
                val newSensation = Random.nextFloat()
                _uiState.update { it.copy(speed = newSpeed, sensation = newSensation) }
                sendCurrentStrokeEngineCommand()
                delay(Random.nextLong(1200L, 4500L))
            }
        }
    }

    // ---- Mode aléatoire / piston (cases à cocher) — Teasing & Pounding + Simple Stroke ----
    // Chaque case cochée fait varier son paramètre par une MARCHE ALÉATOIRE DOUCE (petits
    // pas, pas de saut brusque), dans les bornes réglées par l'utilisateur :
    //   - vitesse : promenée entre 0 et le max réglé ;
    //   - retrait / fond : le point se promène entre min et max réglés ;
    //   - sensation (Teasing seulement) : moitié basse / haute / les deux.
    // N'écrase PAS les valeurs de slider (qui restent les plafonds/ancres) : envoie des
    // commandes stroke-engine vérifiées (depth avant stroke → jamais de coup au fond).
    private var teasingRandomJob: Job? = null
    // Valeurs courantes de la marche aléatoire (conservées entre deux pas pour lisser).
    private var randWalkSpeed = 0f
    private var randWalkMin = 0f
    private var randWalkMax = 1f
    private var randWalkSens = 0.5f

    fun setRandomMode(target: RandomTarget, enabled: Boolean) {
        _uiState.update { st ->
            when (target) {
                RandomTarget.SPEED -> st.copy(randSpeed = enabled)
                RandomTarget.DEPTH_MIN -> st.copy(randDepthMin = enabled)
                RandomTarget.DEPTH_MAX -> st.copy(randDepthMax = enabled)
                RandomTarget.SENSATION_LOW -> st.copy(randSensationLow = enabled)
                RandomTarget.SENSATION_HIGH -> st.copy(randSensationHigh = enabled)
            }
        }
        updateTeasingRandomTicker()
    }

    /**
     * Profondeur aléatoire = UNE seule case : le piston se promène entre le min et le
     * max réglés (on active retrait ET fond ensemble, promenade de toute la fenêtre).
     */
    fun setDepthRandom(enabled: Boolean) {
        _uiState.update { it.copy(randDepthMin = enabled, randDepthMax = enabled) }
        updateTeasingRandomTicker()
    }

    /** Inverse le sens du mode Live (persisté). Effet immédiat sur les prochaines commandes stream. */
    fun setLiveInvert(enabled: Boolean) {
        viewModelScope.launch { safetySettingsRepository.setLiveInvertEnabled(enabled) }
        bleManager.liveInvert = enabled
        _uiState.update { it.copy(liveInvert = enabled) }
    }

    /** Active/désactive le mode « à l'écoute » (persisté). */
    fun setListeningMode(enabled: Boolean) {
        viewModelScope.launch { safetySettingsRepository.setListeningModeEnabled(enabled) }
        // Mise à jour optimiste immédiate (le collect persistera aussi).
        _uiState.update { it.copy(listeningMode = enabled) }
        updateListeningMonitor()
    }

    /** Démarre le micro seulement quand le mode à l'écoute ET le mode aléatoire tournent. */
    private fun updateListeningMonitor() {
        val st = _uiState.value
        val shouldListen = st.listeningMode && teasingRandomJob?.isActive == true
        if (shouldListen) {
            audioLevelMonitor.start(viewModelScope)
        } else {
            audioLevelMonitor.stop()
        }
    }

    /** Un pas de marche aléatoire borné : reste proche de [prev] (≤ maxStep), dans [lo, hi]. */
    private fun randomWalkStep(prev: Float, lo: Float, hi: Float, maxStep: Float): Float {
        if (hi <= lo) return lo
        val step = (Random.nextFloat() - 0.5f) * 2f * maxStep   // ∈ [-maxStep, +maxStep]
        return (prev + step).coerceIn(lo, hi)
    }

    private fun updateTeasingRandomTicker() {
        val st = _uiState.value
        val active = st.activePatternKey in RANDOM_CAPABLE_KEYS &&
            st.activePattern?.mode == PatternControlMode.STROKE_ENGINE &&
            st.anyRandomActive
        if (!active) {
            teasingRandomJob?.cancel(); teasingRandomJob = null
            audioLevelMonitor.stop()
            // Retour propre aux valeurs des sliders quand on désactive tout.
            if (st.activePattern?.mode == PatternControlMode.STROKE_ENGINE) {
                sendCurrentStrokeEngineCommand()
            }
            return
        }
        if (teasingRandomJob?.isActive == true) return
        // Point de départ de la marche = valeurs actuelles des sliders.
        randWalkSpeed = st.speed
        randWalkMin = st.depthMin
        randWalkMax = st.depthMax
        randWalkSens = st.sensation
        teasingRandomJob = viewModelScope.launch {
            while (isActive) {
                val s = _uiState.value
                if (s.activePatternKey !in RANDOM_CAPABLE_KEYS || !s.anyRandomActive) break

                val minSpan = 0.05f
                val baseMin = s.depthMin
                val baseMax = s.depthMax

                // Mode à l'écoute : niveau sonore 0..1 (0 si désactivé) → remonte le
                // PLANCHER de chaque promenade vers le haut (plus tu réagis, plus ça
                // reste vite/profond, sans jamais dépasser tes maximums réglés).
                val bias = if (s.listeningMode) audioLevelMonitor.level.value.coerceIn(0f, 1f) else 0f

                // Vitesse : marche douce entre un plancher (0, relevé par le son) et le
                // max réglé (pas ≤ 25 % de la plage pour éviter les grands écarts).
                val speed = if (s.randSpeed) {
                    val loSpeed = bias * 0.8f * s.speed
                    randWalkSpeed = randomWalkStep(randWalkSpeed.coerceIn(loSpeed, s.speed), loSpeed, s.speed, s.speed * 0.25f)
                    randWalkSpeed
                } else s.speed

                // Retrait : marche douce dans [plancher, max−5 %] (jamais plus reculé que min).
                val rMin = if (s.randDepthMin) {
                    val hi = (baseMax - minSpan).coerceAtLeast(baseMin)
                    val loMin = baseMin + bias * 0.7f * (hi - baseMin)
                    randWalkMin = randomWalkStep(randWalkMin.coerceIn(loMin, hi), loMin, hi, (hi - baseMin) * 0.3f)
                    randWalkMin
                } else baseMin

                // Fond : marche douce dans [plancher, max réglé] (jamais plus profond que max).
                val rMax = if (s.randDepthMax) {
                    val lo = (rMin + minSpan).coerceAtMost(baseMax)
                    val loMax = lo + bias * 0.9f * (baseMax - lo)
                    randWalkMax = randomWalkStep(randWalkMax.coerceIn(loMax, baseMax), loMax, baseMax, (baseMax - lo) * 0.3f)
                    randWalkMax
                } else baseMax.coerceAtLeast((rMin + minSpan).coerceAtMost(baseMax))

                // Sensation : moitié basse (0–50), moitié haute (50–100), ou les deux.
                val sensation = if (s.randSensationLow || s.randSensationHigh) {
                    val lo = if (s.randSensationLow) 0f else 0.5f
                    val hi = if (s.randSensationHigh) 1f else 0.5f
                    randWalkSens = randomWalkStep(randWalkSens.coerceIn(lo, hi), lo, hi, (hi - lo) * 0.3f)
                    randWalkSens
                } else s.sensation

                bleManager.sendCommand(
                    OssmCommand.UpdateStrokeEngine(
                        StrokeEngineCommand(
                            speed = speed,
                            depthMin = rMin,
                            depthMax = rMax,
                            sensation = sensation
                        )
                    )
                )
                delay(Random.nextLong(TEASE_RANDOM_MIN_MS, TEASE_RANDOM_MAX_MS))
            }
        }
        // Démarre le micro si le mode à l'écoute est actif (maintenant que le ticker tourne).
        updateListeningMonitor()
    }

    /**
     * Estimate one full back-and-forth period (ms) so the ramp increments ONCE per stroke.
     * Firmware (StrokeEngine::_recalcTimeOfStroke): timeOfStroke = 3*stroke / ((speed/100)*maxStepPerSec),
     * i.e. period ∝ strokeDistance / speed. We can't read the machine's measuredStrokeSteps, so
     * PROGRESSIVE_K_MS is an empirically-calibrated constant (tunable for feel).
     */
    private fun progressiveStrokeMs(speedPercent: Int): Long {
        val s = speedPercent.coerceIn(1, 100)
        val st = _uiState.value
        val strokeFraction = (st.depthMax - st.depthMin).coerceIn(0.05f, 1f)
        val ms = (PROGRESSIVE_K_MS * strokeFraction / s).toLong()
        return ms.coerceIn(PROGRESSIVE_MIN_MS, PROGRESSIVE_MAX_MS)
    }

    // ---- Auto Random: mixes the chosen patterns within limits, intensity rising over time ----
    private var autoJob: Job? = null
    private var autoFirmwareMode: PatternControlMode? = null   // last mode we put the machine in

    fun toggleAutoSelected(key: String) {
        _uiState.update {
            val set = it.autoSelectedKeys.toMutableSet()
            if (key in set) set.remove(key) else set.add(key)
            it.copy(autoSelectedKeys = set)
        }
    }

    fun setAutoMaxSpeed(value: Float) {
        _uiState.update { it.copy(autoMaxSpeed = value.coerceIn(0.1f, 1f)) }
    }

    fun setAutoIntensityCap(value: Float) {
        _uiState.update { it.copy(autoIntensityCap = value.coerceIn(0.1f, 1f)) }
    }

    fun setAutoRandomness(value: Float) {
        _uiState.update { it.copy(autoRandomness = value.coerceIn(0f, 1f)) }
    }

    fun setAutoInitialIntensity(value: Float) {
        _uiState.update { it.copy(autoInitialIntensity = value.coerceIn(0f, 1f)) }
    }

    /** Pressing "start" first asks the user to confirm their limits are set. */
    fun requestAutoStart() {
        if (_uiState.value.autoSelectedKeys.isEmpty()) return
        _uiState.update { it.copy(pendingAutoStart = true) }
    }
    fun cancelAutoStart() { _uiState.update { it.copy(pendingAutoStart = false) } }

    fun confirmAutoStart() {
        val startIntensity = _uiState.value.autoInitialIntensity
        _uiState.update { it.copy(pendingAutoStart = false, autoRunning = true, autoIntensity = startIntensity, isRunning = true, isPaused = false) }
        startAutoMix(startIntensity)
    }

    fun stopAuto() {
        autoJob?.cancel(); autoJob = null
        autoFirmwareMode = null
        _uiState.update { it.copy(autoRunning = false, autoIntensity = 0f, isRunning = false, isPaused = false) }
        bleManager.sendCommand(OssmCommand.Stop)
    }

    fun pause() {
        val st = _uiState.value
        if (!st.isRunning || st.isPaused) return
        val mode = st.activePattern?.mode ?: return
        liveLoopJob?.cancel(); liveLoopJob = null
        streamJob?.cancel(); streamJob = null
        progressiveJob?.cancel(); progressiveJob = null
        chaosJob?.cancel(); chaosJob = null
        teasingRandomJob?.cancel(); teasingRandomJob = null
        audioLevelMonitor.stop()
        autoJob?.cancel(); autoJob = null
        _uiState.update { it.copy(isPaused = true, isRunning = false, liveRec = LiveRecState.IDLE) }
        when (mode) {
            PatternControlMode.STREAMING -> { /* ticker stopped → machine holds last sent position */ }
            else -> bleManager.liveSet("speed", 0)
        }
    }

    fun resume() {
        val st = _uiState.value
        if (!st.isPaused) return
        val mode = st.activePattern?.mode ?: return
        _uiState.update { it.copy(isPaused = false, isRunning = true) }
        when (mode) {
            PatternControlMode.STREAMING -> setStreamActive(true)
            PatternControlMode.PROGRESSIVE -> startProgressiveRamp((st.speed * 100f).toInt().coerceAtLeast(1))
            PatternControlMode.AUTO_RANDOM -> startAutoMix(st.autoIntensity)
            else -> {
                // Stroke engine / simple penetration: re-send the current speed command
                sendCurrentStrokeEngineCommand()
                // Relance le mode aléatoire s'il était actif avant la pause.
                updateTeasingRandomTicker()
            }
        }
    }

    private fun startAutoMix(initialIntensity: Float = 0f) {
        autoJob?.cancel()
        autoFirmwareMode = null
        autoJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            var lastPattern: OssmPattern? = null
            while (isActive) {
                val st = _uiState.value
                val keys = st.autoSelectedKeys.toList()
                if (keys.isEmpty()) break

                // Overall session trend: monotonic rise from initialIntensity → cap over BUILD_UP window.
                // This is what's displayed in the UI so the user sees a smooth progression.
                val elapsed = System.currentTimeMillis() - startMs
                val buildup = (elapsed.toFloat() / AUTO_BUILDUP_MS).coerceIn(0f, 1f)
                val trend = (initialIntensity + buildup * (st.autoIntensityCap - initialIntensity))
                    .coerceIn(0f, st.autoIntensityCap)

                // Organic wave: 3 overlapping sines with non-harmonic periods (~11s, ~4.7s, ~1.9s).
                // Amplitude grows with trend so early-session variation is subtle.
                val t = elapsed.toDouble() / 1000.0
                val wave = (
                    0.50 * kotlin.math.sin(t / 11.3) +
                    0.35 * kotlin.math.sin(t / 4.7) +
                    0.15 * kotlin.math.sin(t / 1.9)
                ).toFloat()
                val intensity = (trend + wave * trend * 0.30f).coerceIn(0f, st.autoIntensityCap)

                val randomness = st.autoRandomness.coerceIn(0f, 1f)
                _uiState.update { it.copy(autoIntensity = trend) }  // show trend, not wave

                val candidates = AutoRandomMixablePatterns.filter { it.key in keys }
                val switchChance = interpolate(0.18f, 0.92f, randomness)
                val pattern = when {
                    candidates.isEmpty() -> KnownStrokeEnginePattern
                    lastPattern == null -> candidates.random()
                    Random.nextFloat() < switchChance -> candidates.random()
                    else -> lastPattern!!
                }
                lastPattern = pattern

                // Switch firmware mode only when needed (avoids the costly go:menu round-trip).
                if (pattern.mode != autoFirmwareMode) {
                    bleManager.sendCommand(OssmCommand.ActivatePattern(pattern))
                    autoFirmwareMode = pattern.mode
                    delay(1300) // let the mode switch settle
                } else if (pattern.mode == PatternControlMode.STROKE_ENGINE) {
                    bleManager.setStrokeEnginePattern(pattern.id ?: 0)
                    delay(120)
                }

                // Speed: user-defined intensity raises the floor over time, while randomness
                // controls how far each step is allowed to wander from that floor.
                val floor = interpolate(0.12f, 0.42f, intensity).coerceAtMost(st.autoMaxSpeed)
                val speedWander = interpolate(0.15f, 1f, randomness)
                val speed = (
                    floor + (st.autoMaxSpeed - floor) * Random.nextFloat() * speedWander
                ).coerceIn(0.05f, st.autoMaxSpeed)

                // Depth: stay inside the user's hard range. Intensity increases average stroke
                // span; randomness changes how much the span and sub-range wander around.
                val lo = st.depthMin
                val hi = st.depthMax
                val fullSpan = (hi - lo).coerceIn(0.05f, 1f)
                val minStroke = fullSpan * interpolate(0.18f, 0.62f, intensity)
                val stroke = (
                    minStroke + (fullSpan - minStroke) * Random.nextFloat() * interpolate(0.2f, 1f, randomness)
                ).coerceIn(0.05f, fullSpan)
                val maxStart = (hi - stroke).coerceAtLeast(lo)
                val dMin = (
                    lo + (maxStart - lo) * Random.nextFloat() * interpolate(0.15f, 1f, randomness)
                ).coerceIn(lo, hi)
                val dMax = (dMin + stroke).coerceIn(dMin, hi)
                bleManager.liveSet("speed", (speed * 100f).toInt())
                bleManager.liveSet("depth", (dMax * 100f).toInt())
                bleManager.liveSet("stroke", (stroke * 100f).toInt())
                if (pattern.mode == PatternControlMode.STROKE_ENGINE) {
                    val sensationBase = interpolate(0.35f, 0.75f, intensity)
                    val sensationSwing = interpolate(0.08f, 0.9f, randomness)
                    val sensation = (
                        sensationBase + (Random.nextFloat() - 0.5f) * sensationSwing
                    ).coerceIn(0f, 1f)
                    bleManager.liveSet("sensation", (sensation * 100f).toInt())
                }
                _uiState.update { it.copy(speed = speed) }

                // Change frequency: TREND (not wave) drives pace so timing accelerates smoothly.
                val holdCenter = interpolate(AUTO_HOLD_MAX_MS.toFloat(), AUTO_HOLD_MIN_MS.toFloat(), trend)
                val holdJitter = interpolate(250f, 1800f, randomness).toLong()
                val minHold = (holdCenter.toLong() - holdJitter).coerceAtLeast(AUTO_HOLD_MIN_MS)
                val maxHold = (holdCenter.toLong() + holdJitter).coerceAtMost(AUTO_HOLD_MAX_MS)
                delay(Random.nextLong(minHold, maxHold.coerceAtLeast(minHold + 1)))
            }
        }
    }

    private fun interpolate(min: Float, max: Float, amount: Float): Float {
        return min + (max - min) * amount.coerceIn(0f, 1f)
    }

    // ---- Live streaming : envoi DIRECT position + temps réel écoulé ----
    // La vitesse du chariot suit la vitesse du doigt : le firmware calcule
    // vitesse = distance / temps pour chaque stream:pos:time. L'ancien ticker à
    // rampe (2,5 %/35 ms) datait de l'époque où la liaison était peu fiable — il
    // rendait tout mouvement uniformément lent, peu importe le geste.
    private var streamTarget: Float = 0f          // position voulue par le doigt (0..100)
    private var streamSent: Float = 0f            // dernière position envoyée
    private var streamJob: Job? = null            // (plus de ticker ; conservé pour les cancel)
    // Enregistreur Live : échantillons (t relatif ms, position 0-100).
    private val recSamples = ArrayList<Pair<Long, Int>>()
    private var recStartMs = 0L
    private var liveLoopJob: Job? = null

    fun setStreamTarget(positionPercent: Int) {
        streamTarget = positionPercent.toFloat().coerceIn(0f, 100f)
    }

    fun setStreamActive(active: Boolean) {
        val st = _uiState.value
        if (st.activePattern?.mode != PatternControlMode.STREAMING && st.rangeWizard == null) return
        if (active) {
            // Toucher le pad pendant une boucle = reprendre la main.
            if (st.liveRec == LiveRecState.PLAYING) stopLiveLoop()
            if (st.liveRec == LiveRecState.ARMED) {
                recSamples.clear()
                recStartMs = System.currentTimeMillis()
                _uiState.update { it.copy(liveRec = LiveRecState.RECORDING) }
            }
            if (streamJob?.isActive == true) return
            // Un Stop/pause précédent a pu mettre speed=0 : en streaming le firmware
            // ignore silencieusement tout stream:pos:time si speed=0 — on restaure au
            // plafond pour ne pas brider les gestes rapides.
            if ((bleManager.machineState.value.speed ?: 0) < 80) {
                bleManager.liveSet("speed", 80)
            }
            // Cadence fixe = temps de trajet : chaque commande finit pile quand la
            // suivante arrive → JAMAIS de file d'attente côté firmware (les commandes
            // en avance y sont exécutées séquentiellement — une file ferait rejouer
            // l'historique du doigt en retard, jusqu'à aller au fond sur de vieilles
            // cibles). La distance varie avec le geste → la vitesse suit le doigt.
            streamJob = viewModelScope.launch {
                var lastSendMs = 0L
                var nudge = false
                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (_uiState.value.liveRec == LiveRecState.RECORDING) {
                        recSamples.add((now - recStartMs) to streamTarget.toInt())
                    }
                    if (kotlin.math.abs(streamTarget - streamSent) >= 1f) {
                        streamSent = streamTarget
                        lastSendMs = now
                        bleManager.sendCommand(
                            OssmCommand.Stream(
                                positionPercent = streamTarget.toInt(),
                                // Durée > cadence : les mouvements se CHEVAUCHENT au
                                // lieu de s'arrêter entre chaque pas (anti-saccades).
                                timeMs = STREAM_MOVE_MS
                            )
                        )
                    } else if (now - lastSendMs > STREAM_REFRESH_MS) {
                        // RAPPEL périodique : un geste trop rapide fait raccourcir le
                        // mouvement par le firmware (vitesse max) → la machine reste en
                        // retard alors que l'app croit la cible atteinte. On rappelle la
                        // cible (±1 alterné : le firmware plante sur deux positions
                        // identiques consécutives) jusqu'à convergence.
                        nudge = !nudge
                        lastSendMs = now
                        val p = (streamTarget + if (nudge) 1f else -1f).coerceIn(0f, 100f)
                        bleManager.sendCommand(OssmCommand.Stream(p.toInt(), timeMs = STREAM_REFRESH_MOVE_MS))
                    }
                    delay(STREAM_CADENCE_MS)
                }
            }
        } else {
            streamJob?.cancel()
            streamJob = null
            if (_uiState.value.liveRec == LiveRecState.RECORDING) {
                // Levée du doigt : l'enregistrement part immédiatement en boucle.
                finishRecordingAndLoop()
                return
            }
            // Fin de geste : rejoint la position finale, avec deux rappels espacés
            // pour rattraper un éventuel retard (mouvements raccourcis par le firmware).
            viewModelScope.launch {
                streamSent = streamTarget
                bleManager.sendCommand(OssmCommand.Stream(streamTarget.toInt(), timeMs = 200))
                delay(300)
                bleManager.sendCommand(OssmCommand.Stream((streamTarget + 1f).coerceIn(0f, 100f).toInt(), timeMs = 300))
                delay(300)
                bleManager.sendCommand(OssmCommand.Stream((streamTarget - 1f).coerceAtLeast(0f).toInt(), timeMs = 300))
            }
        }
    }

    // ---- Enregistreur de mouvement Live (un bouton : Enregistrer → REC → Pause) ----

    fun toggleLiveRecord() {
        when (_uiState.value.liveRec) {
            LiveRecState.IDLE -> _uiState.update { it.copy(liveRec = LiveRecState.ARMED) }
            LiveRecState.ARMED -> _uiState.update { it.copy(liveRec = LiveRecState.IDLE) }
            LiveRecState.RECORDING -> {
                recSamples.clear()
                _uiState.update { it.copy(liveRec = LiveRecState.IDLE) }
            }
            LiveRecState.PLAYING -> stopLiveLoop()
        }
    }

    private fun stopLiveLoop() {
        liveLoopJob?.cancel(); liveLoopJob = null
        _uiState.update { it.copy(liveRec = LiveRecState.IDLE) }
    }

    private fun finishRecordingAndLoop() {
        // Coupe le silence initial (échantillons immobiles avant le premier mouvement)
        // pour que la boucle soit continue.
        val samples = recSamples.toList()
        recSamples.clear()
        val firstPos = samples.firstOrNull()?.second
        val firstMoveIdx = samples.indexOfFirst { it.second != firstPos }
        if (firstPos == null || firstMoveIdx < 1) {
            _uiState.update { it.copy(liveRec = LiveRecState.IDLE) }
            return
        }
        val t0 = samples[firstMoveIdx - 1].first
        val trimmed = samples.drop(firstMoveIdx - 1).map { (t, p) -> (t - t0) to p }
        if (trimmed.size < 3 || trimmed.last().first < 300L) {
            _uiState.update { it.copy(liveRec = LiveRecState.IDLE) }
            return
        }
        _uiState.update { it.copy(liveRec = LiveRecState.PLAYING) }
        liveLoopJob?.cancel()
        liveLoopJob = viewModelScope.launch {
            // Rejoint le point de départ en douceur avant la première itération.
            bleManager.sendCommand(OssmCommand.Stream(trimmed.first().second, timeMs = 400))
            delay(450)
            while (isActive) {
                val start = System.currentTimeMillis()
                var lastPos = trimmed.first().second
                for ((t, pos) in trimmed) {
                    val wait = start + t - System.currentTimeMillis()
                    if (wait > 0) delay(wait)
                    if (!isActive) return@launch
                    if (kotlin.math.abs(pos - lastPos) >= 1) {
                        bleManager.sendCommand(OssmCommand.Stream(pos, timeMs = STREAM_MOVE_MS))
                        lastPos = pos
                    }
                }
                // Jonction de boucle : retour souple au point de départ.
                val startPos = trimmed.first().second
                if (kotlin.math.abs(startPos - lastPos) >= 1) {
                    bleManager.sendCommand(OssmCommand.Stream(startPos, timeMs = 300))
                    delay(350)
                }
            }
        }
    }

    // ---- Assistant de plage au toucher ----

    fun startRangeWizard() {
        val current = _uiState.value
        if (current.rangeWizard != null) return
        val mode = current.activePattern?.mode ?: return
        if (mode == PatternControlMode.STREAMING || mode == PatternControlMode.LAUNCH_ONLY) return
        // Stoppe toute activité en cours avant de passer la machine en suivi de position.
        liveLoopJob?.cancel(); liveLoopJob = null
        streamJob?.cancel(); streamJob = null
        progressiveJob?.cancel(); progressiveJob = null
        chaosJob?.cancel(); chaosJob = null
        autoJob?.cancel(); autoJob = null; autoFirmwareMode = null
        streamTarget = 0f; streamSent = 0f
        _uiState.update {
            it.copy(
                rangeWizard = RangeWizardState(returnPatternKey = it.activePatternKey),
                isRunning = false,
                isPaused = false,
                autoRunning = false,
                pendingManualChange = null
            )
        }
        bleManager.sendCommand(OssmCommand.EnterStreaming)
    }

    fun captureRangePoint() {
        val wiz = _uiState.value.rangeWizard ?: return
        val pos = (streamSent / 100f).coerceIn(0f, 1f)
        if (wiz.step == 1) {
            _uiState.update { it.copy(rangeWizard = wiz.copy(step = 2, capturedMax = pos)) }
        } else {
            val other = wiz.capturedMax ?: pos
            val lo = minOf(pos, other)
            var hi = maxOf(pos, other)
            if (hi - lo < 0.05f) hi = (lo + 0.05f).coerceAtMost(1f)  // plage minimale 5 %
            finishRangeWizard(lo, hi, wiz.returnPatternKey)
        }
    }

    fun cancelRangeWizard() {
        val wiz = _uiState.value.rangeWizard ?: return
        streamJob?.cancel(); streamJob = null
        _uiState.update { it.copy(rangeWizard = null) }
        activatePattern(wiz.returnPatternKey)
    }

    private fun finishRangeWizard(lo: Float, hi: Float, returnKey: String) {
        streamJob?.cancel(); streamJob = null
        _uiState.update {
            it.copy(rangeWizard = null, depthMin = lo, depthMax = hi, pendingManualChange = null)
        }
        viewModelScope.launch { userHabitsRepository.recordDepthHabit(lo, hi) }
        // Retour au pattern d'origine : activatePattern renvoie les paramètres
        // (dont la nouvelle plage) une fois le changement de mode stabilisé.
        activatePattern(returnKey)
    }

    fun savePatternOrder(keys: List<String>) {
        _uiState.update { it.copy(patternOrder = keys) }
        viewModelScope.launch { userHabitsRepository.savePatternOrder(keys) }
    }

    fun applyPreset(preset: Preset) {
        val matchingPattern = _uiState.value.availablePatterns.firstOrNull { it.key == preset.patternKey }
            ?: OssmPattern(
                key = preset.patternKey,
                name = preset.patternName,
                mode = when (preset.patternKey) {
                    KnownSimplePenetrationPattern.key -> PatternControlMode.SIMPLE_PENETRATION
                    KnownStreamingPattern.key -> PatternControlMode.STREAMING
                    else -> PatternControlMode.STROKE_ENGINE
                }
            )

        _uiState.update {
            it.copy(
                activePatternKey = matchingPattern.key,
                speed = preset.speed,
                depthMin = preset.depthMin,
                depthMax = preset.depthMax,
                isRunning = true,
                pendingManualChange = null
            )
        }
        bleManager.sendCommand(OssmCommand.ActivatePattern(matchingPattern))
        sendCurrentStrokeEngineCommand()
    }

    fun requestSensationChange(value: Float) {
        val v = value.coerceIn(0f, 1f)
        when (_uiState.value.activePattern?.mode) {
            PatternControlMode.STROKE_ENGINE -> {
                _uiState.update { it.copy(sensation = v) }
                sendCurrentStrokeEngineCommand()
            }
            PatternControlMode.PROGRESSIVE -> {
                // Sensation = ramp rate; the running ramp reads it live, no firmware write needed.
                _uiState.update { it.copy(sensation = v) }
            }
            else -> return
        }
    }

    private fun maybeApplyManualChange(
        control: GuardedControl,
        speed: Float,
        depthMin: Float,
        depthMax: Float
    ) {
        val current = _uiState.value
        val guard = when (control) {
            GuardedControl.SPEED -> current.speedGuard
            GuardedControl.DEPTH -> current.depthGuard
        }
        val threshold = guard.thresholdPercent / 100f
        // Garde ASYMÉTRIQUE : on ne confirme que les variations qui AUGMENTENT
        // l'intensité (plus vite, plus profond, ou course allongée). Ralentir ou
        // réduire la profondeur passe toujours sans confirmation.
        val delta = when (control) {
            GuardedControl.SPEED -> speed - current.speed
            GuardedControl.DEPTH -> maxOf(
                depthMax - current.depthMax,      // fond plus profond
                current.depthMin - depthMin        // retrait reculé = course allongée
            )
        }

        if (guard.enabled && control !in sessionBypass && delta > threshold) {
            _uiState.update {
                it.copy(
                    pendingManualChange = PendingManualChange(
                        control = control,
                        speed = speed,
                        depthMin = depthMin,
                        depthMax = depthMax,
                        thresholdPercent = guard.thresholdPercent
                    )
                )
            }
            return
        }

        applyManualChange(speed, depthMin, depthMax)
    }

    private fun applyManualChange(speed: Float, depthMin: Float, depthMax: Float) {
        val normalizedMin = depthMin.coerceIn(0f, 1f)
        val normalizedMax = depthMax.coerceIn(normalizedMin, 1f)
        _uiState.update {
            it.copy(
                speed = speed.coerceIn(0f, 1f),
                depthMin = normalizedMin,
                depthMax = normalizedMax,
                isRunning = speed > 0f,
                pendingManualChange = null
            )
        }
        sendCurrentStrokeEngineCommand()
    }

    private fun sendCurrentStrokeEngineCommand() {
        val state = _uiState.value
        if (state.activePattern == null) return
        bleManager.sendCommand(
            OssmCommand.UpdateStrokeEngine(
                StrokeEngineCommand(
                    speed = state.speed,
                    depthMin = state.depthMin,
                    depthMax = state.depthMax,
                    sensation = state.sensation
                )
            )
        )
    }

    override fun onCleared() {
        super.onCleared()
        liveLoopJob?.cancel()
        streamJob?.cancel()
        progressiveJob?.cancel()
        chaosJob?.cancel()
        teasingRandomJob?.cancel()
        audioLevelMonitor.stop()
    }

    companion object {
        // Live streaming : envois rapprochés, durée > cadence pour un mouvement
        // continu (léger chevauchement, file bornée à ~1 commande).
        private const val STREAM_CADENCE_MS = 60L
        private const val STREAM_MOVE_MS = 90
        // Rappels de position (rattrapage du retard sur gestes rapides).
        private const val STREAM_REFRESH_MS = 350L
        private const val STREAM_REFRESH_MOVE_MS = 250

        // Progressif ramp timing: wait ≈ ONE full back-and-forth between increments.
        // period(ms) = K * strokeFraction / speed%, clamped [MIN, MAX].
        private const val PROGRESSIVE_K_MS = 100000f
        private const val PROGRESSIVE_MIN_MS = 400L
        private const val PROGRESSIVE_MAX_MS = 60000L
        // Sensation drives how many % the speed gains per stroke: min=+1, max=+10.
        private const val PROGRESSIVE_MAX_INCREMENT = 10

        // Auto Random : montée globale sur ~12 min ; temps de maintien entre deux
        // changements (raccourci à mesure que l'intensité monte).
        private const val AUTO_BUILDUP_MS = 720_000f
        private const val AUTO_HOLD_MIN_MS = 2_500L
        private const val AUTO_HOLD_MAX_MS = 20_000L

        // Mode aléatoire / piston : intervalle entre deux pas de la marche (ms).
        // Court + petits pas = promenade fluide sans grands écarts.
        private const val TEASE_RANDOM_MIN_MS = 700L
        private const val TEASE_RANDOM_MAX_MS = 1_900L
    }
}
