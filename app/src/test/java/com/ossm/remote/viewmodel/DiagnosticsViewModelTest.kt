package com.ossm.remote.viewmodel

import com.ossm.remote.ble.BleManager
import com.ossm.remote.data.repository.DiagnosticsRepository
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.DiagnosticsLog
import com.ossm.remote.model.LogLevel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: DiagnosticsRepository
    private lateinit var bleManager: BleManager
    private lateinit var logsFlow: MutableSharedFlow<DiagnosticsLog>

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        bleManager = mockk(relaxed = true)
        logsFlow = MutableSharedFlow(extraBufferCapacity = 1_024)

        every { repository.logs } returns logsFlow
        every { repository.lastCommand } returns MutableStateFlow("")
        every { bleManager.connectionState } returns MutableStateFlow(BleConnectionState.Disconnected)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `diagnostics keeps every collected log`() = runTest {
        val viewModel = DiagnosticsViewModel(repository, bleManager)
        advanceUntilIdle()

        repeat(600) { index ->
            logsFlow.tryEmit(
                DiagnosticsLog(
                    level = LogLevel.INFO,
                    tag = "TEST",
                    message = "entry-$index"
                )
            )
        }
        advanceUntilIdle()

        assertEquals(600, viewModel.logs.value.size)
        assertEquals("entry-599", viewModel.logs.value.last().message)
    }
}
