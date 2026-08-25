package com.ossm.remote.session

import com.ossm.remote.adaptive.AdaptiveEngine
import com.ossm.remote.adaptive.AdaptiveUiState
import com.ossm.remote.audio.AudioLevelMonitor
import com.ossm.remote.audio.CadenceDetector
import com.ossm.remote.ble.BleManager
import com.ossm.remote.data.repository.ControlSafetySettingsRepository
import com.ossm.remote.data.repository.UserHabitsRepository
import com.ossm.remote.model.AutoRandomMixablePatterns
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.KnownAdaptivePattern
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.KnownSimplePenetrationPattern
import com.ossm.remote.model.KnownStreamingPattern
import com.ossm.remote.model.KnownStrokeEnginePattern
import com.ossm.remote.model.LiveTargetProducer
import com.ossm.remote.model.OssmCommand
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.PatternControlMode
import com.ossm.remote.model.Preset
import com.ossm.remote.model.StrokeEngineCommand
import com.ossm.remote.viewmodel.ControlUiState
import com.ossm.remote.viewmodel.GuardedControl
import com.ossm.remote.viewmodel.PendingManualChange
import com.ossm.remote.viewmodel.RandomTarget
import com.ossm.remote.viewmodel.RangeWizardState
import com.ossm.remote.viewmodel.SliderGuardUiState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Patterns qui exposent le mode aléatoire par cases. */
private const val TEASING_POUNDING_KEY = "teasingPounding"
private const val SIMPLE_STROKE_KEY = "simpleStroke"
/** Patterns sur lesquels le mode aléatoire (piston) est disponible. */
private val RANDOM_CAPABLE_KEYS = setOf(TEASING_POUNDING_KEY, SIMPLE_STROKE_KEY)

/**
 * Source de vérité de la session de contrôle (état + garde anti-saut + throttling).
 *
 * Singleton partagé entre le `ControlViewModel` (UI) et le futur overlay en
 * ForegroundService : les deux observent le même `StateFlow<ControlUiState>` et les
 * mêmes champs critiques. Toutes les mutations d'état, la garde anti-saut
 * (pendingManualChange) et le throttling des envois BLE live vivent ICI, pas dans le
 * ViewModel, pour que l'overlay respecte exactement les mêmes règles que l'écran.
 *
 * Thread-safe : l'état est un `StateFlow` mis à jour par `update` (atomique) et toute
 * la logique tourne sur `Dispatchers.Main.immediate` via [storeScope].
 */
