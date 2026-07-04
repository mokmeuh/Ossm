package com.ossm.remote.viewmodel

import app.cash.turbine.test
import com.ossm.remote.audio.AudioLevelMonitor
import com.ossm.remote.ble.BleManager
import com.ossm.remote.data.repository.ControlSafetySettings
import com.ossm.remote.data.repository.ControlSafetySettingsRepository
import com.ossm.remote.data.repository.SessionDefaults
import com.ossm.remote.data.repository.SliderGuardSettings
import com.ossm.remote.data.repository.UserHabitsRepository
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.MachineState
import com.ossm.remote.model.KnownStrokeEnginePattern
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
import kotlinx.coroutines.test.resetMain
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
    private lateinit var viewModel: ControlViewModel
    private lateinit var patternsFlow: MutableStateFlow<List<OssmPattern>>
    private lateinit var settingsFlow: MutableStateFlow<ControlSafetySettings>
    private lateinit var machineStateFlow: MutableStateFlow<MachineState>
    private lateinit var streamingReadyFlow: MutableStateFlow<Boolean>

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
        streamingReadyFlow = MutableStateFlow(false)

        every { bleManager.availablePatterns } returns patternsFlow
        every { bleManager.machineState } returns machineStateFlow
        every { bleManager.streamingReady } returns streamingReadyFlow
        every { bleManager.connectionState } returns MutableStateFlow(com.ossm.remote.model.BleConnectionState.Disconnected)
        every { safetySettingsRepository.settings } returns settingsFlow
        coEvery { safetySettingsRepository.setSpeedGuardEnabled(any()) } returns Unit
        coEvery { safetySettingsRepository.setDepthGuardEnabled(any()) } returns Unit
        every { userHabitsRepository.sessionDefaults } returns flowOf(SessionDefaults(null, null, null))
        every { safetySettingsRepository.listeningModeEnabled } returns MutableStateFlow(false)
        audioLevelMonitor = mockk(relaxed = true)
        every { audioLevelMonitor.level } returns MutableStateFlow(0f)

        viewModel = ControlViewModel(bleManager, safetySettingsRepository, userHabitsRepository, audioLevelMonitor)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state defaults to stroke engine safe range`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(KnownStrokeEnginePattern.key, state.activePatternKey)
            assertEquals(0f, state.depthMin)
            assertEquals(1f, state.depthMax)
            assertEquals(10, state.speedGuard.thresholdPercent)
            assertEquals(5, state.depthGuard.thresholdPercent)
            cancelAndIgnoreRemainingEvents()
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
        verify { bleManager.sendCommand(match { it is OssmCommand.ActivatePattern && it.pattern.key == pattern.key }) }
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
}
