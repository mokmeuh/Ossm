package com.ossm.remote.session

import com.ossm.remote.ble.BleManager
import com.ossm.remote.ble.mapLiveStreamPositionPercent
import com.ossm.remote.data.repository.ControlSafetySettingsRepository
import com.ossm.remote.data.repository.UserHabitsRepository
import com.ossm.remote.model.AutoRandomMixablePatterns
import com.ossm.remote.model.BleConnectionState
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
import com.ossm.remote.model.LogLevel
import com.ossm.remote.natural.NaturalAiEndpoint
import com.ossm.remote.natural.NaturalAiFailureKind
import com.ossm.remote.natural.NaturalAiLoadTimeoutException
import com.ossm.remote.natural.NaturalAiModelState
import com.ossm.remote.natural.NaturalAiProblem
import com.ossm.remote.natural.OllamaModelLoader
import com.ossm.remote.natural.OllamaModelMissingException
import com.ossm.remote.natural.classifyNaturalAiFailure
import com.ossm.remote.natural.NaturalAiFallback
import com.ossm.remote.natural.NaturalAiFallbackKind
import com.ossm.remote.natural.NaturalAiPacing
import com.ossm.remote.natural.NaturalAiPacingInfo
import com.ossm.remote.natural.NaturalAiPlannerFactory
import com.ossm.remote.natural.NaturalConstraints
import com.ossm.remote.natural.NaturalFrame
import com.ossm.remote.natural.NaturalLimits
import com.ossm.remote.natural.NaturalModeEngine
import com.ossm.remote.natural.NaturalPlan
import com.ossm.remote.natural.NaturalPlanInput
import com.ossm.remote.natural.NaturalPlanner
import com.ossm.remote.natural.NaturalSettings
import com.ossm.remote.model.VOICE_PATTERN_KEY
import com.ossm.remote.model.isVoicePattern
import com.ossm.remote.remote.PhoneHostMotionLimits
import com.ossm.remote.voice.NotInstalledVoiceCommandInterpreter
import com.ossm.remote.voice.VoiceCommandInterpreter
import com.ossm.remote.voice.VoiceDepthRange
import com.ossm.remote.voice.VoiceModulation
import com.ossm.remote.voice.VoiceMotif
import com.ossm.remote.voice.VoiceMotifMath
import com.ossm.remote.voice.VoiceMotifValidator
import com.ossm.remote.voice.VoiceShortWordStop
import com.ossm.remote.voice.VoiceStopSource
import com.ossm.remote.voice.VoiceInput
import com.ossm.remote.voice.VoiceRuleContext
import com.ossm.remote.voice.VoiceInterpretResult
import com.ossm.remote.voice.VoiceInterpretation
import com.ossm.remote.voice.VoicePlanner
import com.ossm.remote.voice.VoiceStep
import com.ossm.remote.voice.VoiceStepValidator
import com.ossm.remote.remote.PhoneHostReadiness
import com.ossm.remote.remote.RemoteSessionView
import com.ossm.remote.viewmodel.ControlUiState
import com.ossm.remote.viewmodel.GuardedControl
import com.ossm.remote.viewmodel.LiveRecordingState
import com.ossm.remote.viewmodel.NaturalPlannerStatus
import com.ossm.remote.viewmodel.PendingManualChange
import com.ossm.remote.viewmodel.RandomTarget
import com.ossm.remote.viewmodel.RangeWizardState
import com.ossm.remote.viewmodel.RandomPresetTarget
import com.ossm.remote.viewmodel.RandomRangePreset
import com.ossm.remote.viewmodel.SliderGuardUiState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Patterns qui exposent le mode aléatoire par cases. */
private const val TEASING_POUNDING_KEY = "teasingPounding"
private const val SIMPLE_STROKE_KEY = "simpleStroke"
/** Patterns sur lesquels le mode aléatoire (piston) est disponible. */
private val RANDOM_CAPABLE_KEYS = setOf(TEASING_POUNDING_KEY, SIMPLE_STROKE_KEY)

private data class LiveRecordingSample(
    val positionPercent: Int,
    val offsetMs: Long
)

internal class StrokeEngineActivationSpeedGate {
    private var nextActivationToken = 0L
    private var pendingActivationToken: Long? = null
    private var latestCommand: StrokeEngineCommand? = null
    private var hasPendingOverride = false

    @Synchronized
    fun begin(initialCommand: StrokeEngineCommand? = null): Long {
        val token = ++nextActivationToken
        pendingActivationToken = token
        latestCommand = initialCommand
        hasPendingOverride = false
        return token
    }

    @Synchronized
    fun retainIfActivating(speedPercent: Int): Boolean {
        if (pendingActivationToken == null) return false
        val safeSpeed = speedPercent.coerceIn(0, 100) / 100f
        latestCommand = (latestCommand ?: StrokeEngineCommand()).copy(speed = safeSpeed)
        hasPendingOverride = true
        return true
    }

    @Synchronized
    fun retainIfActivating(command: StrokeEngineCommand): Boolean {
        if (pendingActivationToken == null) return false
        latestCommand = command
        hasPendingOverride = true
        return true
    }

    @Synchronized
    fun finishResult(token: Long, activationSucceeded: Boolean): ActivationSpeedFinish {
        if (pendingActivationToken != token) {
            return ActivationSpeedFinish(accepted = false, speedPercent = null)
        }
        pendingActivationToken = null
        val latest = latestCommand
        val override = hasPendingOverride
        latestCommand = null
        hasPendingOverride = false
        return ActivationSpeedFinish(
            accepted = true,
            speedPercent = latest
                ?.takeIf { activationSucceeded && override }
                ?.let { (it.speed * 100f).roundToInt().coerceIn(0, 100) },
            command = latest.takeIf { activationSucceeded && override },
        )
    }

    @Synchronized
    fun finish(token: Long, activationSucceeded: Boolean): Int? {
        val result = finishResult(token, activationSucceeded)
        return result.speedPercent.takeIf { result.accepted }
    }

    @Synchronized
    fun finish(activationSucceeded: Boolean): Int? =
        pendingActivationToken?.let { finish(it, activationSucceeded) }

    @Synchronized
    fun cancel() {
        pendingActivationToken = null
        latestCommand = null
        hasPendingOverride = false
    }
}

