package com.ossm.remote.session

import com.ossm.remote.data.db.SessionEntity
import com.ossm.remote.data.db.epochDayOf
import com.ossm.remote.data.repository.SessionStatsRepository
import com.ossm.remote.viewmodel.ControlUiState
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStatsTrackerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fixture(): Triple<MutableStateFlow<ControlUiState>, SessionStatsRepository, ControlSessionStore> {
        val uiStateFlow = MutableStateFlow(ControlUiState())
        val store = mockk<ControlSessionStore>()
        every { store.uiState } returns uiStateFlow
        val repo = mockk<SessionStatsRepository>(relaxed = true)
        return Triple(uiStateFlow, repo, store)
    }

    @Test
    fun `start and stop records one session with pattern and depth captured`() = runTest {
        val (uiStateFlow, repo, store) = fixture()
        SessionStatsTracker(store, repo)
        runCurrent()

        uiStateFlow.value = ControlUiState(
            speed = 0.5f,
            isRunning = true,
            activePatternKey = "simpleStroke",
            depthMin = 0.2f,
            depthMax = 0.8f
        )
        runCurrent()
        uiStateFlow.value = uiStateFlow.value.copy(isRunning = false)
        runCurrent()
        runCurrent()

        val captured = slot<SessionEntity>()
        coVerify(exactly = 1) { repo.recordSession(capture(captured)) }
        val entity = captured.captured
        assertEquals("simpleStroke", entity.patternKey)
        assertEquals("Simple Stroke", entity.patternName)
        assertEquals(0.2f, entity.depthMin, 0f)
        assertEquals(0.8f, entity.depthMax, 0f)
        assertTrue(entity.complete)
        assertTrue(entity.durationMillis >= 0)
        assertEquals(epochDayOf(entity.startTimeMillis), entity.dateEpochDay)
        assertTrue(entity.endTimeMillis >= entity.startTimeMillis)
    }

    @Test
    fun `pause keeps the session open and does not record`() = runTest {
        val (uiStateFlow, repo, store) = fixture()
        SessionStatsTracker(store, repo)
        runCurrent()

        uiStateFlow.value = ControlUiState(speed = 0.5f, isRunning = true)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        uiStateFlow.value = uiStateFlow.value.copy(isRunning = false, isPaused = true)
        runCurrent()
        coVerify(exactly = 0) { repo.recordSession(any()) }

        uiStateFlow.value = uiStateFlow.value.copy(isRunning = true, isPaused = false)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        uiStateFlow.value = uiStateFlow.value.copy(isRunning = false)
        runCurrent()
        runCurrent()

        // Une seule session malgré la pause : le temps en pause est exclu de la durée.
        coVerify(exactly = 1) { repo.recordSession(any()) }
    }

    @Test
    fun `sampling computes average and max speed percent`() = runTest {
        val (uiStateFlow, repo, store) = fixture()
        SessionStatsTracker(store, repo)
        runCurrent()

        uiStateFlow.value = ControlUiState(speed = 0.5f, sensation = 0.8f, isRunning = true)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        uiStateFlow.value = uiStateFlow.value.copy(isRunning = false)
        runCurrent()
        runCurrent()

        val captured = slot<SessionEntity>()
        coVerify(exactly = 1) { repo.recordSession(capture(captured)) }
        assertEquals(50f, captured.captured.avgSpeedPercent, 0.01f)
        assertEquals(50f, captured.captured.maxSpeedPercent, 0.01f)
        assertEquals(80f, captured.captured.avgSensationPercent, 0.01f)
    }

    @Test
    fun `pattern change starts a new session`() = runTest {
        val (uiStateFlow, repo, store) = fixture()
        SessionStatsTracker(store, repo)
        runCurrent()

        uiStateFlow.value = ControlUiState(speed = 0.5f, isRunning = true, activePatternKey = "simpleStroke")
        runCurrent()
        uiStateFlow.value = uiStateFlow.value.copy(isRunning = false, activePatternKey = "progressive")
        runCurrent()
        runCurrent()

        uiStateFlow.value = ControlUiState(speed = 0.6f, isRunning = true, activePatternKey = "progressive")
        runCurrent()
        uiStateFlow.value = uiStateFlow.value.copy(isRunning = false)
        runCurrent()
        runCurrent()

        val slots = mutableListOf<SessionEntity>()
        coVerify(exactly = 2) { repo.recordSession(capture(slots)) }
        assertEquals("simpleStroke", slots[0].patternKey)
        assertEquals("progressive", slots[1].patternKey)
        assertEquals("Progressif", slots[1].patternName)
    }
}
