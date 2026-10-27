package com.ossm.remote.viewmodel

import app.cash.turbine.test
import com.ossm.remote.ble.BleManager
import com.ossm.remote.data.repository.ControlSafetySettings
import com.ossm.remote.data.repository.ControlSafetySettingsRepository
import com.ossm.remote.data.repository.SessionDefaults
import com.ossm.remote.data.repository.SliderGuardSettings
import com.ossm.remote.session.ControlSessionStore
import com.ossm.remote.data.repository.UserHabitsRepository
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.MachineState
import com.ossm.remote.model.KnownStrokeEnginePattern
import com.ossm.remote.model.KnownStreamingPattern
import com.ossm.remote.model.LiveTargetProducer
import com.ossm.remote.model.OssmCommand
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.PatternControlMode
import com.ossm.remote.model.Preset
import com.ossm.remote.viewmodel.RandomPresetTarget
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bleManager: BleManager
    private lateinit var safetySettingsRepository: ControlSafetySettingsRepository
    private lateinit var userHabitsRepository: UserHabitsRepository
    private lateinit var controlSessionStore: ControlSessionStore
    private lateinit var viewModel: ControlViewModel
    private lateinit var patternsFlow: MutableStateFlow<List<OssmPattern>>
    private lateinit var settingsFlow: MutableStateFlow<ControlSafetySettings>
    private lateinit var machineStateFlow: MutableStateFlow<MachineState>
    private lateinit var connectionStateFlow: MutableStateFlow<BleConnectionState>
    private lateinit var postSetupReadyFlow: MutableStateFlow<Boolean>
    private lateinit var streamingReadyFlow: MutableStateFlow<Boolean>
    private lateinit var depthLimitMinFlow: MutableStateFlow<Float>
    private lateinit var depthLimitMaxFlow: MutableStateFlow<Float>
    private lateinit var liveAccelerationFlow: MutableStateFlow<Int>
    private lateinit var precisionOverridesFlow: MutableStateFlow<Map<String, Boolean>>

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        bleManager = mockk(relaxed = true)
        safetySettingsRepository = mockk(relaxed = true)
        patternsFlow = MutableStateFlow(KnownFallbackPatterns)
        settingsFlow = MutableStateFlow(
            ControlSafetySettings(
                speed = SliderGuardSettings(enabled = true, thresholdPercent = 10),
                depth = SliderGuardSettings(enabled = true, thresholdPercent = 5)
            )
        )

        userHabitsRepository = mockk(relaxed = true)
        machineStateFlow = MutableStateFlow(MachineState())
        connectionStateFlow = MutableStateFlow(BleConnectionState.Disconnected)
        postSetupReadyFlow = MutableStateFlow(true)
        streamingReadyFlow = MutableStateFlow(false)
        depthLimitMinFlow = MutableStateFlow(0f)
        depthLimitMaxFlow = MutableStateFlow(1f)
        liveAccelerationFlow = MutableStateFlow(33)
        precisionOverridesFlow = MutableStateFlow(emptyMap())

        every { bleManager.availablePatterns } returns patternsFlow
        every { bleManager.machineState } returns machineStateFlow
        every { bleManager.postSetupReady } returns postSetupReadyFlow
        every { bleManager.streamingReady } returns streamingReadyFlow
        every { bleManager.connectionState } returns connectionStateFlow
        every { safetySettingsRepository.settings } returns settingsFlow
        coEvery { safetySettingsRepository.setSpeedGuardEnabled(any()) } returns Unit
        coEvery { safetySettingsRepository.setDepthGuardEnabled(any()) } returns Unit
        coEvery { safetySettingsRepository.setDepthLimits(any(), any()) } returns Unit
        every { userHabitsRepository.sessionDefaults } returns flowOf(SessionDefaults(null, null, null))
        every { safetySettingsRepository.depthLimitMin } returns depthLimitMinFlow
        every { safetySettingsRepository.depthLimitMax } returns depthLimitMaxFlow
        every { safetySettingsRepository.liveInvertEnabled } returns MutableStateFlow(false)
        every { safetySettingsRepository.liveAccelerationPercent } returns liveAccelerationFlow
        every { safetySettingsRepository.precisionOverrides } returns precisionOverridesFlow
        coEvery { safetySettingsRepository.setPrecisionEnabled(any(), any()) } returns Unit
        coEvery { safetySettingsRepository.setLiveInvertEnabled(any()) } returns Unit
        coEvery { safetySettingsRepository.setLiveAccelerationPercent(any()) } returns Unit

        controlSessionStore = ControlSessionStore(
            bleManager,
            safetySettingsRepository,
            userHabitsRepository
        )
        viewModel = ControlViewModel(controlSessionStore)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `precision setter delegates without optimistic state before persisted snapshot`() = runTest {
        viewModel.setPrecisionEnabled("simpleStroke", false)
        advanceUntilIdle()
        coVerify(exactly = 1) { safetySettingsRepository.setPrecisionEnabled("simpleStroke", false) }
        assertEquals(emptyMap<String, Boolean>(), viewModel.uiState.value.precisionOverrides)
        precisionOverridesFlow.value = mapOf("simpleStroke" to false, "streaming" to true)
        advanceUntilIdle()
        assertEquals(
            mapOf("simpleStroke" to false, "streaming" to true),
            viewModel.uiState.value.precisionOverrides
        )
        assertEquals(0f, viewModel.uiState.value.speed)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state exposes full depth limits and guards by default`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(KnownStrokeEnginePattern.key, state.activePatternKey)
            assertEquals(0f, state.depthMin)
            assertEquals(1f, state.depthMax)
            assertEquals(0f, state.depthLimitMin)
            assertEquals(1f, state.depthLimitMax)
            assertEquals(10, state.speedGuard.thresholdPercent)
            assertEquals(5, state.depthGuard.thresholdPercent)
            assertEquals(33, state.liveAccelerationPercent)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Live acceleration clamps persists and sends while connected in Live`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()

        viewModel.setLiveAcceleration(4)
        advanceUntilIdle()

        assertEquals(10, viewModel.uiState.value.liveAccelerationPercent)
        coVerify { safetySettingsRepository.setLiveAccelerationPercent(10) }
        verify { bleManager.setLiveAcceleration(10) }
    }

    @Test
    fun `Live recording release returns slowly and replay needs an explicit action`() = runTest {
        patternsFlow.value = KnownFallbackPatterns
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "AA:BB:CC:DD:EE:FF")
        viewModel.activatePattern(KnownStreamingPattern.key)
        streamingReadyFlow.value = true
        advanceUntilIdle()

        viewModel.toggleLiveRecording()
        assertEquals(LiveRecordingState.ARMED, viewModel.uiState.value.liveRecordingState)

        viewModel.setStreamPositionDrag(45)
        advanceTimeBy(25)
        viewModel.setStreamPositionDrag(49)
        assertEquals(LiveRecordingState.RECORDING, viewModel.uiState.value.liveRecordingState)

        // Normal release keeps the recording but follows the same slow return contract as
        // every other Live radial release; it must not auto-start replay.
        viewModel.endLiveTouch()
        runCurrent()

        assertEquals(LiveRecordingState.RECORDED, viewModel.uiState.value.liveRecordingState)
        clearMocks(bleManager)

        // Replay is an explicit second action on the existing record control.
        viewModel.toggleLiveRecording()
        runCurrent()
        assertEquals(LiveRecordingState.PLAYING, viewModel.uiState.value.liveRecordingState)
        advanceTimeBy(25)
        runCurrent()
        verify { bleManager.sendCommand(match<OssmCommand.Stream> { it.positionPercent == 45 }) }
        verify { bleManager.sendCommand(match<OssmCommand.Stream> { it.positionPercent == 49 }) }

        viewModel.toggleLiveRecording()
        assertEquals(LiveRecordingState.IDLE, viewModel.uiState.value.liveRecordingState)
        verify(atLeast = 1) { bleManager.sendCommand(OssmCommand.Stop) }
    }

    @Test
    fun `explicit STOP cancels recording without starting replay`() = runTest {
        patternsFlow.value = KnownFallbackPatterns
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "AA:BB:CC:DD:EE:FF")
        viewModel.activatePattern(KnownStreamingPattern.key)
        streamingReadyFlow.value = true
        advanceUntilIdle()

        viewModel.toggleLiveRecording()
        viewModel.setStreamPositionDrag(45)
        advanceTimeBy(25)
        viewModel.setStreamPositionDrag(49)
        assertEquals(LiveRecordingState.RECORDING, viewModel.uiState.value.liveRecordingState)

        viewModel.stopLiveTouch()
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        assertEquals(LiveRecordingState.IDLE, viewModel.uiState.value.liveRecordingState)
        assertFalse(viewModel.uiState.value.isRunning)
        verify(atLeast = 1) { bleManager.sendCommand(OssmCommand.Stop) }
    }

    @Test
    fun `scalar callback captured before STOP cannot rearm speed or sensation`() = runTest {
        patternsFlow.value = KnownFallbackPatterns
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "AA:BB:CC:DD:EE:FF")
        viewModel.activatePattern(KnownStrokeEnginePattern.key)
        advanceUntilIdle()

        val capturedEpoch = viewModel.uiState.value.liveGestureEpoch
        val baselineSensation = viewModel.uiState.value.sensation
        viewModel.stop()
        viewModel.setSpeedLiveAtEpoch(0.8f, capturedEpoch)
        viewModel.setSensationLiveAtEpoch(0.9f, capturedEpoch)

        assertEquals(0f, viewModel.uiState.value.speed)
        assertEquals(baselineSensation, viewModel.uiState.value.sensation, 0.0001f)
        verify(exactly = 0) { bleManager.liveSet(any(), any(), any()) }
    }

    @Test
    fun `connection waits through post setup and preflight then enters stroke engine once when safe`() = runTest {
        postSetupReadyFlow.value = false
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "00:11:22:33:44:55")
        runCurrent()

        machineStateFlow.value = MachineState(state = "preflight")
        runCurrent()

        verify(exactly = 0) { bleManager.activatePatternVerified(any(), any(), any(), any()) }

        machineStateFlow.value = MachineState(state = "idle")
        runCurrent()

        verify(exactly = 0) { bleManager.activatePatternVerified(any(), any(), any(), any()) }

        postSetupReadyFlow.value = true
        runCurrent()

        verify(exactly = 1) {
            bleManager.activatePatternVerified(
                patternId = any(),
                patternName = KnownStrokeEnginePattern.name,
                command = match { it.speed == 0f },
                onFinished = any()
            )
        }

        machineStateFlow.value = MachineState(state = "menu")
        runCurrent()

        verify(exactly = 1) { bleManager.activatePatternVerified(any(), any(), any(), any()) }
    }

    @Test
    fun `manual mode activation is held until post setup readiness`() = runTest {
        val livePattern = OssmPattern("live", "Live", PatternControlMode.STREAMING)
        patternsFlow.value = listOf(livePattern)
        postSetupReadyFlow.value = false
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "00:11:22:33:44:55")
        runCurrent()

        viewModel.activatePattern(livePattern.key)
        runCurrent()

        verify(exactly = 0) { bleManager.enterStreaming(any()) }

        postSetupReadyFlow.value = true
        runCurrent()

        verify(exactly = 1) { bleManager.enterStreaming(any()) }
    }

    @Test
    fun `connection fallback enters stroke engine when no machine state notification arrives`() = runTest {
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "00:11:22:33:44:55")
        runCurrent()

        advanceTimeBy(6_001)
        runCurrent()

        verify(exactly = 1) {
            bleManager.activatePatternVerified(
                patternId = any(),
                patternName = KnownStrokeEnginePattern.name,
                command = match { it.speed == 0f },
                onFinished = any()
            )
        }
    }

    @Test
    fun `committed sensation change propagates one scalar to stroke engine`() = runTest {
        viewModel.requestSensationChange(0.42f)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0.42f, state.sensation)
        verify {
            bleManager.sendCommand(match {
                it is OssmCommand.UpdateStrokeEngine &&
                    kotlin.math.abs(it.command.sensation - 0.42f) < 0.001f
            })
        }
    }

    @Test
    fun `live sensation change updates scalar and sends legacy parameter`() = runTest {
        viewModel.setSensationLive(0.439f)
        advanceUntilIdle()

        assertEquals(0.439f, viewModel.uiState.value.sensation)
        verify { bleManager.liveSet("sensation", 43) }
    }

    @Test
    fun `random mode exposes speed and depth only`() = runTest {
        patternsFlow.value = listOf(OssmPattern("liveOnly", "Live Only", PatternControlMode.STREAMING))
        advanceUntilIdle()

        viewModel.setRandomMode(RandomTarget.SPEED, true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.randSpeed)
        assertTrue(viewModel.uiState.value.anyRandomActive)
        assertFalse(viewModel.uiState.value.randDepthMin)
        assertFalse(viewModel.uiState.value.randDepthMax)

        viewModel.setDepthRandom(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.randDepthMin)
        assertTrue(viewModel.uiState.value.randDepthMax)
        viewModel.setRandomMode(RandomTarget.SPEED, false)
        viewModel.setDepthRandom(false)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.anyRandomActive)
    }

    @Test
    fun `random presets edit save and apply depth with optional speed`() = runTest {
        settingsFlow.value = settingsFlow.value.copy(
            speed = SliderGuardSettings(enabled = false, thresholdPercent = 10),
            depth = SliderGuardSettings(enabled = false, thresholdPercent = 5),
        )
        advanceUntilIdle()
        viewModel.setSpeedLive(0.42f)
        viewModel.editRandomPreset(RandomPresetTarget.DEPTH, 1, 20, 60, saveSpeedToo = true)
        advanceUntilIdle()

        val edited = viewModel.uiState.value.randomDepthPresets[1]
        assertEquals(20, edited.minPercent)
        assertEquals(60, edited.maxPercent)
        assertEquals(42, edited.savedSpeedPercent)

        viewModel.applyRandomPreset(RandomPresetTarget.DEPTH, 1)
        advanceUntilIdle()

        assertEquals(0.2f, viewModel.uiState.value.depthMin)
        assertEquals(0.6f, viewModel.uiState.value.depthMax)
        assertEquals(0.42f, viewModel.uiState.value.speed)
    }

    @Test
    fun `single preset save keeps its range and captures current speed`() = runTest {
        settingsFlow.value = settingsFlow.value.copy(
            speed = SliderGuardSettings(enabled = false, thresholdPercent = 10),
        )
        advanceUntilIdle()
        viewModel.setSpeedLive(0.37f)
        viewModel.saveRandomPreset(RandomPresetTarget.SPEED, 2)
        advanceUntilIdle()

        val saved = viewModel.uiState.value.randomSpeedPresets[2]
        assertEquals(50, saved.minPercent)
        assertEquals(75, saved.maxPercent)
        assertEquals(37, saved.savedSpeedPercent)
    }

    @Test
    fun `auto random depth sliders update limits without immediate stroke command`() = runTest {
        patternsFlow.value = listOf(OssmPattern("autoRandom", "Auto Random", PatternControlMode.AUTO_RANDOM))
        advanceUntilIdle()

        viewModel.setDepthLive(0.2f, 0.7f)
        advanceUntilIdle()

        assertEquals(0.2f, viewModel.uiState.value.depthMin)
        assertEquals(0.7f, viewModel.uiState.value.depthMax)
        verify(exactly = 0) { bleManager.liveSet("depth", any()) }
        verify(exactly = 0) { bleManager.liveSet("stroke", any()) }

        viewModel.requestDepthRangeChange(0.25f, 0.74f)
        advanceUntilIdle()

        assertEquals(0.25f, viewModel.uiState.value.depthMin)
        assertEquals(0.74f, viewModel.uiState.value.depthMax)
        verify(exactly = 0) { bleManager.sendCommand(match { it is OssmCommand.UpdateStrokeEngine }) }
    }

    @Test
    fun `live depth change dispatches one coherent depth and stroke range`() = runTest {
        viewModel.setDepthLive(0.2f, 0.8f)
        advanceUntilIdle()

        verify { bleManager.liveSetDepthRange(depthPercent = 80, strokePercent = 60) }
        verify(exactly = 0) { bleManager.liveSet("depth", any()) }
        verify(exactly = 0) { bleManager.liveSet("stroke", any()) }
    }

    @Test
    fun `depth mutations clamp to the configured depth limits`() = runTest {
        viewModel.setDepthLimits(0.2f, 0.8f)
        advanceUntilIdle()

        viewModel.setDepthLive(0f, 1f)
        advanceUntilIdle()

        assertEquals(0.2f, viewModel.uiState.value.depthMin)
        assertEquals(0.8f, viewModel.uiState.value.depthMax)
        verify { bleManager.liveSetDepthRange(depthPercent = 80, strokePercent = 60) }
    }

    @Test
    fun `depth limits order crossed handles and preserve a five percent span`() = runTest {
        viewModel.setDepthLimits(0.8f, 0.2f)
        advanceUntilIdle()

        assertEquals(0.2f, viewModel.uiState.value.depthLimitMin, 0.001f)
        assertEquals(0.8f, viewModel.uiState.value.depthLimitMax, 0.001f)
        coVerify { safetySettingsRepository.setDepthLimits(0.2f, 0.8f) }

        viewModel.setDepthLimits(0.4f, 0.41f)
        advanceUntilIdle()

        assertEquals(0.4f, viewModel.uiState.value.depthLimitMin, 0.001f)
        assertEquals(0.45f, viewModel.uiState.value.depthLimitMax, 0.001f)
        coVerify {
            safetySettingsRepository.setDepthLimits(
                0.4f,
                match { kotlin.math.abs(it - 0.45f) < 0.001f }
            )
        }
    }

    @Test
    fun `live depth change orders crossed handles`() = runTest {
        settingsFlow.value = ControlSafetySettings(
            speed = SliderGuardSettings(enabled = true, thresholdPercent = 10),
            depth = SliderGuardSettings(enabled = false, thresholdPercent = 5)
        )
        advanceUntilIdle()

        viewModel.setDepthLive(0.8f, 0.2f)
        advanceUntilIdle()

        assertEquals(0.2f, viewModel.uiState.value.depthMin)
        assertEquals(0.8f, viewModel.uiState.value.depthMax)
        verify { bleManager.liveSetDepthRange(depthPercent = 80, strokePercent = 60) }
    }

    @Test
    fun `progressive speed live updates state without live speed command`() = runTest {
        patternsFlow.value = listOf(OssmPattern("progressive", "Progressif", PatternControlMode.PROGRESSIVE))
        advanceUntilIdle()

        viewModel.setSpeedLive(0.33f)
        advanceUntilIdle()

        assertEquals(0.33f, viewModel.uiState.value.speed)
        verify(exactly = 0) { bleManager.liveSet("speed", any()) }
    }

    @Test
    fun `latest live speed during stroke activation waits for activation completion`() = runTest {
        settingsFlow.value = settingsFlow.value.copy(
            speed = SliderGuardSettings(enabled = false, thresholdPercent = 10),
        )
        postSetupReadyFlow.value = false
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "00:11:22:33:44:55")
        advanceUntilIdle()

        var activationFinished: ((Boolean) -> Unit)? = null
        every {
            bleManager.activatePatternVerified(
                patternId = any(),
                patternName = any(),
                command = any(),
                onFinished = any(),
            )
        } answers {
            activationFinished = arg(3)
            Unit
        }

        viewModel.activatePattern(KnownStrokeEnginePattern.key)
        advanceUntilIdle()

        viewModel.setSpeedLive(0.02f)
        viewModel.setSpeedLive(0.07f)
        advanceUntilIdle()
        verify(exactly = 0) { bleManager.liveSet("speed", any()) }

        postSetupReadyFlow.value = true
        advanceUntilIdle()
        assertNotNull(activationFinished)

        activationFinished!!(true)
        advanceUntilIdle()

        assertEquals(0.07f, viewModel.uiState.value.speed)
        verify(exactly = 1) {
            bleManager.sendCommand(match {
                it is OssmCommand.UpdateStrokeEngine &&
                    kotlin.math.abs(it.command.speed - 0.07f) < 0.001f
            })
        }
        verify(exactly = 0) { bleManager.liveSet("speed", any()) }
    }

    @Test
    fun `committed speed during stroke activation is replayed only after completion`() = runTest {
        settingsFlow.value = settingsFlow.value.copy(
            speed = SliderGuardSettings(enabled = false, thresholdPercent = 10),
        )
        postSetupReadyFlow.value = false
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "00:11:22:33:44:55")
        advanceUntilIdle()

        var activationFinished: ((Boolean) -> Unit)? = null
        every {
            bleManager.activatePatternVerified(
                patternId = any(),
                patternName = any(),
                command = any(),
                onFinished = any(),
            )
        } answers {
            activationFinished = arg(3)
            Unit
        }

        viewModel.activatePattern(KnownStrokeEnginePattern.key)
        advanceUntilIdle()
        viewModel.requestSpeedChange(0.01f)
        advanceUntilIdle()

        verify(exactly = 0) {
            bleManager.sendCommand(match { it is OssmCommand.UpdateStrokeEngine })
        }

        postSetupReadyFlow.value = true
        advanceUntilIdle()
        assertNotNull(activationFinished)
        activationFinished!!(true)
        advanceUntilIdle()

        verify(exactly = 1) {
            bleManager.sendCommand(match {
                it is OssmCommand.UpdateStrokeEngine &&
                    kotlin.math.abs(it.command.speed - 0.01f) < 0.001f
            })
        }
    }

    @Test
    fun `progressive controls retain speed zero and sensation until verified activation`() = runTest {
        settingsFlow.value = settingsFlow.value.copy(
            speed = SliderGuardSettings(enabled = false, thresholdPercent = 10),
            depth = SliderGuardSettings(enabled = false, thresholdPercent = 5),
        )
        patternsFlow.value = listOf(
            OssmPattern("progressive", "Progressif", PatternControlMode.PROGRESSIVE),
        )
        postSetupReadyFlow.value = false
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "00:11:22:33:44:55")
        advanceUntilIdle()

        var activationFinished: ((Boolean) -> Unit)? = null
        every {
            bleManager.activatePatternVerified(
                patternId = any(),
                patternName = any(),
                command = any(),
                onFinished = any(),
            )
        } answers {
            activationFinished = arg(3)
            Unit
        }

        viewModel.activatePattern("progressive")
        advanceUntilIdle()
        viewModel.requestSpeedChange(0.01f)
        viewModel.setDepthLive(0.25f, 0.65f)
        viewModel.requestSensationChange(0.77f)
        advanceUntilIdle()

        verify(exactly = 0) {
            bleManager.sendCommand(match { it is OssmCommand.UpdateStrokeEngine })
        }

        postSetupReadyFlow.value = true
        advanceUntilIdle()
        assertNotNull(activationFinished)
        activationFinished!!(true)
        runCurrent()

        assertEquals(0.01f, viewModel.uiState.value.speed)
        assertEquals(0.25f, viewModel.uiState.value.depthMin)
        assertEquals(0.65f, viewModel.uiState.value.depthMax)
        assertEquals(0.77f, viewModel.uiState.value.sensation)
        verify(exactly = 1) {
            bleManager.sendCommand(match {
                it is OssmCommand.UpdateStrokeEngine &&
                    kotlin.math.abs(it.command.speed - 0.01f) < 0.001f &&
                    kotlin.math.abs(it.command.depthMin - 0.25f) < 0.001f &&
                    kotlin.math.abs(it.command.depthMax - 0.65f) < 0.001f &&
                    kotlin.math.abs(it.command.sensation - 0.77f) < 0.001f
            })
        }
    }

    @Test
    fun `progressive pending zero remains stopped when activation completes`() = runTest {
        settingsFlow.value = settingsFlow.value.copy(
            speed = SliderGuardSettings(enabled = false, thresholdPercent = 10),
        )
        patternsFlow.value = listOf(
            OssmPattern("progressive", "Progressif", PatternControlMode.PROGRESSIVE),
        )
        postSetupReadyFlow.value = false
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "00:11:22:33:44:55")
        advanceUntilIdle()

        var activationFinished: ((Boolean) -> Unit)? = null
        every {
            bleManager.activatePatternVerified(
                patternId = any(),
                patternName = any(),
                command = any(),
                onFinished = any(),
            )
        } answers {
            activationFinished = arg(3)
            Unit
        }

        viewModel.activatePattern("progressive")
        advanceUntilIdle()
        viewModel.requestSpeedChange(0f)
        advanceUntilIdle()

        postSetupReadyFlow.value = true
        advanceUntilIdle()
        assertNotNull(activationFinished)
        activationFinished!!(true)
        runCurrent()

        assertEquals(0f, viewModel.uiState.value.speed)
        assertFalse(viewModel.uiState.value.isRunning)
        verify(exactly = 0) {
            bleManager.sendCommand(match { it is OssmCommand.UpdateStrokeEngine })
        }
    }

    @Test
    fun `progressive zero after activation stops instead of restarting at one percent`() = runTest {
        settingsFlow.value = settingsFlow.value.copy(
            speed = SliderGuardSettings(enabled = false, thresholdPercent = 10),
        )
        patternsFlow.value = listOf(
            OssmPattern("progressive", "Progressif", PatternControlMode.PROGRESSIVE),
        )
        postSetupReadyFlow.value = false
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "00:11:22:33:44:55")
        advanceUntilIdle()

        var activationFinished: ((Boolean) -> Unit)? = null
        every {
            bleManager.activatePatternVerified(
                patternId = any(),
                patternName = any(),
                command = any(),
                onFinished = any(),
            )
        } answers {
            activationFinished = arg(3)
            Unit
        }

        viewModel.activatePattern("progressive")
        advanceUntilIdle()
        postSetupReadyFlow.value = true
        advanceUntilIdle()
        assertNotNull(activationFinished)

        // The verified activation still starts from the safety-zero command. The
        // intentional 1% Progressive default must not reach the BLE seam before
        // the verified callback confirms setup.
        assertEquals(0f, viewModel.uiState.value.speed)
        assertFalse(viewModel.uiState.value.isRunning)
        verify(exactly = 0) {
            bleManager.sendCommand(match { it is OssmCommand.UpdateStrokeEngine })
        }

        activationFinished!!(true)
        runCurrent()
        assertEquals(0.01f, viewModel.uiState.value.speed, 0.0001f)
        assertTrue(viewModel.uiState.value.isRunning)
        verify(exactly = 1) {
            bleManager.sendCommand(match {
                it is OssmCommand.UpdateStrokeEngine &&
                    kotlin.math.abs(it.command.speed - 0.01f) < 0.0001f
            })
        }

        viewModel.requestSpeedChange(0f)
        runCurrent()
        advanceTimeBy(65_000)
        runCurrent()

        assertEquals(0f, viewModel.uiState.value.speed)
        assertFalse(viewModel.uiState.value.isRunning)
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stop) }
        // The activation's initial positive command is the only Update; the cancelled
        // Progressive producer must not emit a later 1%/ramp command.
        verify(exactly = 1) {
            bleManager.sendCommand(match { it is OssmCommand.UpdateStrokeEngine })
        }
    }

    @Test
    fun `stop invalidates a queued stroke activation completion`() = runTest {
        settingsFlow.value = settingsFlow.value.copy(
            speed = SliderGuardSettings(enabled = false, thresholdPercent = 10),
        )
        postSetupReadyFlow.value = false
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "00:11:22:33:44:55")
        advanceUntilIdle()

        var activationFinished: ((Boolean) -> Unit)? = null
        every {
            bleManager.activatePatternVerified(
                patternId = any(),
                patternName = any(),
                command = any(),
                onFinished = any(),
            )
        } answers {
            activationFinished = arg(3)
            Unit
        }

        viewModel.activatePattern(KnownStrokeEnginePattern.key)
        advanceUntilIdle()
        viewModel.requestSpeedChange(0.01f)
        postSetupReadyFlow.value = true
        advanceUntilIdle()
        assertNotNull(activationFinished)

        viewModel.stop()
        advanceUntilIdle()
        activationFinished!!(true)
        runCurrent()

        assertEquals(0f, viewModel.uiState.value.speed)
        assertFalse(viewModel.uiState.value.isRunning)
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stop) }
        verify(exactly = 0) {
            bleManager.sendCommand(match { it is OssmCommand.UpdateStrokeEngine })
        }
    }

    @Test
    fun `pattern list from ble updates state`() = runTest {
        patternsFlow.value = listOf(
            KnownStrokeEnginePattern,
            OssmPattern("simplePenetration", "Simple Penetration", PatternControlMode.LAUNCH_ONLY)
        )
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.availablePatterns.size)
        assertTrue(viewModel.uiState.value.availablePatterns.any { it.key == "simplePenetration" })
    }

    @Test
    fun `small speed change sends stroke engine command immediately`() = runTest {
        viewModel.requestSpeedChange(0.04f)
        advanceUntilIdle()

        assertEquals(0.04f, viewModel.uiState.value.speed)
        verify { bleManager.sendCommand(match { it is OssmCommand.UpdateStrokeEngine }) }
    }

    @Test
    fun `speed change over speed threshold requires confirmation`() = runTest {
        viewModel.requestSpeedChange(0.2f)
        advanceUntilIdle()

        assertEquals(0f, viewModel.uiState.value.speed)
        assertEquals(GuardedControl.SPEED, viewModel.uiState.value.pendingManualChange?.control)
    }

    @Test
    fun `live speed increase requires confirmation before it reaches the machine`() = runTest {
        viewModel.setSpeedLive(0.2f)
        advanceUntilIdle()

        assertEquals(0f, viewModel.uiState.value.speed)
        assertEquals(GuardedControl.SPEED, viewModel.uiState.value.pendingManualChange?.control)
        verify(exactly = 0) { bleManager.liveSet("speed", any()) }

        viewModel.confirmPendingManualChange(skipForSession = true, neverAskAgain = false)
        advanceUntilIdle()

        assertEquals(0.2f, viewModel.uiState.value.speed)
        verify { bleManager.sendCommand(match { it is OssmCommand.UpdateStrokeEngine }) }
    }

    @Test
    fun `activating another pattern clears pending speed change and stops motion`() = runTest {
        viewModel.setSpeedLive(0.2f)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.pendingManualChange)

        viewModel.activatePattern(KnownStrokeEnginePattern.key)
        advanceUntilIdle()

        assertEquals(0f, viewModel.uiState.value.speed)
        assertFalse(viewModel.uiState.value.isRunning)
        assertNull(viewModel.uiState.value.pendingManualChange)
    }

    @Test
    fun `depth increase over threshold requires confirmation, decrease does not`() = runTest {
        // Réduire la plage (moins profond) passe sans confirmation.
        viewModel.requestDepthRangeChange(0.3f, 0.6f)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.pendingManualChange)
        assertEquals(0.6f, viewModel.uiState.value.depthMax)

        // Aller nettement plus profond demande confirmation.
        viewModel.requestDepthRangeChange(0.3f, 0.9f)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.pendingManualChange)
        assertEquals(GuardedControl.DEPTH, viewModel.uiState.value.pendingManualChange?.control)
    }

    @Test
    fun `session bypass for speed does not bypass depth`() = runTest {
        viewModel.requestSpeedChange(0.2f)
        advanceUntilIdle()
        viewModel.confirmPendingManualChange(skipForSession = true, neverAskAgain = false)
        advanceUntilIdle()

        viewModel.requestSpeedChange(0.35f)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.pendingManualChange)

        viewModel.requestDepthRangeChange(0.3f, 0.6f)
        advanceUntilIdle()
        viewModel.requestDepthRangeChange(0.3f, 0.9f)
        advanceUntilIdle()
        assertEquals(GuardedControl.DEPTH, viewModel.uiState.value.pendingManualChange?.control)
    }

    @Test
    fun `never ask again disables only the pending control`() = runTest {
        viewModel.requestSpeedChange(0.2f)
        advanceUntilIdle()

        viewModel.confirmPendingManualChange(skipForSession = false, neverAskAgain = true)
        advanceUntilIdle()

        coVerify { safetySettingsRepository.setSpeedGuardEnabled(false) }
        coVerify(exactly = 0) { safetySettingsRepository.setDepthGuardEnabled(any()) }
    }

    @Test
    fun `activating launch only pattern switches active key`() = runTest {
        val pattern = OssmPattern("simplePenetration", "Simple Penetration", PatternControlMode.LAUNCH_ONLY)
        patternsFlow.value = listOf(KnownStrokeEnginePattern, pattern)
        postSetupReadyFlow.value = false
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "00:11:22:33:44:55")
        advanceUntilIdle()

        viewModel.activatePattern(pattern.key)
        advanceUntilIdle()

        assertEquals(pattern.key, viewModel.uiState.value.activePatternKey)
        assertFalse(viewModel.uiState.value.activePatternSupportsControls)
        verify(exactly = 0) { bleManager.activatePatternVerified(any(), any(), any(), any()) }

        postSetupReadyFlow.value = true
        advanceUntilIdle()

        verify {
            bleManager.activatePatternVerified(
                patternId = 0,
                patternName = pattern.name,
                command = any(),
                onFinished = any()
            )
        }
    }

    @Test
    fun `apply preset restores saved pattern and range`() = runTest {
        val preset = Preset(
            name = "Range preset",
            patternKey = KnownStrokeEnginePattern.key,
            patternName = KnownStrokeEnginePattern.name,
            speed = 0.5f,
            depthMin = 0.25f,
            depthMax = 0.75f
        )

        viewModel.applyPreset(preset)
        advanceUntilIdle()

        with(viewModel.uiState.value) {
            assertEquals(0.5f, speed)
            assertEquals(0.25f, depthMin)
            assertEquals(0.75f, depthMax)
            assertTrue(isRunning)
        }
    }

    @Test
    fun `live stream position sends one clamped command only when ready`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = true
        machineStateFlow.value = MachineState(speed = 0)
        advanceUntilIdle()

        viewModel.setStreamPosition(130)
        advanceUntilIdle()

        assertEquals(100, viewModel.uiState.value.streamPositionPercent)
        verify(exactly = 0) { bleManager.liveSet("speed", any()) }
        verify { bleManager.sendCommand(OssmCommand.Stream(positionPercent = 100, timeMs = 60)) }
    }

    @Test
    fun `live stream position does not dispatch before streaming is ready`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = false
        advanceUntilIdle()

        viewModel.setStreamPosition(50)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.streamPositionPercent)
        verify(exactly = 0) { bleManager.sendCommand(match { it is OssmCommand.Stream }) }
    }

    @Test
    fun `live stream drag starts with a bounded target and retains the latest desired callback`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = true
        machineStateFlow.value = MachineState(speed = 100)
        advanceUntilIdle()

        viewModel.setStreamPositionDrag(10)
        assertEquals(5, viewModel.uiState.value.streamPositionPercent)
        viewModel.setStreamPositionDrag(20)
        viewModel.setStreamPositionDrag(30)

        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(5, timeMs = 60)) }
    }

    @Test
    fun `anchored Live gesture follows fast back and forth directly without touch slew`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = true
        machineStateFlow.value = MachineState(speed = 100)
        advanceUntilIdle()

        // Anchor = current position (0): pointer-down emits no motion.
        viewModel.setStreamPositionDrag(0)
        verify(exactly = 0) { bleManager.sendCommand(match { it is OssmCommand.Stream }) }

        // Rapid anchor <-> far round trips: every sample is forwarded immediately, no 80 %/s cap.
        viewModel.setStreamPositionDrag(100)
        assertEquals(100, viewModel.uiState.value.streamPositionPercent)
        viewModel.setStreamPositionDrag(0)
        assertEquals(0, viewModel.uiState.value.streamPositionPercent)
        viewModel.setStreamPositionDrag(90)
        assertEquals(90, viewModel.uiState.value.streamPositionPercent)

        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(100, timeMs = 60)) }
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(0, timeMs = 60)) }
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(90, timeMs = 60)) }
        verify(exactly = 0) { bleManager.sendCommand(OssmCommand.Stream(5, timeMs = 60)) }
    }

    @Test
    fun `entry ramp only joins a far first contact then the gesture follows directly`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = true
        machineStateFlow.value = MachineState(speed = 100)
        advanceUntilIdle()

        // A first contact far from the current position (e.g. TalkBack setProgress) is ramped.
        viewModel.setStreamPositionDrag(60)
        assertEquals(5, viewModel.uiState.value.streamPositionPercent)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(60, viewModel.uiState.value.streamPositionPercent)

        // Once joined, the same gesture is no longer slewed.
        viewModel.setStreamPositionDrag(0)
        assertEquals(0, viewModel.uiState.value.streamPositionPercent)
        viewModel.setStreamPositionDrag(100)
        assertEquals(100, viewModel.uiState.value.streamPositionPercent)
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(100, timeMs = 60)) }
    }

    @Test
    fun `new touch during the slow return anchors at the current position without a jump`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = true
        machineStateFlow.value = MachineState(speed = 100)
        advanceUntilIdle()

        viewModel.setStreamPositionDrag(0)
        viewModel.setStreamPositionDrag(80)
        viewModel.endLiveTouch()
        advanceTimeBy(1_000)
        runCurrent()
        val returning = viewModel.uiState.value.streamPositionPercent
        assertTrue("return is slow, got $returning", returning in 40..79)

        // The pad anchors the new touch at the current logical position: no Stream is emitted
        // and the return stops there (no physical jump, no continued return).
        clearMocks(bleManager, answers = false)
        viewModel.setStreamPositionDrag(returning)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(returning, viewModel.uiState.value.streamPositionPercent)
        verify(exactly = 0) { bleManager.sendCommand(match { it is OssmCommand.Stream }) }

        // Moving away adds depth beyond that anchor, directly.
        viewModel.setStreamPositionDrag(returning + 15)
        assertEquals(returning + 15, viewModel.uiState.value.streamPositionPercent)
        verify(exactly = 1) {
            bleManager.sendCommand(OssmCommand.Stream(returning + 15, timeMs = 60))
        }
    }

    @Test
    fun `STOP during a direct Live gesture cuts every later target`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = true
        machineStateFlow.value = MachineState(speed = 100)
        advanceUntilIdle()

        viewModel.setStreamPositionDrag(0)
        viewModel.setStreamPositionDrag(70)
        viewModel.stopLiveTouch()
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stop) }
        val stoppedAt = viewModel.uiState.value.streamPositionPercent
        assertFalse(viewModel.uiState.value.isRunning)

        clearMocks(bleManager, answers = false)
        advanceTimeBy(10_000)
        runCurrent()
        verify(exactly = 0) { bleManager.sendCommand(match { it is OssmCommand.Stream }) }
        assertEquals(stoppedAt, viewModel.uiState.value.streamPositionPercent)

        // The next gesture starts un-engaged again: a far first sample is ramped, not direct.
        viewModel.setStreamPositionDrag(0)
        viewModel.setStreamPositionDrag(90)
        assertTrue(viewModel.uiState.value.streamPositionPercent <= stoppedAt + 5)
    }

    @Test
    fun `live stream drag forwards reversal samples to central safety scheduling`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = true
        machineStateFlow.value = MachineState(speed = 100)
        advanceUntilIdle()

        viewModel.setStreamPositionDrag(20)
        viewModel.setStreamPositionDrag(80)
        viewModel.setStreamPositionDrag(0)

        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(5, timeMs = 60)) }
    }

    @Test
    fun `live stream drag updates state but is gated before streaming is ready`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = false
        advanceUntilIdle()

        viewModel.setStreamPositionDrag(50)
        assertEquals(50, viewModel.uiState.value.streamPositionPercent)
        advanceUntilIdle()
        verify(exactly = 0) { bleManager.sendCommand(match { it is OssmCommand.Stream }) }

        streamingReadyFlow.value = true
        advanceUntilIdle()
        verify(exactly = 0) { bleManager.sendCommand(match { it is OssmCommand.Stream }) }
    }

    @Test
    fun `live stream release forwards final target without changing callback types`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = true
        machineStateFlow.value = MachineState(speed = 100)
        advanceUntilIdle()

        viewModel.setStreamPositionDrag(10)
        viewModel.setStreamPositionDrag(20)
        viewModel.setStreamPosition(80)

        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(5, timeMs = 60)) }
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(80, timeMs = 60)) }
    }

    @Test
    fun `ending Live touch immediately stops motion without entering emergency state`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = true
        machineStateFlow.value = MachineState(speed = 100)
        advanceUntilIdle()

        viewModel.setStreamPositionDrag(65)
        viewModel.stopLiveTouch()

        assertEquals(5, viewModel.uiState.value.streamPositionPercent)
        assertFalse(viewModel.uiState.value.isRunning)
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stop) }
        verify(exactly = 0) { bleManager.emergencyStop() }
    }

    @Test
    fun `normal Live release returns slowly to zero without STOP`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = true
        machineStateFlow.value = MachineState(speed = 100)
        advanceUntilIdle()

        viewModel.setStreamPositionDrag(40)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(40, viewModel.uiState.value.streamPositionPercent)

        viewModel.endLiveTouch()
        runCurrent()
        verify(exactly = 0) { bleManager.sendCommand(OssmCommand.Stop) }
        advanceTimeBy(500)
        runCurrent()
        val midway = viewModel.uiState.value.streamPositionPercent
        assertTrue("return is gradual (≤ 20 %/s), got $midway", midway in 1..39)

        // 40 points at ≤ 20 %/s needs at least two seconds; give it ample time to finish.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, viewModel.uiState.value.streamPositionPercent)
        assertFalse(viewModel.uiState.value.isRunning)
        verify(exactly = 0) { bleManager.sendCommand(OssmCommand.Stop) }
        verify { bleManager.sendCommand(match<OssmCommand.Stream> { it.positionPercent == 0 }) }
    }

    @Test
    fun `STOP during the slow Live return cancels it and no target follows`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = true
        machineStateFlow.value = MachineState(speed = 100)
        advanceUntilIdle()

        viewModel.setStreamPositionDrag(60)
        advanceTimeBy(3_000)
        runCurrent()
        viewModel.endLiveTouch()
        advanceTimeBy(300)
        runCurrent()

        viewModel.stop()
        val stoppedAt = viewModel.uiState.value.streamPositionPercent
        clearMocks(bleManager, answers = false)
        advanceTimeBy(10_000)
        runCurrent()

        verify(exactly = 0) { bleManager.sendCommand(match { it is OssmCommand.Stream }) }
        assertEquals(stoppedAt, viewModel.uiState.value.streamPositionPercent)
    }

    @Test
    fun `range wizard captures the last committed live position`() = runTest {
        streamingReadyFlow.value = true
        advanceUntilIdle()

        viewModel.startRangeWizard()
        advanceUntilIdle()
        viewModel.setStreamPosition(80)
        viewModel.captureRangePoint()
        viewModel.setStreamPosition(20)
        viewModel.captureRangePoint()
        advanceUntilIdle()

        assertEquals(0.2f, viewModel.uiState.value.depthMin, 0.001f)
        assertEquals(0.8f, viewModel.uiState.value.depthMax, 0.001f)
        assertNull(viewModel.uiState.value.rangeWizard)
        verify {
            bleManager.sendCommand(
                OssmCommand.Stream(80, timeMs = 60, producer = LiveTargetProducer.RANGE_WIZARD)
            )
        }
    }

}
