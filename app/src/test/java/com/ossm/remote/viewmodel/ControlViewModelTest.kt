package com.ossm.remote.viewmodel

import app.cash.turbine.test
import com.ossm.remote.ble.BleManager
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bleManager: BleManager
    private lateinit var viewModel: ControlViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        bleManager = mockk(relaxed = true)
        viewModel = ControlViewModel(bleManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is zero speed`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(0f, state.speed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setSpeed updates state and sends command`() = runTest {
        viewModel.setSpeed(0.75f)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0.75f, state.speed)
        assertEquals(0, state.activePatternId) // pattern reset
        verify { bleManager.sendCommand(any()) }
    }

    @Test
    fun `setDepth updates state`() = runTest {
        viewModel.setDepth(0.5f)
        assertEquals(0.5f, viewModel.uiState.value.depth)
    }

    @Test
    fun `setStrokeLength updates state`() = runTest {
        viewModel.setStrokeLength(0.3f)
        assertEquals(0.3f, viewModel.uiState.value.strokeLength)
    }

    @Test
    fun `setSensation updates state`() = runTest {
        viewModel.setSensation(0.9f)
        assertEquals(0.9f, viewModel.uiState.value.sensation)
    }

    @Test
    fun `stop resets speed and sends emergency stop`() = runTest {
        viewModel.setSpeed(0.8f)
        viewModel.stop()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0f, viewModel.uiState.value.speed)
        assertFalse(viewModel.uiState.value.isRunning)
        verify(atLeast = 1) { bleManager.emergencyStop() }
    }

    @Test
    fun `activatePattern sets activePatternId`() = runTest {
        viewModel.activatePattern(2)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.activePatternId)
        assertTrue(viewModel.uiState.value.isRunning)
        verify { bleManager.sendCommand(any()) }
    }

    @Test
    fun `applyPreset updates all values`() = runTest {
        viewModel.applyPreset(0.6f, 0.8f, 0.7f, 0.5f)
        with(viewModel.uiState.value) {
            assertEquals(0.6f, speed)
            assertEquals(0.8f, depth)
            assertEquals(0.7f, strokeLength)
            assertEquals(0.5f, sensation)
            assertEquals(0, activePatternId)
        }
    }

    @Test
    fun `setSpeed clears active pattern`() = runTest {
        viewModel.activatePattern(1)
        viewModel.setSpeed(0.5f)
        assertEquals(0, viewModel.uiState.value.activePatternId)
    }
}
