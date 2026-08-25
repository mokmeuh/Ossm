package com.ossm.remote.viewmodel

import app.cash.turbine.test
import com.ossm.remote.adaptive.AdaptiveEngine
import com.ossm.remote.audio.AudioLevelMonitor
import com.ossm.remote.audio.CadenceDetector
import com.ossm.remote.audio.CadenceReading
import com.ossm.remote.ble.BleManager
import com.ossm.remote.data.repository.ControlSafetySettings
import com.ossm.remote.data.repository.ControlSafetySettingsRepository
import com.ossm.remote.data.repository.SessionDefaults
import com.ossm.remote.data.repository.SliderGuardSettings
import com.ossm.remote.session.ControlSessionStore
import com.ossm.remote.data.repository.UserHabitsRepository
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.KnownAdaptivePattern
import com.ossm.remote.model.MachineState
import com.ossm.remote.model.KnownStrokeEnginePattern
import com.ossm.remote.model.LiveTargetProducer
import com.ossm.remote.model.OssmCommand
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.PatternControlMode
import com.ossm.remote.model.Preset
import io.mockk.coEvery
import io.mockk.coVerify
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
    private lateinit var audioLevelMonitor: AudioLevelMonitor
    private lateinit var cadenceDetector: CadenceDetector
    private lateinit var adaptiveEngine: AdaptiveEngine
    private lateinit var controlSessionStore: ControlSessionStore
    private lateinit var viewModel: ControlViewModel
    private lateinit var patternsFlow: MutableStateFlow<List<OssmPattern>>
    private lateinit var settingsFlow: MutableStateFlow<ControlSafetySettings>
    private lateinit var machineStateFlow: MutableStateFlow<MachineState>
    private lateinit var connectionStateFlow: MutableStateFlow<BleConnectionState>
    private lateinit var streamingReadyFlow: MutableStateFlow<Boolean>
    private lateinit var depthLimitMinFlow: MutableStateFlow<Float>
    private lateinit var depthLimitMaxFlow: MutableStateFlow<Float>
    private lateinit var listeningModeFlow: MutableStateFlow<Boolean>
    private lateinit var listeningCeilingFlow: MutableStateFlow<Float>
    private lateinit var listeningTargetSpeedFlow: MutableStateFlow<Boolean>
    private lateinit var listeningTargetDepthFlow: MutableStateFlow<Boolean>
    private lateinit var liveAccelerationFlow: MutableStateFlow<Int>
    private lateinit var audioLevelFlow: MutableStateFlow<Float>

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
        streamingReadyFlow = MutableStateFlow(false)
        depthLimitMinFlow = MutableStateFlow(0f)
        depthLimitMaxFlow = MutableStateFlow(1f)
        listeningModeFlow = MutableStateFlow(false)
        listeningCeilingFlow = MutableStateFlow(1f)
        // Défaut du dépôt après correction : aucune cible audio active par défaut.
        listeningTargetSpeedFlow = MutableStateFlow(false)
        listeningTargetDepthFlow = MutableStateFlow(false)
        liveAccelerationFlow = MutableStateFlow(33)
        audioLevelFlow = MutableStateFlow(0f)

        every { bleManager.availablePatterns } returns patternsFlow
        every { bleManager.machineState } returns machineStateFlow
        every { bleManager.streamingReady } returns streamingReadyFlow
        every { bleManager.connectionState } returns connectionStateFlow
        every { safetySettingsRepository.settings } returns settingsFlow
        coEvery { safetySettingsRepository.setSpeedGuardEnabled(any()) } returns Unit
        coEvery { safetySettingsRepository.setDepthGuardEnabled(any()) } returns Unit
        coEvery { safetySettingsRepository.setDepthLimits(any(), any()) } returns Unit
        coEvery { safetySettingsRepository.setListeningModeEnabled(any()) } returns Unit
        coEvery { safetySettingsRepository.setListeningCeiling(any()) } returns Unit
        coEvery { safetySettingsRepository.setListeningTargetSpeedEnabled(any()) } returns Unit
        coEvery { safetySettingsRepository.setListeningTargetDepthEnabled(any()) } returns Unit
        every { userHabitsRepository.sessionDefaults } returns flowOf(SessionDefaults(null, null, null))
        every { safetySettingsRepository.depthLimitMin } returns depthLimitMinFlow
        every { safetySettingsRepository.depthLimitMax } returns depthLimitMaxFlow
        every { safetySettingsRepository.listeningModeEnabled } returns listeningModeFlow
        every { safetySettingsRepository.listeningCeiling } returns listeningCeilingFlow
        every { safetySettingsRepository.listeningTargetSpeedEnabled } returns listeningTargetSpeedFlow
        every { safetySettingsRepository.listeningTargetDepthEnabled } returns listeningTargetDepthFlow
        every { safetySettingsRepository.liveInvertEnabled } returns MutableStateFlow(false)
        every { safetySettingsRepository.liveAccelerationPercent } returns liveAccelerationFlow
        coEvery { safetySettingsRepository.setLiveInvertEnabled(any()) } returns Unit
        coEvery { safetySettingsRepository.setLiveAccelerationPercent(any()) } returns Unit
        audioLevelMonitor = mockk(relaxed = true)
        every { audioLevelMonitor.level } returns audioLevelFlow
        cadenceDetector = mockk(relaxed = true)
        every { cadenceDetector.consume(any(), any()) } returns
            CadenceReading(peakDetected = false, peaksPerMinute = 0f, speedFraction = 0f)
        adaptiveEngine = mockk(relaxed = true)

        controlSessionStore = ControlSessionStore(
            bleManager,
            safetySettingsRepository,
            userHabitsRepository,
            audioLevelMonitor,
            cadenceDetector,
            adaptiveEngine
        )
        viewModel = ControlViewModel(controlSessionStore, audioLevelMonitor)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state exposes full depth limits and no listening target enabled by default`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(KnownStrokeEnginePattern.key, state.activePatternKey)
            assertEquals(0f, state.depthMin)
            assertEquals(1f, state.depthMax)
            assertEquals(0f, state.depthLimitMin)
            assertEquals(1f, state.depthLimitMax)
            assertFalse(state.listeningTargetSpeed)
            assertFalse(state.listeningTargetDepth)
            assertEquals(1f, state.listeningCeiling)
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
    fun `listening target speed defaults to off from repository flow and initial state`() = runTest {
        // Miroir du défaut DataStore (`?: false`) : la cible vitesse est absente
        // tant que l'utilisateur ne la coche pas.
        assertFalse(viewModel.uiState.value.listeningTargetSpeed)
        assertFalse(viewModel.uiState.value.listeningTargetDepth)
    }

    @Test
    fun `fresh view model exposes the pure control state default of no listening targets`() = runTest {
        // Un store fraîchement construit (collecteurs init pas encore exécutés) expose
        // le défaut brut de ControlUiState, avant la surcharge du flow dépôt.
        val freshStore = ControlSessionStore(
            bleManager,
            safetySettingsRepository,
            userHabitsRepository,
            audioLevelMonitor,
            cadenceDetector,
            adaptiveEngine
        )
        val fresh = ControlViewModel(freshStore, audioLevelMonitor)
        assertFalse(fresh.uiState.value.listeningTargetSpeed)
        assertFalse(fresh.uiState.value.listeningTargetDepth)
    }

    @Test
    fun `connection waits through preflight then enters stroke engine once when safe`() = runTest {
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "00:11:22:33:44:55")
        runCurrent()

        machineStateFlow.value = MachineState(state = "preflight")
        runCurrent()

        verify(exactly = 0) { bleManager.activatePatternVerified(any(), any(), any()) }

        machineStateFlow.value = MachineState(state = "idle")
        runCurrent()

        verify(exactly = 1) {
            bleManager.activatePatternVerified(
                patternId = any(),
                patternName = KnownStrokeEnginePattern.name,
                command = match { it.speed == 0f }
            )
        }

        machineStateFlow.value = MachineState(state = "menu")
        runCurrent()

        verify(exactly = 1) { bleManager.activatePatternVerified(any(), any(), any()) }
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
                command = match { it.speed == 0f }
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
    fun `listening dispatches only selected targets and never sensation`() = runTest {
        every { cadenceDetector.consume(any(), any()) } returns
            CadenceReading(peakDetected = true, peaksPerMinute = 60f, speedFraction = 0.5f)
        audioLevelFlow.value = 0.5f
        viewModel.setDepthLimits(0.2f, 0.8f)
        viewModel.setListeningMode(true)
        viewModel.setListeningTargetDepth(true)
        advanceUntilIdle()

        verify(exactly = 0) { bleManager.liveSet("speed", any()) }
        verify { bleManager.liveSetDepthRange(depthPercent = 50, strokePercent = 30) }
        verify(exactly = 0) { bleManager.liveSet("sensation", any()) }
        assertEquals(0.2f, viewModel.uiState.value.depthMin)
        assertEquals(0.8f, viewModel.uiState.value.depthMax)
        assertEquals(0.5f, viewModel.uiState.value.listeningDepthCurrent)
    }

    @Test
    fun `listening depth maps within the selected range without moving its handles`() = runTest {
        viewModel.requestDepthRangeChange(0.3f, 0.7f)
        advanceUntilIdle()
        every { cadenceDetector.consume(any(), any()) } returns
            CadenceReading(peakDetected = true, peaksPerMinute = 30f, speedFraction = 0.25f)
        audioLevelFlow.value = 0.5f
        viewModel.setListeningMode(true)
        viewModel.setListeningTargetDepth(true)
        advanceUntilIdle()

        with(viewModel.uiState.value) {
            assertEquals(0.3f, depthMin)
            assertEquals(0.7f, depthMax)
            assertEquals(0.4f, listeningDepthCurrent)
        }
        verify(exactly = 1) { bleManager.liveSetDepthRange(depthPercent = 40, strokePercent = 10) }
    }

    @Test
    fun `listening speed maps volume to ceiling-scaled speed`() = runTest {
        viewModel.setListeningMode(true)
        viewModel.setListeningTargetSpeed(true)
        audioLevelFlow.value = 0.7f
        advanceUntilIdle()

        assertEquals(0.7f, viewModel.uiState.value.speed)
        assertTrue(viewModel.uiState.value.isRunning)
        verify { bleManager.liveSet("speed", 70) }
    }

    @Test
    fun `listening volume respects selected maximum ceiling`() = runTest {
        // Niveau micro volontairement fort (1.0) : la vitesse = volume × plafond
        // → 1.0 × 0.6 = 60 %, jamais au-delà du plafond réglé.
        viewModel.setListeningCeiling(0.6f)
        viewModel.setListeningMode(true)
        viewModel.setListeningTargetSpeed(true)
        audioLevelFlow.value = 1f
        advanceUntilIdle()

        assertEquals(0.6f, viewModel.uiState.value.listeningCeiling)
        assertEquals(0.6f, viewModel.uiState.value.speed)
        coVerify { safetySettingsRepository.setListeningCeiling(0.6f) }
        verify { bleManager.liveSet("speed", 60) }
    }

    @Test
    fun `volume silence drives speed to zero and stops running`() = runTest {
        viewModel.setListeningMode(true)
        viewModel.setListeningTargetSpeed(true)
        audioLevelFlow.value = 0f
        advanceUntilIdle()

        assertEquals(0f, viewModel.uiState.value.speed)
        assertFalse(viewModel.uiState.value.isRunning)
        verify { bleManager.liveSet("speed", 0) }
    }

    @Test
    fun `cadence maps depth within the selected range while volume drives speed`() = runTest {
        every { cadenceDetector.consume(any(), any()) } returns
            CadenceReading(peakDetected = true, peaksPerMinute = 72f, speedFraction = 0.8f)
        viewModel.requestDepthRangeChange(0.2f, 0.6f)
        advanceUntilIdle()
        viewModel.setListeningMode(true)
        viewModel.setListeningTargetSpeed(true)
        viewModel.setListeningTargetDepth(true)
        audioLevelFlow.value = 1f
        advanceUntilIdle()

        with(viewModel.uiState.value) {
            // Les poignées ne bougent jamais ; la profondeur reste bornée par la plage.
            assertEquals(0.2f, depthMin)
            assertEquals(0.6f, depthMax)
            assertNotNull(listeningDepthCurrent)
            assertEquals(0.2f + 0.8f * (0.6f - 0.2f), listeningDepthCurrent!!, 0.001f)
        }
        verify { bleManager.liveSetDepthRange(depthPercent = 52, strokePercent = 32) }
        verify { bleManager.liveSet("speed", 100) }
    }

    @Test
    fun `sensation is never dispatched by the listening driver`() = runTest {
        viewModel.setListeningMode(true)
        viewModel.setListeningTargetSpeed(true)
        audioLevelFlow.value = 0.75f
        advanceUntilIdle()

        verify(exactly = 0) { bleManager.liveSet("sensation", any()) }
        verify(exactly = 0) { bleManager.liveSet("sensation", 75) }
    }

    @Test
    fun `disabling listening restores the manual speed snapshot`() = runTest {
        viewModel.applyPreset(
            Preset(
                name = "Manual 40%",
                patternKey = KnownStrokeEnginePattern.key,
                patternName = KnownStrokeEnginePattern.name,
                speed = 0.4f,
                depthMin = 0f,
                depthMax = 1f
            )
        )
        advanceUntilIdle()

        viewModel.setListeningMode(true)
        viewModel.setListeningTargetSpeed(true)
        audioLevelFlow.value = 0.8f
        advanceUntilIdle()

        assertEquals(0.8f, viewModel.uiState.value.speed)
        assertTrue(viewModel.uiState.value.isRunning)

        viewModel.setListeningMode(false)
        advanceUntilIdle()

        assertEquals(0.4f, viewModel.uiState.value.speed)
        assertTrue(viewModel.uiState.value.isRunning)
    }

    @Test
    fun `disabling speed target mid-listening restores manual speed`() = runTest {
        viewModel.applyPreset(
            Preset(
                name = "Manual 40%",
                patternKey = KnownStrokeEnginePattern.key,
                patternName = KnownStrokeEnginePattern.name,
                speed = 0.4f,
                depthMin = 0f,
                depthMax = 1f
            )
        )
        advanceUntilIdle()

        viewModel.setListeningMode(true)
        viewModel.setListeningTargetSpeed(true)
        viewModel.setListeningTargetDepth(true)
        audioLevelFlow.value = 0.8f
        advanceUntilIdle()

        assertEquals(0.8f, viewModel.uiState.value.speed)

        viewModel.setListeningTargetSpeed(false)
        advanceUntilIdle()

        // La profondeur continue d'être pilotée, mais la vitesse revient au manuel.
        assertEquals(0.4f, viewModel.uiState.value.speed)
        assertTrue(viewModel.uiState.value.isRunning)
    }

    @Test
    fun `pendingManualChange pauses the listening driver`() = runTest {
        viewModel.setListeningMode(true)
        viewModel.setListeningTargetSpeed(true)
        audioLevelFlow.value = 0.5f
        advanceUntilIdle()
        verify { bleManager.liveSet("speed", 50) }

        // Monter la vitesse manuelle au-dessus de la vitesse pilotée déclenche la garde.
        viewModel.requestSpeedChange(0.9f)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.pendingManualChange)

        // Le nouveau niveau sonore ne doit plus atteindre le BLE tant que la garde est ouverte.
        audioLevelFlow.value = 1f
        advanceUntilIdle()
        verify(exactly = 1) { bleManager.liveSet("speed", 50) }
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
    fun `pattern list from ble updates state`() = runTest {
        patternsFlow.value = listOf(
            KnownStrokeEnginePattern,
            OssmPattern("simplePenetration", "Simple Penetration", PatternControlMode.LAUNCH_ONLY)
        )
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.availablePatterns.size)
        assertTrue(viewModel.uiState.value.availablePatterns.any { it.key == KnownAdaptivePattern.key })    }

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
        advanceUntilIdle()

        viewModel.activatePattern(pattern.key)
        advanceUntilIdle()

        assertEquals(pattern.key, viewModel.uiState.value.activePatternKey)
        assertFalse(viewModel.uiState.value.activePatternSupportsControls)
        verify {
            bleManager.activatePatternVerified(
                patternId = 0,
                patternName = pattern.name,
                command = any()
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
    fun `live stream drag forwards every callback to the shared target scheduler`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = true
        machineStateFlow.value = MachineState(speed = 100)
        advanceUntilIdle()

        viewModel.setStreamPositionDrag(10)
        assertEquals(10, viewModel.uiState.value.streamPositionPercent)
        viewModel.setStreamPositionDrag(20)
        viewModel.setStreamPositionDrag(30)

        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(10, timeMs = 60)) }
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(20, timeMs = 60)) }
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(30, timeMs = 60)) }
    }

    @Test
    fun `fast live stream drag preserves the latest finger target for central coalescing`() = runTest {
        patternsFlow.value = listOf(OssmPattern("live", "Live", PatternControlMode.STREAMING))
        streamingReadyFlow.value = true
        machineStateFlow.value = MachineState(speed = 100)
        advanceUntilIdle()

        viewModel.setStreamPositionDrag(0)
        viewModel.setStreamPositionDrag(100)

        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(0, timeMs = 60)) }
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(100, timeMs = 60)) }
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

        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(20, timeMs = 60)) }
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(80, timeMs = 60)) }
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(0, timeMs = 60)) }
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

        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(10, timeMs = 60)) }
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stream(20, timeMs = 60)) }
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

        assertEquals(65, viewModel.uiState.value.streamPositionPercent)
        assertFalse(viewModel.uiState.value.isRunning)
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stop) }
        verify(exactly = 0) { bleManager.emergencyStop() }
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