@Singleton
class ControlSessionStore @Inject constructor(
    private val bleManager: BleManager,
    private val safetySettingsRepository: ControlSafetySettingsRepository,
    private val userHabitsRepository: UserHabitsRepository,
    private val audioLevelMonitor: AudioLevelMonitor,
    private val cadenceDetector: CadenceDetector,
    private val adaptiveEngine: AdaptiveEngine
) {
    /**
     * Scope de session : survit au ViewModel (le store est un singleton) pour que le
     * futur overlay continue de piloter la machine quand l'UI n'est plus affichée.
     */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(ControlUiState())
    val uiState: StateFlow<ControlUiState> = _uiState.asStateFlow()

    /** État du mode adaptatif exposé pour l'UI (Phase 2). */
    val adaptiveUiState: StateFlow<AdaptiveUiState> get() = adaptiveEngine.uiState

    // ---- Champs critiques exposés au futur overlay (lecture directe de l'état) ----

    /** Vitesse courante (0..1). */
    val currentSpeed: Float get() = uiState.value.speed
    /** Point de RETRAIT courant (0..1). */
    val currentDepthMin: Float get() = uiState.value.depthMin
    /** FOND courant (0..1). */
    val currentDepthMax: Float get() = uiState.value.depthMax
    /** Vrai quand la machine est en mouvement (hors pause). */
    val isRunning: Boolean get() = uiState.value.isRunning
    /** Vrai quand la session est en pause. */
    val isPaused: Boolean get() = uiState.value.isPaused
    /** Pattern actif (null si aucun). */
    val activePattern: OssmPattern? get() = uiState.value.activePattern
    /** Changement manuel en attente de confirmation (garde anti-saut), null sinon. */
    val pendingManualChange: PendingManualChange? get() = uiState.value.pendingManualChange
    /** État de connexion BLE (flow exposé par le BleManager). */
    val connectionState: StateFlow<BleConnectionState> get() = bleManager.connectionState

    private val sessionBypass = mutableSetOf<GuardedControl>()
    private var autoEnterJob: Job? = null
    private var connectionEpoch = 0L
    private var autoEnteredConnectionEpoch: Long? = null

    init {
        storeScope.launch {
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

        storeScope.launch {
            bleManager.availablePatterns.collect { patterns ->
                val safePatterns = patterns.ifEmpty { listOf(KnownStrokeEnginePattern) }
                // La liste firmware REMPLACE KnownFallbackPatterns à la connexion : on
                // réinjecte le mode adaptatif pour qu'il ne disparaisse jamais.
                val mergedPatterns = (if (safePatterns.isEmpty()) KnownFallbackPatterns else safePatterns)
                    .let { list -> if (list.any { it.key == KnownAdaptivePattern.key }) list else list + KnownAdaptivePattern }
                adaptiveEngine.setFirmwareLevel(AdaptiveEngine.detectFirmwareLevel(safePatterns))
                _uiState.update { current ->
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
        storeScope.launch {
            var wasConnected = false
            bleManager.connectionState.collect { conn ->
                val connected = conn is BleConnectionState.Connected
                if (connected && !wasConnected) {
                    connectionEpoch += 1
                    _uiState.update { it.copy(speed = 0f, isRunning = false, isPaused = false) }
                    requestAutoEnterModeOnConnect()
                } else if (!connected && wasConnected) {
                    autoEnterJob?.cancel()
                    autoEnterJob = null
                }
                wasConnected = connected
            }
        }

        storeScope.launch {
            userHabitsRepository.patternOrder.collect { order ->
                _uiState.update { it.copy(patternOrder = order) }
            }
        }

        storeScope.launch {
            safetySettingsRepository.listeningModeEnabled.collect { enabled ->
                _uiState.update {
                    it.copy(
                        listeningMode = enabled,
                        listeningDepthCurrent = if (enabled) it.listeningDepthCurrent else null
                    )
                }
                updateListeningMonitor()
            }
        }

        storeScope.launch {
            safetySettingsRepository.listeningCeiling.collect { ceiling ->
                _uiState.update { it.copy(listeningCeiling = ceiling) }
            }
        }

        storeScope.launch {
            safetySettingsRepository.listeningTargetSpeedEnabled.collect { enabled ->
                _uiState.update { it.copy(listeningTargetSpeed = enabled) }
                updateListeningSpeedDriver()
            }
        }

        storeScope.launch {
            safetySettingsRepository.listeningTargetDepthEnabled.collect { enabled ->
                _uiState.update {
                    it.copy(
                        listeningTargetDepth = enabled,
                        listeningDepthCurrent = if (enabled) it.listeningDepthCurrent else null
                    )
                }
                updateListeningSpeedDriver()
            }
        }

        storeScope.launch {
            safetySettingsRepository.liveInvertEnabled.collect { enabled ->
                bleManager.liveInvert = enabled
                _uiState.update { it.copy(liveInvert = enabled) }
            }
        }

        storeScope.launch {
            safetySettingsRepository.liveAccelerationPercent.collect { percent ->
                _uiState.update { it.copy(liveAccelerationPercent = percent) }
            }
        }

        storeScope.launch {
            bleManager.streamingReady.collect { ready ->
                if (ready) {
                    // Le firmware vient de se replacer en position 0 (home) : on
                    // resynchronise le suivi local pour que slider et machine repartent
                    // du même point (pad au repos = home). ON N'ENVOIE AUCUNE COMMANDE
                    // ICI (comportement baseline v1.20.5) : le firmware est déjà au home,
                    // et tout stream: envoyé maintenant risquerait de le déplacer.
                    _uiState.update { it.copy(streamPositionPercent = 0) }
                }
                _uiState.update { it.copy(streamingReady = ready) }
            }
        }

        // Learned habits: pre-select the pattern/depth range the user typically starts with,
        // based on their first manual choice of each of the last few days.
        storeScope.launch {
            val defaults = userHabitsRepository.sessionDefaults.first()
            if (defaults.patternKey != null || defaults.depthMin != null) {
                _uiState.update { current ->
                    val (depthMin, depthMax) = clampDepthRange(
                        defaults.depthMin ?: current.depthMin,
                        defaults.depthMax ?: current.depthMax,
                        current.depthLimitMin,
                        current.depthLimitMax
                    )
                    current.copy(
                        activePatternKey = defaults.patternKey
                            ?.takeIf { key -> current.availablePatterns.any { it.key == key } }
                            ?: current.activePatternKey,
                        depthMin = depthMin,
                        depthMax = depthMax
                    )
                }
                if (bleManager.connectionState.value is BleConnectionState.Connected) {
                    requestAutoEnterModeOnConnect()
                }
            }
        }

        storeScope.launch {
            combine(
                safetySettingsRepository.depthLimitMin,
                safetySettingsRepository.depthLimitMax
            ) { min, max -> normalizeDepthLimits(min, max) }.collect { (min, max) ->
                _uiState.update { current ->
                    val (depthMin, depthMax) = clampDepthRange(current.depthMin, current.depthMax, min, max)
                    current.copy(
                        depthMin = depthMin,
                        depthMax = depthMax,
                        depthLimitMin = min,
                        depthLimitMax = max
                    )
                }
            }
        }
    }

    // ---- Auto-entrée en mode à la connexion ----

    private fun requestAutoEnterModeOnConnect() {
        if (autoEnteredConnectionEpoch == connectionEpoch) return
        autoEnterJob?.cancel()
        val epoch = connectionEpoch
        autoEnterJob = storeScope.launch { autoEnterModeOnConnect(epoch) }
    }

    private suspend fun autoEnterModeOnConnect(epoch: Long) {
        // A state can move through preflight/homing before becoming safe; only this
        // epoch's first eligible state may enter the selected StrokeEngine pattern.
        withTimeoutOrNull(6_000) {
            bleManager.machineState.first { it.state != "unknown" && it.isReady }
        }
        if (connectionEpoch != epoch ||
            bleManager.connectionState.value !is BleConnectionState.Connected ||
            autoEnteredConnectionEpoch == epoch
        ) return
        val pattern = _uiState.value.activePattern ?: return
        // Uniquement stroke engine : Progressif lancerait sa rampe tout seul, et
        // streaming/auto ne doivent jamais démarrer sans geste explicite.
        if (pattern.mode != PatternControlMode.STROKE_ENGINE) return
        autoEnteredConnectionEpoch = epoch
        activatePattern(pattern.key)
    }

    // ---- Contrat public (partagé avec le futur overlay) ----

    /**
     * Active un pattern par sa key. C'est l'API existante du ControlViewModel, gardée
     * ici sous le même nom : en entrant dans un mode la vitesse est FORCÉE à 0 (la
     * machine ne bouge jamais toute seule) et les tickers du mode précédent sont arrêtés.
     */
    fun activatePattern(patternKey: String) {
        val pattern = _uiState.value.availablePatterns.firstOrNull { it.key == patternKey } ?: return
        storeScope.launch { userHabitsRepository.recordPatternHabit(pattern.key) }
        // Leaving any previous live/progressive session: stop the tickers.
        progressiveJob?.cancel(); progressiveJob = null
        chaosJob?.cancel(); chaosJob = null
        teasingRandomJob?.cancel(); teasingRandomJob = null
        adaptiveJob?.cancel(); adaptiveJob = null; adaptiveFirmwareMode = null
        adaptiveEngine.setRunning(false)
        adaptiveEngine.setActivePattern(null)
        // Le micro suit le mode à l'écoute (pas le ticker) : ne pas le couper ici.
        updateListeningMonitor()
        autoJob?.cancel(); autoJob = null; autoFirmwareMode = null
        // Repart sur des cases décochées à chaque changement de pattern (jamais
        // d'aléatoire surprise en revenant sur Teasing & Pounding).
        _uiState.update {
            it.copy(
                randSpeed = false, randDepthMin = false, randDepthMax = false,
                randomSpeedCurrent = null,
                randomDepthMinCurrent = null,
                randomDepthMaxCurrent = null
            )
        }
        _uiState.update { it.copy(autoRunning = false, autoIntensity = 0f, pendingAutoStart = false, streamPositionPercent = 0) }
        val defaultSensation = 0.5f
        _uiState.update {
            it.copy(
                activePatternKey = pattern.key,
                // Vitesse FORCÉE à 0 à chaque changement de pattern : la machine ne
                // bouge JAMAIS toute seule en entrant dans un mode (l'utilisateur monte
                // la vitesse lui-même). Sinon une vitesse résiduelle du mode précédent
                // (ex. streaming à 80, ou 1-3 % traînant) faisait cogner le stroke engine.
                speed = 0f,
                sensation = defaultSensation,
                progressiveMaxSpeed = 1f,
                pendingManualChange = null,
                isRunning = false
            )
        }
        // Le pilotage micro ne vaut que pour les patterns stroke-engine : réévalue.
        updateListeningSpeedDriver()
        if (pattern.mode == PatternControlMode.AUTO_RANDOM) return
        when (pattern.mode) {
            PatternControlMode.AUTO_RANDOM -> {
                // Just select it. The user picks modes + limits, then presses start (with a
                // limits-reminder popup). The mix engine handles its own firmware mode switches.
            }
            PatternControlMode.ADAPTIVE -> {
                // Just select it. The engine only starts on an explicit gesture (startAdaptive);
                // activation already reset the state (speed=0 etc.) above.
            }
            PatternControlMode.STREAMING -> {
                bleManager.enterStreaming(_uiState.value.liveAccelerationPercent)
            }
            PatternControlMode.PROGRESSIVE -> {
                bleManager.sendCommand(OssmCommand.ActivatePattern(pattern))
                storeScope.launch {
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
                // ENTRÉE DE MODE SÉQUENTIELLE (une seule coroutine) : navigation +
                // set:pattern + ré-application depth/stroke, dans le bon ordre. Élimine la
                // collision d'écritures BLE (Android n'a PAS de file GATT) qui faisait perdre
                // set:depth/set:stroke et laissait la bande streaming 100/100 FUIR dans le mode
                // suivant → « avance ~10 % puis cogne au fond, n'atteint jamais 50 % ».
                val st = _uiState.value
                bleManager.activatePatternVerified(
                    patternId = pattern.id ?: 0,
                    patternName = pattern.name,
                    command = StrokeEngineCommand(
                        speed = st.speed,
                        depthMin = st.depthMin,
                        depthMax = st.depthMax,
                        sensation = st.sensation
                    )
                )
            }
        }
    }

    /** Variante objet du contrat overlay : délègue à l'API existante par key. */
    fun activatePattern(pattern: OssmPattern) = activatePattern(pattern.key)

    fun requestSpeedChange(value: Float) {
        val current = _uiState.value
        if (!current.activePatternSupportsControls) return
        val mode = current.activePattern?.mode ?: return
        if (mode == PatternControlMode.AUTO_RANDOM || mode == PatternControlMode.STREAMING ||
            mode == PatternControlMode.ADAPTIVE
        ) {
            if (mode == PatternControlMode.ADAPTIVE) adaptiveEngine.recordManualAdjustment()
            val speed = value.coerceIn(0f, 1f)
            _uiState.update { it.copy(speed = speed, isRunning = speed > 0f) }
            return
        }
        // In Progressif, setting the speed (re)starts the ramp from that value up to 100%.
        if (mode == PatternControlMode.PROGRESSIVE) {
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
        val mode = current.activePattern?.mode ?: return
        val (lo, hi) = clampDepthRange(min, max, current.depthLimitMin, current.depthLimitMax)
        storeScope.launch { userHabitsRepository.recordDepthHabit(lo, hi) }
        if (mode == PatternControlMode.AUTO_RANDOM || mode == PatternControlMode.STREAMING ||
            mode == PatternControlMode.ADAPTIVE
        ) {
            if (mode == PatternControlMode.ADAPTIVE) adaptiveEngine.recordManualAdjustment()
            _uiState.update { it.copy(depthMin = lo, depthMax = hi, pendingManualChange = null) }
            return
        }
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
        if (mode == PatternControlMode.STREAMING || mode == PatternControlMode.LAUNCH_ONLY) return
        // Frozen while a guard popup is open: the state/machine must NOT move until resolved,
        // otherwise "Annuler" would have nothing correct to fall back to.
        if (current.pendingManualChange != null) return
        if (mode == PatternControlMode.ADAPTIVE) adaptiveEngine.recordManualAdjustment()
        val v = value.coerceIn(0f, 1f)
        val sendsLiveSpeed = mode == PatternControlMode.STROKE_ENGINE
        // Garde ASYMÉTRIQUE : ralentir passe toujours sans confirmation.
        if (sendsLiveSpeed && current.speedGuard.enabled && GuardedControl.SPEED !in sessionBypass &&
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
        if (sendsLiveSpeed && liveThrottleOk()) bleManager.liveSet("speed", (v * 100f).toInt())
    }

    fun setSensationLive(value: Float) {
        val mode = _uiState.value.activePattern?.mode ?: return
        val sensation = value.coerceIn(0f, 1f)
        _uiState.update { it.copy(sensation = sensation) }
        if (mode == PatternControlMode.STROKE_ENGINE && liveThrottleOk()) {
            bleManager.liveSet("sensation", (sensation * 100f).toInt())
        }
    }

    fun setDepthLive(min: Float, max: Float) {
        val current = _uiState.value
        val mode = current.activePattern?.mode ?: return
        if (mode == PatternControlMode.STREAMING || mode == PatternControlMode.LAUNCH_ONLY) return
        if (current.pendingManualChange != null) return
        val (lo, hi) = clampDepthRange(min, max, current.depthLimitMin, current.depthLimitMax)
        if (mode == PatternControlMode.ADAPTIVE) adaptiveEngine.recordManualAdjustment()
        val sendsLiveDepth = mode == PatternControlMode.STROKE_ENGINE || mode == PatternControlMode.PROGRESSIVE
        // Garde ASYMÉTRIQUE (comme le commit) : seulement si l'intensité AUGMENTE.
        if (sendsLiveDepth && current.depthGuard.enabled && GuardedControl.DEPTH !in sessionBypass &&
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
        if (sendsLiveDepth && liveThrottleOk()) {
            bleManager.liveSetDepthRange(
                depthPercent = (hi * 100f).toInt(),
                strokePercent = ((hi - lo) * 100f).toInt()
            )
        }
    }

    fun confirmPendingManualChange(skipForSession: Boolean, neverAskAgain: Boolean) {
        val pending = _uiState.value.pendingManualChange ?: return
        if (skipForSession) {
            sessionBypass += pending.control
        }
        storeScope.launch {
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
        storeScope.launch {
            if (enabled) {
                sessionBypass.remove(GuardedControl.SPEED)
            }
            safetySettingsRepository.setSpeedGuardEnabled(enabled)
        }
    }

    fun setDepthGuardEnabled(enabled: Boolean) {
        storeScope.launch {
            if (enabled) {
                sessionBypass.remove(GuardedControl.DEPTH)
            }
            safetySettingsRepository.setDepthGuardEnabled(enabled)
        }
    }

    fun setDepthLimits(min: Float, max: Float) {
        val (limitMin, limitMax) = normalizeDepthLimits(min, max)
        storeScope.launch { safetySettingsRepository.setDepthLimits(limitMin, limitMax) }
        _uiState.update { current ->
            val (depthMin, depthMax) = clampDepthRange(current.depthMin, current.depthMax, limitMin, limitMax)
            current.copy(
                depthMin = depthMin,
                depthMax = depthMax,
                depthLimitMin = limitMin,
                depthLimitMax = limitMax
            )
        }
    }

    fun setLiveAcceleration(percent: Int) {
        val acceleration = ControlSafetySettingsRepository.normalizeLiveAcceleration(percent)
        _uiState.update { it.copy(liveAccelerationPercent = acceleration) }
        storeScope.launch { safetySettingsRepository.setLiveAccelerationPercent(acceleration) }

        val state = _uiState.value
        if (bleManager.connectionState.value is BleConnectionState.Connected &&
            state.activePattern?.mode == PatternControlMode.STREAMING
        ) {
            bleManager.setLiveAcceleration(acceleration)
        }
    }

    fun stop() {
        cancelSessionJobs()
        _uiState.update { it.copy(isRunning = false, isPaused = false, autoRunning = false, autoIntensity = 0f, speed = 0f, pendingManualChange = null) }
        bleManager.sendCommand(OssmCommand.Stop)
    }

    /** Arrêt d'urgence : stoppe tout, met la machine en état EmergencyStop puis restaure. */
    fun emergencyStop() {
        cancelSessionJobs()
        _uiState.update { it.copy(isRunning = false, isPaused = false, autoRunning = false, autoIntensity = 0f, speed = 0f, pendingManualChange = null) }
        bleManager.emergencyStop()
    }

    fun home() {
        val pattern = _uiState.value.activePattern
        adaptiveJob?.cancel(); adaptiveJob = null; adaptiveFirmwareMode = null
        adaptiveEngine.setRunning(false)
        _uiState.update { it.copy(isRunning = false, speed = 0f, pendingManualChange = null) }
        when (pattern?.mode) {
            PatternControlMode.SIMPLE_PENETRATION -> bleManager.home("simplePenetration", 0)
            PatternControlMode.STREAMING -> bleManager.enterStreaming(_uiState.value.liveAccelerationPercent)
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
        progressiveJob = storeScope.launch {
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

    // Random varied strokes once the ramp tops out: switches pattern and speed at random
    // intervals. Uses the stroke engine (oscillates from home → no slam risk); depth and
    // sensation stay within the user's range.
    private var chaosJob: Job? = null
    private fun startChaos() {
        chaosJob?.cancel()
        chaosJob = storeScope.launch {
            while (isActive) {
                val max = _uiState.value.progressiveMaxSpeed.coerceIn(0.1f, 1f)
                // ~55% of the time, jump to a different stroke-engine pattern for a new feel.
                if (Random.nextFloat() < 0.55f) {
                    bleManager.setStrokeEnginePattern(Random.nextInt(0, 7))
                    delay(80)
                }
                // Random speed between 35% and 100% of the ceiling.
                val newSpeed = ((0.35f + Random.nextFloat() * 0.65f) * max).coerceIn(0.05f, max)
                _uiState.update { it.copy(speed = newSpeed) }
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
    // N'écrase PAS les valeurs de slider (qui restent les plafonds/ancres) : envoie des
    // commandes stroke-engine vérifiées (depth avant stroke → jamais de coup au fond).
    private var teasingRandomJob: Job? = null
    // Valeurs courantes de la marche aléatoire (conservées entre deux pas pour lisser).
    private var randWalkSpeed = 0f
    private var randWalkMin = 0f
    private var randWalkMax = 1f
    // Vitesse aléatoire par BLOCS (paliers) : on tient une vitesse pendant un bloc de
    // quelques secondes, jamais 0, et le plancher monte à mesure qu'on est allé haut
    // (cliquet : une fois monté à 7-8/10, on ne redescend plus sous ~3-5/10).
    private var speedBlockEndMs = 0L
    private var currentBlockSpeed = 0f
    private var maxBlockSpeed = 0f

    fun setRandomMode(target: RandomTarget, enabled: Boolean) {
        _uiState.update { st ->
            val updated = when (target) {
                RandomTarget.SPEED -> st.copy(randSpeed = enabled)
                RandomTarget.DEPTH_MIN -> st.copy(randDepthMin = enabled)
                RandomTarget.DEPTH_MAX -> st.copy(randDepthMax = enabled)
            }
            updated.copy(
                randomSpeedCurrent = updated.randomSpeedCurrent?.takeIf { updated.randSpeed },
                randomDepthMinCurrent = updated.randomDepthMinCurrent?.takeIf { updated.randDepthMin },
                randomDepthMaxCurrent = updated.randomDepthMaxCurrent?.takeIf { updated.randDepthMax }
            )
        }
        updateTeasingRandomTicker()
    }

    /**
     * Profondeur aléatoire = UNE seule case : le piston se promène entre le min et le
     * max réglés (on active retrait ET fond ensemble, promenade de toute la fenêtre).
     */
    fun setDepthRandom(enabled: Boolean) {
        _uiState.update {
            it.copy(
                randDepthMin = enabled,
                randDepthMax = enabled,
                randomDepthMinCurrent = if (enabled) it.randomDepthMinCurrent else null,
                randomDepthMaxCurrent = if (enabled) it.randomDepthMaxCurrent else null
            )
        }
        updateTeasingRandomTicker()
    }

    /** Inverse le sens du mode Live (persisté). Effet immédiat sur les prochaines commandes stream. */
    fun setLiveInvert(enabled: Boolean) {
        storeScope.launch { safetySettingsRepository.setLiveInvertEnabled(enabled) }
        bleManager.liveInvert = enabled
        _uiState.update { it.copy(liveInvert = enabled) }
    }

    /** Active/désactive le mode « à l'écoute » (persisté). */
    fun setListeningMode(enabled: Boolean) {
        storeScope.launch { safetySettingsRepository.setListeningModeEnabled(enabled) }
        // Mise à jour optimiste immédiate (le collect persistera aussi).
        _uiState.update {
            it.copy(
                listeningMode = enabled,
                listeningDepthCurrent = if (enabled) it.listeningDepthCurrent else null
            )
        }
        if (enabled) {
            // À CHAQUE activation, les deux cibles audio repartent à OFF : aucune cible
            // persistée d'une session précédente ne doit rester cochée (vitesse ET
            // profondeur désactivées tant que l'utilisateur ne les recooche pas).
            _uiState.update {
                it.copy(
                    listeningTargetSpeed = false,
                    listeningTargetDepth = false,
                    listeningDepthCurrent = null
                )
            }
            storeScope.launch {
                safetySettingsRepository.setListeningTargetSpeedEnabled(false)
                safetySettingsRepository.setListeningTargetDepthEnabled(false)
            }
        }
        updateListeningMonitor()
    }

    fun setListeningCeiling(value: Float) {
        val ceiling = value.coerceIn(0f, 1f)
        storeScope.launch { safetySettingsRepository.setListeningCeiling(ceiling) }
        _uiState.update { it.copy(listeningCeiling = ceiling) }
    }

    fun setListeningTargetSpeed(enabled: Boolean) {
        storeScope.launch { safetySettingsRepository.setListeningTargetSpeedEnabled(enabled) }
        _uiState.update { it.copy(listeningTargetSpeed = enabled) }
        updateListeningSpeedDriver()
    }

    fun setListeningTargetDepth(enabled: Boolean) {
        storeScope.launch { safetySettingsRepository.setListeningTargetDepthEnabled(enabled) }
        _uiState.update {
            it.copy(
                listeningTargetDepth = enabled,
                listeningDepthCurrent = if (enabled) it.listeningDepthCurrent else null
            )
        }
        updateListeningSpeedDriver()
    }

    /**
     * Le micro tourne dès que le mode à l'écoute est activé (et la permission accordée),
     * INDÉPENDAMMENT du ticker aléatoire. Avant, il n'était démarré que pendant le ticker
     * Teasing & Pounding → « le mode à l'écoute ne fait rien » tant qu'aucun aléatoire ne
     * tournait, et l'indicateur de niveau restait figé. La permission RECORD_AUDIO est
     * demandée à l'activation du toggle (MainActivity.toggleListeningMode).
     */
    private fun updateListeningMonitor() {
        if (_uiState.value.listeningMode && audioLevelMonitor.hasPermission()) {
            audioLevelMonitor.start(storeScope)
        } else {
            audioLevelMonitor.stop()
        }
        updateListeningSpeedDriver()
    }

    private var listeningDriveJob: Job? = null
    // Vitesse manuelle en vigueur quand le pilotage micro a pris la main : rendue
    // à l'arrêt du mode à l'écoute ou à la désactivation de la cible vitesse.
    private var manualSpeedSnapshot: Float? = null

    private fun restoreManualSpeed() {
        val snapshot = manualSpeedSnapshot ?: return
        manualSpeedSnapshot = null
        _uiState.update { it.copy(speed = snapshot, isRunning = snapshot > 0f) }
    }

    private fun updateListeningSpeedDriver() {
        val st = _uiState.value
        val strokeEngineReady = st.activePatternSupportsControls &&
            st.activePattern?.mode == PatternControlMode.STROKE_ENGINE
        val speedDrives = st.listeningMode && strokeEngineReady && st.listeningTargetSpeed
        val shouldDrive = st.listeningMode && strokeEngineReady &&
            (st.listeningTargetSpeed || st.listeningTargetDepth)
        if (!speedDrives) {
            restoreManualSpeed()
        }
        if (!shouldDrive) {
            listeningDriveJob?.cancel(); listeningDriveJob = null
            _uiState.update { it.copy(listeningDepthCurrent = null) }
            return
        }
        if (listeningDriveJob?.isActive == true) return
        if (st.listeningTargetSpeed && manualSpeedSnapshot == null) {
            manualSpeedSnapshot = st.speed
        }
        listeningDriveJob = storeScope.launch {
            var lastSendMs = 0L
            var lastSentSpeedPercent: Int? = null
            var lastSentDepthPercent: Int? = null
            var lastSentStrokePercent: Int? = null
            audioLevelMonitor.level.collect { level ->
                val cur = _uiState.value
                if (!cur.listeningMode || cur.pendingManualChange != null) return@collect
                if (!cur.listeningTargetSpeed && manualSpeedSnapshot != null) {
                    restoreManualSpeed()
                }
                if (cur.listeningTargetSpeed && manualSpeedSnapshot == null) {
                    manualSpeedSnapshot = cur.speed
                }
                val normalizedLevel = level.coerceIn(0f, 1f)
                // Le VOLUME total du micro pilote la VITESSE ; la CADENCE (fréquence
                // des pics détectés) pilote la PROFONDEUR. Le détecteur reçoit
                // l'horodatage depuis le driver.
                val cadence = cadenceDetector.consume(normalizedLevel, System.currentTimeMillis())
                val speed = normalizedLevel * cur.listeningCeiling.coerceIn(0f, 1f)
                val depth = cur.depthMin + cadence.speedFraction * (cur.depthMax - cur.depthMin)
                _uiState.update {
                    it.copy(
                        speed = if (cur.listeningTargetSpeed) speed else it.speed,
                        listeningDepthCurrent = if (cur.listeningTargetDepth) depth else null,
                        isRunning = if (cur.listeningTargetSpeed) speed > 0f else it.isRunning
                    )
                }
                val now = System.currentTimeMillis()
                val speedPercent = (speed * 100f).roundToInt()
                val depthPercent = (depth * 100f).roundToInt()
                val strokePercent = ((depth - cur.depthMin) * 100f).roundToInt()
                val hasChangedTarget =
                    (cur.listeningTargetSpeed && speedPercent != lastSentSpeedPercent) ||
                        (cur.listeningTargetDepth && (
                            depthPercent != lastSentDepthPercent || strokePercent != lastSentStrokePercent
                            ))
                if (now - lastSendMs >= LISTENING_SEND_MIN_MS && hasChangedTarget) {
                    lastSendMs = now
                    if (cur.listeningTargetSpeed) {
                        bleManager.liveSet("speed", speedPercent)
                        lastSentSpeedPercent = speedPercent
                    }
                    if (cur.listeningTargetDepth) {
                        bleManager.liveSetDepthRange(
                            depthPercent = depthPercent,
                            strokePercent = strokePercent
                        )
                        lastSentDepthPercent = depthPercent
                        lastSentStrokePercent = strokePercent
                    }
                }
            }
        }
    }

    /** Un pas de marche aléatoire borné : reste proche de [prev] (≤ maxStep), dans [lo, hi]. */
    private fun randomWalkStep(prev: Float, lo: Float, hi: Float, maxStep: Float): Float {
        if (hi <= lo) return lo
        val step = (Random.nextFloat() - 0.5f) * 2f * maxStep   // ∈ [-maxStep, +maxStep]
        return (prev + step).coerceIn(lo, hi)
    }

    private fun updateTeasingRandomTicker() {
        // Le micro suit le mode à l'écoute, jamais l'état du ticker aléatoire.
        updateListeningMonitor()
        val st = _uiState.value
        val active = st.activePatternKey in RANDOM_CAPABLE_KEYS &&
            st.activePattern?.mode == PatternControlMode.STROKE_ENGINE &&
            st.anyRandomActive
        if (!active) {
            teasingRandomJob?.cancel(); teasingRandomJob = null
            _uiState.update {
                it.copy(
                    randomSpeedCurrent = null,
                    randomDepthMinCurrent = null,
                    randomDepthMaxCurrent = null
                )
            }
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
        // Reset des blocs de vitesse (nouveau cliquet).
        speedBlockEndMs = 0L
        currentBlockSpeed = st.speed
        maxBlockSpeed = 0f
        teasingRandomJob = storeScope.launch {
            while (isActive) {
                val s = _uiState.value
                if (s.activePatternKey !in RANDOM_CAPABLE_KEYS || !s.anyRandomActive) break

                val minSpan = 0.05f
                val baseMin = s.depthMin
                val baseMax = s.depthMax

                // Mode à l'écoute : le VOLUME du micro (0..1, 0 si désactivé) remonte le
                // PLANCHER de chaque promenade (vitesse ET profondeur) vers le haut, sans
                // jamais dépasser tes maximums réglés.
                val bias = if (s.listeningMode) audioLevelMonitor.level.value.coerceIn(0f, 1f) else 0f

                // Vitesse : par BLOCS (paliers). On tient une vitesse pendant quelques
                // secondes, puis on en tire une nouvelle. Jamais 0. Le plancher monte
                // avec le max déjà atteint (cliquet) et avec le son (mode à l'écoute).
                val speed = if (s.randSpeed) {
                    val ceiling = s.speed
                    val nowMs = System.currentTimeMillis()
                    if (nowMs >= speedBlockEndMs && ceiling > 0.05f) {
                        val absMin = ceiling * 0.15f                    // jamais 0 / ne s'arrête pas
                        val ratchetFloor = maxBlockSpeed * SPEED_RATCHET // cliquet : monté haut → reste haut
                        val biasFloor = bias * 0.8f * ceiling            // mode à l'écoute
                        val floor = maxOf(absMin, ratchetFloor, biasFloor).coerceIn(0f, ceiling)
                        currentBlockSpeed = (floor + Random.nextFloat() * (ceiling - floor)).coerceIn(0.05f, ceiling)
                        if (currentBlockSpeed > maxBlockSpeed) maxBlockSpeed = currentBlockSpeed
                        speedBlockEndMs = nowMs + Random.nextLong(SPEED_BLOCK_MIN_MS, SPEED_BLOCK_MAX_MS)
                    }
                    currentBlockSpeed.coerceAtMost(ceiling)
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

                _uiState.update {
                    it.copy(
                        randomSpeedCurrent = speed.takeIf { s.randSpeed },
                        randomDepthMinCurrent = rMin.takeIf { s.randDepthMin },
                        randomDepthMaxCurrent = rMax.takeIf { s.randDepthMax }
                    )
                }

                bleManager.sendCommand(
                    OssmCommand.UpdateStrokeEngine(
                        StrokeEngineCommand(
                            speed = speed,
                            depthMin = rMin,
                            depthMax = rMax,
                            sensation = s.sensation
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

    // ---- Mode adaptatif : mix des 7 patterns firmware piloté par l'apprentissage ----
    private var adaptiveJob: Job? = null
    private var adaptiveFirmwareMode: PatternControlMode? = null

    /** Démarre le moteur adaptatif sur le pattern ADAPTIVE sélectionné (geste explicite). */
    fun startAdaptive() {
        if (_uiState.value.activePattern?.mode != PatternControlMode.ADAPTIVE) return
        _uiState.update { it.copy(isRunning = true, isPaused = false) }
        startAdaptiveMix()
    }

    /** Arrête le moteur adaptatif et la machine. */
    fun stopAdaptive() {
        adaptiveJob?.cancel(); adaptiveJob = null
        adaptiveFirmwareMode = null
        adaptiveEngine.setRunning(false)
        _uiState.update { it.copy(isRunning = false, isPaused = false) }
        bleManager.sendCommand(OssmCommand.Stop)
    }

    /**
     * Boucle du moteur adaptatif, dans le style de startAutoMix : l'AdaptiveEngine choisit
     * la cible (un des 7 patterns firmware, jamais hors 0-6), on commute le mode firmware
     * seulement si nécessaire, puis on RAMPÉ doucement vitesse/profondeur via liveSet.
     */
    private fun startAdaptiveMix() {
        adaptiveJob?.cancel()
        adaptiveFirmwareMode = null
        adaptiveJob = storeScope.launch {
            val startMs = System.currentTimeMillis()
            var lastPatternKey: String? = null
            var appliedSpeed = _uiState.value.speed
            var appliedDepthMin = _uiState.value.depthMin
            var appliedDepthMax = _uiState.value.depthMax
            try {
                adaptiveEngine.onSessionStarted()
                adaptiveEngine.setRunning(true)
                while (isActive) {
                    val st = _uiState.value
                    if (st.activePattern?.mode != PatternControlMode.ADAPTIVE) break
                    // Garde anti-saut ouverte : on fige le moteur tant que l'utilisateur n'a pas confirmé.
                    if (st.pendingManualChange != null) { delay(ADAPTIVE_PAUSE_POLL_MS); continue }
                    val elapsed = System.currentTimeMillis() - startMs
                    val buildup = (elapsed.toFloat() / ADAPTIVE_BUILDUP_MS).coerceIn(0f, 1f)
                    val intensity = interpolate(ADAPTIVE_START_INTENSITY, ADAPTIVE_CAP_INTENSITY, buildup)
                    val decision = AdaptiveEngine.decide(
                        lastPatternKey = lastPatternKey,
                        intensity = intensity,
                        speedCeiling = ADAPTIVE_MAX_SPEED,
                        depthMin = st.depthMin,
                        depthMax = st.depthMax,
                        learning = adaptiveEngine.currentLearning,
                        settings = adaptiveEngine.currentSettings
                    )
                    lastPatternKey = decision.pattern.key
                    adaptiveEngine.recordPatternUsed(decision.pattern.key)
                    adaptiveEngine.recordIntensitySample(intensity)
                    adaptiveEngine.setActivePattern(decision.pattern.key)
                    _uiState.update { it.copy(speed = decision.speed) }

                    // Premier démarrage : navigation menu → strokeEngine ; ensuite simple set:pattern.
                    if (adaptiveFirmwareMode == null) {
                        bleManager.activatePatternVerified(
                            patternId = decision.pattern.id ?: 0,
                            patternName = decision.pattern.name,
                            command = StrokeEngineCommand(
                                speed = 0f,
                                depthMin = appliedDepthMin,
                                depthMax = appliedDepthMax,
                                sensation = st.sensation
                            )
                        )
                        adaptiveFirmwareMode = PatternControlMode.STROKE_ENGINE
                        delay(ADAPTIVE_FIRST_SWITCH_DELAY_MS)
                    } else {
                        bleManager.setStrokeEnginePattern(decision.pattern.id ?: 0)
                        delay(ADAPTIVE_PATTERN_SWITCH_DELAY_MS)
                    }

                    rampSpeedTo(appliedSpeed, decision.speed)
                    rampDepthRangeTo(appliedDepthMin, appliedDepthMax, decision.depthMin, decision.depthMax)
                    appliedSpeed = decision.speed
                    appliedDepthMin = decision.depthMin
                    appliedDepthMax = decision.depthMax

                    val (holdMin, holdMax) = AdaptiveEngine.holdRangeMillis(adaptiveEngine.currentSettings.pace)
                    delay(Random.nextLong(holdMin, holdMax.coerceAtLeast(holdMin + 1)))
                }
            } finally {
                adaptiveEngine.onSessionStopped(System.currentTimeMillis() - startMs)
                adaptiveEngine.setRunning(false)
                adaptiveEngine.setActivePattern(null)
            }
        }
    }

    /** Rampe la vitesse vers [to] par petits pas bornés (garde anti-saut). */
    private suspend fun rampSpeedTo(from: Float, to: Float) {
        for (percent in AdaptiveEngine.rampValues(from, to, ADAPTIVE_RAMP_STEP)) {
            bleManager.liveSet("speed", (percent * 100f).toInt())
            delay(ADAPTIVE_RAMP_STEP_DELAY_MS)
        }
    }

    /** Rampe le couple fond/course par petits pas bornés (profondeur jamais brutale). */
    private suspend fun rampDepthRangeTo(fromMin: Float, fromMax: Float, toMin: Float, toMax: Float) {
        val minSteps = AdaptiveEngine.rampValues(fromMin, toMin, ADAPTIVE_RAMP_STEP)
        val maxSteps = AdaptiveEngine.rampValues(fromMax, toMax, ADAPTIVE_RAMP_STEP)
        val stepCount = maxOf(minSteps.size, maxSteps.size)
        var min = fromMin
        var max = fromMax
        repeat(stepCount) { index ->
            min = minSteps.getOrNull(index) ?: toMin
            max = maxSteps.getOrNull(index) ?: toMax
            bleManager.liveSetDepthRange(
                depthPercent = (max * 100f).toInt(),
                strokePercent = ((max - min).coerceAtLeast(0.05f) * 100f).toInt()
            )
            delay(ADAPTIVE_RAMP_STEP_DELAY_MS)
        }
    }

    fun pause() {
        val st = _uiState.value
        if (!st.isRunning || st.isPaused) return
        val mode = st.activePattern?.mode ?: return
        progressiveJob?.cancel(); progressiveJob = null
        chaosJob?.cancel(); chaosJob = null
        teasingRandomJob?.cancel(); teasingRandomJob = null
        listeningDriveJob?.cancel(); listeningDriveJob = null
        audioLevelMonitor.stop()
        autoJob?.cancel(); autoJob = null
        adaptiveJob?.cancel(); adaptiveJob = null
        _uiState.update { it.copy(isPaused = true, isRunning = false) }
        when (mode) {
            PatternControlMode.STREAMING -> Unit
            else -> bleManager.liveSet("speed", 0)
        }
    }

    fun resume() {
        val st = _uiState.value
        if (!st.isPaused) return
        val mode = st.activePattern?.mode ?: return
        _uiState.update { it.copy(isPaused = false, isRunning = true) }
        // Reprise de session : relance le micro si le mode à l'écoute est resté activé.
        updateListeningMonitor()
        when (mode) {
            PatternControlMode.STREAMING -> setStreamPosition(st.streamPositionPercent)
            PatternControlMode.PROGRESSIVE -> startProgressiveRamp((st.speed * 100f).toInt().coerceAtLeast(1))
            PatternControlMode.AUTO_RANDOM -> startAutoMix(st.autoIntensity)
            PatternControlMode.ADAPTIVE -> startAdaptiveMix()
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
        autoJob = storeScope.launch {
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

    fun setStreamPositionDrag(positionPercent: Int) {
        val target = positionPercent.coerceIn(0, 100)
        _uiState.update { it.copy(streamPositionPercent = target, isRunning = true, isPaused = false) }

        val state = _uiState.value
        if (canStreamPosition(state)) sendStreamPositionTarget(target)
    }

    fun setStreamPosition(positionPercent: Int) {
        val st = _uiState.value
        if (!canStreamPosition(st)) return
        val target = positionPercent.coerceIn(0, 100)
        _uiState.update { it.copy(streamPositionPercent = target, isRunning = true, isPaused = false) }
        sendStreamPositionTarget(target)
    }

    fun stopLiveTouch() {
        _uiState.update { it.copy(isRunning = false, isPaused = false, speed = 0f) }
        bleManager.sendCommand(OssmCommand.Stop)
    }

    private fun canStreamPosition(state: ControlUiState): Boolean {
        val canStream = state.rangeWizard != null || state.activePattern?.mode == PatternControlMode.STREAMING
        return canStream && state.streamingReady
    }

    private fun sendStreamPositionTarget(target: Int) {
        val producer = if (_uiState.value.rangeWizard != null) {
            LiveTargetProducer.RANGE_WIZARD
        } else {
            LiveTargetProducer.TOUCH
        }
        bleManager.sendCommand(
            OssmCommand.Stream(target, timeMs = STREAM_RELEASE_MOVE_MS, producer = producer)
        )
    }

    // ---- Assistant de plage au toucher ----

    fun startRangeWizard() {
        val current = _uiState.value
        if (current.rangeWizard != null) return
        val mode = current.activePattern?.mode ?: return
        if (mode == PatternControlMode.STREAMING || mode == PatternControlMode.LAUNCH_ONLY) return
        // Stoppe toute activité en cours avant de passer la machine en suivi de position.
        progressiveJob?.cancel(); progressiveJob = null
        chaosJob?.cancel(); chaosJob = null
        autoJob?.cancel(); autoJob = null; autoFirmwareMode = null
        adaptiveJob?.cancel(); adaptiveJob = null; adaptiveFirmwareMode = null
        adaptiveEngine.setRunning(false)
        _uiState.update {
            it.copy(
                rangeWizard = RangeWizardState(returnPatternKey = it.activePatternKey),
                streamPositionPercent = 0,
                isRunning = false,
                isPaused = false,
                autoRunning = false,
                pendingManualChange = null
            )
        }
        bleManager.enterStreaming(_uiState.value.liveAccelerationPercent)
    }

    fun captureRangePoint() {
        val wiz = _uiState.value.rangeWizard ?: return
        val pos = (_uiState.value.streamPositionPercent / 100f).coerceIn(0f, 1f)
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
        _uiState.update { it.copy(rangeWizard = null) }
        activatePattern(wiz.returnPatternKey)
    }

    private fun finishRangeWizard(lo: Float, hi: Float, returnKey: String) {
        val current = _uiState.value
        val (depthMin, depthMax) = clampDepthRange(lo, hi, current.depthLimitMin, current.depthLimitMax)
        _uiState.update {
            it.copy(rangeWizard = null, depthMin = depthMin, depthMax = depthMax, pendingManualChange = null)
        }
        storeScope.launch { userHabitsRepository.recordDepthHabit(depthMin, depthMax) }
        // Retour au pattern d'origine : activatePattern renvoie les paramètres
        // (dont la nouvelle plage) une fois le changement de mode stabilisé.
        activatePattern(returnKey)
    }

    fun savePatternOrder(keys: List<String>) {
        _uiState.update { it.copy(patternOrder = keys) }
        storeScope.launch { userHabitsRepository.savePatternOrder(keys) }
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

        val current = _uiState.value
        val (depthMin, depthMax) = clampDepthRange(
            preset.depthMin,
            preset.depthMax,
            current.depthLimitMin,
            current.depthLimitMax
        )
        _uiState.update {
            it.copy(
                activePatternKey = matchingPattern.key,
                speed = preset.speed,
                depthMin = depthMin,
                depthMax = depthMax,
                isRunning = true,
                pendingManualChange = null
            )
        }
        bleManager.sendCommand(OssmCommand.ActivatePattern(matchingPattern))
        sendCurrentStrokeEngineCommand()
    }

    fun requestSensationChange(value: Float) {
        val sensation = value.coerceIn(0f, 1f)
        when (_uiState.value.activePattern?.mode) {
            PatternControlMode.STROKE_ENGINE -> {
                _uiState.update { it.copy(sensation = sensation) }
                sendCurrentStrokeEngineCommand()
            }
            PatternControlMode.PROGRESSIVE -> {
                // Sensation = ramp rate; the running ramp reads it live, no firmware write needed.
                _uiState.update { it.copy(sensation = sensation) }
            }
            else -> return
        }
    }

    // ---- Garde anti-saut (enforcee ici pour que l'overlay la respecte aussi) ----

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
        val current = _uiState.value
        val (normalizedMin, normalizedMax) = clampDepthRange(
            depthMin,
            depthMax,
            current.depthLimitMin,
            current.depthLimitMax
        )
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

    private fun clampDepthRange(min: Float, max: Float, limitMin: Float, limitMax: Float): Pair<Float, Float> {
        val lowerBound = minOf(limitMin, limitMax).coerceIn(0f, 1f)
        val upperBound = maxOf(limitMin, limitMax).coerceIn(lowerBound, 1f)
        val requestedMin = minOf(min, max).coerceIn(lowerBound, upperBound)
        val requestedMax = maxOf(min, max).coerceIn(lowerBound, upperBound)
        if (requestedMax - requestedMin >= MIN_DEPTH_LIMIT_SPAN) {
            return requestedMin to requestedMax
        }
        return if (requestedMin + MIN_DEPTH_LIMIT_SPAN <= upperBound) {
            requestedMin to requestedMin + MIN_DEPTH_LIMIT_SPAN
        } else {
            upperBound - MIN_DEPTH_LIMIT_SPAN to upperBound
        }
    }

    private fun normalizeDepthLimits(min: Float, max: Float): Pair<Float, Float> {
        val lower = minOf(min, max).coerceIn(0f, 1f)
        val upper = maxOf(min, max).coerceIn(0f, 1f)
        if (upper - lower >= MIN_DEPTH_LIMIT_SPAN) return lower to upper
        return if (lower + MIN_DEPTH_LIMIT_SPAN <= 1f) {
            lower to lower + MIN_DEPTH_LIMIT_SPAN
        } else {
            1f - MIN_DEPTH_LIMIT_SPAN to 1f
        }
    }

    // ---- Nettoyage ----

    /** Arrête tous les tickers de session en cours (usage interne stop/emergencyStop). */
    private fun cancelSessionJobs() {
        progressiveJob?.cancel(); progressiveJob = null
        chaosJob?.cancel(); chaosJob = null
        teasingRandomJob?.cancel(); teasingRandomJob = null
        listeningDriveJob?.cancel(); listeningDriveJob = null
        manualSpeedSnapshot = null
        audioLevelMonitor.stop()
        autoJob?.cancel(); autoJob = null
        autoFirmwareMode = null
        adaptiveJob?.cancel(); adaptiveJob = null
        adaptiveFirmwareMode = null
        adaptiveEngine.setRunning(false)
    }

    /**
     * Appelé quand le ViewModel qui observe ce store est détruit. On coupe seulement le
     * micro : l'état et les tickers du store restent vivants pour le futur overlay.
     */
    fun onUiCleared() {
        audioLevelMonitor.stop()
    }

    companion object {
        // Retour souple sur lever du doigt (au lieu d'un snap 5 ms brutal).
        private const val STREAM_RELEASE_MOVE_MS = 60

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

        // Mode adaptatif : montée d'intensité, pas max de rampe (garde anti-saut),
        // délais de sécurité pour les transitions firmware.
        private const val ADAPTIVE_BUILDUP_MS = 720_000f
        private const val ADAPTIVE_START_INTENSITY = 0.25f
        private const val ADAPTIVE_CAP_INTENSITY = 0.9f
        private const val ADAPTIVE_MAX_SPEED = 0.85f
        private const val ADAPTIVE_RAMP_STEP = 0.05f
        private const val ADAPTIVE_RAMP_STEP_DELAY_MS = 140L
        private const val ADAPTIVE_FIRST_SWITCH_DELAY_MS = 1300L
        private const val ADAPTIVE_PATTERN_SWITCH_DELAY_MS = 120L
        private const val ADAPTIVE_PAUSE_POLL_MS = 250L

        // Mode aléatoire / piston : intervalle entre deux pas de la marche (ms).
        // Court + petits pas = promenade fluide sans grands écarts.
        private const val TEASE_RANDOM_MIN_MS = 700L
        private const val TEASE_RANDOM_MAX_MS = 1_900L

        // Mode à l'écoute : cadence maxi d'envoi BLE de la vitesse pilotée par le micro
        // (~10 Hz). Le micro n'émet de toute façon qu'un niveau toutes les ~200 ms.
        private const val LISTENING_SEND_MIN_MS = 100L

        // Vitesse aléatoire par blocs (paliers).
        private const val SPEED_BLOCK_MIN_MS = 2_000L   // durée mini d'un palier
        private const val SPEED_BLOCK_MAX_MS = 6_000L   // durée maxi (~5-6 s)
        private const val SPEED_RATCHET = 0.5f          // plancher = 50 % du max déjà atteint
        private const val MIN_DEPTH_LIMIT_SPAN = 0.05f
    }
}