internal data class ActivationSpeedFinish(
    val accepted: Boolean,
    val speedPercent: Int?,
    val command: StrokeEngineCommand? = null,
)

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
    private val userHabitsRepository: UserHabitsRepository
) {
    /**
     * Scope de session : survit au ViewModel (le store est un singleton) pour que le
     * futur overlay continue de piloter la machine quand l'UI n'est plus affichée.
     */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(ControlUiState())
    val uiState: StateFlow<ControlUiState> = _uiState.asStateFlow()

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

    /**
     * Capture the local phone-owned envelope before a browser can issue a
     * command. The optional values let the remote session seam pass the exact
     * local controls it displayed; omitted values use the current Store state.
     */
    fun beginRemoteSession(
        localSpeedMaximum: Float? = null,
        localDepthMinimum: Float? = null,
        localDepthMaximum: Float? = null,
        localSensation: Float? = null,
    ): PhoneHostMotionLimits {
        val current = _uiState.value
        val currentDepthRange = defaultLocalDepthRange(current)
        // Bipolar sensation: the local slider position s sets a symmetric intensity |s - 0.5|.
        // By default the current sensation is used, so it is always inside the captured range.
        val captured = PhoneHostMotionLimits(
            speedMaximum = localSpeedMaximum ?: defaultLocalSpeedMaximum(current),
            depthMinimum = localDepthMinimum ?: currentDepthRange.first,
            depthMaximum = localDepthMaximum ?: currentDepthRange.second,
            sensationIntensity = PhoneHostMotionLimits.sensationIntensityOf(localSensation ?: current.sensation),
        )
        val activeLimits = synchronized(remoteSessionLock) {
            remoteHostLimits = remoteHostLimits ?: captured
            remoteHostLimits ?: captured
        }
        bleManager.setPhoneHostSpeedCeiling(activeLimits.speedMaximum)
        // Until the browser acts, the remote* values mirror the machine's real state.
        val effective = effectiveRemoteMotionLimits() ?: activeLimits
        _remoteSessionView.value = RemoteSessionView(
            active = true,
            speedMax = effective.speedMaximum,
            depthMin = effective.depthMinimum,
            depthMax = effective.depthMaximum,
            sensationMin = effective.sensationMinimum,
            sensationMax = effective.sensationMaximum,
            remoteSpeed = effective.clampSpeed(current.speed),
            remoteDepthMin = effective.clampDepthRange(current.depthMin, current.depthMax).first,
            remoteDepthMax = effective.clampDepthRange(current.depthMin, current.depthMax).second,
            remoteSensation = effective.clampSensation(current.sensation),
            remotePatternKey = current.activePatternKey,
        )
        return activeLimits
    }

    /**
     * End of a remote session (transport loss, expiry, controller loss, explicit stop,
     * service destroyed). SAFETY: the session always ends in a STOP, never by adopting the
     * envelope maximum or the browser's last value as a local command:
     *  - every producer (progressive, chaos, random, auto, Live replay, pending activation)
     *    is cancelled and STOP (`set:speed:0`) is sent while the envelope still applies;
     *  - the resulting local state is safe: speed 0, not running, not paused, no pending
     *    guard popup. Depth and sensation keep their last EFFECTIVE values (never the maximum);
     *  - nothing restarts motion until the user moves a control himself after the session.
     * A short confirmation STOP follows to cover a stroke command that was already in flight
     * when the session ended; it is skipped if the user has started something meanwhile.
     *
     * @return true when a session was actually active.
     */
    fun endRemoteSession(): Boolean {
        val hadSession = remoteHostLimits != null
        if (hadSession) stop()
        naturalRemoteSpeed = 0f
        synchronized(remoteSessionLock) { remoteHostLimits = null }
        bleManager.setPhoneHostSpeedCeiling(null)
        _remoteSessionView.value = RemoteSessionView()
        if (hadSession) {
            _uiState.update {
                it.copy(
                    speed = 0f,
                    isRunning = false,
                    isPaused = false,
                    autoRunning = false,
                    autoIntensity = 0f,
                    pendingManualChange = null,
                    pendingAutoStart = false,
                )
            }
            scheduleRemoteSessionEndConfirmationStop()
        }
        return hadSession
    }

    private var remoteSessionEndConfirmJob: Job? = null

    private fun scheduleRemoteSessionEndConfirmationStop() {
        remoteSessionEndConfirmJob?.cancel()
        remoteSessionEndConfirmJob = storeScope.launch {
            delay(REMOTE_SESSION_END_CONFIRM_STOP_MS)
            val state = _uiState.value
            // Only when still idle: never override something the user started himself.
            if (remoteHostLimits == null && !state.isRunning && state.speed <= 0f) {
                bleManager.sendCommand(OssmCommand.Stop)
            }
        }
    }

    fun remoteSessionLimits(): PhoneHostMotionLimits? = remoteHostLimits

    private val _remoteSessionView = MutableStateFlow(RemoteSessionView())

    /**
     * Live view of the Internet session (envelope + what the browser asked for, after clamping).
     * Separate from [uiState]: during a session the local sliders show the envelope
     * (`speedMax`, `depthMin`, `depthMax`), never the remote values.
     */
    val remoteSessionView: StateFlow<RemoteSessionView> = _remoteSessionView.asStateFlow()

    /** Refresh the envelope fields (effective, intersected with local depth limits) and clamp remote values to them. */
    private fun publishRemoteSessionView(transform: (RemoteSessionView) -> RemoteSessionView = { it }) {
        val raw = remoteHostLimits
        if (raw == null) {
            _remoteSessionView.value = RemoteSessionView()
            return
        }
        val effective = effectiveRemoteMotionLimits()
        _remoteSessionView.update { previous ->
            val next = transform(previous).copy(active = true)
            val speedMax = effective?.speedMaximum ?: raw.speedMaximum
            val depthMin = effective?.depthMinimum ?: raw.depthMinimum
            val depthMax = effective?.depthMaximum ?: raw.depthMaximum
            val sensationLimits = effective ?: raw
            val sensationMin = sensationLimits.sensationMinimum
            val sensationMax = sensationLimits.sensationMaximum
            next.copy(
                speedMax = speedMax,
                depthMin = depthMin,
                depthMax = depthMax,
                sensationMin = sensationMin,
                sensationMax = sensationMax,
                remoteSpeed = next.remoteSpeed?.coerceIn(0f, speedMax),
                remoteDepthMin = next.remoteDepthMin?.coerceIn(depthMin, depthMax),
                remoteDepthMax = next.remoteDepthMax?.coerceIn(depthMin, depthMax),
                remoteSensation = next.remoteSensation?.coerceIn(sensationMin, sensationMax),
            )
        }
    }

    private fun markRemoteCommand(transform: (RemoteSessionView) -> RemoteSessionView = { it }) {
        publishRemoteSessionView { transform(it).copy(lastRemoteCommandAtMillis = System.currentTimeMillis()) }
    }

    /**
     * Readiness for the ordinary phone BLE path. Firmware remote claim,
     * heartbeat, release, watchdog and transport leases are intentionally not
     * consulted here.
     */
    fun hostReadiness(): PhoneHostReadiness = PhoneHostReadiness(
        connected = bleManager.connectionState.value is BleConnectionState.Connected,
        machineReady = bleManager.machineState.value.state != "unknown" &&
            bleManager.machineState.value.isReady,
        postSetupReady = bleManager.postSetupReady.value,
    )

    /** Remote-only entry points. Local callers continue using the existing APIs. */
    fun requestRemoteSpeedChange(value: Float) {
        if (rejectRemoteMotionIfUnavailable()) return
        val effective = effectiveRemoteMotionLimits()?.clampSpeed(value) ?: 0f
        markRemoteCommand { it.copy(remoteSpeed = effective) }
        requestRemoteSpeedChangeInternal(value)
    }

    fun requestRemoteDepthRangeChange(min: Float, max: Float) {
        if (rejectRemoteMotionIfUnavailable()) return
        requestDepthRangeChangeInternal(min, max, remote = true)
        val state = _uiState.value
        markRemoteCommand { it.copy(remoteDepthMin = state.depthMin, remoteDepthMax = state.depthMax) }
    }

    /** Browser `set_sensation`: clamped into the phone-owned symmetric range 0.5 ± d. */
    fun requestRemoteSensationChange(value: Float) {
        if (value.isNaN()) return
        if (rejectRemoteMotionIfUnavailable()) return
        val limits = effectiveRemoteMotionLimits() ?: return
        val sensation = limits.clampSensation(value)
        applySensationLive(sensation, throttled = true)
        markRemoteCommand { it.copy(remoteSensation = sensation) }
    }

    fun activateRemotePattern(patternKey: String) {
        // Voix listens to the phone microphone: never selectable from the browser.
        if (patternKey == VOICE_PATTERN_KEY) return
        if (rejectRemoteMotionIfUnavailable()) return
        markRemoteCommand { it.copy(remotePatternKey = patternKey) }
        activatePattern(patternKey)
        effectiveRemoteMotionLimits()?.let { limits ->
            _uiState.update { current ->
                val depth = limits.clampDepthRange(current.depthMin, current.depthMax)
                current.copy(
                    speed = limits.clampSpeed(current.speed),
                    progressiveMaxSpeed = limits.clampSpeed(current.progressiveMaxSpeed),
                    autoMaxSpeed = limits.clampSpeed(current.autoMaxSpeed),
                    depthMin = depth.first,
                    depthMax = depth.second,
                    sensation = limits.clampSensation(current.sensation),
                )
            }
        }
    }

    fun setRemoteStreamPosition(positionPercent: Int): Boolean {
        if (!bleManager.canEnforcePhoneHostSpeedCeiling()) {
            bleManager.rejectRemoteLiveTargetForPhoneLimit()
            stop()
            return false
        }
        val limits = effectiveRemoteMotionLimits() ?: run {
            rejectRemoteMotionIfUnavailable()
            return false
        }
        // BleManager owns the final inverse mapping. Convert the requested
        // physical target back to the raw argument that preserves the host
        // interval after that mapping.
        // Browser targets keep the phone-owned 80 %/s slew: their cadence crosses the network.
        acceptLiveDrag(
            limits.mapLiveUiPositionForBle(positionPercent, bleManager.liveInvert),
            directFollow = false,
        )
        markRemoteCommand { it.copy(remoteLivePercent = positionPercent.coerceIn(0, 100)) }
        return true
    }

    private val sessionBypass = mutableSetOf<GuardedControl>()
    private val remoteSessionLock = Any()
    @Volatile private var remoteHostLimits: PhoneHostMotionLimits? = null
    private var autoEnterJob: Job? = null
    private var modeActivationJob: Job? = null
    private var liveReturnJob: Job? = null
    private var liveTargetSlewJob: Job? = null
    private val strokeEngineActivationSpeed = StrokeEngineActivationSpeedGate()
    private var connectionEpoch = 0L
    private var autoEnteredConnectionEpoch: Long? = null
    private var liveRecordingStartMs = 0L
    private val liveRecordingSamples = mutableListOf<LiveRecordingSample>()
    private var liveReplayJob: Job? = null

    // Voix (2.3.26) state, declared before init: the init collectors may cancel the runner.
    private val voiceRunner = VoiceSequenceRunner(
        scope = storeScope,
        canRun = { voiceCanMove(_uiState.value) },
        emit = { logical, durationMs -> emitVoiceTarget(logical, durationMs) },
        onStep = { index -> onVoiceStep(index) },
        onFinished = { completed ->
            if (!completed) {
                logVoice(LogLevel.WARNING, "Plan vocal interrompu : ${voiceBlockReason(_uiState.value) ?: "annulé"}")
            }
            _uiState.update {
                it.copy(voiceRunning = false, voiceStepIndex = null, voiceMotif = null, isRunning = false)
            }
        },
        maxRate = { voiceMaxRate(_uiState.value) },
        onRampDone = { levels ->
            logVoice(
                LogLevel.INFO,
                "Voix : niveau final atteint prof ${levels.depth.roundToInt()} %, vitesse ${levels.speed.roundToInt()} %",
            )
        },
    )
    /** One refusal line per plan (never one per 40 ms tick). */
    private var voiceRefusalLogged = false

    /** On-device interpreter seam (none installed in 2.3.26; never a network service). */
    internal var voiceInterpreter: VoiceCommandInterpreter = NotInstalledVoiceCommandInterpreter
        set(value) {
            field = value
            _uiState.update { it.copy(voiceModelInstalled = value.isInstalled) }
        }
    private var voiceInterpretJob: Job? = null

    /** Cancel held native input without disabling fresh controls after STOP. */
    private fun invalidateLiveGestureEpoch() {
        _uiState.update { it.copy(liveGestureEpoch = it.liveGestureEpoch + 1L) }
    }

    private fun effectiveRemoteMotionLimits(): PhoneHostMotionLimits? {
        val captured = remoteHostLimits ?: return null
        val current = _uiState.value
        return captured
            .intersectLocalDepthRange(current.depthLimitMin, current.depthLimitMax)
            ?.takeIf { it.hasMinimumDepthSpan() && it.speedMaximum > 0f }
    }

    private fun rejectRemoteMotionIfUnavailable(): Boolean {
        if (remoteHostLimits == null) return true
        if (effectiveRemoteMotionLimits() != null) return false
        // A local range that no longer intersects the captured envelope is a
        // stop/reject condition. Never widen back into the old range.
        stop()
        return true
    }

    private fun hostDepthRange(requestedMinimum: Float, requestedMaximum: Float): Pair<Float, Float> {
        val limits = effectiveRemoteMotionLimits() ?: return requestedMinimum to requestedMaximum
        return limits.clampDepthRange(requestedMinimum, requestedMaximum)
    }

    private fun hostSpeed(requested: Float): Float {
        if (remoteHostLimits == null) return requested.coerceIn(0f, 1f)
        return effectiveRemoteMotionLimits()?.clampSpeed(requested) ?: 0f
    }

    /**
     * Remote-session model: while a session is active the app's speed/depth sliders no longer
     * drive the machine, they only set the envelope the browser may act in. Each local entry
     * point calls [consumeLocalSpeedAsMaximum] / [consumeLocalDepthAsEnvelope] first and
     * returns when it reports true, so no speed/depth BLE command is sent for the slider move.
     * Outside a session both return false and the ordinary local behavior is unchanged.
     */
    private fun consumeLocalSpeedAsMaximum(value: Float): Boolean {
        if (remoteHostLimits == null) return false
        updateRemoteSpeedMaximumFromLocal(value)
        return true
    }

    private fun consumeLocalDepthAsEnvelope(minimum: Float, maximum: Float): Boolean {
        if (remoteHostLimits == null) return false
        updateRemoteDepthRangeFromLocal(minimum, maximum)
        return true
    }

    /**
     * Local sensation in a session: the slider position s only sets the symmetric intensity
     * d = |s - 0.5| allowed to the browser (range 0.5 - d .. 0.5 + d). The slider value itself is
     * never sent to the motor.
     */
    private fun consumeLocalSensationAsIntensity(value: Float, commit: Boolean): Boolean {
        if (remoteHostLimits == null) return false
        updateRemoteSensationIntensityFromLocal(value, commit)
        return true
    }

    /** A recentring decided during a throttled drag that may not have reached the motor yet. */
    @Volatile private var pendingSensationRecentre = false

    private fun updateRemoteSensationIntensityFromLocal(value: Float, commit: Boolean) {
        if (value.isNaN()) return
        val intensity = PhoneHostMotionLimits.sensationIntensityOf(value)
        val updated = synchronized(remoteSessionLock) {
            remoteHostLimits = remoteHostLimits?.copy(sensationIntensity = intensity)
            remoteHostLimits
        } ?: return
        // Narrowing the range re-centres the current (browser) sensation into it: the nearest
        // bound on its own side, never the local slider value. Widening never moves it.
        val current = _uiState.value.sensation
        val recentred = updated.clampSensation(current)
        if (recentred != current) {
            applySensationLive(recentred, throttled = !commit)
            pendingSensationRecentre = !commit
        } else if (commit && pendingSensationRecentre) {
            // The drag's last recentring may have been throttled out: send the settled value once.
            applySensationLive(current, throttled = false)
            pendingSensationRecentre = false
        }
        if (commit) pendingSensationRecentre = false
        publishRemoteSessionView { view ->
            view.copy(remoteSensation = view.remoteSensation?.let(updated::clampSensation))
        }
    }

    private fun updateRemoteSpeedMaximumFromLocal(value: Float) {
        if (value.isNaN()) return
        val maximum = value.coerceIn(0f, 1f)
        val hadRemoteSession = synchronized(remoteSessionLock) {
            val active = remoteHostLimits != null
            remoteHostLimits = remoteHostLimits?.copy(speedMaximum = maximum)
            active
        }
        if (!hadRemoteSession) return
        bleManager.setPhoneHostSpeedCeiling(maximum)
        // A zero maximum stops everything; otherwise only lower what the browser had above it.
        if (maximum <= 0f) stop() else enforceRemoteEnvelopeOnCurrentValues()
        publishRemoteSessionView()
    }

    private fun updateRemoteDepthRangeFromLocal(minimum: Float, maximum: Float) {
        if (minimum.isNaN() || maximum.isNaN()) return
        val normalizedMinimum = minOf(minimum, maximum).coerceIn(0f, 1f)
        val normalizedMaximum = maxOf(minimum, maximum).coerceIn(0f, 1f)
        val hadRemoteSession = synchronized(remoteSessionLock) {
            val active = remoteHostLimits != null
            remoteHostLimits = remoteHostLimits?.copy(
                depthMinimum = normalizedMinimum,
                depthMaximum = normalizedMaximum,
            )
            active
        }
        if (!hadRemoteSession) return
        enforceRemoteEnvelopeOnCurrentValues()
        publishRemoteSessionView()
    }

    /**
     * Lower, never raise, the values currently imposed by the browser so they sit inside the
     * envelope. Re-sends the stroke command only when something actually had to be lowered;
     * an envelope that no longer allows motion (zero speed, empty depth intersection) stops.
     */
    private fun enforceRemoteEnvelopeOnCurrentValues() {
        if (remoteHostLimits == null) return
        val limits = effectiveRemoteMotionLimits() ?: run {
            stop()
            return
        }
        val state = _uiState.value
        naturalRemoteSpeed = limits.clampSpeed(naturalRemoteSpeed)
        enforceNaturalNow()
        val speed = limits.clampSpeed(state.speed)
        val (depthMin, depthMax) = limits.clampDepthRange(state.depthMin, state.depthMax)
        val sensation = limits.clampSensation(state.sensation)
        if (speed == state.speed && depthMin == state.depthMin && depthMax == state.depthMax &&
            sensation == state.sensation
        ) return
        _uiState.update { it.copy(speed = speed, depthMin = depthMin, depthMax = depthMax, sensation = sensation) }
        val mode = state.activePattern?.mode
        if (state.isRunning && (mode == PatternControlMode.STROKE_ENGINE || mode == PatternControlMode.PROGRESSIVE)) {
            sendCurrentStrokeEngineCommand()
        }
    }

    private fun defaultLocalSpeedMaximum(state: ControlUiState): Float {
        return when (state.activePattern?.mode) {
            PatternControlMode.PROGRESSIVE -> state.progressiveMaxSpeed
            PatternControlMode.AUTO_RANDOM -> state.autoMaxSpeed
            PatternControlMode.NATURAL -> state.naturalMaxSpeed
            else -> state.speed
        }.coerceIn(0f, 1f)
    }

    private fun defaultLocalDepthRange(state: ControlUiState): Pair<Float, Float> {
        val lower = maxOf(state.depthMin, state.depthLimitMin)
        val upper = minOf(state.depthMax, state.depthLimitMax)
        return if (lower <= upper) lower to upper else state.depthLimitMin to state.depthLimitMax
    }

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
                val mergedPatterns = if (safePatterns.isEmpty()) KnownFallbackPatterns else safePatterns
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
                    // Naturel: BLE loss halts the producer at once; nothing can be written.
                    haltNatural(sendStop = false)
                    cancelVoiceSequence()
                    naturalMachineFrame = null
                    _uiState.update { it.copy(naturalReady = false) }
                    // BLE loss stops all producers and pending targets, but the
                    // browser/socket session still owns the captured phone envelope.
                    // Remote commands remain rejected by host readiness until BLE
                    // reconnects; only socket teardown calls endRemoteSession().
                    if (remoteHostLimits != null) {
                        stop()
                    } else {
                        invalidateLiveGestureEpoch()
                        cancelLiveReturn()
                        cancelLiveTargetSlew()
                        autoEnterJob?.cancel()
                        autoEnterJob = null
                        modeActivationJob?.cancel()
                        modeActivationJob = null
                        bleManager.cancelStrokeEngineActivation()
                        strokeEngineActivationSpeed.cancel()
                    }
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
            safetySettingsRepository.liveInvertEnabled.collect { enabled ->
                bleManager.liveInvert = enabled
                _uiState.update { it.copy(liveInvert = enabled) }
            }
        }

        // Naturel: homing, preflight or a machine error halts the producer immediately.
        storeScope.launch {
            bleManager.machineState.collect { machine ->
                if ((machine.isHoming || machine.isPreflight || machine.isError) &&
                    (_uiState.value.naturalRunning || naturalJob != null)
                ) {
                    haltNatural(sendStop = true)
                }
            }
        }

        storeScope.launch {
            safetySettingsRepository.naturalAiEnabled.collect { enabled ->
                _uiState.update {
                    it.copy(
                        naturalAiEnabled = enabled,
                    ).withNaturalAiStatus()
                }
            }
        }

        storeScope.launch {
            safetySettingsRepository.naturalAiAddress.collect { address ->
                _uiState.update { it.copy(naturalAiAddress = address) }
            }
        }

        storeScope.launch {
            safetySettingsRepository.naturalAiReasoningRelay.collect { enabled ->
                if (enabled != _uiState.value.naturalAiReasoningRelay) applyNaturalAiReasoningRelay(enabled)
            }
        }

        storeScope.launch {
            safetySettingsRepository.liveAccelerationPercent.collect { percent ->
                _uiState.update { it.copy(liveAccelerationPercent = percent) }
            }
        }

        storeScope.launch {
            safetySettingsRepository.precisionOverrides.collect { overrides ->
                _uiState.update { it.copy(precisionOverrides = overrides) }
            }
        }

        storeScope.launch {
            var wasStreamingReady = false
            bleManager.streamingReady.collect { ready ->
                if (!ready) {
                    if (wasStreamingReady) invalidateLiveGestureEpoch()
                    cancelVoiceSequence()
                    cancelLiveReturn()
                    cancelLiveTargetSlew()
                    cancelLiveReplay(sendStop = false)
                    cancelLiveRecordingTouch()
                    liveRecordingSamples.clear()
                }
                _uiState.update { it.copy(streamingReady = ready) }
                if (ready && !wasStreamingReady &&
                    _uiState.value.activePattern?.mode == PatternControlMode.STREAMING
                ) {
                    // 2.3.17: BleManager opens streamingReady only after the entry convergence
                    // has slowly brought the carriage to Live 0 (see convergeLiveEntryToHome),
                    // so the logical scalar restarts at 0 and the first touch slews from the
                    // real position. Speed and targets stay disarmed until an explicit touch.
                    _uiState.update {
                        it.copy(streamPositionPercent = 0, isRunning = false, speed = 0f, isPaused = false)
                    }
                    voiceRunner.resetPosition(0)
                }
                wasStreamingReady = ready
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

        storeScope.launch {
            safetySettingsRepository.voiceShortWordStop.collect { enabled ->
                _uiState.update { it.copy(voiceShortWordStop = enabled) }
            }
        }

        storeScope.launch {
            bleManager.linkSuspectEvents.collect { onLinkSuspect() }
        }

        // 2.3.28: persisted Voix depth range (raw fractions; the hard limits apply at use).
        storeScope.launch {
            combine(
                safetySettingsRepository.voiceDepthMin,
                safetySettingsRepository.voiceDepthMax
            ) { min, max -> ControlSafetySettingsRepository.normalizeVoiceDepthRange(min, max) }
                .collect { (min, max) ->
                    _uiState.update { it.copy(voiceDepthMin = min, voiceDepthMax = max) }
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
            combine(bleManager.machineState, bleManager.postSetupReady) { state, postSetupReady ->
                postSetupReady && state.state != "unknown" && state.isReady
            }.first { it }
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
        val previousPosition = _uiState.value.streamPositionPercent
        invalidateLiveGestureEpoch()
        cancelLiveReturn()
        cancelLiveTargetSlew()
        modeActivationJob?.cancel()
        modeActivationJob = null
        bleManager.cancelStrokeEngineActivation()
        strokeEngineActivationSpeed.cancel()
        cancelLiveReplay(sendStop = false)
        liveRecordingSamples.clear()
        // Voix is never learned as a start habit (it would open the microphone at launch).
        if (pattern.key != VOICE_PATTERN_KEY) {
            storeScope.launch { userHabitsRepository.recordPatternHabit(pattern.key) }
        }
        cancelVoiceSequence()
        resetVoiceSession()
        // Leaving any previous live/progressive session: stop the tickers.
        progressiveJob?.cancel(); progressiveJob = null
        chaosJob?.cancel(); chaosJob = null
        teasingRandomJob?.cancel(); teasingRandomJob = null
        sensationTurnJob?.cancel(); sensationTurnJob = null
        sensationTurnJob?.cancel(); sensationTurnJob = null
        lastRandomStrokeCommand = null
        autoJob?.cancel(); autoJob = null; autoFirmwareMode = null
        cancelNaturalProducer()
        naturalMachineFrame = null
        naturalRemoteSpeed = 0f
        _uiState.update { it.copy(naturalRunning = false, naturalReady = false, naturalStartPending = false) }
        // Repart sur des cases décochées à chaque changement de pattern (jamais
        // d'aléatoire surprise en revenant sur Teasing & Pounding).
        _uiState.update {
            it.copy(
                randSpeed = false, randDepthMin = false, randDepthMax = false,
                randSensation = false,
                randomSpeedCurrent = null,
                randomDepthMinCurrent = null,
                randomDepthMaxCurrent = null,
                randomSensationCurrent = null
            )
        }
        _uiState.update {
            it.copy(
                autoRunning = false,
                autoIntensity = 0f,
                pendingAutoStart = false,
                liveRecordingState = LiveRecordingState.IDLE,
                // Preserve a stale Live scalar until the new streaming readiness gate; the
                // gate then performs the phone-owned visual convergence to 0 instead of an
                // immediate state jump that could race a snapshot.
                streamPositionPercent = if (pattern.mode == PatternControlMode.STREAMING) {
                    previousPosition
                } else {
                    0
                },
                streamingReady = false,
            )
        }
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
        if (pattern.mode == PatternControlMode.AUTO_RANDOM) return
        requestModeActivation(pattern)
    }

    private fun requestModeActivation(pattern: OssmPattern) {
        val epoch = connectionEpoch
        if (bleManager.connectionState.value !is BleConnectionState.Connected) return
        val activationToken = when (pattern.mode) {
            PatternControlMode.STROKE_ENGINE,
            PatternControlMode.PROGRESSIVE -> {
                val state = _uiState.value
                strokeEngineActivationSpeed.begin(
                    StrokeEngineCommand(
                        speed = state.speed,
                        depthMin = state.depthMin,
                        depthMax = state.depthMax,
                        sensation = state.sensation,
                    )
                )
            }
            else -> null
        }
        modeActivationJob?.cancel()
        modeActivationJob = storeScope.launch {
            bleManager.postSetupReady.first { it }
            if (connectionEpoch != epoch ||
                bleManager.connectionState.value !is BleConnectionState.Connected ||
                _uiState.value.activePatternKey != pattern.key
            ) return@launch
            activateModeAfterPostSetup(pattern, epoch, activationToken)
        }
    }

    private fun activateModeAfterPostSetup(
        pattern: OssmPattern,
        activationEpoch: Long,
        activationToken: Long?,
    ) {
        when (pattern.mode) {
            PatternControlMode.AUTO_RANDOM -> {
                // Just select it. The user picks modes + limits, then presses start (with a
                // limits-reminder popup). The mix engine handles its own firmware mode switches.
            }
            PatternControlMode.STREAMING -> {
                bleManager.enterStreaming(_uiState.value.liveAccelerationPercent)
            }
            PatternControlMode.NATURAL -> {
                // Verified, sequential mode entry at speed 0 (depth before stroke). Nothing
                // moves until an explicit start; the producer begins from these values.
                val st = _uiState.value
                val raw = StrokeEngineCommand(
                    speed = 0f,
                    depthMin = st.depthMin,
                    depthMax = st.depthMax,
                    sensation = NaturalLimits.NEUTRAL_SENSATION,
                )
                val command = effectiveRemoteMotionLimits()?.clampStrokeEngineCommand(raw) ?: raw
                bleManager.activatePatternVerified(
                    patternId = pattern.id ?: 1,
                    patternName = pattern.name,
                    command = command,
                    onFinished = { succeeded ->
                        onNaturalActivationFinished(pattern.key, activationEpoch, command, succeeded)
                    },
                )
            }
            PatternControlMode.PROGRESSIVE -> {
                // Use the verified activation seam so the automatic ramp cannot race
                // navigation/setup. The retained first speed is replayed only after
                // depth/stroke and the ready state are confirmed.
                val st = _uiState.value
                bleManager.activatePatternVerified(
                    patternId = pattern.id ?: 0,
                    patternName = pattern.name,
                    command = StrokeEngineCommand(
                        speed = 0f,
                        depthMin = st.depthMin,
                        depthMax = st.depthMax,
                        sensation = st.sensation
                    ),
                    onFinished = { succeeded ->
                        if (activationToken != null) {
                            onProgressiveActivationFinished(
                                pattern.key,
                                activationEpoch,
                                activationToken,
                                succeeded,
                            )
                        }
                    },
                )
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
                    ),
                    onFinished = { succeeded ->
                        if (activationToken != null) {
                            onStrokeEngineActivationFinished(
                                pattern.key,
                                activationEpoch,
                                activationToken,
                                succeeded,
                            )
                        }
                    },
                )
            }
        }
    }

    private fun onStrokeEngineActivationFinished(
        patternKey: String,
        activationEpoch: Long,
        activationToken: Long,
        succeeded: Boolean,
    ) {
        storeScope.launch {
            if (connectionEpoch != activationEpoch || _uiState.value.activePatternKey != patternKey) return@launch
            val completion = strokeEngineActivationSpeed.finishResult(activationToken, succeeded)
            if (!completion.accepted || !succeeded) return@launch
            if (_uiState.value.activePattern?.mode == PatternControlMode.STROKE_ENGINE) {
                completion.command?.let { command ->
                    // Re-enter the Store safety seam so a local range reduction made
                    // while setup was in flight is applied before the BLE write.
                    sendStrokeEngineCommand(command)
                }
            }
        }
    }

    private fun onProgressiveActivationFinished(
        patternKey: String,
        activationEpoch: Long,
        activationToken: Long,
        succeeded: Boolean,
    ) {
        storeScope.launch {
            if (connectionEpoch != activationEpoch ||
                _uiState.value.activePatternKey != patternKey ||
                _uiState.value.activePattern?.mode != PatternControlMode.PROGRESSIVE
            ) return@launch
            val completion = strokeEngineActivationSpeed.finishResult(activationToken, succeeded)
            if (!completion.accepted || !succeeded) return@launch
            val retainedSpeed = completion.speedPercent
            if (retainedSpeed == 0) {
                _uiState.update { it.copy(speed = 0f, isRunning = false) }
                return@launch
            }
            completion.command?.let { command ->
                _uiState.update {
                    it.copy(
                        depthMin = command.depthMin,
                        depthMax = command.depthMax,
                        sensation = command.sensation,
                    )
                }
            }
            startProgressiveRamp(retainedSpeed?.coerceAtLeast(1) ?: 1)
        }
    }

    /** Variante objet du contrat overlay : délègue à l'API existante par key. */
    fun activatePattern(pattern: OssmPattern) = activatePattern(pattern.key)

    fun requestSpeedChange(value: Float) = requestSpeedChangeInternal(value, remote = false)

    /** Commit from a scalar gesture captured before a STOP/loss epoch change. */
    fun requestSpeedChangeAtEpoch(value: Float, gestureEpoch: Long) =
        requestSpeedChangeInternal(value, remote = false, expectedGestureEpoch = gestureEpoch)

    private fun requestSpeedChangeInternal(
        value: Float,
        remote: Boolean,
        expectedGestureEpoch: Long? = null,
    ) {
        if (expectedGestureEpoch != null && expectedGestureEpoch != _uiState.value.liveGestureEpoch) return
        if (remote && rejectRemoteMotionIfUnavailable()) return
        val current = _uiState.value
        if (!current.activePatternSupportsControls) return
        val mode = current.activePattern?.mode ?: return
        if (mode == PatternControlMode.NATURAL) {
            // Naturel: the browser speed is its bounded start/ceiling; locally the speed
            // control is the Naturel maximum (never a direct motor command).
            if (remote) requestRemoteNaturalSpeed(hostSpeed(value)) else setNaturalMaxSpeed(value)
            return
        }
        // During a remote session the local speed slider only sets the maximum.
        if (!remote && consumeLocalSpeedAsMaximum(value)) return
        val speedRequest = if (remote) hostSpeed(value) else value.coerceIn(0f, 1f)
        if (remote && mode == PatternControlMode.AUTO_RANDOM) {
            // The AutoRandom speed control is its start/intensity control; the
            // producer's own autoMaxSpeed remains a separate local ceiling.
            setAutoInitialIntensity(speedRequest)
            _uiState.update {
                it.copy(
                    autoIntensity = speedRequest,
                    speed = speedRequest,
                    isRunning = speedRequest > 0f,
                )
            }
            return
        }
        if (mode == PatternControlMode.AUTO_RANDOM || mode == PatternControlMode.STREAMING) {
            val speed = speedRequest
            _uiState.update { it.copy(speed = speed, isRunning = speed > 0f) }
            return
        }
        // In Progressif, setting the speed (re)starts the ramp from that value up to 100%.
        if (mode == PatternControlMode.PROGRESSIVE) {
            val speed = speedRequest
            val speedPercent = (speed * 100f).roundToInt().coerceIn(0, 100)
            _uiState.update { it.copy(speed = speed, isRunning = speed > 0f) }
            if (speedPercent == 0) {
                // An explicit zero is a stop, never the Progressive ramp's minimum 1%.
                // Cancel both producers even while activation is pending so an already
                // queued ramp cannot overwrite the explicit stop before its callback.
                progressiveJob?.cancel(); progressiveJob = null
                chaosJob?.cancel(); chaosJob = null
                if (strokeEngineActivationSpeed.retainIfActivating(0)) return
                bleManager.sendCommand(OssmCommand.Stop)
                return
            }
            if (strokeEngineActivationSpeed.retainIfActivating(speedPercent)) return
            startProgressiveRamp(speedPercent.coerceAtLeast(1))
            return
        }
        maybeApplyManualChange(
            control = GuardedControl.SPEED,
            speed = speedRequest,
            depthMin = current.depthMin,
            depthMax = current.depthMax,
            localAuthority = !remote,
        )
    }

    private fun requestRemoteSpeedChangeInternal(value: Float) =
        requestSpeedChangeInternal(value, remote = true)

    fun requestDepthRangeChange(min: Float, max: Float) =
        requestDepthRangeChangeInternal(min, max, remote = false)

    private fun requestDepthRangeChangeInternal(min: Float, max: Float, remote: Boolean) {
        if (remote && rejectRemoteMotionIfUnavailable()) return
        val current = _uiState.value
        if (!current.activePatternSupportsControls) return
        val mode = current.activePattern?.mode ?: return
        val remoteLimits = if (remote) effectiveRemoteMotionLimits() else null
        if (remote && remoteLimits == null) {
            rejectRemoteMotionIfUnavailable()
            return
        }
        val (lo, hi) = if (remote) {
            val limits = remoteLimits ?: return
            if (!limits.hasMinimumDepthSpan()) {
                rejectRemoteMotionIfUnavailable()
                return
            }
            clampDepthRange(min, max, limits.depthMinimum, limits.depthMaximum)
        } else {
            clampDepthRange(min, max, current.depthLimitMin, current.depthLimitMax)
        }
        // During a remote session the local depth range only sets the envelope.
        if (!remote && consumeLocalDepthAsEnvelope(lo, hi)) return
        storeScope.launch { userHabitsRepository.recordDepthHabit(lo, hi) }
        if (mode == PatternControlMode.AUTO_RANDOM || mode == PatternControlMode.STREAMING ||
            mode == PatternControlMode.NATURAL
        ) {
            _uiState.update { it.copy(depthMin = lo, depthMax = hi, pendingManualChange = null) }
            if (mode == PatternControlMode.NATURAL) enforceNaturalNow()
            return
        }
        maybeApplyManualChange(
            control = GuardedControl.DEPTH,
            speed = current.speed,
            depthMin = lo,
            depthMax = hi,
            localAuthority = !remote,
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

    fun setSpeedLive(value: Float) = setSpeedLive(value, expectedGestureEpoch = null)

    /** Live scalar update that is rejected if STOP/loss advanced the captured gesture epoch. */
    fun setSpeedLive(value: Float, expectedGestureEpoch: Long?) {
        if (expectedGestureEpoch != null && expectedGestureEpoch != _uiState.value.liveGestureEpoch) return
        val current = _uiState.value
        val mode = current.activePattern?.mode ?: return
        if (mode == PatternControlMode.STREAMING || mode == PatternControlMode.LAUNCH_ONLY) return
        if (mode == PatternControlMode.NATURAL) {
            setNaturalMaxSpeed(value)
            return
        }
        // Frozen while a guard popup is open: the state/machine must NOT move until resolved,
        // otherwise "Annuler" would have nothing correct to fall back to.
        if (current.pendingManualChange != null) return
        // During a remote session the local speed slider only sets the maximum.
        if (consumeLocalSpeedAsMaximum(value)) return
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
        if (mode == PatternControlMode.PROGRESSIVE &&
            strokeEngineActivationSpeed.retainIfActivating((v * 100f).roundToInt().coerceIn(0, 100))
        ) {
            return
        }
        if (sendsLiveSpeed) {
            val speedPercent = (v * 100f).toInt()
            if (strokeEngineActivationSpeed.retainIfActivating(speedPercent)) return
            if (liveThrottleOk()) bleManager.liveSet("speed", speedPercent)
        }
    }

    fun setSensationLive(value: Float) = setSensationLive(value, expectedGestureEpoch = null)

    /** Live scalar update that is rejected if STOP/loss advanced the captured gesture epoch. */
    fun setSensationLive(value: Float, expectedGestureEpoch: Long?) {
        if (expectedGestureEpoch != null && expectedGestureEpoch != _uiState.value.liveGestureEpoch) return
        if (value.isNaN()) return
        _uiState.value.activePattern?.mode ?: return
        // During a remote session the local sensation slider only sets the symmetric intensity.
        if (consumeLocalSensationAsIntensity(value, commit = false)) return
        applySensationLive(value.coerceIn(0f, 1f), throttled = true)
    }

    private fun applySensationLive(value: Float, throttled: Boolean) {
        val mode = _uiState.value.activePattern?.mode ?: return
        val sensation = value.coerceIn(0f, 1f)
        _uiState.update { it.copy(sensation = sensation) }
        if (mode == PatternControlMode.STROKE_ENGINE || mode == PatternControlMode.PROGRESSIVE) {
            val state = _uiState.value
            if (strokeEngineActivationSpeed.retainIfActivating(
                    StrokeEngineCommand(
                        speed = state.speed,
                        depthMin = state.depthMin,
                        depthMax = state.depthMax,
                        sensation = state.sensation,
                    )
                )
            ) return
        }
        if (mode == PatternControlMode.STROKE_ENGINE && (!throttled || liveThrottleOk())) {
            bleManager.liveSet("sensation", (sensation * 100f).toInt())
        }
    }

    fun setDepthLive(min: Float, max: Float) {
        val current = _uiState.value
        val mode = current.activePattern?.mode ?: return
        if (mode == PatternControlMode.STREAMING || mode == PatternControlMode.LAUNCH_ONLY) return
        if (current.pendingManualChange != null) return
        val (lo, hi) = clampDepthRange(min, max, current.depthLimitMin, current.depthLimitMax)
        // During a remote session the local depth range only sets the envelope.
        if (consumeLocalDepthAsEnvelope(lo, hi)) return
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
        if (mode == PatternControlMode.NATURAL) enforceNaturalNow()
        if (mode == PatternControlMode.STROKE_ENGINE || mode == PatternControlMode.PROGRESSIVE) {
            val state = _uiState.value
            if (strokeEngineActivationSpeed.retainIfActivating(
                    StrokeEngineCommand(
                        speed = state.speed,
                        depthMin = state.depthMin,
                        depthMax = state.depthMax,
                        sensation = state.sensation,
                    )
                )
            ) return
        }
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
            if (remoteHostLimits != null) {
                // A session opened while the popup was showing: the confirmed local change
                // only sets the corresponding envelope maximum, it never drives the motor.
                _uiState.update { it.copy(pendingManualChange = null) }
                when (pending.control) {
                    GuardedControl.SPEED -> consumeLocalSpeedAsMaximum(pending.speed)
                    GuardedControl.DEPTH -> consumeLocalDepthAsEnvelope(pending.depthMin, pending.depthMax)
                }
                return@launch
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
        // Hard local limits changed: the envelope is intersected with them dynamically
        // (effectiveRemoteMotionLimits); lower what the browser holds, or stop if nothing is left.
        enforceNaturalNow()
        enforceRemoteEnvelopeOnCurrentValues()
        publishRemoteSessionView()
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
        if (remoteHostLimits != null) publishRemoteSessionView { it.copy(remoteSpeed = 0f) }
    }

    /** Arrêt d'urgence : stoppe tout, met la machine en état EmergencyStop puis restaure. */
    fun emergencyStop() {
        cancelSessionJobs()
        _uiState.update { it.copy(isRunning = false, isPaused = false, autoRunning = false, autoIntensity = 0f, speed = 0f, pendingManualChange = null) }
        bleManager.emergencyStop()
        if (remoteHostLimits != null) publishRemoteSessionView { it.copy(remoteSpeed = 0f) }
    }

    fun home() {
        // Homing re-enters the mode: Naturel stops at once and needs a fresh verified entry.
        cancelNaturalProducer()
        naturalMachineFrame = null
        _uiState.update { it.copy(naturalRunning = false, naturalStartPending = false, naturalReady = false) }
        cancelVoiceSequence()
        invalidateLiveGestureEpoch()
        cancelLiveReturn()
        cancelLiveTargetSlew()
        val pattern = _uiState.value.activePattern
        _uiState.update { it.copy(isRunning = false, speed = 0f, pendingManualChange = null) }
        if (remoteHostLimits != null) publishRemoteSessionView { it.copy(remoteSpeed = 0f) }
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
        val max = value.coerceIn(0f, 1f)
        // During a remote session this ceiling only sets the envelope maximum: no producer
        // restart and no motor command (a zero maximum stops, a lower one lowers the speed
        // through the envelope enforcement, which must still see the old speed).
        if (remoteHostLimits != null) {
            _uiState.update { it.copy(progressiveMaxSpeed = max) }
            consumeLocalSpeedAsMaximum(max)
            return
        }
        _uiState.update {
            it.copy(progressiveMaxSpeed = max, speed = it.speed.coerceAtMost(max))
        }
        if (max <= 0f) {
            progressiveJob?.cancel(); progressiveJob = null
            chaosJob?.cancel(); chaosJob = null
            if (strokeEngineActivationSpeed.retainIfActivating(0)) return
            bleManager.sendCommand(OssmCommand.Stop)
            return
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
        val initialCap = (hostSpeed(_uiState.value.progressiveMaxSpeed) * 100f).toInt().coerceIn(0, 100)
        if (initialCap <= 0) {
            _uiState.update { it.copy(speed = 0f, isRunning = false) }
            bleManager.sendCommand(OssmCommand.Stop)
            return
        }
        _uiState.update {
            it.copy(speed = speedPercent.coerceAtMost(initialCap) / 100f, isRunning = true)
        }
        sendCurrentStrokeEngineCommand()
        progressiveJob = storeScope.launch {
            while (isActive) {
                val cap = (hostSpeed(_uiState.value.progressiveMaxSpeed) * 100f).toInt().coerceIn(0, 100)
                if (cap <= 0) {
                    _uiState.update { it.copy(speed = 0f, isRunning = false) }
                    bleManager.sendCommand(OssmCommand.Stop)
                    break
                }
                if (speedPercent >= cap) break
                delay(progressiveStrokeMs(speedPercent))
                if (!isActive) break
                speedPercent = (speedPercent + progressiveIncrement()).coerceAtMost(cap)
                _uiState.update { it.copy(speed = hostSpeed(speedPercent / 100f)) }
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
            _uiState.update { it.copy(speed = hostSpeed(it.progressiveMaxSpeed)) }
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
                val max = hostSpeed(_uiState.value.progressiveMaxSpeed)
                if (max <= 0f) {
                    _uiState.update { it.copy(speed = 0f, isRunning = false) }
                    bleManager.sendCommand(OssmCommand.Stop)
                    break
                }
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
    private var randWalkSensation = 0.5f
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
                RandomTarget.SENSATION -> st.copy(randSensation = enabled)
            }
            updated.copy(
                randomSpeedCurrent = updated.randomSpeedCurrent?.takeIf { updated.randSpeed },
                randomDepthMinCurrent = updated.randomDepthMinCurrent?.takeIf { updated.randDepthMin },
                randomDepthMaxCurrent = updated.randomDepthMaxCurrent?.takeIf { updated.randDepthMax },
                randomSensationCurrent = updated.randomSensationCurrent?.takeIf { updated.randSensation }
            )
        }
        if (target == RandomTarget.SENSATION && enabled) {
            // Each start of the random sensation begins gently, between -10 and +10.
            _uiState.update {
                it.copy(
                    randomSensationMin = RANDOM_SENSATION_START_MIN,
                    randomSensationMax = RANDOM_SENSATION_START_MAX
                )
            }
            sensationPhaseAdvance = true
            randWalkSensation = pickRandomSensation(advance = true, s = _uiState.value)
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
        restartTeasingRandomTicker()
    }

    fun saveRandomPreset(target: RandomPresetTarget, index: Int) {
        _uiState.update { state ->
            val safeIndex = index.coerceIn(0, 3)
            val currentSpeedPercent = (state.speed * 100f).roundToInt().coerceIn(0, 100)
            when (target) {
                RandomPresetTarget.SPEED -> state.copy(
                    randomSpeedPresets = state.randomSpeedPresets.replacePreset(safeIndex) { preset ->
                        preset.copy(savedSpeedPercent = currentSpeedPercent)
                    },
                    selectedRandomSpeedPreset = safeIndex
                )
                RandomPresetTarget.DEPTH -> state.copy(
                    randomDepthPresets = state.randomDepthPresets.replacePreset(safeIndex) { preset ->
                        preset.copy(
                            minPercent = (state.depthMin * 100f).roundToInt().coerceIn(0, 100),
                            maxPercent = (state.depthMax * 100f).roundToInt().coerceIn(0, 100),
                            savedSpeedPercent = preset.savedSpeedPercent?.let { currentSpeedPercent }
                        )
                    },
                    selectedRandomDepthPreset = safeIndex
                )
            }
        }
        restartTeasingRandomTicker()
    }

    fun applyRandomPreset(target: RandomPresetTarget, index: Int) {
        val state = _uiState.value
        val safeIndex = index.coerceIn(0, 3)
        val preset = when (target) {
            RandomPresetTarget.SPEED -> state.randomSpeedPresets.getOrNull(safeIndex)
            RandomPresetTarget.DEPTH -> state.randomDepthPresets.getOrNull(safeIndex)
        } ?: return
        when (target) {
            RandomPresetTarget.SPEED -> {
                val speed = preset.savedSpeedPercent?.div(100f)
                    ?: ((preset.minFraction + preset.maxFraction) / 2f)
                maybeApplyManualChange(
                    control = GuardedControl.SPEED,
                    speed = speed,
                    depthMin = state.depthMin,
                    depthMax = state.depthMax
                )
            }
            RandomPresetTarget.DEPTH -> {
                val speed = preset.savedSpeedPercent?.div(100f) ?: state.speed
                val speedDelta = speed - state.speed
                val depthDelta = maxOf(
                    preset.maxFraction - state.depthMax,
                    state.depthMin - preset.minFraction
                )
                val control = if (
                    state.speedGuard.enabled &&
                    GuardedControl.SPEED !in sessionBypass &&
                    speedDelta > state.speedGuard.thresholdPercent / 100f
                ) {
                    GuardedControl.SPEED
                } else if (
                    state.depthGuard.enabled &&
                    GuardedControl.DEPTH !in sessionBypass &&
                    depthDelta > state.depthGuard.thresholdPercent / 100f
                ) {
                    GuardedControl.DEPTH
                } else {
                    GuardedControl.DEPTH
                }
                maybeApplyManualChange(
                    control = control,
                    speed = speed,
                    depthMin = preset.minFraction,
                    depthMax = preset.maxFraction
                )
            }
        }
        if (_uiState.value.pendingManualChange == null) {
            _uiState.update {
                when (target) {
                    RandomPresetTarget.SPEED -> it.copy(selectedRandomSpeedPreset = safeIndex)
                    RandomPresetTarget.DEPTH -> it.copy(selectedRandomDepthPreset = safeIndex)
                }
            }
        }
        restartTeasingRandomTicker()
    }

    fun editRandomPreset(
        target: RandomPresetTarget,
        index: Int,
        minPercent: Int,
        maxPercent: Int,
        saveSpeedToo: Boolean
    ) {
        val safeIndex = index.coerceIn(0, 3)
        val min = minPercent.coerceIn(0, 100)
        val max = maxPercent.coerceIn(min, 100)
        _uiState.update { state ->
            val speed = (state.speed * 100f).roundToInt().coerceIn(0, 100)
            val update = { preset: RandomRangePreset ->
                preset.copy(
                    minPercent = min,
                    maxPercent = max,
                    savedSpeedPercent = speed.takeIf { saveSpeedToo }
                )
            }
            when (target) {
                RandomPresetTarget.SPEED -> state.copy(
                    randomSpeedPresets = state.randomSpeedPresets.replacePreset(safeIndex, update),
                    selectedRandomSpeedPreset = safeIndex
                )
                RandomPresetTarget.DEPTH -> state.copy(
                    randomDepthPresets = state.randomDepthPresets.replacePreset(safeIndex, update),
                    selectedRandomDepthPreset = safeIndex
                )
            }
        }
        restartTeasingRandomTicker()
    }

    private fun restartTeasingRandomTicker() {
        teasingRandomJob?.cancel()
        teasingRandomJob = null
        updateTeasingRandomTicker()
    }

    private fun List<RandomRangePreset>.replacePreset(
        index: Int,
        transform: (RandomRangePreset) -> RandomRangePreset
    ): List<RandomRangePreset> = toMutableList().also { presets ->
        presets[index] = transform(presets[index])
    }

    /** Inverse le sens du mode Live (persisté). Effet immédiat sur les prochaines commandes stream. */
    fun setLiveInvert(enabled: Boolean) {
        storeScope.launch { safetySettingsRepository.setLiveInvertEnabled(enabled) }
        bleManager.liveInvert = enabled
        _uiState.update { it.copy(liveInvert = enabled) }
    }

    fun setPrecisionEnabled(patternKey: String, enabled: Boolean) {
        // Publish only persisted snapshots, including explicit false values.
        storeScope.launch { safetySettingsRepository.setPrecisionEnabled(patternKey, enabled) }
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
        sensationTurnJob?.cancel(); sensationTurnJob = null
            sensationTurnJob?.cancel(); sensationTurnJob = null
            lastRandomStrokeCommand = null
            _uiState.update {
                it.copy(
                    randomSpeedCurrent = null,
                    randomDepthMinCurrent = null,
                    randomDepthMaxCurrent = null,
                    randomSensationCurrent = null
                )
            }
            // Retour propre aux valeurs des sliders quand on désactive tout.
            if (st.activePattern?.mode == PatternControlMode.STROKE_ENGINE) {
                sendCurrentStrokeEngineCommand()
            }
            return
        }
        if (teasingRandomJob?.isActive == true) {
            updateSensationTurnJob()
            return
        }
        // Point de départ de la marche = valeurs actuelles des sliders.
        randWalkSpeed = st.speed
        randWalkMin = st.depthMin
        randWalkMax = st.depthMax
        randWalkSensation = if (st.randSensation) pickRandomSensation(advance = true, s = st) else st.sensation
        // Reset des blocs de vitesse (nouveau cliquet).
        speedBlockEndMs = 0L
        currentBlockSpeed = st.speed
        maxBlockSpeed = 0f
        teasingRandomJob = storeScope.launch {
            while (isActive) {
                val s = _uiState.value
                if (s.activePatternKey !in RANDOM_CAPABLE_KEYS || !s.anyRandomActive) break

                val hostRange = if (remoteHostLimits != null) effectiveRemoteMotionLimits() else null
                if (remoteHostLimits != null && hostRange == null) {
                    rejectRemoteMotionIfUnavailable()
                    break
                }
                val speedMaximum = hostSpeed(st.autoMaxSpeed)
                if (speedMaximum <= 0f) {
                    _uiState.update {
                        it.copy(autoRunning = false, autoIntensity = 0f, speed = 0f, isRunning = false)
                    }
                    bleManager.sendCommand(OssmCommand.Stop)
                    break
                }
                val baseMin = hostRange?.depthMinimum ?: s.depthMin
                val baseMax = hostRange?.depthMaximum ?: s.depthMax
                // Range presets are no longer exposed on the control screen: random mode
                // follows the sliders only, so a hidden saved preset never narrows it.
                val speedPreset: RandomRangePreset? = null
                val depthPreset: RandomRangePreset? = null
                val depthPresetMin = depthPreset?.minFraction ?: baseMin
                val depthPresetMax = depthPreset?.maxFraction ?: baseMax
                val depthIntersectionMin = maxOf(baseMin, depthPresetMin)
                val depthIntersectionMax = minOf(baseMax, depthPresetMax)
                val randomDepthRangeValid = depthIntersectionMax - depthIntersectionMin >= 0.05f
                val randomDepthMin = if (randomDepthRangeValid) depthIntersectionMin else baseMin
                val randomDepthMax = if (randomDepthRangeValid) depthIntersectionMax else baseMax
                val minSpan = 0.05f.coerceAtMost((baseMax - baseMin).coerceAtLeast(0f))

                // Vitesse : par BLOCS (paliers). On tient une vitesse pendant quelques
                // secondes, puis on en tire une nouvelle. Jamais 0. Le plancher monte
                // avec le max déjà atteint (cliquet).
                val speed = if (s.randSpeed) {
                    val presetFloor = speedPreset?.minFraction ?: 0f
                    val presetCeiling = speedPreset?.maxFraction ?: s.speed
                    val ceiling = minOf(hostSpeed(s.speed), presetCeiling).coerceAtLeast(0f)
                    val nowMs = System.currentTimeMillis()
                    if (nowMs >= speedBlockEndMs && ceiling > 0.05f) {
                        val absMin = maxOf(presetFloor.coerceAtMost(ceiling), ceiling * 0.15f) // jamais 0 / ne s'arrête pas
                        val ratchetFloor = maxBlockSpeed * SPEED_RATCHET // cliquet : monté haut → reste haut
                        val floor = maxOf(absMin, ratchetFloor).coerceIn(0f, ceiling)
                        currentBlockSpeed = (floor + Random.nextFloat() * (ceiling - floor)).coerceIn(
                            presetFloor.coerceAtMost(ceiling).coerceAtLeast(0.05f),
                            ceiling
                        )
                        if (currentBlockSpeed > maxBlockSpeed) maxBlockSpeed = currentBlockSpeed
                        speedBlockEndMs = nowMs + Random.nextLong(SPEED_BLOCK_MIN_MS, SPEED_BLOCK_MAX_MS)
                    }
                    currentBlockSpeed.coerceAtMost(ceiling)
                } else hostSpeed(s.speed)

                // Retrait : marche douce dans [plancher, max−5 %] (jamais plus reculé que min).
                val rMin = if (s.randDepthMin) {
                    val hi = (randomDepthMax - minSpan).coerceAtLeast(randomDepthMin)
                    randWalkMin = randomWalkStep(randWalkMin.coerceIn(randomDepthMin, hi), randomDepthMin, hi, (hi - randomDepthMin) * 0.3f)
                    randWalkMin
                } else baseMin

                // Fond : marche douce dans [plancher, max réglé] (jamais plus profond que max).
                val rMax = if (s.randDepthMax) {
                    val lo = (rMin + minSpan).coerceAtMost(randomDepthMax)
                    randWalkMax = randomWalkStep(randWalkMax.coerceIn(lo, randomDepthMax), lo, randomDepthMax, (randomDepthMax - lo) * 0.3f)
                    randWalkMax
                } else baseMax.coerceAtLeast((rMin + minSpan).coerceAtMost(baseMax))

                // Sensation : la valeur est choisie aux retournements de l'actuateur par
                // [sensationTurnJob] ; ce pas ne fait que la renvoyer avec vitesse/profondeur.
                val sensation = if (s.randSensation) randWalkSensation else s.sensation
                lastRandomStrokeCommand = StrokeEngineCommand(
                    speed = speed,
                    depthMin = rMin,
                    depthMax = rMax,
                    sensation = sensation
                )

                _uiState.update {
                    it.copy(
                        randomSpeedCurrent = speed.takeIf { s.randSpeed },
                        randomDepthMinCurrent = rMin.takeIf { s.randDepthMin },
                        randomDepthMaxCurrent = rMax.takeIf { s.randDepthMax },
                        randomSensationCurrent = sensation.takeIf { s.randSensation }
                    )
                }

                sendStrokeEngineCommand(
                    StrokeEngineCommand(
                        speed = speed,
                        depthMin = rMin,
                        depthMax = rMax,
                        sensation = sensation
                    )
                )
                delay(Random.nextLong(TEASE_RANDOM_MIN_MS, TEASE_RANDOM_MAX_MS))
            }
        }
        updateSensationTurnJob()
    }

    fun setRandomSensationRange(min: Float, max: Float) {
        val lo = minOf(min, max).coerceIn(0f, 1f)
        val hi = maxOf(min, max).coerceIn(0f, 1f)
        _uiState.update { it.copy(randomSensationMin = lo, randomSensationMax = hi) }
        // Keep the value in use inside the new bounds right away.
        if (_uiState.value.randSensation && randWalkSensation !in lo..hi) {
            applyTurnSensation(pickRandomSensation(sensationPhaseAdvance, _uiState.value))
        }
    }

    /**
     * Random sensation follows the actuator: a new value is drawn at each turnaround, on the
     * advance side (fast advance) for an advance and on the retract side (fast retract) for a
     * retract, so the two alternate. The faster the stroke, the more often it changes.
     */
    private var sensationTurnJob: Job? = null
    @Volatile
    private var sensationPhaseAdvance = true
    private var lastRandomStrokeCommand: StrokeEngineCommand? = null

    private fun updateSensationTurnJob() {
        val st = _uiState.value
        val wanted = st.randSensation && teasingRandomJob?.isActive == true
        if (!wanted) {
            sensationTurnJob?.cancel(); sensationTurnJob = null
            return
        }
        if (sensationTurnJob?.isActive == true) return
        sensationPhaseAdvance = true
        // Position polling runs off the main thread (it never touches the UI); only the
        // rare turnaround hops back to the main thread to update the state and send.
        sensationTurnJob = storeScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var extreme: Float? = null
            var movingAdvance: Boolean? = null
            var lastTurnMs = android.os.SystemClock.elapsedRealtime()
            while (isActive) {
                val s = _uiState.value
                if (!s.randSensation || teasingRandomJob?.isActive != true) break
                val now = android.os.SystemClock.elapsedRealtime()
                var turnTo: Boolean? = null
                val position = bleManager.machineState.value.positionMm?.takeIf { it.isFinite() }
                if (position != null) {
                    // Position grows toward the depth (advance). A turnaround is a move of at
                    // least the hysteresis back from the last extreme.
                    val ext = extreme
                    if (ext == null) {
                        extreme = position
                    } else if (movingAdvance == null) {
                        if (kotlin.math.abs(position - ext) >= SENSATION_TURN_HYSTERESIS_MM) {
                            val advancing = position > ext
                            movingAdvance = advancing
                            extreme = position
                            if (advancing != sensationPhaseAdvance) turnTo = advancing
                        }
                    } else if (movingAdvance == true) {
                        if (position > ext) {
                            extreme = position
                        } else if (ext - position >= SENSATION_TURN_HYSTERESIS_MM) {
                            movingAdvance = false
                            extreme = position
                            turnTo = false
                        }
                    } else {
                        if (position < ext) {
                            extreme = position
                        } else if (position - ext >= SENSATION_TURN_HYSTERESIS_MM) {
                            movingAdvance = true
                            extreme = position
                            turnTo = true
                        }
                    }
                }
                // Without fresh position samples, fall back on the stroke time estimated from
                // the speed actually sent (faster stroke -> sooner turnaround).
                if (turnTo == null && now - lastTurnMs >= estimatedHalfStrokeMs(s) * SENSATION_TURN_TIMEOUT_FACTOR) {
                    turnTo = !sensationPhaseAdvance
                    movingAdvance = turnTo
                    extreme = position
                }
                val turn = turnTo
                if (turn != null) {
                    lastTurnMs = now
                    sensationPhaseAdvance = turn
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        val current = _uiState.value
                        // Re-checked on the main thread: STOP/disable may have happened meanwhile.
                        if (current.randSensation && teasingRandomJob?.isActive == true) {
                            applyTurnSensation(pickRandomSensation(turn, current))
                        }
                    }
                }
                delay(SENSATION_TURN_POLL_MS)
            }
        }
    }

    private fun applyTurnSensation(value: Float) {
        randWalkSensation = value
        _uiState.update { it.copy(randomSensationCurrent = value.takeIf { _ -> it.randSensation }) }
        lastRandomStrokeCommand?.let { last ->
            val command = last.copy(sensation = value)
            lastRandomStrokeCommand = command
            sendStrokeEngineCommand(command)
        }
    }

    private fun estimatedHalfStrokeMs(s: ControlUiState): Long {
        val speedPercent = (((s.randomSpeedCurrent ?: s.speed) * 100f).toInt()).coerceIn(1, 100)
        return progressiveStrokeMs(speedPercent) / 2
    }

    /** Advance side = above neutral (fast advance), retract side = below (fast retract). */
    private fun pickRandomSensation(advance: Boolean, s: ControlUiState): Float {
        val lo = minOf(s.randomSensationMin, s.randomSensationMax).coerceIn(0f, 1f)
        val hi = maxOf(s.randomSensationMin, s.randomSensationMax).coerceIn(0f, 1f)
        if (hi - lo < 0.001f) return lo
        val (a, b) = if (lo < 0.5f && hi > 0.5f) {
            if (advance) 0.5f to hi else lo to 0.5f
        } else {
            lo to hi
        }
        return a + Random.nextFloat() * (b - a)
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
        val maximum = value.coerceIn(0f, 1f)
        _uiState.update { it.copy(autoMaxSpeed = maximum) }
        // During a remote session this only sets the envelope maximum (zero stops).
        if (consumeLocalSpeedAsMaximum(maximum)) return
        if (maximum <= 0f) {
            autoJob?.cancel(); autoJob = null
            autoFirmwareMode = null
            _uiState.update { it.copy(autoRunning = false, autoIntensity = 0f, speed = 0f, isRunning = false) }
            bleManager.sendCommand(OssmCommand.Stop)
        }
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
        // Voix has no paused state: pause = STOP of the spoken sequence.
        if (st.activePattern.isVoicePattern() && (st.voiceRunning || voiceRunner.isActive)) {
            stop()
            return
        }
        if (!st.isRunning || st.isPaused) return
        val mode = st.activePattern?.mode ?: return
        progressiveJob?.cancel(); progressiveJob = null
        chaosJob?.cancel(); chaosJob = null
        teasingRandomJob?.cancel(); teasingRandomJob = null
        sensationTurnJob?.cancel(); sensationTurnJob = null
        autoJob?.cancel(); autoJob = null
        cancelNaturalProducer()
        _uiState.update { it.copy(isPaused = true, isRunning = false, naturalRunning = false) }
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
        when (mode) {
            PatternControlMode.STREAMING -> setStreamPosition(st.streamPositionPercent)
            PatternControlMode.PROGRESSIVE -> startProgressiveRamp((st.speed * 100f).toInt().coerceAtLeast(1))
            PatternControlMode.AUTO_RANDOM -> startAutoMix(st.autoIntensity)
            PatternControlMode.NATURAL -> {
                // Explicit resume: restart progressively from speed 0.
                _uiState.update { it.copy(isRunning = false) }
                requestNaturalStart(remote = remoteHostLimits != null)
            }
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
                val hostRange = if (remoteHostLimits != null) effectiveRemoteMotionLimits() else null
                if (remoteHostLimits != null && hostRange == null) {
                    rejectRemoteMotionIfUnavailable()
                    break
                }
                // AutoRandom's current ceiling is the local AutoRandom maximum
                // passed through the active phone host envelope. Without a remote
                // envelope hostSpeed preserves ordinary local 0..1 behavior; an
                // explicit zero remains zero and must not start motion.
                val speedMaximum = hostSpeed(st.autoMaxSpeed)
                if (speedMaximum <= 0f) {
                    _uiState.update {
                        it.copy(autoRunning = false, autoIntensity = 0f, speed = 0f, isRunning = false)
                    }
                    bleManager.sendCommand(OssmCommand.Stop)
                    break
                }

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
                val floor = interpolate(0.12f, 0.42f, intensity).coerceAtMost(speedMaximum)
                val speedWander = interpolate(0.15f, 1f, randomness)
                val speed = (
                    floor + (speedMaximum - floor) * Random.nextFloat() * speedWander
                ).coerceIn(0.05f.coerceAtMost(speedMaximum), speedMaximum)

                // Depth: stay inside the user's hard range. Intensity increases average stroke
                // span; randomness changes how much the span and sub-range wander around.
                val lo = hostRange?.depthMinimum ?: st.depthMin
                val hi = hostRange?.depthMaximum ?: st.depthMax
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
                bleManager.liveSet("speed", (hostSpeed(speed) * 100f).roundToInt())
                bleManager.liveSet("depth", (dMax * 100f).toInt())
                bleManager.liveSet("stroke", (stroke * 100f).toInt())
                _uiState.update { it.copy(speed = hostSpeed(speed), depthMin = dMin, depthMax = dMax) }

                // Change frequency: TREND (not wave) drives pace so timing accelerates smoothly.
                val holdCenter = interpolate(AUTO_HOLD_MAX_MS.toFloat(), AUTO_HOLD_MIN_MS.toFloat(), trend)
                val holdJitter = interpolate(250f, 1800f, randomness).toLong()
                val minHold = (holdCenter.toLong() - holdJitter).coerceAtLeast(AUTO_HOLD_MIN_MS)
                val maxHold = (holdCenter.toLong() + holdJitter).coerceAtMost(AUTO_HOLD_MAX_MS)
                delay(Random.nextLong(minHold, maxHold.coerceAtLeast(minHold + 1)))
            }
        }
    }

    // ---- Naturel (2.3.18): coherent phone-generated gestures, local first ----
    // Runs on the verified Teasing Pounding stroke-engine entry and only modulates
    // speed/depth/stroke/sensation through the existing coalesced live writers. Every tick
    // re-reads the user maxima, local depth limits and remote envelope. STOP, emergency
    // stop, BLE loss, homing, machine error, mode exit, pause and remote session end cancel
    // the producer synchronously before any other command. The optional AI planner only
    // replaces FUTURE gestures; motion never waits for it.
    private var naturalJob: Job? = null
    private var naturalPlannerJob: Job? = null
    private var naturalEngine: NaturalModeEngine? = null
    private var naturalRunEpoch = 0L
    /** Machine parameters known after verified entry / last Naturel write (speed excluded). */
    private var naturalMachineFrame: NaturalFrame? = null
    private var naturalLastSent: NaturalSentValues? = null
    /** Browser-requested Naturel ceiling (already clamped by the envelope). */
    @Volatile private var naturalRemoteSpeed = 0f
    private var naturalElapsedMs = 0L
    private var naturalAiNextAttemptMs = 0L
    private var naturalAiPlanReceived = false
    private var naturalPlannerCache: Pair<NaturalAiEndpoint, NaturalPlanner>? = null
    private var naturalAiPacing = NaturalAiPacing.forEndpoint(reasoningRelay = false)
    private var naturalAiRequestStartedMs = 0L
    // 2.3.23: AI-fed run (no local filler) and its explicit, visible local fallback.
    private var naturalAiFeeding = false
    private var naturalAiFallbackState: NaturalAiFallback? = null
    private var naturalAiLastRequestInfo: NaturalAiPacingInfo? = null
    private var naturalPendingInitial: NaturalFrame? = null

    /** Test seams: deterministic seed and fake planner. Production uses Ollama via OkHttp. */
    internal var naturalSeedProvider: () -> Long = { System.nanoTime() }
    internal var naturalPlannerFactory: (NaturalAiEndpoint) -> NaturalPlanner = NaturalAiPlannerFactory::create

    private data class NaturalSentValues(
        val speedPercent: Int,
        val depthPercent: Int,
        val strokePercent: Int,
        val sensationPercent: Int,
    )

    /** Explicit local start (button). Ignored during a remote session: the browser drives. */
    fun startNatural() {
        if (remoteHostLimits != null) return
        requestNaturalStart(remote = false)
    }

    /** Explicit local stop of the Naturel producer: immediate STOP. */
    fun stopNatural() {
        haltNatural(sendStop = false)
        bleManager.sendCommand(OssmCommand.Stop)
        if (remoteHostLimits != null) publishRemoteSessionView { it.copy(remoteSpeed = 0f) }
    }

    fun setNaturalEnergy(value: Float) {
        if (value.isNaN()) return
        val previous = _uiState.value.naturalEnergy
        _uiState.update { it.copy(naturalEnergy = value.coerceIn(0f, 1f)) }
        refreshNaturalFutureIfChanged(previous, _uiState.value.naturalEnergy)
    }

    fun setNaturalVariety(value: Float) {
        if (value.isNaN()) return
        val previous = _uiState.value.naturalVariety
        _uiState.update { it.copy(naturalVariety = value.coerceIn(0f, 1f)) }
        refreshNaturalFutureIfChanged(previous, _uiState.value.naturalVariety)
    }

    fun setNaturalMaxSpeed(value: Float) {
        if (value.isNaN()) return
        val maximum = value.coerceIn(0f, 1f)
        _uiState.update { it.copy(naturalMaxSpeed = maximum) }
        // During a remote session this only sets the envelope maximum (zero stops).
        if (consumeLocalSpeedAsMaximum(maximum)) return
        val state = _uiState.value
        if (maximum <= 0f) {
            if (state.naturalRunning || state.naturalStartPending) haltNatural(sendStop = true)
            return
        }
        enforceNaturalNow()
    }

    /**
     * "Utiliser l'IA" (true) / "Ne plus utiliser l'IA" (false). Plans are only requested once
     * the model is confirmed loaded; turning the AI off also cancels the model pre-load.
     */
    fun setNaturalAiEnabled(enabled: Boolean) {
        storeScope.launch { safetySettingsRepository.setNaturalAiEnabled(enabled) }
        naturalPlannerJob?.cancel(); naturalPlannerJob = null
        naturalAiNextAttemptMs = 0L
        naturalAiPlanReceived = false
        if (!enabled) resetNaturalAiModel()
        _uiState.update { it.copy(naturalAiEnabled = enabled).withNaturalAiStatus() }
        logNaturalAi(LogLevel.INFO, if (enabled) "IA choisie pour Naturel" else "IA desactivee - generateur local")
    }

    fun setNaturalAiAddress(address: String) {
        val cleaned = address.trim().take(NATURAL_AI_ADDRESS_MAX_LENGTH)
        storeScope.launch { safetySettingsRepository.setNaturalAiAddress(cleaned) }
        naturalPlannerJob?.cancel(); naturalPlannerJob = null
        naturalPlannerCache = null
        naturalAiLoaderCache = null
        naturalAiNextAttemptMs = 0L
        naturalAiPlanReceived = false
        naturalAiPacing = NaturalAiPacing.forEndpoint(_uiState.value.naturalAiReasoningRelay)
        resetNaturalAiModel()
        _uiState.update { it.copy(naturalAiAddress = cleaned).withNaturalAiStatus() }
    }

    /**
     * "Passer par le relais de raisonnement (PC)": same address, port 11435, think:true and
     * longer plans. Motion never depends on it; the local generator keeps running.
     */
    fun setNaturalAiReasoningRelay(enabled: Boolean) {
        storeScope.launch { safetySettingsRepository.setNaturalAiReasoningRelay(enabled) }
        applyNaturalAiReasoningRelay(enabled)
    }

    private fun applyNaturalAiReasoningRelay(enabled: Boolean) {
        naturalPlannerJob?.cancel(); naturalPlannerJob = null
        naturalPlannerCache = null
        naturalAiNextAttemptMs = 0L
        naturalAiPlanReceived = false // next plan from the new path replaces the future
        naturalAiPacing = NaturalAiPacing.forEndpoint(enabled)
        _uiState.update { it.copy(naturalAiReasoningRelay = enabled, naturalAiLastLatencySeconds = null) }
        logNaturalAi(
            LogLevel.INFO,
            if (enabled) "Relais de raisonnement PC active (port 11435, think)" else "Relais de raisonnement PC desactive",
        )
    }

    /** Endpoint actually used: the typed address, or the relay port on the same host. */
    private fun naturalAiEndpoint(state: ControlUiState = _uiState.value): NaturalAiEndpoint? =
        NaturalAiEndpoint.parse(state.naturalAiAddress)?.withReasoningRelay(state.naturalAiReasoningRelay)

    /** "Charger Qwen3" / "Reessayer": background model pre-load, one at a time. */
    fun loadNaturalAiModel() = startNaturalAiPreheat()

    // ---- Naturel AI model pre-load (2.3.20) ----
    // Decoupled from motion: it never commands the machine, so STOP, pause, mode exit and
    // BLE loss leave it running. Only AI deactivation or an address change cancel it. A
    // single load request (`POST /api/generate`, empty prompt) is ever in flight; progress
    // is followed by read-only `GET /api/ps` every 15 s and a local per-second counter.
    private var naturalAiPreheatJob: Job? = null
    private var naturalAiLoadCall: Deferred<Result<Unit>>? = null
    private var naturalAiLoadCallEndpoint: NaturalAiEndpoint? = null
    private var naturalAiMonitorJob: Job? = null
    private var naturalAiLoaderCache: Pair<NaturalAiEndpoint, OllamaModelLoader>? = null

    /** Test seam: fake transport. Production uses Ollama via OkHttp. */
    internal var naturalAiLoaderFactory: (NaturalAiEndpoint) -> OllamaModelLoader =
        NaturalAiPlannerFactory::createLoader

    private fun ControlUiState.withNaturalAiStatus(): ControlUiState = copy(
        naturalPlannerStatus = when {
            !naturalAiEnabled -> NaturalPlannerStatus.LOCAL
            naturalAiModelState == NaturalAiModelState.FAILED -> NaturalPlannerStatus.AI_UNAVAILABLE
            naturalAiModelState == NaturalAiModelState.LOADED -> NaturalPlannerStatus.AI_READY
            else -> NaturalPlannerStatus.AI_WAITING_MODEL
        }
    )

    private fun logNaturalAi(level: LogLevel, message: String) {
        runCatching { bleManager.appendExternalDiagnostic(level, NATURAL_AI_LOG_TAG, message) }
    }

    private fun naturalAiLoaderFor(endpoint: NaturalAiEndpoint): OllamaModelLoader =
        naturalAiLoaderCache?.takeIf { it.first == endpoint }?.second
            ?: naturalAiLoaderFactory(endpoint).also { naturalAiLoaderCache = endpoint to it }

    private fun cancelNaturalAiPreheat() {
        naturalAiPreheatJob?.cancel(); naturalAiPreheatJob = null
        naturalAiLoadCall?.cancel(); naturalAiLoadCall = null
        naturalAiLoadCallEndpoint = null
        naturalAiMonitorJob?.cancel(); naturalAiMonitorJob = null
    }

    private fun resetNaturalAiModel() {
        cancelNaturalAiPreheat()
        _uiState.update {
            it.copy(
                naturalAiModelState = NaturalAiModelState.IDLE,
                naturalAiLoadSeconds = 0,
                naturalAiModelInstalled = null,
                naturalAiModelUnloaded = false,
                naturalAiProblem = null,
            ).withNaturalAiStatus()
        }
    }

    /** Short read-only request; a stalled read counts as "PC injoignable". */
    private suspend fun <T> naturalAiRead(block: suspend () -> T): T = try {
        withTimeout(NATURAL_AI_READ_TIMEOUT_MS) { block() }
    } catch (_: TimeoutCancellationException) {
        throw java.io.IOException("Ollama read stalled")
    }

    /** `/api/ps` check that never throws (except cancellation). */
    private suspend fun naturalAiModelListedInPs(loader: OllamaModelLoader): Boolean = try {
        naturalAiRead { loader.isModelLoaded() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun startNaturalAiPreheat() {
        if (naturalAiPreheatJob?.isActive == true) return
        naturalAiMonitorJob?.cancel(); naturalAiMonitorJob = null
        val endpoint = naturalAiEndpoint()
        if (endpoint == null) {
            failNaturalAi(NaturalAiProblem(NaturalAiFailureKind.INVALID_ADDRESS), NATURAL_AI_MODEL_NAME)
            return
        }
        val loader = naturalAiLoaderFor(endpoint)
        _uiState.update {
            it.copy(
                naturalAiModelState = NaturalAiModelState.LOADING,
                naturalAiLoadSeconds = 0,
                naturalAiModelInstalled = null,
                naturalAiModelUnloaded = false,
                naturalAiProblem = null,
            ).withNaturalAiStatus()
        }
        logNaturalAi(LogLevel.INFO, "Chargement du modele ${loader.model} demande")
        naturalAiPreheatJob = storeScope.launch {
            var seconds = 0
            // Local counter only: no network request per second.
            val ticker = launch {
                while (isActive) {
                    delay(1_000L)
                    seconds++
                    _uiState.update { it.copy(naturalAiLoadSeconds = seconds) }
                }
            }
            val failure: Throwable? = try {
                val installed = naturalAiRead { loader.isModelInstalled() }
                if (!installed) throw OllamaModelMissingException()
                _uiState.update { it.copy(naturalAiModelInstalled = true) }
                logNaturalAi(LogLevel.INFO, "Modele ${loader.model} present sur le PC")
                if (!naturalAiModelListedInPs(loader)) awaitNaturalAiLoad(endpoint, loader)
                null
            } catch (cancelled: CancellationException) {
                ticker.cancel()
                throw cancelled
            } catch (error: Exception) {
                error
            }
            ticker.cancel()
            if (failure == null) {
                onNaturalAiLoaded(loader, seconds)
            } else {
                if (failure is OllamaModelMissingException) {
                    _uiState.update { it.copy(naturalAiModelInstalled = false) }
                }
                failNaturalAi(classifyNaturalAiFailure(failure, seconds), loader.model)
            }
        }
    }

    /** Waits for the single in-flight load request, or for `/api/ps` to list the model. */
    private suspend fun awaitNaturalAiLoad(endpoint: NaturalAiEndpoint, loader: OllamaModelLoader) {
        val call = naturalAiLoadCall?.takeIf { it.isActive && naturalAiLoadCallEndpoint == endpoint }
            ?: storeScope.async {
                try {
                    withTimeout(NaturalAiPlannerFactory.COLD_LOAD_TIMEOUT_MS) { loader.load() }
                    Result.success(Unit)
                } catch (_: TimeoutCancellationException) {
                    Result.failure(NaturalAiLoadTimeoutException())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }.also {
                naturalAiLoadCall = it
                naturalAiLoadCallEndpoint = endpoint
            }
        while (true) {
            val outcome = withTimeoutOrNull(NATURAL_AI_PS_INTERVAL_MS) { call.await() }
            if (outcome != null) {
                outcome.getOrThrow()
                return
            }
            if (naturalAiModelListedInPs(loader)) return
        }
    }

    private fun onNaturalAiLoaded(loader: OllamaModelLoader, seconds: Int) {
        naturalAiNextAttemptMs = 0L
        naturalAiPlanReceived = false
        _uiState.update {
            it.copy(
                naturalAiModelState = NaturalAiModelState.LOADED,
                naturalAiModelInstalled = true,
                naturalAiModelUnloaded = false,
                naturalAiProblem = null,
            ).withNaturalAiStatus()
        }
        logNaturalAi(LogLevel.INFO, "Modele ${loader.model} charge (${seconds} s)")
        startNaturalAiMonitor(loader)
    }

    /** While loaded: read-only `/api/ps` every 30 s; unloaded (keep_alive expired) -> IDLE. */
    private fun startNaturalAiMonitor(loader: OllamaModelLoader) {
        naturalAiMonitorJob?.cancel()
        naturalAiMonitorJob = storeScope.launch {
            var errors = 0
            while (isActive) {
                delay(NATURAL_AI_MONITOR_MS)
                if (naturalPlannerJob?.isActive == true) continue
                val loaded = try {
                    naturalAiRead { loader.isModelLoaded() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (++errors >= 2) {
                        failNaturalAi(classifyNaturalAiFailure(error, 0), loader.model)
                        return@launch
                    }
                    continue
                }
                errors = 0
                if (!loaded) {
                    _uiState.update {
                        it.copy(
                            naturalAiModelState = NaturalAiModelState.IDLE,
                            naturalAiLoadSeconds = 0,
                            naturalAiModelUnloaded = true,
                        ).withNaturalAiStatus()
                    }
                    logNaturalAi(LogLevel.WARNING, "Modele ${loader.model} decharge du PC (keep_alive expire)")
                    return@launch
                }
            }
        }
    }

    private fun failNaturalAi(problem: NaturalAiProblem, model: String) {
        _uiState.update {
            it.copy(
                naturalAiModelState = NaturalAiModelState.FAILED,
                naturalAiProblem = problem,
            ).withNaturalAiStatus()
        }
        logNaturalAi(LogLevel.WARNING, "Indisponible : ${problem.logText(model)}")
    }

    /** Browser `set_speed` in Naturel: > 0 starts (bounded by the envelope), 0 stops. */
    private fun requestRemoteNaturalSpeed(speed: Float) {
        naturalRemoteSpeed = speed.coerceIn(0f, 1f)
        markRemoteCommand { it.copy(remoteSpeed = naturalRemoteSpeed) }
        if (naturalRemoteSpeed <= 0f) {
            haltNatural(sendStop = true)
            return
        }
        val state = _uiState.value
        if (state.naturalRunning) enforceNaturalNow() else requestNaturalStart(remote = true)
    }

    private fun requestNaturalStart(remote: Boolean) {
        val state = _uiState.value
        val pattern = state.activePattern ?: return
        if (pattern.mode != PatternControlMode.NATURAL || state.naturalRunning) return
        if (state.rangeWizard != null) return
        if (bleManager.connectionState.value !is BleConnectionState.Connected) return
        if (remote != (remoteHostLimits != null)) return
        val limits = naturalLimits()
        if (limits == null || !limits.allowsMotion) {
            if (remote) rejectRemoteMotionIfUnavailable()
            return
        }
        if (!state.naturalReady || naturalMachineFrame == null) {
            // Explicit start before the verified entry finished: wait for it (speed 0 until then).
            _uiState.update { it.copy(naturalStartPending = true) }
            if (modeActivationJob?.isActive != true) requestModeActivation(pattern)
            return
        }
        beginNaturalRun(limits)
    }

    private fun onNaturalActivationFinished(
        patternKey: String,
        activationEpoch: Long,
        command: StrokeEngineCommand,
        succeeded: Boolean,
    ) {
        storeScope.launch {
            val state = _uiState.value
            if (connectionEpoch != activationEpoch || state.activePatternKey != patternKey ||
                state.activePattern?.mode != PatternControlMode.NATURAL
            ) return@launch
            if (!succeeded) {
                naturalMachineFrame = null
                _uiState.update { it.copy(naturalReady = false, naturalStartPending = false) }
                return@launch
            }
            naturalMachineFrame = NaturalFrame(0f, command.depthMin, command.depthMax, command.sensation)
            _uiState.update { it.copy(naturalReady = true) }
            if (_uiState.value.naturalStartPending) {
                _uiState.update { it.copy(naturalStartPending = false) }
                requestNaturalStart(remote = remoteHostLimits != null)
            }
        }
    }

    private fun beginNaturalRun(limits: NaturalLimits) {
        cancelNaturalProducer()
        val epoch = ++naturalRunEpoch
        val settings = naturalSettings()
        val engine = NaturalModeEngine(naturalSeedProvider())
        val initial = naturalMachineFrame ?: run {
            val st = _uiState.value
            NaturalFrame(0f, st.depthMin, st.depthMax, NaturalLimits.NEUTRAL_SENSATION)
        }
        // AI active ("Utiliser l'IA" + model loaded): every gesture comes from the AI, so the
        // run waits at speed 0 for the first validated plan. Otherwise: local as before.
        val aiActive = naturalAiActive(_uiState.value)
        naturalAiFeeding = aiActive
        naturalAiFallbackState = null
        naturalAiLastRequestInfo = null
        if (aiActive) {
            naturalPendingInitial = initial
        } else {
            naturalPendingInitial = null
            engine.start(initial, limits, settings)
        }
        naturalEngine = engine
        naturalLastSent = naturalSentValues(initial.copy(speed = 0f), limits)
        naturalElapsedMs = 0L
        naturalAiNextAttemptMs = 0L
        naturalAiPlanReceived = false
        _uiState.update {
            it.copy(
                naturalRunning = true,
                naturalStartPending = false,
                isRunning = true,
                isPaused = false,
                naturalAiWaitingFirstPlan = aiActive,
                naturalAiWaitSeconds = 0,
                naturalAiFallback = null,
            ).withNaturalAiStatus()
        }
        if (aiActive) logNaturalAi(LogLevel.INFO, "Naturel IA : attente du premier plan (vitesse 0, aucun geste local)")
        naturalJob = storeScope.launch {
            while (isActive) {
                if (!naturalTick(epoch, NATURAL_TICK_MS)) break
                delay(NATURAL_TICK_MS)
            }
        }
    }

    /** One producer step; false ends the loop. Never sends after a halt. */
    private fun naturalTick(epoch: Long, dtMs: Long): Boolean {
        if (epoch != naturalRunEpoch) return false
        val engine = naturalEngine ?: return false
        val state = _uiState.value
        if (!state.naturalRunning || state.activePattern?.mode != PatternControlMode.NATURAL) return false
        if (bleManager.connectionState.value !is BleConnectionState.Connected) {
            haltNatural(sendStop = false)
            return false
        }
        val machine = bleManager.machineState.value
        if (machine.isHoming || machine.isPreflight || machine.isError) {
            haltNatural(sendStop = true)
            return false
        }
        if (remoteHostLimits != null && effectiveRemoteMotionLimits() == null) {
            rejectRemoteMotionIfUnavailable()
            return false
        }
        val limits = naturalLimits()
        if (limits == null || !limits.allowsMotion) {
            haltNatural(sendStop = true)
            return false
        }
        naturalElapsedMs += dtMs
        if (!engine.started) {
            val waiting = _uiState.value
            if (naturalAiFallbackState == null && waiting.naturalAiEnabled &&
                waiting.naturalAiModelState == NaturalAiModelState.LOADED
            ) {
                // Speed 0, nothing sent, until the first validated AI plan starts the engine.
                if (dtMs > 0L) _uiState.update { it.copy(naturalAiWaitSeconds = (naturalElapsedMs / 1_000L).toInt()) }
                maybeRequestNaturalPlan(engine, epoch, limits)
                return true
            }
            // AI switched off by the user (plain local run) or failing (explicit fallback).
            if (waiting.naturalAiEnabled && naturalAiFallbackState == null) {
                enterNaturalAiFallback(naturalAiModelLostFallback(waiting))
            }
            if (!waiting.naturalAiEnabled) naturalAiFeeding = false
            engine.start(naturalStartFrame(), limits, naturalSettings())
            naturalPendingInitial = null
            _uiState.update { it.copy(naturalAiWaitingFirstPlan = false) }
        }
        val refillsBefore = engine.localRefillCount
        val frame = engine.tick(dtMs, limits, naturalSettings())
        if (naturalAiFeeding && naturalAiFallbackState == null) {
            val st = _uiState.value
            when {
                !st.naturalAiEnabled -> naturalAiFeeding = false // user choice, not a failure
                engine.localRefillCount != refillsBefore -> {
                    val inFlightMs = if (naturalPlannerJob?.isActive == true) {
                        naturalElapsedMs - naturalAiRequestStartedMs
                    } else {
                        0L
                    }
                    val latencyMs = maxOf(naturalAiPacing.latencyEstimateMs, inFlightMs)
                    enterNaturalAiFallback(
                        NaturalAiFallback(
                            NaturalAiFallbackKind.RESERVE_EXHAUSTED,
                            latencySeconds = ((latencyMs + 500L) / 1_000L).toInt(),
                            reserveSeconds = naturalAiLastRequestInfo?.reserveSeconds ?: 0,
                        )
                    )
                }
                st.naturalAiModelState != NaturalAiModelState.LOADED ->
                    enterNaturalAiFallback(naturalAiModelLostFallback(st))
            }
        }
        sendNaturalFrame(frame, limits)
        _uiState.update {
            it.copy(
                speed = frame.speed,
                naturalSpeedCurrent = frame.speed,
                naturalDepthMinCurrent = frame.depthMin,
                naturalDepthMaxCurrent = frame.depthMax,
                naturalMotif = engine.currentGesture?.motif?.wireName,
                naturalAiReserveSeconds = if (it.naturalAiEnabled) (engine.reserveMs / 1_000L).toInt() else null,
            )
        }
        maybeRequestNaturalPlan(engine, epoch, limits)
        return true
    }

    /** Apply a lowered limit now (dt 0: clamp only, no time advance). */
    private fun enforceNaturalNow() {
        if (!_uiState.value.naturalRunning) return
        naturalTick(naturalRunEpoch, 0L)
    }

    private fun sendNaturalFrame(frame: NaturalFrame, limits: NaturalLimits) {
        val next = naturalSentValues(frame, limits)
        val last = naturalLastSent
        if (last == null || last.depthPercent != next.depthPercent || last.strokePercent != next.strokePercent) {
            // Coalesced pair writer: depth (deep end) before stroke, newest pair only.
            bleManager.liveSetDepthRange(depthPercent = next.depthPercent, strokePercent = next.strokePercent)
        }
        if (last == null || last.sensationPercent != next.sensationPercent) {
            bleManager.liveSet("sensation", next.sensationPercent)
        }
        if (next.speedPercent > 0 && (last == null || last.speedPercent != next.speedPercent)) {
            bleManager.liveSet("speed", next.speedPercent)
        }
        naturalLastSent = next
        naturalMachineFrame = frame.copy(speed = 0f)
    }

    /** Integer percents that never round above the limits. */
    private fun naturalSentValues(frame: NaturalFrame, limits: NaturalLimits): NaturalSentValues {
        val speed = floorPercent(frame.speed).coerceIn(0, floorPercent(limits.speedMaximum))
        val depth = floorPercent(frame.depthMax).coerceIn(0, floorPercent(limits.depthMaximum))
        val stroke = floorPercent(frame.depthMax - frame.depthMin).coerceIn(0, depth)
        val sensationLow = ceil(limits.sensationMinimum * 100f - PERCENT_EPSILON).toInt().coerceIn(0, 100)
        val sensationHigh = floorPercent(limits.sensationMaximum).coerceIn(sensationLow, 100)
        val sensation = (frame.sensation * 100f).roundToInt().coerceIn(sensationLow, sensationHigh)
        return NaturalSentValues(speed, depth, stroke, sensation)
    }

    private fun floorPercent(value: Float): Int = (value * 100f + PERCENT_EPSILON).toInt().coerceIn(0, 100)

    private fun naturalSettings(): NaturalSettings {
        val state = _uiState.value
        return NaturalSettings(energy = state.naturalEnergy, variety = state.naturalVariety)
    }

    /** User maxima and local depth limits, intersected with the remote envelope in a session. */
    private fun naturalLimits(): NaturalLimits? {
        val state = _uiState.value
        if (remoteHostLimits != null) {
            val effective = effectiveRemoteMotionLimits() ?: return null
            val (low, high) = effective.clampDepthRange(state.depthMin, state.depthMax)
            return NaturalLimits.of(
                speedMaximum = minOf(effective.speedMaximum, naturalRemoteSpeed),
                depthMinimum = low,
                depthMaximum = high,
                sensationMinimum = effective.sensationMinimum,
                sensationMaximum = effective.sensationMaximum,
            )
        }
        return NaturalLimits.of(
            speedMaximum = state.naturalMaxSpeed,
            depthMinimum = maxOf(state.depthMin, state.depthLimitMin),
            depthMaximum = minOf(state.depthMax, state.depthLimitMax),
            sensationMinimum = NaturalLimits.NEUTRAL_SENSATION - NATURAL_LOCAL_SENSATION_INTENSITY,
            sensationMaximum = NaturalLimits.NEUTRAL_SENSATION + NATURAL_LOCAL_SENSATION_INTENSITY,
        )
    }

    private fun refreshNaturalFutureIfChanged(previous: Float, current: Float) {
        if (!_uiState.value.naturalRunning) return
        if (kotlin.math.abs(previous - current) < NATURAL_SETTINGS_REFRESH_DELTA) return
        // AI-fed queue: never inject local material; the next AI plans use the new settings.
        if (naturalAiFeeding && naturalAiFallbackState == null) return
        naturalEngine?.takeIf { it.started }?.let { engine ->
            engine.replaceFuture(
                com.ossm.remote.natural.localNaturalPlan(engine.nextPlanSeed(), NaturalModeEngine.LOCAL_PHRASES_PER_PLAN),
                naturalSettings(),
            )
        }
    }

    /**
     * Optional AI planner: one request at a time, only once the model is confirmed loaded,
     * strict validation in the parser. Any failure/timeout/invalid JSON leaves the local
     * generator in charge. A request cancelled by STOP/pause/mode exit is not a failure.
     */
    private fun maybeRequestNaturalPlan(engine: NaturalModeEngine, epoch: Long, limits: NaturalLimits) {
        val state = _uiState.value
        if (!state.naturalAiEnabled) return
        if (state.naturalAiModelState != NaturalAiModelState.LOADED) return
        if (naturalPlannerJob?.isActive == true) return
        if (naturalElapsedMs < naturalAiNextAttemptMs) return
        // Adaptive look-ahead: request as soon as the AI reserve < 3 x measured latency + 20 s;
        // while waiting for the first plan or in local fallback: request as soon as allowed.
        val pacing = naturalAiPacing
        val reserveMs = engine.reserveMs
        if (naturalAiFeeding && naturalAiFallbackState == null && engine.started &&
            !pacing.shouldRequest(reserveMs)
        ) return
        val endpoint = naturalAiEndpoint(state)
        if (endpoint == null) {
            failNaturalAi(NaturalAiProblem(NaturalAiFailureKind.INVALID_ADDRESS), NATURAL_AI_MODEL_NAME)
            return
        }
        val constraints = naturalConstraints(limits, engine.nextPlanSeed()) ?: return
        val planner = naturalPlannerCache
            ?.takeIf { it.first == endpoint }
            ?.second
            ?: naturalPlannerFactory(endpoint).also { naturalPlannerCache = endpoint to it }
        val phraseCount = pacing.phrasesPerPlan(endpoint.reasoningRelay)
        val pacingInfo = pacing.info(reserveMs)
        naturalAiLastRequestInfo = pacingInfo
        naturalAiRequestStartedMs = naturalElapsedMs
        logNaturalAi(
            LogLevel.INFO,
            "Demande de plan IA ($phraseCount phrases) - avance ${pacingInfo.reserveSeconds} s, " +
                "seuil ${pacingInfo.thresholdSeconds} s" +
                (pacingInfo.lastLatencySeconds?.let { ", derniere latence $it s" } ?: ""),
        )
        naturalPlannerJob = storeScope.launch {
            var plan: NaturalPlan? = null
            var failure: Throwable? = null
            try {
                plan = withTimeout(NATURAL_AI_TIMEOUT_MS) {
                    planner.plan(NaturalPlanInput(constraints, phraseCount, pacingInfo))
                }
            } catch (timeout: TimeoutCancellationException) {
                failure = timeout
            } catch (cancelled: CancellationException) {
                throw cancelled // STOP/pause/mode exit: not an availability failure
            } catch (error: Exception) {
                failure = error
            }
            val current = _uiState.value
            if (epoch != naturalRunEpoch || naturalEngine !== engine || !current.naturalRunning ||
                !current.naturalAiEnabled
            ) return@launch
            if (plan == null) {
                naturalAiNextAttemptMs = naturalElapsedMs + NATURAL_AI_RETRY_MS
                val problem = classifyNaturalAiFailure(
                    failure ?: IllegalStateException("empty plan"),
                    (NATURAL_AI_TIMEOUT_MS / 1_000L).toInt(),
                )
                if (naturalAiFeeding && naturalAiFallbackState == null) {
                    enterNaturalAiFallback(NaturalAiFallback(NaturalAiFallbackKind.AI_ERROR, problem = problem))
                }
                if (problem.kind == NaturalAiFailureKind.INVALID_JSON ||
                    problem.kind == NaturalAiFailureKind.HTTP_ERROR
                ) {
                    // Model still loaded: keep it, show the cause, retry later or on demand.
                    _uiState.update {
                        it.copy(naturalAiProblem = problem, naturalPlannerStatus = NaturalPlannerStatus.AI_UNAVAILABLE)
                    }
                    logNaturalAi(LogLevel.WARNING, "Plan IA refuse : ${problem.logText(NATURAL_AI_MODEL_NAME)}")
                } else {
                    naturalAiMonitorJob?.cancel(); naturalAiMonitorJob = null
                    failNaturalAi(problem, NATURAL_AI_MODEL_NAME)
                }
            } else {
                val latencyMs = (naturalElapsedMs - naturalAiRequestStartedMs).coerceAtLeast(0L)
                if (pacing === naturalAiPacing) pacing.record(latencyMs)
                val recovered = naturalAiFallbackState != null
                if (!engine.started) {
                    val startLimits = naturalLimits()
                    if (startLimits == null || !startLimits.allowsMotion) return@launch // tick halts
                    engine.start(naturalStartFrame(), startLimits, naturalSettings(), firstAiPlan = plan)
                    naturalPendingInitial = null
                } else {
                    // Return from fallback (or AI enabled mid-run): replace the queued local
                    // material after the gesture in progress; otherwise extend the AI reserve.
                    engine.acceptAiPlan(plan, naturalSettings(), replaceLocalFuture = !naturalAiFeeding || recovered)
                }
                naturalAiFeeding = true
                naturalAiFallbackState = null
                naturalAiPlanReceived = true
                if (recovered) logNaturalAi(LogLevel.INFO, "IA retablie : le plan reprend apres le geste local en cours")
                val after = pacing.info(engine.reserveMs)
                _uiState.update {
                    it.copy(
                        naturalAiProblem = null,
                        naturalPlannerStatus = NaturalPlannerStatus.AI_CONNECTED,
                        naturalAiLastLatencySeconds = after.lastLatencySeconds,
                        naturalAiReserveSeconds = after.reserveSeconds,
                        naturalAiWaitingFirstPlan = false,
                        naturalAiFallback = null,
                    )
                }
                logNaturalAi(
                    LogLevel.INFO,
                    "Plan IA recu (${plan.phrases.size} phrases, latence ${after.lastLatencySeconds} s) - " +
                        "avance ${after.reserveSeconds} s, prochain seuil ${after.thresholdSeconds} s",
                )
            }
        }
    }

    private fun naturalAiActive(state: ControlUiState): Boolean =
        state.naturalAiEnabled && state.naturalAiModelState == NaturalAiModelState.LOADED &&
            naturalAiEndpoint(state) != null

    private fun naturalStartFrame(): NaturalFrame = naturalPendingInitial ?: run {
        val st = _uiState.value
        NaturalFrame(0f, st.depthMin, st.depthMax, NaturalLimits.NEUTRAL_SENSATION)
    }

    private fun naturalAiModelLostFallback(state: ControlUiState): NaturalAiFallback {
        val problem = state.naturalAiProblem
        return if (state.naturalAiModelState == NaturalAiModelState.FAILED && problem != null) {
            NaturalAiFallback(NaturalAiFallbackKind.AI_ERROR, problem = problem)
        } else {
            NaturalAiFallback(NaturalAiFallbackKind.MODEL_UNLOADED)
        }
    }

    /** Explicit, visible hand-over to the local generator; back to AI on the next valid plan. */
    private fun enterNaturalAiFallback(fallback: NaturalAiFallback) {
        naturalAiFallbackState = fallback
        _uiState.update { it.copy(naturalAiFallback = fallback) }
        logNaturalAi(LogLevel.WARNING, fallback.logText(NATURAL_AI_MODEL_NAME))
    }

    private fun naturalConstraints(limits: NaturalLimits, seed: Long): NaturalConstraints? {
        val low = ceil(limits.depthMinimum * 100f - PERCENT_EPSILON).toInt().coerceIn(0, 100)
        val high = floorPercent(limits.depthMaximum)
        if (high - low < NaturalConstraints.MINIMUM_DEPTH_SPAN_PERCENT) return null
        val speed = floorPercent(limits.speedMaximum).coerceIn(1, 100)
        return NaturalConstraints(low, high, speed, seed)
    }

    private fun cancelNaturalProducer() {
        naturalRunEpoch++
        naturalJob?.cancel(); naturalJob = null
        naturalPlannerJob?.cancel(); naturalPlannerJob = null
        naturalEngine = null
        naturalAiFeeding = false
        naturalAiFallbackState = null
        naturalPendingInitial = null
    }

    /** Halt Naturel synchronously; with [sendStop] an urgent `set:speed:0` follows. */
    private fun haltNatural(sendStop: Boolean) {
        val state = _uiState.value
        val wasActive = state.naturalRunning || state.naturalStartPending || naturalJob != null
        cancelNaturalProducer()
        if (state.naturalRunning || state.naturalStartPending) {
            _uiState.update {
                it.copy(
                    naturalRunning = false,
                    naturalStartPending = false,
                    isRunning = false,
                    isPaused = false,
                    speed = 0f,
                    naturalSpeedCurrent = null,
                    naturalDepthMinCurrent = null,
                    naturalDepthMaxCurrent = null,
                    naturalMotif = null,
                    naturalAiWaitingFirstPlan = false,
                    naturalAiFallback = null,
                    naturalAiReserveSeconds = null,
                )
            }
        }
        if (sendStop && wasActive) {
            bleManager.sendCommand(OssmCommand.Stop)
            if (remoteHostLimits != null) publishRemoteSessionView { it.copy(remoteSpeed = 0f) }
        }
    }

    private fun interpolate(min: Float, max: Float, amount: Float): Float {
        return min + (max - min) * amount.coerceIn(0f, 1f)
    }

    /**
     * Native Live touch (2.3.19 anchored pad). The target follows the finger directly once the
     * gesture is engaged; only the first contact is slewed when it is not already within
     * [LIVE_DIRECT_ENTRY_TOLERANCE_PERCENT] of the current logical position.
     */
    fun setStreamPositionDrag(positionPercent: Int) = acceptLiveDrag(positionPercent, directFollow = true)

    private fun acceptLiveDrag(positionPercent: Int, directFollow: Boolean) {
        // A fresh touch owns the scalar again; cancel any phone-owned release/entry return
        // before accepting its first target. The BLE touch scheduler coalesces the pending path.
        cancelLiveReturn()
        // A touch or browser Live target takes over from any spoken sequence.
        cancelVoiceSequence()
        val target = positionPercent.coerceIn(0, 100)
        updateLiveRecordingOnDrag(target)
        val state = _uiState.value
        val activeLive = state.rangeWizard == null &&
            state.activePattern?.mode == PatternControlMode.STREAMING &&
            state.streamingReady
        if (activeLive) {
            if (directFollow) followLiveTargetDirect(target) else requestLiveTargetSlew(target)
        } else {
            cancelLiveTargetSlew()
            _uiState.update { it.copy(streamPositionPercent = target, isRunning = true, isPaused = false) }
            if (canStreamPosition(state)) sendStreamPositionTarget(target)
        }
    }

    fun setStreamPosition(positionPercent: Int) {
        cancelLiveReturn()
        cancelLiveTargetSlew()
        val st = _uiState.value
        if (!canStreamPosition(st)) return
        val target = positionPercent.coerceIn(0, 100)
        _uiState.update { it.copy(streamPositionPercent = target, isRunning = true, isPaused = false) }
        sendStreamPositionTarget(target)
    }

    /**
     * Normal finger/remote release. Unlike [stopLiveTouch], this does not issue an immediate
     * STOP: the phone owns a bounded return of the logical Live target to 0.
     */
    fun endLiveTouch() {
        // A completed recording is retained for an explicit replay action, but normal radial
        // release always follows the same phone-owned slow-return path as an unrecorded touch.
        cancelLiveTargetSlew()
        cancelVoiceSequence()
        finishLiveRecordingTouch()
        if (_uiState.value.liveRecordingState == LiveRecordingState.PLAYING) {
            cancelLiveReplay(sendStop = false)
        }
        val state = _uiState.value
        if (state.activePattern?.mode != PatternControlMode.STREAMING || !state.streamingReady) {
            stopLiveTouch()
            return
        }
        startLiveReturnToHome(emitTargets = true)
    }

    fun stopLiveTouch() {
        invalidateLiveGestureEpoch()
        cancelLiveReturn()
        cancelLiveTargetSlew()
        // STOP/cancel must never transition RECORDING into the established successful
        // recording->replay workflow. It cancels all producers and leaves the machine stopped.
        cancelLiveRecordingTouch()
        cancelLiveReplay(sendStop = false)
        liveRecordingSamples.clear()
        _uiState.update {
            it.copy(
                liveRecordingState = LiveRecordingState.IDLE,
                isRunning = false,
                isPaused = false,
                speed = 0f,
            )
        }
        bleManager.sendCommand(OssmCommand.Stop)
    }

    private fun startLiveReturnToHome(emitTargets: Boolean) {
        val state = _uiState.value
        if (state.activePattern?.mode != PatternControlMode.STREAMING || !state.streamingReady) return
        val initial = state.streamPositionPercent.coerceIn(0, 100)
        cancelLiveReturn()
        cancelLiveTargetSlew()
        if (initial <= 0) {
            _uiState.update { it.copy(streamPositionPercent = 0, isRunning = false, speed = 0f) }
            return
        }
        _uiState.update { it.copy(isRunning = true, isPaused = false) }
        liveReturnJob = storeScope.launch {
            // Seed the first sample at the same monotonic baseline as the ramp. Without this,
            // a nonzero device uptime would consume the entire elapsed interval on the first
            // sample and visually snap straight to home.
            val ramp = LiveReturnRamp(
                initialPercent = initial,
                startAtMillis = monotonicMillis(),
            )
            var lastEmitted = initial
            try {
                while (isActive &&
                    _uiState.value.activePattern?.mode == PatternControlMode.STREAMING &&
                    _uiState.value.streamingReady
                ) {
                    val target = ramp.advance(monotonicMillis())
                    if (target != lastEmitted) {
                        _uiState.update {
                            it.copy(
                                streamPositionPercent = target,
                                isRunning = target > 0,
                                isPaused = false,
                            )
                        }
                        if (emitTargets) sendStreamPositionTarget(target)
                        lastEmitted = target
                    }
                    if (ramp.isComplete) break
                    delay(LIVE_RETURN_TICK_MS)
                }
                if (isActive && ramp.isComplete) {
                    _uiState.update {
                        it.copy(streamPositionPercent = 0, isRunning = false, speed = 0f)
                    }
                }
            } finally {
                if (liveReturnJob === coroutineContext[Job]) liveReturnJob = null
            }
        }
    }

    private fun cancelLiveReturn() {
        liveReturnJob?.cancel()
        liveReturnJob = null
    }

    /**
     * Slew desired targets from both the native radial pad and the browser. The pointer/browser
     * event cadence is not the safety boundary: this job keeps advancing toward the latest
     * desired target even when the producer pauses between events.
     */
    /**
     * Direct-follow path for the native anchored pad. No touch-target slew while the gesture is
     * engaged: every distinct sample goes straight to the coalesced BLE scheduler, which keeps the
     * user speed envelope and the firmware limits. The entry ramp ([requestLiveTargetSlew]) is
     * used only to join the finger when the first contact is far from the current position.
     */
    private fun followLiveTargetDirect(target: Int) {
        val slew = liveTargetSlew
        if (!liveDirectFollowEngaged && liveTargetSlewJob?.isActive == true && slew != null) {
            // Entry ramp still joining the finger: hand over as soon as it is close enough.
            if (kotlin.math.abs(slew.current - target) > LIVE_DIRECT_ENTRY_TOLERANCE_PERCENT) {
                slew.setDesired(target)
                _uiState.update { it.copy(isRunning = true, isPaused = false) }
                return
            }
            cancelLiveTargetSlew()
        }
        val current = _uiState.value.streamPositionPercent
        if (!liveDirectFollowEngaged &&
            kotlin.math.abs(target - current) > LIVE_DIRECT_ENTRY_TOLERANCE_PERCENT
        ) {
            // The touched point is the new 0: coming back down to it keeps the slow release
            // return rate, climbing from it keeps the normal Live rate.
            requestLiveTargetSlew(
                target,
                engageDirectWhenReached = true,
                descentRatePercentPerSecond = LIVE_RELEASE_RETURN_RATE_PERCENT_PER_SECOND,
            )
            return
        }
        liveDirectFollowEngaged = true
        _uiState.update { it.copy(streamPositionPercent = target, isRunning = true, isPaused = false) }
        if (target != current) sendStreamPositionTarget(target)
    }

    private fun requestLiveTargetSlew(
        desiredPercent: Int,
        engageDirectWhenReached: Boolean = false,
        descentRatePercentPerSecond: Float? = null,
    ) {
        val current = _uiState.value.streamPositionPercent
        val slew = liveTargetSlew
        if (liveTargetSlewJob?.isActive == true && slew != null) {
            slew.setDesired(desiredPercent)
            _uiState.update { it.copy(isRunning = true, isPaused = false) }
            return
        }
        val nextSlew = if (descentRatePercentPerSecond != null) {
            // Joining the finger (up or down) is always slow: the actuator never leaps to it.
            LiveTargetSlew(
                initialPercent = current,
                ratePercentPerSecond = descentRatePercentPerSecond,
                initialStepPercent = LIVE_JOIN_INITIAL_STEP_PERCENT,
                descentRatePercentPerSecond = descentRatePercentPerSecond,
            )
        } else {
            LiveTargetSlew(initialPercent = current)
        }
        nextSlew.setDesired(desiredPercent)
        // Low latency: the first bounded step leaves synchronously with the touch event; only
        // the remaining catch-up toward a far finger runs in the slew job.
        val firstStep = nextSlew.advance(monotonicMillis())
        _uiState.update { it.copy(streamPositionPercent = firstStep, isRunning = true, isPaused = false) }
        if (firstStep != current) sendStreamPositionTarget(firstStep)
        if (nextSlew.isAtDesired()) {
            if (engageDirectWhenReached) liveDirectFollowEngaged = true
            return
        }
        liveTargetSlew = nextSlew
        liveTargetSlewJob = storeScope.launch {
            var lastEmitted = firstStep
            try {
                while (isActive && canStreamPosition(_uiState.value) && !nextSlew.isAtDesired()) {
                    delay(LIVE_TARGET_TICK_MS)
                    if (!isActive || !canStreamPosition(_uiState.value)) break
                    val target = nextSlew.advance(monotonicMillis())
                    if (target != lastEmitted) {
                        _uiState.update {
                            it.copy(streamPositionPercent = target, isRunning = true, isPaused = false)
                        }
                        sendStreamPositionTarget(target)
                        lastEmitted = target
                    }
                }
                if (engageDirectWhenReached && isActive && nextSlew.isAtDesired() &&
                    liveTargetSlewJob === coroutineContext[Job]
                ) {
                    liveDirectFollowEngaged = true
                }
            } finally {
                if (liveTargetSlewJob === coroutineContext[Job]) {
                    liveTargetSlewJob = null
                    liveTargetSlew = null
                }
            }
        }
    }

    private var liveTargetSlew: LiveTargetSlew? = null

    /** True once the current native gesture follows the finger without a touch-target slew. */
    private var liveDirectFollowEngaged = false

    /** Cancels the entry/remote slew and ends direct follow (release, STOP, loss, mode exit). */
    private fun cancelLiveTargetSlew() {
        liveTargetSlewJob?.cancel()
        liveTargetSlewJob = null
        liveTargetSlew = null
        liveDirectFollowEngaged = false
    }

    fun toggleLiveRecording() {
        val state = _uiState.value
        if (state.activePattern?.mode != PatternControlMode.STREAMING || !state.streamingReady) return
        when (state.liveRecordingState) {
            LiveRecordingState.IDLE -> {
                cancelLiveReplay(sendStop = false)
                liveRecordingSamples.clear()
                _uiState.update { it.copy(liveRecordingState = LiveRecordingState.ARMED) }
            }
            LiveRecordingState.ARMED -> {
                liveRecordingSamples.clear()
                _uiState.update {
                    it.copy(
                        liveRecordingState = LiveRecordingState.IDLE,
                        isRunning = false,
                        isPaused = false,
                        speed = 0f
                    )
                }
            }
            LiveRecordingState.RECORDING -> {
                // Stopping the recorder is an explicit control action. Keep the captured samples
                // in RECORDED, stop the current device motion, and require a second deliberate
                // press to start replay.
                val captured = finishLiveRecordingTouch()
                bleManager.sendCommand(OssmCommand.Stop)
                if (captured && _uiState.value.liveRecordingState == LiveRecordingState.RECORDED) {
                    _uiState.update { it.copy(isRunning = false, isPaused = false, speed = 0f) }
                } else {
                    liveRecordingSamples.clear()
                    _uiState.update {
                        it.copy(
                            liveRecordingState = LiveRecordingState.IDLE,
                            isRunning = false,
                            isPaused = false,
                            speed = 0f
                        )
                    }
                }
            }
            LiveRecordingState.RECORDED -> {
                if (liveRecordingSamples.size >= 2) {
                    startLiveReplay(liveRecordingSamples.toList())
                } else {
                    liveRecordingSamples.clear()
                    _uiState.update { it.copy(liveRecordingState = LiveRecordingState.IDLE) }
                }
            }
            LiveRecordingState.PLAYING -> {
                cancelLiveReplay(sendStop = true)
                liveRecordingSamples.clear()
                _uiState.update {
                    it.copy(
                        liveRecordingState = LiveRecordingState.IDLE,
                        isRunning = false,
                        isPaused = false,
                        speed = 0f
                    )
                }
            }
        }
    }

    private fun canStreamPosition(state: ControlUiState): Boolean {
        val canStream = state.rangeWizard != null || state.activePattern?.mode == PatternControlMode.STREAMING
        return canStream && state.streamingReady
    }

    private fun updateLiveRecordingOnDrag(target: Int) {
        when (_uiState.value.liveRecordingState) {
            LiveRecordingState.ARMED -> {
                liveReplayJob?.cancel()
                liveReplayJob = null
                liveRecordingSamples.clear()
                liveRecordingStartMs = monotonicMillis()
                liveRecordingSamples += LiveRecordingSample(target, offsetMs = 0L)
                _uiState.update { it.copy(liveRecordingState = LiveRecordingState.RECORDING) }
            }
            LiveRecordingState.RECORDING -> {
                val previous = liveRecordingSamples.lastOrNull()
                if (previous?.positionPercent != target) {
                    liveRecordingSamples += LiveRecordingSample(
                        positionPercent = target,
                        offsetMs = monotonicMillis() - liveRecordingStartMs
                    )
                }
            }
            LiveRecordingState.PLAYING -> {
                cancelLiveReplay(sendStop = false)
                liveRecordingSamples.clear()
                _uiState.update { it.copy(liveRecordingState = LiveRecordingState.IDLE) }
            }
            LiveRecordingState.RECORDED -> {
                // A fresh touch abandons a previously captured recording rather than silently
                // mixing new samples into it.
                liveRecordingSamples.clear()
                _uiState.update { it.copy(liveRecordingState = LiveRecordingState.IDLE) }
            }
            LiveRecordingState.IDLE -> Unit
        }
    }

    private fun finishLiveRecordingTouch(): Boolean {
        if (_uiState.value.liveRecordingState != LiveRecordingState.RECORDING) return false
        val recording = liveRecordingSamples.toList()
        if (recording.size < 2 || !canStreamPosition(_uiState.value)) {
            liveRecordingSamples.clear()
            _uiState.update {
                it.copy(
                    liveRecordingState = LiveRecordingState.IDLE,
                    isRunning = false,
                    isPaused = false,
                    speed = 0f
                )
            }
            return false
        }
        // Successful normal release retains the samples but does not replay them. The next
        // deliberate press of the record button enters PLAYING via toggleLiveRecording().
        _uiState.update { it.copy(liveRecordingState = LiveRecordingState.RECORDED) }
        return true
    }

    /** Cancellation-only recording cleanup; never starts replay. */
    private fun cancelLiveRecordingTouch(): Boolean {
        if (_uiState.value.liveRecordingState != LiveRecordingState.RECORDING &&
            _uiState.value.liveRecordingState != LiveRecordingState.ARMED &&
            _uiState.value.liveRecordingState != LiveRecordingState.RECORDED &&
            _uiState.value.liveRecordingState != LiveRecordingState.PLAYING
        ) return false
        liveRecordingSamples.clear()
        _uiState.update {
            it.copy(
                liveRecordingState = LiveRecordingState.IDLE,
                isRunning = false,
                isPaused = false,
                speed = 0f,
            )
        }
        return true
    }

    private fun startLiveReplay(recording: List<LiveRecordingSample>) {
        liveReplayJob?.cancel()
        liveReplayJob = storeScope.launch {
            try {
                _uiState.update {
                    it.copy(liveRecordingState = LiveRecordingState.PLAYING, isRunning = true, isPaused = false)
                }
                while (isActive && canStreamPosition(_uiState.value)) {
                    var previousOffset = 0L
                    recording.forEachIndexed { index, sample ->
                        if (!isActive || !canStreamPosition(_uiState.value)) return@launch
                        val waitMs = if (index == 0) 0L else {
                            (sample.offsetMs - previousOffset)
                                .coerceIn(LIVE_REPLAY_MIN_DELAY_MS, LIVE_REPLAY_MAX_DELAY_MS)
                        }
                        if (waitMs > 0L) delay(waitMs)
                        if (!isActive || !canStreamPosition(_uiState.value)) return@launch
                        setStreamPosition(sample.positionPercent)
                        previousOffset = sample.offsetMs
                    }
                }
            } finally {
                if (liveReplayJob === coroutineContext[Job]) {
                    liveReplayJob = null
                    _uiState.update { it.copy(liveRecordingState = LiveRecordingState.IDLE, isRunning = false) }
                }
            }
        }
    }

    private fun cancelLiveReplay(sendStop: Boolean) {
        liveReplayJob?.cancel()
        liveReplayJob = null
        if (sendStop) bleManager.sendCommand(OssmCommand.Stop)
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

    private fun sendStreamPositionTarget(
        target: Int,
        voiceDurationMs: Int? = null,
    ) {
        val safeTarget = hostBoundLiveRawPosition(target) ?: return
        val producer = when {
            voiceDurationMs != null -> LiveTargetProducer.VOICE
            _uiState.value.rangeWizard != null -> LiveTargetProducer.RANGE_WIZARD
            else -> LiveTargetProducer.TOUCH
        }
        bleManager.sendCommand(
            OssmCommand.Stream(safeTarget, timeMs = voiceDurationMs ?: STREAM_RELEASE_MOVE_MS, producer = producer)
        )
    }

    /**
     * Bound an already raw/UI-mapped Live target in physical device space. This
     * path is used by recording replay and must not apply the remote-fraction
     * mapping a second time.
     */
    private fun hostBoundLiveRawPosition(target: Int): Int? {
        val rawTarget = target.coerceIn(0, 100)
        val limits = remoteHostLimits ?: return rawTarget
        val effective = effectiveRemoteMotionLimits() ?: run {
            rejectRemoteMotionIfUnavailable()
            return null
        }
        val physicalTarget = mapLiveStreamPositionPercent(rawTarget, bleManager.liveInvert)
        return effective.mapPhysicalPositionForBle(physicalTarget, bleManager.liveInvert)
    }

    // ---- Voix (2.3.26, base) ----
    // Mode STREAMING with key "voice". A future ON-DEVICE model turns speech into VoiceStep
    // plans; here they are strictly validated, bounded by the voice speed maximum and executed
    // as Live targets scaled into the hard depth limits, then through sendStreamPositionTarget
    // (remote envelope, inversion, BLE ceiling). The Live entry ramp is unchanged. Nothing moves
    // before an accepted plan; the end of a plan stays in place. STOP / mode exit / BLE or
    // session loss cancel everything.

    private fun voiceModeActive(state: ControlUiState): Boolean =
        state.rangeWizard == null && state.activePattern.isVoicePattern()

    private fun voiceCanMove(state: ControlUiState): Boolean = voiceBlockReason(state) == null

    /** Why Voix targets cannot be sent right now (journaled, never silent), null when they can. */
    private fun voiceBlockReason(state: ControlUiState): String? = when {
        !voiceModeActive(state) -> "mode Voix inactif"
        bleManager.connectionState.value !is BleConnectionState.Connected -> "OSSM non connecté"
        !canStreamPosition(state) -> "streaming non prêt"
        voiceMaxRate(state) <= 0f -> "vitesse max (voix ou enveloppe) à 0"
        else -> null
    }

    private fun onVoiceStep(index: Int?) {
        _uiState.update { it.copy(voiceStepIndex = index) }
        val step = index?.let { _uiState.value.voicePlan.getOrNull(it) } ?: return
        // Decision log only: target and duration, never the spoken text.
        logVoice(
            LogLevel.INFO,
            "Voix : étape ${index + 1}/${_uiState.value.voicePlan.size} cible ${step.targetPercent} % en ${step.moveMs} ms",
        )
    }

    /** Arms the Live speed like the first touch, before the first Voix target. */
    private fun armVoiceLiveSpeed(): Boolean {
        val armed = runCatching { bleManager.armLiveSpeed("voix") }.getOrDefault(false)
        if (!armed) logVoice(LogLevel.WARNING, "Vitesse Live non armée : cibles vocales refusées par le transport")
        return armed
    }

    private fun voiceMaxRate(state: ControlUiState): Float {
        val local = state.voiceMaxSpeed.coerceIn(0f, 1f)
        val ceiling = if (remoteHostLimits != null) {
            effectiveRemoteMotionLimits()?.speedMaximum ?: 0f
        } else 1f
        return VoicePlanner.maxRateFor(minOf(local, ceiling))
    }

    /** Effective Voix range: the user Voix range intersected with the hard depth limits. */
    private fun voiceDepthRange(state: ControlUiState): Pair<Float, Float> =
        VoiceDepthRange.effective(
            state.voiceDepthMin, state.voiceDepthMax, state.depthLimitMin, state.depthLimitMax
        )

    /**
     * Logical voice percent -> Live scalar inside the Voix range (itself inside the hard depth
     * limits), then the Live path (remote envelope, inversion, BLE ceiling). A target farther
     * from the current scalar than one legitimate runner tick (range changed, range minimum
     * above the entry position) is joined through the existing bounded LiveTargetSlew instead
     * of being sent as a jump; while that ramp is active, later targets only move its goal.
     */
    private fun emitVoiceTarget(logical: Int, durationMs: Int) {
        val state = _uiState.value
        voiceBlockReason(state)?.let { reason ->
            if (!voiceRefusalLogged) {
                voiceRefusalLogged = true
                logVoice(LogLevel.WARNING, "Cible vocale refusée : $reason")
            }
            return
        }
        val range = voiceDepthRange(state)
        val scalar = VoiceDepthRange.scalarFor(logical, range)
        val current = state.streamPositionPercent
        val tolerance = VoiceDepthRange.directTolerance(VoiceSequenceRunner.MAX_STEP, range)
        if (liveTargetSlewJob?.isActive == true || kotlin.math.abs(scalar - current) > tolerance) {
            requestLiveTargetSlew(scalar)
            return
        }
        _uiState.update { it.copy(streamPositionPercent = scalar, isRunning = true, isPaused = false) }
        if (scalar != current) sendStreamPositionTarget(scalar, voiceDurationMs = durationMs)
    }

    private fun cancelVoiceSequence() {
        voiceInterpretJob?.cancel(); voiceInterpretJob = null
        voiceJoinJob?.cancel(); voiceJoinJob = null
        voiceRunner.cancel()
        if (_uiState.value.voiceRunning || _uiState.value.voiceStepIndex != null || _uiState.value.voiceMotif != null) {
            _uiState.update { it.copy(voiceRunning = false, voiceStepIndex = null, voiceMotif = null) }
        }
    }

    private fun resetVoiceSession() {
        voiceRunner.resetPosition(0)
        _uiState.update {
            it.copy(
                voiceInterpretation = VoiceInterpretation.NONE,
                voicePlan = emptyList(),
                voiceMotif = null,
                voiceStepIndex = null,
                voiceRunning = false,
            )
        }
    }

    private fun logVoice(level: LogLevel, message: String) {
        // Never any audio or phrase text: only what was decided.
        runCatching { bleManager.appendExternalDiagnostic(level, VOICE_LOG_TAG, message) }
    }

    /**
     * Priority STOP of the Voix mode: cancels the plan (and any interpretation in progress)
     * and calls the existing STOP (speed 0, no further target). Screen button and future model.
     */
    fun voiceStopNow(source: VoiceStopSource = VoiceStopSource.BUTTON) {
        cancelVoiceSequence()
        if (voiceModeActive(_uiState.value)) {
            _uiState.update { it.copy(voiceInterpretation = VoiceInterpretation.STOPPED, voicePlan = emptyList()) }
        }
        logVoice(LogLevel.INFO, "STOP vocal (${source.label})")
        stop()
    }

    /** 2.3.29: true while the Voix runner, a join ramp or a motif is moving. */
    private fun voiceMotionRunning(): Boolean =
        _uiState.value.voiceRunning || voiceRunner.isActive || voiceJoinJob?.isActive == true

    fun setVoiceShortWordStop(enabled: Boolean) {
        _uiState.update { it.copy(voiceShortWordStop = enabled) }
        storeScope.launch { safetySettingsRepository.setVoiceShortWordStop(enabled) }
    }

    /** Any motion the phone produces or could resume (volume STOP, background STOP). */
    fun isMotionModeActive(): Boolean {
        val st = _uiState.value
        return st.isRunning || st.autoRunning || st.naturalRunning || st.naturalStartPending ||
            voiceMotionRunning() || naturalJob != null ||
            progressiveJob?.isActive == true || chaosJob?.isActive == true ||
            teasingRandomJob?.isActive == true || autoJob?.isActive == true ||
            liveReplayJob?.isActive == true || liveReturnJob?.isActive == true ||
            liveTargetSlewJob?.isActive == true ||
            st.activePattern?.mode == PatternControlMode.STREAMING ||
            st.activePattern.isVoicePattern() || st.activePattern?.key == com.ossm.remote.model.NATURAL_PATTERN_KEY
    }

    /** 2.3.29 hardware STOP (volume keys): immediate STOP of every producer. */
    fun hardwareStop() {
        if (voiceModeActive(_uiState.value)) {
            voiceStopNow(VoiceStopSource.VOLUME_KEY)
            return
        }
        if (_uiState.value.naturalRunning || _uiState.value.naturalStartPending) haltNatural(sendStop = false)
        bleManager.appendExternalDiagnostic(LogLevel.INFO, VOICE_LOG_TAG, "STOP (touche volume)")
        stop()
    }

    /**
     * 2.3.29 SAFETY: app in background / onStop / screen off. Voix is always CANCELLED with
     * STOP (never paused then resumed). Other phone producers (Naturel, progressif, auto,
     * Live, replay, returns) are stopped too, unless an Internet remote session owns the motion
     * (it has its own heartbeat loss -> stop). Nothing restarts without a new instruction.
     */
    fun onAppBackground() {
        val voice = voiceMotionRunning()
        if (voice) {
            cancelVoiceSequence()
            logVoice(LogLevel.WARNING, "Voix annulée : application en arrière-plan (STOP)")
            if (voiceModeActive(_uiState.value)) {
                _uiState.update { it.copy(voiceInterpretation = VoiceInterpretation.STOPPED, voicePlan = emptyList()) }
            }
        }
        val others = phoneProducerActive()
        if (voice || (others && remoteHostLimits == null)) {
            if (_uiState.value.naturalRunning || _uiState.value.naturalStartPending) haltNatural(sendStop = false)
            if (others && !voice) {
                bleManager.appendExternalDiagnostic(LogLevel.WARNING, VOICE_LOG_TAG, "STOP : application en arrière-plan")
            }
            stop()
        }
    }

    /** 2.3.29: link suspect (missed ping): same rule as background, logged as such. */
    private fun onLinkSuspect() {
        val voice = voiceMotionRunning()
        if (voice) {
            cancelVoiceSequence()
            logVoice(LogLevel.WARNING, "Voix annulée : lien BLE suspect (STOP)")
            if (voiceModeActive(_uiState.value)) {
                _uiState.update { it.copy(voiceInterpretation = VoiceInterpretation.STOPPED, voicePlan = emptyList()) }
            }
        }
        if (voice || phoneProducerActive()) {
            if (_uiState.value.naturalRunning || _uiState.value.naturalStartPending) haltNatural(sendStop = false)
            stop()
        }
    }

    private fun phoneProducerActive(): Boolean {
        val st = _uiState.value
        return st.naturalRunning || st.naturalStartPending || naturalJob != null ||
            progressiveJob?.isActive == true || chaosJob?.isActive == true ||
            teasingRandomJob?.isActive == true || autoJob?.isActive == true || st.autoRunning ||
            liveReplayJob?.isActive == true || liveReturnJob?.isActive == true ||
            liveTargetSlewJob?.isActive == true ||
            (st.activePattern?.mode == PatternControlMode.STREAMING && st.isRunning)
    }

    /**
     * Executes a plan produced by the interpreter. Returns true when it was accepted. Invalid
     * plans are rejected whole; a plan submitted during a move replaces the current one when
     * that move ends, or at once when [immediate] (or while holding / idle).
     */
    /** 2.3.31: last accepted (validated, unbounded) plan, for the rule "encore". */
    private var lastVoiceSteps: List<VoiceStep> = emptyList()

    /** 2.3.31: what [com.ossm.remote.voice.VoiceRuleParser] may know (read-only). */
    fun voiceRuleContext(): VoiceRuleContext = VoiceRuleContext(
        positionPercent = voiceRunner.nextStartPercent(immediate = false),
        motifRunning = voiceRunner.motifActive,
        lastSteps = lastVoiceSteps,
    )

    fun voiceExecuteSteps(steps: List<VoiceStep>, immediate: Boolean = false): Boolean {
        val state = _uiState.value
        if (!voiceModeActive(state)) return false
        val valid = VoiceStepValidator.validate(steps)
        if (valid == null) {
            _uiState.update { it.copy(voiceInterpretation = VoiceInterpretation.REJECTED) }
            logVoice(LogLevel.WARNING, "Plan vocal rejete (hors bornes)")
            return false
        }
        if (!voiceCanMove(state)) {
            _uiState.update { it.copy(voiceInterpretation = VoiceInterpretation.NOT_READY) }
            logVoice(LogLevel.WARNING, "Plan vocal ignore : Live non pret ou maximum a 0")
            return false
        }
        cancelLiveReturn()
        val joinScalar = if (voiceRunner.isActive) null else anchorVoiceRunner(_uiState.value)
        val bounded = VoicePlanner.bound(
            valid,
            startPercent = voiceRunner.nextStartPercent(immediate),
            maxRatePercentPerSecond = voiceMaxRate(state),
        )
        if (!armVoiceLiveSpeed()) {
            _uiState.update { it.copy(voiceInterpretation = VoiceInterpretation.NOT_READY) }
            return false
        }
        voiceRefusalLogged = false
        _uiState.update {
            it.copy(
                voiceInterpretation = VoiceInterpretation.PLANNED,
                voicePlan = bounded,
                voiceMotif = null,
                voiceRunning = true,
                isPaused = false,
            )
        }
        lastVoiceSteps = valid
        logVoice(LogLevel.INFO, "Plan vocal accepte (${bounded.size} etapes)")
        startVoiceAfterJoin(joinScalar) { voiceRunner.submit(bounded, immediate) }
        return true
    }

    /**
     * 2.3.28: continuous back-and-forth motif. Strict bounds (else "not understood"); the
     * strokes start from the current position, inside the Voix range, at a cadence relative
     * to the Voix speed maximum (bounded by the remote envelope). Runs until STOP / new order.
     */
    fun voiceExecuteMotif(motif: VoiceMotif): Boolean {
        val state = _uiState.value
        if (!voiceModeActive(state)) return false
        if (!VoiceMotifValidator.isValid(motif)) {
            _uiState.update { it.copy(voiceInterpretation = VoiceInterpretation.NOT_UNDERSTOOD) }
            logVoice(LogLevel.WARNING, "Motif vocal non compris (hors bornes)")
            return false
        }
        voiceBlockReason(state)?.let { reason ->
            _uiState.update { it.copy(voiceInterpretation = VoiceInterpretation.NOT_READY) }
            logVoice(LogLevel.WARNING, "Motif vocal ignore : $reason")
            return false
        }
        val requested = motif
        @Suppress("NAME_SHADOWING")
        val motif = VoiceMotifMath.withStartFloors(requested)
        cancelLiveReturn()
        val joinScalar = if (voiceRunner.isActive) null else anchorVoiceRunner(_uiState.value)
        if (!armVoiceLiveSpeed()) {
            _uiState.update { it.copy(voiceInterpretation = VoiceInterpretation.NOT_READY) }
            return false
        }
        voiceRefusalLogged = false
        _uiState.update {
            it.copy(
                voiceInterpretation = VoiceInterpretation.PLANNED,
                voicePlan = emptyList(),
                voiceMotif = motif,
                voiceRunning = true,
                isPaused = false,
            )
        }
        logVoice(
            LogLevel.INFO,
            "Voix : motif prof ${motif.depthStart}→${motif.depthEnd} %, vitesse ${motif.speedStart}→${motif.speedEnd} %, " +
                "montée ${motif.rampSeconds} s" + if (motif != requested) " (planchers appliqués)" else "",
        )
        startVoiceAfterJoin(joinScalar) { voiceRunner.submitMotif(motif) }
        return true
    }

    private var voiceJoinJob: Job? = null

    /**
     * Idle runner only: re-anchors its logical position on the current scalar (clamped into
     * the Voix range) so a new plan/motif starts from where the carriage is. Returns the range
     * edge to join first when the carriage is outside the range (e.g. range minimum above the
     * entry position, range narrowed), else null.
     */
    private fun anchorVoiceRunner(state: ControlUiState): Int? {
        val range = voiceDepthRange(state)
        val current = state.streamPositionPercent
        val low = VoiceDepthRange.scalarFor(0, range)
        val high = VoiceDepthRange.scalarFor(100, range)
        val clamped = current.coerceIn(low, high)
        voiceRunner.resetPosition(VoiceDepthRange.logicalFor(clamped, range))
        return clamped.takeIf { kotlin.math.abs(it - current) > 1 }
    }

    /**
     * Starts [block] at once, or first joins [joinScalar] through the existing bounded
     * LiveTargetSlew (80 %/s): no jump at the start. STOP / cancel abort the join too.
     */
    private fun startVoiceAfterJoin(joinScalar: Int?, block: () -> Unit) {
        voiceJoinJob?.cancel(); voiceJoinJob = null
        if (joinScalar == null) {
            cancelLiveTargetSlew()
            block()
            return
        }
        logVoice(LogLevel.INFO, "Voix : rejoint la plage à $joinScalar % (rampe bornée)")
        cancelLiveTargetSlew()
        requestLiveTargetSlew(joinScalar)
        voiceJoinJob = storeScope.launch {
            while (isActive && liveTargetSlewJob?.isActive == true) delay(LIVE_TARGET_TICK_MS)
            if (!isActive) return@launch
            voiceJoinJob = null
            voiceBlockReason(_uiState.value)?.let { reason ->
                logVoice(LogLevel.WARNING, "Départ vocal annulé : $reason")
                _uiState.update { it.copy(voiceRunning = false, voiceMotif = null) }
                return@launch
            }
            block()
        }
    }

    /** 2.3.28: "plus vite", "plus profond", "garde ce rythme"... only while a motif runs. */
    fun voiceModulate(modulation: VoiceModulation): Boolean {
        if (!voiceModeActive(_uiState.value)) return false
        val next = if (voiceRunner.motifActive) voiceRunner.modulate(modulation) else null
        if (next == null) {
            _uiState.update { it.copy(voiceInterpretation = VoiceInterpretation.NOT_UNDERSTOOD) }
            logVoice(LogLevel.INFO, "Modulation vocale sans motif en cours")
            return false
        }
        _uiState.update { it.copy(voiceInterpretation = VoiceInterpretation.PLANNED, voiceMotif = next) }
        logVoice(
            LogLevel.INFO,
            "Voix : niveau ${modulation.name} prof ${next.depthStart}→${next.depthEnd} %, " +
                "vitesse ${next.speedStart}→${next.speedEnd} %",
        )
        return true
    }

    /** Applies an interpreter result: STOP first, then a plan, else "not understood". */
    fun onVoiceInterpretation(result: VoiceInterpretResult) {
        if (!voiceModeActive(_uiState.value)) return
        when (result) {
            VoiceInterpretResult.Stop -> voiceStopNow(VoiceStopSource.KEYWORD)
            is VoiceInterpretResult.Steps -> voiceExecuteSteps(result.steps, result.immediate)
            is VoiceInterpretResult.Motif -> voiceExecuteMotif(result.motif)
            is VoiceInterpretResult.Modulate -> voiceModulate(result.modulation)
            VoiceInterpretResult.NotUnderstood -> {
                _uiState.update { it.copy(voiceInterpretation = VoiceInterpretation.NOT_UNDERSTOOD) }
                logVoice(LogLevel.INFO, "Consigne vocale non comprise")
            }
            VoiceInterpretResult.Ignored -> Unit
        }
    }

    /** Hands one input to the on-device interpreter (no-op when none is installed). */
    fun interpretVoiceInput(input: VoiceInput) {
        val interpreter = voiceInterpreter
        if (!voiceModeActive(_uiState.value)) return
        // 2.3.29 SAFETY: a very short isolated word while moving = STOP, before ASR/LLM.
        if (input is VoiceInput.Audio && VoiceShortWordStop.shouldStop(
                enabled = _uiState.value.voiceShortWordStop,
                motionRunning = voiceMotionRunning(),
                sampleCount = input.pcm16.size,
                sampleRateHz = input.sampleRateHz,
            )
        ) {
            voiceStopNow(VoiceStopSource.SHORT_WORD)
            return
        }
        if (!interpreter.isInstalled) return
        voiceInterpretJob?.cancel()
        voiceInterpretJob = storeScope.launch {
            val result = try {
                interpreter.interpret(input)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                VoiceInterpretResult.NotUnderstood
            }
            if (!isActive) return@launch
            voiceInterpretJob = null
            onVoiceInterpretation(result)
        }
    }

    /**
     * 2.3.28: Voix depth range (two handles). Stored ordered with the 5 % span and persisted;
     * the hard limits are applied at use. Safest policy while a plan runs: the plan is
     * CANCELLED cleanly (no STOP, no new target, the carriage holds where it is) because its
     * durations were bounded for the old mapping. The runner is re-anchored on the logical
     * position of the current scalar in the new range, so the next plan starts from where the
     * carriage is; if it is now outside the range, the next plan joins the range through the
     * bounded LiveTargetSlew (see [emitVoiceTarget]). Changing the range never moves by itself.
     */
    fun setVoiceDepthRange(min: Float, max: Float) {
        if (min.isNaN() || max.isNaN()) return
        val (low, high) = ControlSafetySettingsRepository.normalizeVoiceDepthRange(min, max)
        val before = _uiState.value
        if (before.voiceDepthMin == low && before.voiceDepthMax == high) return
        _uiState.update { it.copy(voiceDepthMin = low, voiceDepthMax = high) }
        storeScope.launch { safetySettingsRepository.setVoiceDepthRange(low, high) }
        val state = _uiState.value
        if (!voiceModeActive(state)) return
        if (state.voiceRunning || voiceRunner.isActive) {
            cancelVoiceSequence()
            cancelLiveTargetSlew()
            _uiState.update { it.copy(voicePlan = emptyList(), voiceInterpretation = VoiceInterpretation.NONE) }
            logVoice(LogLevel.INFO, "Plan vocal annule (plage modifiee)")
        }
        voiceRunner.resetPosition(VoiceDepthRange.logicalFor(state.streamPositionPercent, voiceDepthRange(state)))
    }

    fun setVoiceMaxSpeed(value: Float) {
        if (value.isNaN()) return
        _uiState.update { it.copy(voiceMaxSpeed = value.coerceIn(0f, 1f)) }
        // A zero maximum forbids any further travel: the running plan stops.
        if (value <= 0f && (voiceRunner.isActive || _uiState.value.voiceRunning)) voiceStopNow()
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
        if (remoteHostLimits != null) {
            // During a remote session a preset only sets the envelope (speed maximum and
            // depth range); it neither switches the pattern nor commands the motor.
            consumeLocalDepthAsEnvelope(depthMin, depthMax)
            consumeLocalSpeedAsMaximum(preset.speed)
            return
        }
        if (matchingPattern.mode == PatternControlMode.NATURAL) {
            // A Naturel preset restores its settings only: verified speed-0 entry, no motion
            // until an explicit start (the preset speed becomes the Naturel maximum).
            activatePattern(matchingPattern.key)
            _uiState.update {
                it.copy(depthMin = depthMin, depthMax = depthMax, naturalMaxSpeed = preset.speed.coerceIn(0f, 1f))
            }
            return
        }
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

    fun requestSensationChange(value: Float) = requestSensationChange(value, expectedGestureEpoch = null)

    /** Commit from a scalar gesture captured before a STOP/loss epoch change. */
    fun requestSensationChange(value: Float, expectedGestureEpoch: Long?) {
        if (expectedGestureEpoch != null && expectedGestureEpoch != _uiState.value.liveGestureEpoch) return
        if (value.isNaN()) return
        // During a remote session the local sensation only sets the symmetric intensity (no motor command).
        if (consumeLocalSensationAsIntensity(value, commit = true)) return
        val sensation = value.coerceIn(0f, 1f)
        when (_uiState.value.activePattern?.mode) {
            PatternControlMode.STROKE_ENGINE -> {
                _uiState.update { it.copy(sensation = sensation) }
                sendCurrentStrokeEngineCommand()
            }
            PatternControlMode.PROGRESSIVE -> {
                // Sensation = ramp rate; the running ramp reads it live, no firmware write needed.
                _uiState.update { it.copy(sensation = sensation) }
                // During mode setup the first ramp command is deliberately held back until
                // the verified activation callback. Keep the latest sensation with the same
                // pending command so that a committed change made before that callback is not
                // silently replaced by the setup snapshot.
                val state = _uiState.value
                strokeEngineActivationSpeed.retainIfActivating(
                    StrokeEngineCommand(
                        speed = state.speed,
                        depthMin = state.depthMin,
                        depthMax = state.depthMax,
                        sensation = state.sensation,
                    )
                )
            }
            else -> return
        }
    }

    // ---- Garde anti-saut (enforcee ici pour que l'overlay la respecte aussi) ----

    private fun maybeApplyManualChange(
        control: GuardedControl,
        speed: Float,
        depthMin: Float,
        depthMax: Float,
        localAuthority: Boolean = true,
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

        if (localAuthority && guard.enabled && control !in sessionBypass && delta > threshold) {
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

        applyManualChange(speed, depthMin, depthMax, localAuthority)
    }

    private fun applyManualChange(
        speed: Float,
        depthMin: Float,
        depthMax: Float,
        localAuthority: Boolean = true,
    ) {
        val current = _uiState.value
        val hostLimits = if (!localAuthority) effectiveRemoteMotionLimits() else null
        if (!localAuthority && (hostLimits == null || !hostLimits.hasMinimumDepthSpan())) {
            rejectRemoteMotionIfUnavailable()
            return
        }
        val (normalizedMin, normalizedMax) = if (hostLimits != null) {
            clampDepthRange(depthMin, depthMax, hostLimits.depthMinimum, hostLimits.depthMaximum)
        } else {
            clampDepthRange(depthMin, depthMax, current.depthLimitMin, current.depthLimitMax)
        }
        val normalizedSpeed = if (localAuthority) speed.coerceIn(0f, 1f) else hostSpeed(speed)
        _uiState.update {
            it.copy(
                speed = normalizedSpeed,
                depthMin = normalizedMin,
                depthMax = normalizedMax,
                isRunning = normalizedSpeed > 0f,
                pendingManualChange = null
            )
        }
        sendCurrentStrokeEngineCommand()
    }

    private fun sendCurrentStrokeEngineCommand() {
        val state = _uiState.value
        if (state.activePattern == null) return
        sendStrokeEngineCommand(
            StrokeEngineCommand(
                speed = state.speed,
                depthMin = state.depthMin,
                depthMax = state.depthMax,
                sensation = state.sensation
            )
        )
    }

    /**
     * Send a complete stroke command, or retain it while verified mode entry is pending.
     * Random/chaos producers use this same seam so they cannot reintroduce an early speed
     * write while the activation retries are still deliberately holding speed at zero.
     */
    private fun sendStrokeEngineCommand(command: StrokeEngineCommand) {
        val mode = _uiState.value.activePattern?.mode ?: return
        val safeCommand = if (remoteHostLimits != null) {
            effectiveRemoteMotionLimits()?.clampStrokeEngineCommand(command) ?: run {
                rejectRemoteMotionIfUnavailable()
                return
            }
        } else {
            command
        }
        if ((mode == PatternControlMode.STROKE_ENGINE || mode == PatternControlMode.PROGRESSIVE) &&
            strokeEngineActivationSpeed.retainIfActivating(safeCommand)
        ) return
        bleManager.sendCommand(OssmCommand.UpdateStrokeEngine(safeCommand))
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
        invalidateLiveGestureEpoch()
        autoEnterJob?.cancel(); autoEnterJob = null
        cancelLiveReturn()
        cancelLiveTargetSlew()
        // A stop must also invalidate a mode entry that is still waiting for post-setup;
        // otherwise that waiter can start the pattern again after the stop callback.
        modeActivationJob?.cancel(); modeActivationJob = null
        progressiveJob?.cancel(); progressiveJob = null
        chaosJob?.cancel(); chaosJob = null
        teasingRandomJob?.cancel(); teasingRandomJob = null
        sensationTurnJob?.cancel(); sensationTurnJob = null
        autoJob?.cancel(); autoJob = null
        autoFirmwareMode = null
        cancelNaturalProducer()
        bleManager.cancelStrokeEngineActivation()
        strokeEngineActivationSpeed.cancel()
        cancelLiveReplay(sendStop = false)
        liveRecordingSamples.clear()
        cancelVoiceSequence()
        _uiState.update {
            it.copy(
                liveRecordingState = LiveRecordingState.IDLE,
                naturalRunning = false,
                naturalStartPending = false,
                // A cancelled (unfinished) mode entry must be redone before the next start.
                naturalReady = it.naturalReady && naturalMachineFrame != null,
            )
        }
    }

    companion object {
        // Retour souple sur lever du doigt (au lieu d'un snap 5 ms brutal).
        private const val STREAM_RELEASE_MOVE_MS = 60
        private const val VOICE_LOG_TAG = "VOICE"
        private const val LIVE_RETURN_TICK_MS = 50L
        private const val LIVE_TARGET_TICK_MS = 16L

        /** First native contact within this distance of the current position follows directly. */
        internal const val LIVE_DIRECT_ENTRY_TOLERANCE_PERCENT = 5
        private const val LIVE_REPLAY_MIN_DELAY_MS = 20L
        private const val LIVE_REPLAY_MAX_DELAY_MS = 2_000L

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
        // Random sensation synced on the actuator turnarounds.
        private const val SENSATION_TURN_HYSTERESIS_MM = 2f
        private const val SENSATION_TURN_POLL_MS = 30L
        private const val SENSATION_TURN_TIMEOUT_FACTOR = 1.25f
        // Random sensation start range: -10..+10 on the -100..+100 scale.
        private const val RANDOM_SENSATION_START_MIN = 0.45f
        private const val RANDOM_SENSATION_START_MAX = 0.55f
        private const val TEASE_RANDOM_MAX_MS = 1_900L

        // Vitesse aléatoire par blocs (paliers).
        private const val SPEED_BLOCK_MIN_MS = 2_000L   // durée mini d'un palier
        private const val SPEED_BLOCK_MAX_MS = 6_000L   // durée maxi (~5-6 s)
        private const val SPEED_RATCHET = 0.5f          // plancher = 50 % du max déjà atteint
        private const val MIN_DEPTH_LIMIT_SPAN = 0.05f
        // Confirmation STOP after a remote session ends (covers an in-flight stroke command).
        private const val REMOTE_SESSION_END_CONFIRM_STOP_MS = 250L

        // Naturel (2.3.18).
        internal const val NATURAL_TICK_MS = 200L
        private const val NATURAL_AI_TIMEOUT_MS = NaturalAiPlannerFactory.COLD_LOAD_TIMEOUT_MS + 5_000L
        private const val NATURAL_AI_RETRY_MS = 60_000L
        private const val NATURAL_AI_ADDRESS_MAX_LENGTH = 200
        // Model pre-load (2.3.20).
        internal const val NATURAL_AI_PS_INTERVAL_MS = 15_000L
        internal const val NATURAL_AI_MONITOR_MS = 30_000L
        private const val NATURAL_AI_READ_TIMEOUT_MS = 15_000L
        private const val NATURAL_AI_LOG_TAG = "NATURAL_AI"
        private const val NATURAL_AI_MODEL_NAME = com.ossm.remote.natural.OllamaNaturalPlannerConfig.DEFAULT_MODEL
        /** Local bipolar sensation range for Naturel asymmetry: 0.5 +/- this. */
        internal const val NATURAL_LOCAL_SENSATION_INTENSITY = 0.3f
        private const val NATURAL_SETTINGS_REFRESH_DELTA = 0.05f
        private const val PERCENT_EPSILON = 0.0001f
    }
}
