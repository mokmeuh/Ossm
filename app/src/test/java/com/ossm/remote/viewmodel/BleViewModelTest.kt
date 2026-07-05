package com.ossm.remote.viewmodel

import app.cash.turbine.test
import com.ossm.remote.ble.BleManager
import com.ossm.remote.model.BleConnectionState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bleManager: BleManager
    private lateinit var viewModel: BleViewModel

    private val connectionFlow = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        bleManager = mockk(relaxed = true)
        every { bleManager.connectionState } returns connectionFlow
        every { bleManager.scannedDevices } returns MutableStateFlow(emptyList())
        viewModel = BleViewModel(bleManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial connection state is disconnected`() = runTest {
        viewModel.connectionState.test {
            assertEquals(BleConnectionState.Disconnected, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startScan delegates to bleManager`() {
        viewModel.startScan()
        verify { bleManager.startScan() }
    }

    @Test
    fun `connect delegates to bleManager`() {
        viewModel.connect("AA:BB:CC:DD:EE:FF")
        verify { bleManager.connect("AA:BB:CC:DD:EE:FF") }
    }

    @Test
    fun `disconnect delegates to bleManager`() {
        viewModel.disconnect()
        verify { bleManager.disconnect() }
    }

    @Test
    fun `emergencyStop delegates to bleManager`() {
        viewModel.emergencyStop()
        verify { bleManager.emergencyStop() }
    }

    @Test
    fun `state reflects bleManager state changes`() = runTest {
        viewModel.connectionState.test {
            assertEquals(BleConnectionState.Disconnected, awaitItem())
            connectionFlow.value = BleConnectionState.Scanning
            assertEquals(BleConnectionState.Scanning, awaitItem())
            connectionFlow.value = BleConnectionState.Connected("OSSM", "AA:BB:CC:DD:EE:FF")
            val connected = awaitItem()
            assertTrue(connected is BleConnectionState.Connected)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
