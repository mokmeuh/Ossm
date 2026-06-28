package com.ossm.remote.viewmodel

import app.cash.turbine.test
import com.ossm.remote.ble.BleManager
import com.ossm.remote.data.repository.ControlSafetySettings
import com.ossm.remote.data.repository.ControlSafetySettingsRepository
import com.ossm.remote.data.repository.SliderGuardSettings
import com.ossm.remote.model.KnownFallbackPatterns
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
    private lateinit var viewModel: ControlViewModel
    private lateinit var patternsFlow: MutableStateFlow<List<OssmPattern>>
    private lateinit var settingsFlow: MutableStateFlow<ControlSafetySettings>

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

        every { bleManager.availablePatterns } returns patternsFlow
        every { safetySettingsRepository.settings } returns settingsFlow
        coEvery { safetySettingsRepository.setSpeedGuardEnabled(any()) } returns Unit
        coEvery { safetySettingsRepository.setDepthGuardEnabled(any()) } returns Unit

        viewModel = ControlViewModel(bleManager, safetySettingsRepository)
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
            assertEquals(0.2f, state.depthMin)
            assertEquals(0.8f, state.depthMax)
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
    fun `depth change over depth threshold requires confirmation`() = runTest {
        viewModel.requestDepthRangeChange(0.2f, 0.95f)
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

        viewModel.requestDepthRangeChange(0.2f, 0.95f)
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
