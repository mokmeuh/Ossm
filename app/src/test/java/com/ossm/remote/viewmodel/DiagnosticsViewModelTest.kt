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
    fun `diagnostics keeps the newest bounded window of collected logs`() = runTest {
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

        // 2.3.17: bounded journal (was unbounded and copied on the main thread per line).
        assertEquals(DIAGNOSTICS_LOG_LIMIT, viewModel.logs.value.size)
        assertEquals("entry-599", viewModel.logs.value.last().message)
        assertEquals("entry-${600 - DIAGNOSTICS_LOG_LIMIT}", viewModel.logs.value.first().message)
    }

    @Test
    fun `journal stays bounded so the main thread never copies an unbounded list`() {
        var logs = emptyList<DiagnosticsLog>()
        repeat(DIAGNOSTICS_LOG_LIMIT * 3) { index ->
            logs = appendBoundedDiagnosticsLog(logs, DiagnosticsLog(level = LogLevel.INFO, tag = "LIVE", message = "m$index"))
        }
        assertEquals(DIAGNOSTICS_LOG_LIMIT, logs.size)
        assertEquals("m${DIAGNOSTICS_LOG_LIMIT * 3 - 1}", logs.last().message)
        assertEquals("m${DIAGNOSTICS_LOG_LIMIT * 2}", logs.first().message)
    }
}
