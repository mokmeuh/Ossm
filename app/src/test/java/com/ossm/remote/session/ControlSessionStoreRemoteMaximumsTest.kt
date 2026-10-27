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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Remote-session model: while a session is active the app's speed/depth sliders only set the
 * ENVELOPE (maximums); the browser acts freely inside it and is always clamped by it.
 *
 * These tests replace the former behavior in which a local slider move during a session also
 * commanded the motor (and silently copied the local speed into the remote ceiling).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlSessionStoreRemoteMaximumsTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bleManager: BleManager
    private lateinit var store: ControlSessionStore

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        bleManager = mockk(relaxed = true)
        val safetySettingsRepository = mockk<ControlSafetySettingsRepository>(relaxed = true)
        val userHabitsRepository = mockk<UserHabitsRepository>(relaxed = true)

        every { bleManager.availablePatterns } returns MutableStateFlow(KnownFallbackPatterns)
        every { bleManager.machineState } returns MutableStateFlow(MachineState())
        every { bleManager.postSetupReady } returns MutableStateFlow(true)
        every { bleManager.streamingReady } returns MutableStateFlow(false)
        every { bleManager.connectionState } returns MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
        every { safetySettingsRepository.settings } returns MutableStateFlow(
            ControlSafetySettings(
                speed = SliderGuardSettings(enabled = false, thresholdPercent = 10),
                depth = SliderGuardSettings(enabled = false, thresholdPercent = 5),
            )
        )
        every { safetySettingsRepository.depthLimitMin } returns MutableStateFlow(0f)
        every { safetySettingsRepository.depthLimitMax } returns MutableStateFlow(1f)
        every { safetySettingsRepository.liveInvertEnabled } returns MutableStateFlow(false)
        every { safetySettingsRepository.liveAccelerationPercent } returns MutableStateFlow(33)
        every { safetySettingsRepository.precisionOverrides } returns MutableStateFlow(emptyMap())
        every { userHabitsRepository.sessionDefaults } returns flowOf(SessionDefaults(null, null, null))
        every { userHabitsRepository.patternOrder } returns flowOf(emptyList())
        coEvery { safetySettingsRepository.setDepthLimits(any(), any()) } returns Unit

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

    private fun strokeSpeeds(): List<Float> {
        val sent = mutableListOf<OssmCommand>()
        verify(atLeast = 0) { bleManager.sendCommand(capture(sent)) }
        return sent.filterIsInstance<OssmCommand.UpdateStrokeEngine>().map { it.command.speed }
    }

    private fun lastStrokeCommand(): OssmCommand.UpdateStrokeEngine {
        val sent = mutableListOf<OssmCommand>()
        verify(atLeast = 1) { bleManager.sendCommand(capture(sent)) }
        return sent.filterIsInstance<OssmCommand.UpdateStrokeEngine>().last()
    }

    private fun motorCommandCount(): Int {
        val sent = mutableListOf<OssmCommand>()
        verify(atLeast = 0) { bleManager.sendCommand(capture(sent)) }
        return sent.size
    }

    @Test
    fun `local speed slider during a session only sets the maximum and sends no motor command`() {
        store.beginRemoteSession(localSpeedMaximum = 0.5f, localDepthMinimum = 0f, localDepthMaximum = 1f)

        store.requestSpeedChange(0.8f)
        testDispatcher.scheduler.runCurrent()

        assertEquals(0, motorCommandCount())
        verify(exactly = 0) { bleManager.liveSet("speed", any(), any()) }
        assertEquals(0.8f, store.remoteSessionView.value.speedMax, 0.0001f)
        assertEquals(0.8f, store.remoteSessionLimits()!!.speedMaximum, 0.0001f)
        verify { bleManager.setPhoneHostSpeedCeiling(0.8f) }
    }

    @Test
    fun `local depth slider during a session only sets the envelope and sends no motor command`() {
        store.beginRemoteSession(localSpeedMaximum = 0.5f, localDepthMinimum = 0f, localDepthMaximum = 1f)

        store.requestDepthRangeChange(0.2f, 0.8f)
        testDispatcher.scheduler.runCurrent()

        assertEquals(0, motorCommandCount())
        val view = store.remoteSessionView.value
        assertEquals(0.2f, view.depthMin, 0.0001f)
        assertEquals(0.8f, view.depthMax, 0.0001f)
    }

    @Test
    fun `remote set_speed 1_0 is clamped to the maximum and not to the local speed`() {
        // Local speed is 0 when the session opens: the envelope is explicit, not copied later.
        store.beginRemoteSession(localSpeedMaximum = 0.4f, localDepthMinimum = 0f, localDepthMaximum = 1f)

        store.requestRemoteSpeedChange(1.0f)
        testDispatcher.scheduler.runCurrent()

        assertEquals(0.4f, lastStrokeCommand().command.speed, 0.0001f)
        assertEquals(0.4f, store.remoteSessionView.value.remoteSpeed!!, 0.0001f)
        assertNotNull(store.remoteSessionView.value.lastRemoteCommandAtMillis)
    }

    @Test
    fun `remote requests below the maximum pass through unchanged and keep the maximum`() {
        store.beginRemoteSession(localSpeedMaximum = 0.4f, localDepthMinimum = 0f, localDepthMaximum = 1f)
        store.requestRemoteSpeedChange(1.0f)
        testDispatcher.scheduler.runCurrent()

        assertEquals(0.4f, store.remoteSessionLimits()!!.speedMaximum, 0.0001f)
        store.requestRemoteSpeedChange(0.3f)
        assertEquals(0.3f, lastStrokeCommand().command.speed, 0.0001f)
        assertEquals(0.4f, store.remoteSessionLimits()!!.speedMaximum, 0.0001f)
    }

    @Test
    fun `lowering the maximum during a session lowers the current remote speed immediately`() {
        store.beginRemoteSession(localSpeedMaximum = 0.6f, localDepthMinimum = 0f, localDepthMaximum = 1f)
        store.requestRemoteSpeedChange(0.6f)
        testDispatcher.scheduler.runCurrent()
        assertEquals(0.6f, lastStrokeCommand().command.speed, 0.0001f)

        store.requestSpeedChange(0.2f)
        testDispatcher.scheduler.runCurrent()

        assertEquals(0.2f, lastStrokeCommand().command.speed, 0.0001f)
        assertEquals(0.2f, store.uiState.value.speed, 0.0001f)
        assertEquals(0.2f, store.remoteSessionView.value.remoteSpeed!!, 0.0001f)
        verify { bleManager.setPhoneHostSpeedCeiling(0.2f) }
    }

    @Test
    fun `raising the maximum during a session does not command the motor or raise the speed`() {
        store.beginRemoteSession(localSpeedMaximum = 0.3f, localDepthMinimum = 0f, localDepthMaximum = 1f)
        store.requestRemoteSpeedChange(0.3f)
        testDispatcher.scheduler.runCurrent()
        val before = motorCommandCount()

        store.requestSpeedChange(0.9f)
        testDispatcher.scheduler.runCurrent()

        assertEquals(before, motorCommandCount())
        assertEquals(0.3f, store.uiState.value.speed, 0.0001f)
        assertEquals(0.9f, store.remoteSessionView.value.speedMax, 0.0001f)
    }

    @Test
    fun `narrowing the depth envelope lowers the remote depth range`() {
        store.beginRemoteSession(localSpeedMaximum = 0.5f, localDepthMinimum = 0f, localDepthMaximum = 1f)
        store.requestRemoteSpeedChange(0.5f)
        store.requestRemoteDepthRangeChange(0f, 1f)
        testDispatcher.scheduler.runCurrent()

        store.requestDepthRangeChange(0.3f, 0.6f)
        testDispatcher.scheduler.runCurrent()

        val command = lastStrokeCommand().command
        assertEquals(0.3f, command.depthMin, 0.0001f)
        assertEquals(0.6f, command.depthMax, 0.0001f)
        val view = store.remoteSessionView.value
        assertEquals(0.3f, view.remoteDepthMin!!, 0.0001f)
        assertEquals(0.6f, view.remoteDepthMax!!, 0.0001f)

        // The browser can no longer leave the new envelope.
        store.requestRemoteDepthRangeChange(0f, 1f)
        assertEquals(0.3f, lastStrokeCommand().command.depthMin, 0.0001f)
        assertEquals(0.6f, lastStrokeCommand().command.depthMax, 0.0001f)
    }

    @Test
    fun `maximum zero lets nothing move and says so`() {
        store.beginRemoteSession(localSpeedMaximum = 0f, localDepthMinimum = 0f, localDepthMaximum = 1f)

        store.requestRemoteSpeedChange(0.7f)
        testDispatcher.scheduler.runCurrent()

        assertTrue(strokeSpeeds().none { it > 0f })
        verify { bleManager.sendCommand(OssmCommand.Stop) }
        val view = store.remoteSessionView.value
        assertTrue(view.active)
        assertTrue(view.motionBlockedByZeroMaximum)
        assertEquals(0f, view.speedMax, 0f)
    }

    @Test
    fun `setting the maximum to zero in a session stops the machine`() {
        store.beginRemoteSession(localSpeedMaximum = 0.5f, localDepthMinimum = 0f, localDepthMaximum = 1f)
        store.requestRemoteSpeedChange(0.5f)
        testDispatcher.scheduler.runCurrent()

        store.requestSpeedChange(0f)
        testDispatcher.scheduler.runCurrent()

        verify { bleManager.sendCommand(OssmCommand.Stop) }
        assertEquals(0f, store.uiState.value.speed, 0f)
        assertEquals(0f, store.remoteSessionView.value.remoteSpeed!!, 0f)
        assertTrue(store.remoteSessionView.value.motionBlockedByZeroMaximum)
    }

    @Test
    fun `STOP stays a priority during a session`() {
        store.beginRemoteSession(localSpeedMaximum = 0.5f, localDepthMinimum = 0f, localDepthMaximum = 1f)
        store.requestRemoteSpeedChange(0.5f)
        testDispatcher.scheduler.runCurrent()

        store.stop()

        verify { bleManager.sendCommand(OssmCommand.Stop) }
        assertEquals(0f, store.uiState.value.speed, 0f)
        assertEquals(0f, store.remoteSessionView.value.remoteSpeed!!, 0f)
        // The envelope itself is untouched by a stop.
        assertEquals(0.5f, store.remoteSessionView.value.speedMax, 0.0001f)
    }

    @Test
    fun `outside a session the local slider still commands the machine`() {
        store.requestSpeedChange(0.3f)
        testDispatcher.scheduler.runCurrent()

        assertEquals(0.3f, lastStrokeCommand().command.speed, 0.0001f)
        assertFalse(store.remoteSessionView.value.active)
        assertNull(store.remoteSessionLimits())
    }

    @Test
    fun `remote session view reflects pattern depth and live commands then resets at the end`() {
        every { bleManager.canEnforcePhoneHostSpeedCeiling() } returns true
        store.beginRemoteSession(localSpeedMaximum = 0.5f, localDepthMinimum = 0.1f, localDepthMaximum = 0.9f)
        assertNull(store.remoteSessionView.value.lastRemoteCommandAtMillis)

        store.activateRemotePattern(KnownStrokeEnginePattern.key)
        store.requestRemoteDepthRangeChange(0f, 1f)
        store.setRemoteStreamPosition(40)
        testDispatcher.scheduler.runCurrent()

        val view = store.remoteSessionView.value
        assertEquals(KnownStrokeEnginePattern.key, view.remotePatternKey)
        assertEquals(0.1f, view.remoteDepthMin!!, 0.0001f)
        assertEquals(0.9f, view.remoteDepthMax!!, 0.0001f)
        assertEquals(40, view.remoteLivePercent)
        assertNotNull(view.lastRemoteCommandAtMillis)

        store.endRemoteSession()
        assertFalse(store.remoteSessionView.value.active)
        assertNull(store.remoteSessionView.value.remoteSpeed)
    }
}
