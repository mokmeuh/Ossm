package com.ossm.remote.session

import com.ossm.remote.ble.BleManager
import com.ossm.remote.ble.DepthByLoadState
import com.ossm.remote.data.repository.ControlSafetySettings
import com.ossm.remote.data.repository.ControlSafetySettingsRepository
import com.ossm.remote.data.repository.SessionDefaults
import com.ossm.remote.data.repository.SliderGuardSettings
import com.ossm.remote.data.repository.UserHabitsRepository
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.KnownStreamingPattern
import com.ossm.remote.model.KnownStrokeEnginePattern
import com.ossm.remote.model.MachineState
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Profondeur selon la résistance : le Store ne décide jamais de la profondeur (le firmware s'en
 * charge) ; il active le mode seulement quand c'est possible et le coupe à chaque sortie sûre.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlSessionStoreDepthByLoadTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bleManager: BleManager
    private lateinit var repository: ControlSafetySettingsRepository
    private lateinit var store: ControlSessionStore
    private lateinit var connectionStateFlow: MutableStateFlow<BleConnectionState>
    private lateinit var depthByLoadFlow: MutableStateFlow<DepthByLoadState>
    private lateinit var sensitivityFlow: MutableStateFlow<Int>
    private lateinit var floorFlow: MutableStateFlow<Int>

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        bleManager = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        val userHabitsRepository = mockk<UserHabitsRepository>(relaxed = true)
        connectionStateFlow = MutableStateFlow(BleConnectionState.Disconnected)
        depthByLoadFlow = MutableStateFlow(DepthByLoadState(present = true))
        sensitivityFlow = MutableStateFlow(50)
        floorFlow = MutableStateFlow(40)

        every { bleManager.availablePatterns } returns MutableStateFlow(KnownFallbackPatterns)
        every { bleManager.machineState } returns MutableStateFlow(MachineState())
        every { bleManager.postSetupReady } returns MutableStateFlow(true)
        every { bleManager.streamingReady } returns MutableStateFlow(false)
        every { bleManager.connectionState } returns connectionStateFlow
        every { bleManager.depthByLoadState } returns depthByLoadFlow
        every { repository.settings } returns MutableStateFlow(
            ControlSafetySettings(
                speed = SliderGuardSettings(enabled = false, thresholdPercent = 10),
                depth = SliderGuardSettings(enabled = false, thresholdPercent = 5),
            )
        )
        every { repository.depthLimitMin } returns MutableStateFlow(0f)
        every { repository.depthLimitMax } returns MutableStateFlow(1f)
        every { repository.liveInvertEnabled } returns MutableStateFlow(false)
        every { repository.liveAccelerationPercent } returns MutableStateFlow(33)
        every { repository.precisionOverrides } returns MutableStateFlow(emptyMap())
        every { repository.depthByLoadSensitivityPercent } returns sensitivityFlow
        every { repository.depthByLoadFloorPercent } returns floorFlow
        coEvery { repository.setDepthByLoadSensitivityPercent(any()) } returns Unit
        coEvery { repository.setDepthByLoadFloorPercent(any()) } returns Unit
        every { userHabitsRepository.sessionDefaults } returns flowOf(SessionDefaults(null, null, null))
        every { userHabitsRepository.patternOrder } returns flowOf(emptyList())

        store = ControlSessionStore(
            bleManager = bleManager,
            safetySettingsRepository = repository,
            userHabitsRepository = userHabitsRepository,
        )
        testDispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun runCurrent() = testDispatcher.scheduler.runCurrent()

    private fun connectOnStrokeEngine() {
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "AA:BB:CC:DD:EE:FF")
        runCurrent()
        store.activatePattern(KnownStrokeEnginePattern.key)
        runCurrent()
    }

    @Test
    fun `saved settings are read at start and the mode itself is never restored`() {
        sensitivityFlow.value = 72
        floorFlow.value = 25
        runCurrent()

        assertEquals(DepthByLoadSettings(sensitivityPercent = 72, floorPercent = 25), store.depthByLoadSettings.value)
        verify(exactly = 0) { bleManager.writeDepthByLoad(true, any(), any()) }
    }

    @Test
    fun `enabling on a stroke engine pattern writes the saved settings`() {
        connectOnStrokeEngine()

        store.setDepthByLoadEnabled(true)

        verify(exactly = 1) { bleManager.writeDepthByLoad(true, 50, 40) }
    }

    @Test
    fun `enabling is ignored when the machine is not connected`() {
        store.activatePattern(KnownStrokeEnginePattern.key)
        runCurrent()

        store.setDepthByLoadEnabled(true)

        verify(exactly = 0) { bleManager.writeDepthByLoad(true, any(), any()) }
    }

    @Test
    fun `enabling is ignored outside the stroke engine`() {
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "AA:BB:CC:DD:EE:FF")
        runCurrent()
        store.activatePattern(KnownStreamingPattern.key)
        runCurrent()

        store.setDepthByLoadEnabled(true)

        verify(exactly = 0) { bleManager.writeDepthByLoad(true, any(), any()) }
    }

    @Test
    fun `disabling always reaches the machine layer`() {
        store.setDepthByLoadEnabled(false)

        verify(exactly = 1) { bleManager.disableDepthByLoad() }
    }

    @Test
    fun `changing a setting while active rewrites the configuration and persists it`() {
        connectOnStrokeEngine()
        depthByLoadFlow.value = DepthByLoadState(present = true, enabled = true)

        store.setDepthByLoadSensitivity(80)
        runCurrent()

        verify(exactly = 1) { bleManager.writeDepthByLoad(true, 80, 40) }
        coVerify(exactly = 1) { repository.setDepthByLoadSensitivityPercent(80) }
        assertEquals(80, store.depthByLoadSettings.value.sensitivityPercent)
    }

    @Test
    fun `changing a setting while inactive only persists it`() {
        connectOnStrokeEngine()

        store.setDepthByLoadFloor(30)
        runCurrent()

        verify(exactly = 0) { bleManager.writeDepthByLoad(any(), any(), any()) }
        coVerify(exactly = 1) { repository.setDepthByLoadFloorPercent(30) }
    }

    @Test
    fun `settings are clamped into the protocol range`() {
        store.setDepthByLoadSensitivity(500)
        store.setDepthByLoadFloor(2)
        runCurrent()

        assertEquals(DepthByLoadSettings(sensitivityPercent = 100, floorPercent = 10), store.depthByLoadSettings.value)
    }

    @Test
    fun `STOP turns the mode off`() {
        connectOnStrokeEngine()

        store.stop()

        verify(atLeast = 1) { bleManager.disableDepthByLoad() }
    }

    @Test
    fun `emergency stop turns the mode off`() {
        connectOnStrokeEngine()

        store.emergencyStop()

        verify(atLeast = 1) { bleManager.disableDepthByLoad() }
    }

    @Test
    fun `leaving the stroke engine turns the mode off`() {
        connectOnStrokeEngine()

        store.activatePattern(KnownStreamingPattern.key)
        runCurrent()

        verify(atLeast = 1) { bleManager.disableDepthByLoad() }
    }

    @Test
    fun `end of a remote session turns the mode off even without an active session`() {
        store.endRemoteSession()

        verify(atLeast = 1) { bleManager.disableDepthByLoad() }
    }
}
