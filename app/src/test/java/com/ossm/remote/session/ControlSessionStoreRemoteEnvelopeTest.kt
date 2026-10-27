package com.ossm.remote.session

import com.ossm.remote.ble.BleManager
import com.ossm.remote.data.repository.ControlSafetySettings
import com.ossm.remote.data.repository.ControlSafetySettingsRepository
import com.ossm.remote.data.repository.SessionDefaults
import com.ossm.remote.data.repository.SliderGuardSettings
import com.ossm.remote.data.repository.UserHabitsRepository
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.KnownStrokeEnginePattern
import com.ossm.remote.model.MachineState
import com.ossm.remote.model.OssmCommand
import com.ossm.remote.model.StrokeEngineCommand
import com.ossm.remote.viewmodel.ControlUiState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
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
class ControlSessionStoreRemoteEnvelopeTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bleManager: BleManager
    private lateinit var safetySettingsRepository: ControlSafetySettingsRepository
    private lateinit var userHabitsRepository: UserHabitsRepository
    private lateinit var store: ControlSessionStore
    private lateinit var connectionStateFlow: MutableStateFlow<BleConnectionState>

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        bleManager = mockk(relaxed = true)
        safetySettingsRepository = mockk(relaxed = true)
        userHabitsRepository = mockk(relaxed = true)

        val settingsFlow = MutableStateFlow(
            ControlSafetySettings(
                speed = SliderGuardSettings(enabled = false, thresholdPercent = 10),
                depth = SliderGuardSettings(enabled = false, thresholdPercent = 5),
            )
        )
        val patternsFlow = MutableStateFlow(KnownFallbackPatterns)
        val machineStateFlow = MutableStateFlow(MachineState())
        connectionStateFlow = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
        val postSetupReadyFlow = MutableStateFlow(true)
        val streamingReadyFlow = MutableStateFlow(false)
        val depthLimitMinFlow = MutableStateFlow(0f)
        val depthLimitMaxFlow = MutableStateFlow(1f)
        val liveAccelerationFlow = MutableStateFlow(33)
        val precisionOverridesFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())

        every { bleManager.availablePatterns } returns patternsFlow
        every { bleManager.machineState } returns machineStateFlow
        every { bleManager.postSetupReady } returns postSetupReadyFlow
        every { bleManager.streamingReady } returns streamingReadyFlow
        every { bleManager.connectionState } returns connectionStateFlow
        every { safetySettingsRepository.settings } returns settingsFlow
        every { safetySettingsRepository.depthLimitMin } returns depthLimitMinFlow
        every { safetySettingsRepository.depthLimitMax } returns depthLimitMaxFlow
        every { safetySettingsRepository.liveInvertEnabled } returns MutableStateFlow(false)
        every { safetySettingsRepository.liveAccelerationPercent } returns liveAccelerationFlow
        every { safetySettingsRepository.precisionOverrides } returns precisionOverridesFlow
        every { userHabitsRepository.sessionDefaults } returns flowOf(SessionDefaults(null, null, null))
        every { userHabitsRepository.patternOrder } returns flowOf(emptyList())

        coEvery { safetySettingsRepository.setSpeedGuardEnabled(any()) } returns Unit
        coEvery { safetySettingsRepository.setDepthGuardEnabled(any()) } returns Unit
        coEvery { safetySettingsRepository.setDepthLimits(any(), any()) } returns Unit
        coEvery { safetySettingsRepository.setPrecisionEnabled(any(), any()) } returns Unit
        coEvery { safetySettingsRepository.setLiveInvertEnabled(any()) } returns Unit
        coEvery { safetySettingsRepository.setLiveAccelerationPercent(any()) } returns Unit

        store = ControlSessionStore(
            bleManager = bleManager,
            safetySettingsRepository = safetySettingsRepository,
            userHabitsRepository = userHabitsRepository,
        )
        testDispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `actual Store activation completion reclamps retained command to current host range`() = runTest {
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

        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "test")
        runCurrent()

        store.activatePattern(KnownStrokeEnginePattern.key)
        runCurrent()
        assertTrue("the real Store activation callback must be captured", activationFinished != null)

        // Retain the old 20..80 command through the real activation gate.
        store.setDepthLive(0.2f, 0.8f)
        store.beginRemoteSession(
            localSpeedMaximum = 1f,
            localDepthMinimum = 0.2f,
            localDepthMaximum = 0.8f,
        )

        // Simulate the owner changing the hard/current range while setup is in flight.
        // Calling setDepthLimits() would intentionally stop the producer in the current
        // Store seam, so this test-only state seam isolates the completion callback itself.
        replaceCurrentHostRange(minimum = 0.2f, maximum = 0.4f)

        val sent = slot<OssmCommand>()
        activationFinished!!.invoke(true)
        runCurrent()

        verify(exactly = 1) { bleManager.sendCommand(capture(sent)) }
        val update = sent.captured as OssmCommand.UpdateStrokeEngine
        assertEquals(0.2f, update.command.depthMin, 0.0001f)
        assertEquals(0.4f, update.command.depthMax, 0.0001f)
    }

    @Test
    fun `BLE loss stops a producer but keeps remote envelope until socket ends`() = runTest {
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "test")
        runCurrent()

        val captured = store.beginRemoteSession(
            localSpeedMaximum = 0.4f,
            localDepthMinimum = 0.2f,
            localDepthMaximum = 0.8f,
        )
        val producer = Job()
        setPrivateJob("progressiveJob", producer)
        assertTrue(producer.isActive)

        connectionStateFlow.value = BleConnectionState.Disconnected
        runCurrent()

        assertEquals(captured, store.remoteSessionLimits())
        assertTrue("BLE loss must cancel active producers", producer.isCancelled)
        verify(exactly = 1) { bleManager.sendCommand(OssmCommand.Stop) }
    }

    private fun replaceCurrentHostRange(minimum: Float, maximum: Float) {
        val field = ControlSessionStore::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(store) as MutableStateFlow<ControlUiState>
        state.update {
            it.copy(
                depthMin = minimum,
                depthMax = maximum,
                depthLimitMin = minimum,
                depthLimitMax = maximum,
            )
        }
    }

    private fun setPrivateJob(name: String, job: Job) {
        val field = ControlSessionStore::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(store, job)
    }
}
