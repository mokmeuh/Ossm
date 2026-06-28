package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ossm.remote.ble.BleManager
import com.ossm.remote.data.repository.ControlSafetySettingsRepository
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlin.random.Random
import kotlinx.coroutines.launch

enum class GuardedControl {
    SPEED,
    DEPTH
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

data class ControlUiState(
    val availablePatterns: List<OssmPattern> = KnownFallbackPatterns,
    val activePatternKey: String = KnownStrokeEnginePattern.key,
    val speed: Float = 0f,
    val depthMin: Float = 0.2f,
    val depthMax: Float = 0.8f,
    val sensation: Float = 0f,
    val progressiveMaxSpeed: Float = 1f,   // red ceiling for the Progressif ramp (0..1)
    val chaosAtMax: Boolean = false,       // when ramp hits max: random varied strokes
    val isRunning: Boolean = false,
    val speedGuard: SliderGuardUiState = SliderGuardUiState(enabled = true, thresholdPercent = 10),
    val depthGuard: SliderGuardUiState = SliderGuardUiState(enabled = true, thresholdPercent = 5),
    val pendingManualChange: PendingManualChange? = null
) {
    val activePattern: OssmPattern?
        get() = availablePatterns.firstOrNull { it.key == activePatternKey }

    val activePatternSupportsControls: Boolean
        get() = activePattern != null && activePattern!!.mode != PatternControlMode.LAUNCH_ONLY

    val activePatternUsesStrokeEngine: Boolean
        get() = activePattern?.mode == PatternControlMode.STROKE_ENGINE
}

@HiltViewModel
class ControlViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val safetySettingsRepository: ControlSafetySettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ControlUiState())
    val uiState: StateFlow<ControlUiState> = _uiState.asStateFlow()

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
    }

    fun activatePattern(patternKey: String) {
        val pattern = _uiState.value.availablePatterns.firstOrNull { it.key == patternKey } ?: return
        // Leaving any previous live/progressive session: stop the tickers.
        streamJob?.cancel(); streamJob = null
        streamTarget = 0f; streamSent = 0f
        progressiveJob?.cancel(); progressiveJob = null
        chaosJob?.cancel(); chaosJob = null
        // Sensation always starts at minimum, except Teasing/Pounding where neutral (50%) is the base.
        val defaultSensation = if (pattern.key == "teasingPounding") 0.5f else 0f
        _uiState.update {
            it.copy(
                activePatternKey = pattern.key,
                sensation = defaultSensation,
                progressiveMaxSpeed = 1f,
                pendingManualChange = null
            )
        }
        bleManager.sendCommand(OssmCommand.ActivatePattern(pattern))
        when (pattern.mode) {
            PatternControlMode.STREAMING -> { /* live pad drives it */ }
            PatternControlMode.PROGRESSIVE -> {
                // Start the auto speed-ramp at 1% once the firmware has settled into the mode.
                viewModelScope.launch {
                    kotlinx.coroutines.delay(1200)
                    startProgressiveRamp(1)
                }
            }
            else -> {
                // resetSettings*() on mode entry wipes settings — re-apply after the switch settles.
                viewModelScope.launch {
                    kotlinx.coroutines.delay(1200)
                    sendCurrentStrokeEngineCommand()
                }
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
        maybeApplyManualChange(
            control = GuardedControl.DEPTH,
            speed = current.speed,
            depthMin = min.coerceIn(0f, 1f),
            depthMax = max.coerceIn(0f, 1f)
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
        val mode = _uiState.value.activePattern?.mode ?: return
        if (mode == PatternControlMode.PROGRESSIVE || mode == PatternControlMode.STREAMING) return
        val v = value.coerceIn(0f, 1f)
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
        val mode = _uiState.value.activePattern?.mode ?: return
        if (mode == PatternControlMode.STREAMING) return
        val lo = min.coerceIn(0f, 1f)
        val hi = max.coerceIn(lo, 1f)
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
        streamJob?.cancel(); streamJob = null
        progressiveJob?.cancel(); progressiveJob = null
        chaosJob?.cancel(); chaosJob = null
        _uiState.update { it.copy(isRunning = false, speed = 0f, pendingManualChange = null) }
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

    // ---- Live streaming: cadence-based ramping sender ----
    // The firmware shortens any single move whose distance exceeds what's achievable in the
    // given time (streaming.cpp). So instead of pushing the raw finger target (which gets
    // truncated on fast flicks and stutters on every micro-move), a steady ticker chases the
    // target by small steps at a fixed cadence: smooth, and fast flicks still arrive (ramped
    // over a few ticks instead of one truncated jump).
    private var streamTarget: Float = 0f          // where the finger wants the actuator (0..100)
    private var streamSent: Float = 0f            // last position actually sent
    private var streamJob: Job? = null

    fun setStreamTarget(positionPercent: Int) {
        streamTarget = positionPercent.toFloat().coerceIn(0f, 100f)
    }

    fun setStreamActive(active: Boolean) {
        if (_uiState.value.activePattern?.mode != PatternControlMode.STREAMING) return
        if (active) {
            if (streamJob?.isActive == true) return
            streamJob = viewModelScope.launch {
                while (isActive) {
                    val diff = streamTarget - streamSent
                    if (kotlin.math.abs(diff) >= STREAM_MIN_DELTA) {
                        val step = diff.coerceIn(-STREAM_MAX_STEP, STREAM_MAX_STEP)
                        streamSent = (streamSent + step).coerceIn(0f, 100f)
                        bleManager.sendCommand(
                            OssmCommand.Stream(
                                positionPercent = streamSent.toInt(),
                                timeMs = STREAM_MOVE_MS
                            )
                        )
                    }
                    delay(STREAM_CADENCE_MS)
                }
            }
        } else {
            streamJob?.cancel()
            streamJob = null
        }
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
        val delta = when (control) {
            GuardedControl.SPEED -> kotlin.math.abs(current.speed - speed)
            GuardedControl.DEPTH -> maxOf(
                kotlin.math.abs(current.depthMin - depthMin),
                kotlin.math.abs(current.depthMax - depthMax)
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
        streamJob?.cancel()
        progressiveJob?.cancel()
        chaosJob?.cancel()
    }

    companion object {
        // Live streaming smoothing: more frequent, smaller overlapping moves reduce the
        // staircase feel while keeping finger/slider/actuator sync intact.
        // MOVE_MS > CADENCE means a new target is sent before the previous micro-move finishes.
        private const val STREAM_CADENCE_MS = 35L   // how often the ticker sends a move
        private const val STREAM_MOVE_MS = 115      // travel time per move
        private const val STREAM_MAX_STEP = 2.5f    // max % the actuator advances per tick
        private const val STREAM_MIN_DELTA = 0.15f  // ignore only truly tiny residual jitter

        // Progressif ramp timing: wait ≈ ONE full back-and-forth between increments.
        // period(ms) = K * strokeFraction / speed%, clamped [MIN, MAX].
        // K calibrated so that at 1% speed / 0.6 stroke the period is ~1 minute (the OSSM
        // genuinely strokes that slowly at 1%). Tunable if the per-stroke sync feels off.
        private const val PROGRESSIVE_K_MS = 100000f
        private const val PROGRESSIVE_MIN_MS = 400L
        private const val PROGRESSIVE_MAX_MS = 60000L
        // Sensation drives how many % the speed gains per stroke: min=+1, max=+10.
        private const val PROGRESSIVE_MAX_INCREMENT = 10
    }
}
