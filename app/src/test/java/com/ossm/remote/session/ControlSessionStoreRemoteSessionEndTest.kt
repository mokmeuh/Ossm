package com.ossm.remote.session

import com.ossm.remote.ble.BleManager
import com.ossm.remote.data.repository.ControlSafetySettings
import com.ossm.remote.data.repository.ControlSafetySettingsRepository
import com.ossm.remote.data.repository.SessionDefaults
import com.ossm.remote.data.repository.SliderGuardSettings
import com.ossm.remote.data.repository.UserHabitsRepository
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.KnownStreamingPattern
import com.ossm.remote.model.MachineState
import com.ossm.remote.model.OssmCommand
import com.ossm.remote.remote.PhoneHostReadiness
import com.ossm.remote.remote.RemoteControlAction
import com.ossm.remote.remote.RemoteSessionSafetyAdapter
import com.ossm.remote.viewmodel.LiveRecordingState
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SAFETY (v2.3.14): whatever ends a remote session (transport loss, expiry, controller/lease
 * loss, explicit stop, service destroyed), the machine stops and never adopts the envelope
 * maximum or the browser's last value as a direct command. The sensation is part of the
 * envelope: the browser is clamped to it and a local sensation move only sets the bound.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlSessionStoreRemoteSessionEndTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bleManager: BleManager
    private lateinit var store: ControlSessionStore
    private lateinit var connectionStateFlow: MutableStateFlow<BleConnectionState>
    private lateinit var streamingReadyFlow: MutableStateFlow<Boolean>
    private var now = 1_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        bleManager = mockk(relaxed = true)
        val safetySettingsRepository = mockk<ControlSafetySettingsRepository>(relaxed = true)
        val userHabitsRepository = mockk<UserHabitsRepository>(relaxed = true)
        connectionStateFlow = MutableStateFlow(BleConnectionState.Disconnected)
        streamingReadyFlow = MutableStateFlow(false)

        every { bleManager.availablePatterns } returns MutableStateFlow(KnownFallbackPatterns)
        every { bleManager.machineState } returns MutableStateFlow(MachineState())
        every { bleManager.postSetupReady } returns MutableStateFlow(true)
        every { bleManager.streamingReady } returns streamingReadyFlow
        every { bleManager.connectionState } returns connectionStateFlow
        every { bleManager.canEnforcePhoneHostSpeedCeiling() } returns true
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

    // ---- helpers ----

    private fun runCurrent() = testDispatcher.scheduler.runCurrent()

    private fun advance(ms: Long) {
        testDispatcher.scheduler.advanceTimeBy(ms)
        testDispatcher.scheduler.runCurrent()
    }

    private fun clearRecordedCalls() {
        clearMocks(
            bleManager,
            answers = false,
            recordedCalls = true,
            childMocks = false,
            verificationMarks = true,
            exclusionRules = false,
        )
    }

    private fun sentCommands(): List<OssmCommand> {
        val sent = mutableListOf<OssmCommand>()
        verify(atLeast = 0) { bleManager.sendCommand(capture(sent)) }
        return sent
    }

    private fun strokeCommands() = sentCommands().filterIsInstance<OssmCommand.UpdateStrokeEngine>()

    /** No speed > 0 of any kind and no Live position may reach the machine. */
    private fun assertNoNonZeroSpeedOrLiveCommand() {
        val nonZero = strokeCommands().filter { it.command.speed > 0f }
        assertTrue("non-zero stroke commands after session end: $nonZero", nonZero.isEmpty())
        assertTrue("Live positions replayed after session end", sentCommands().none { it is OssmCommand.Stream })
        verify(exactly = 0) { bleManager.liveSet("speed", more(0), any()) }
    }

    private fun assertSafeLocalState() {
        val state = store.uiState.value
        assertEquals(0f, state.speed, 0f)
        assertFalse(state.isRunning)
        assertFalse(state.isPaused)
        assertFalse(store.remoteSessionView.value.active)
    }

    /** Session at 100 % maximum with the browser actually driving 100 %. */
    private fun startSessionDrivenAtFullSpeed(): RemoteSessionSafetyAdapter {
        val adapter = RemoteSessionSafetyAdapter(store) { now }
        adapter.beginSession(1f, 0f, 1f)
        adapter.updateReadiness(PhoneHostReadiness(true, true, true))
        adapter.grantLease("controller", 60_000L)
        assertTrue(adapter.acceptAction("controller", 1, RemoteControlAction.SetSpeed(1f)))
        runCurrent()
        assertEquals(1f, strokeCommands().last().command.speed, 0.0001f)
        assertEquals(1f, store.uiState.value.speed, 0.0001f)
        clearRecordedCalls()
        return adapter
    }

    // ---- end of session ----

    @Test
    fun `ending a session at maximum 100 percent stops and never sends a non zero speed`() {
        store.beginRemoteSession(localSpeedMaximum = 1f, localDepthMinimum = 0f, localDepthMaximum = 1f)
        store.requestRemoteSpeedChange(1f)
        runCurrent()
        assertEquals(1f, strokeCommands().last().command.speed, 0.0001f)
        clearRecordedCalls()

        assertTrue(store.endRemoteSession())
        advance(2_000)

        verify(atLeast = 1) { bleManager.sendCommand(OssmCommand.Stop) }
        verify { bleManager.setPhoneHostSpeedCeiling(null) }
        assertNoNonZeroSpeedOrLiveCommand()
        assertSafeLocalState()
    }

    @Test
    fun `after the end non speed local controls do not restart motion`() {
        store.beginRemoteSession(localSpeedMaximum = 1f, localDepthMinimum = 0f, localDepthMaximum = 1f)
        store.requestRemoteSpeedChange(1f)
        runCurrent()
        store.endRemoteSession()
        advance(2_000)
        clearRecordedCalls()

        store.requestDepthRangeChange(0.2f, 0.8f)
        store.setDepthLive(0.25f, 0.75f)
        store.requestSensationChange(0.7f)
        store.setSensationLive(0.6f)
        store.resume()
        advance(2_000)

        assertNoNonZeroSpeedOrLiveCommand()
        assertEquals(0f, store.uiState.value.speed, 0f)
        assertFalse(store.uiState.value.isRunning)
    }

    @Test
    fun `after the end the user's own slider move commands the machine directly again`() {
        store.beginRemoteSession(localSpeedMaximum = 1f, localDepthMinimum = 0f, localDepthMaximum = 1f)
        store.requestRemoteSpeedChange(1f)
        runCurrent()
        store.endRemoteSession()
        clearRecordedCalls()

        // The user moves the slider himself right after the end (before the confirmation STOP).
        store.requestSpeedChange(0.3f)
        runCurrent()
        assertEquals(0.3f, strokeCommands().last().command.speed, 0.0001f)

        // The delayed confirmation STOP of the ended session must not override the user command.
        advance(2_000)
        verify(exactly = 0) { bleManager.sendCommand(OssmCommand.Stop) }
        assertEquals(0.3f, store.uiState.value.speed, 0.0001f)
    }

    @Test
    fun `depth and sensation keep their last effective values not the maximums`() {
        store.beginRemoteSession(
            localSpeedMaximum = 1f,
            localDepthMinimum = 0f,
            localDepthMaximum = 1f,
            localSensation = 1f,
        )
        store.requestRemoteSpeedChange(0.5f)
        store.requestRemoteDepthRangeChange(0.3f, 0.6f)
        store.requestRemoteSensationChange(0.4f)
        runCurrent()

        store.endRemoteSession()
        advance(2_000)

        val state = store.uiState.value
        assertEquals(0.3f, state.depthMin, 0.0001f)
        assertEquals(0.6f, state.depthMax, 0.0001f)
        assertEquals(0.4f, state.sensation, 0.0001f)
        assertEquals(0f, state.speed, 0f)
    }

    @Test
    fun `transport loss then end of session stops safely`() {
        val adapter = startSessionDrivenAtFullSpeed()

        adapter.onTransportLost()
        adapter.endSession()
        advance(2_000)

        verify(atLeast = 1) { bleManager.emergencyStop() }
        verify(atLeast = 1) { bleManager.sendCommand(OssmCommand.Stop) }
        assertNoNonZeroSpeedOrLiveCommand()
        assertSafeLocalState()
    }

    @Test
    fun `transport loss without any controller lease still stops`() {
        val adapter = RemoteSessionSafetyAdapter(store) { now }
        adapter.beginSession(1f, 0f, 1f)
        store.requestRemoteSpeedChange(1f)
        runCurrent()
        clearRecordedCalls()

        adapter.onTransportLost()
        runCurrent()

        verify(atLeast = 1) { bleManager.sendCommand(OssmCommand.Stop) }
        assertEquals(0f, store.uiState.value.speed, 0f)

        adapter.endSession()
        advance(2_000)
        assertNoNonZeroSpeedOrLiveCommand()
        assertSafeLocalState()
    }

    @Test
    fun `explicit stop (session timeout path) stops safely`() {
        val adapter = startSessionDrivenAtFullSpeed()

        // RemoteSessionClient.stop(): stopSocket() then onSessionTimeout().
        adapter.onTransportLost()
        adapter.endSession()
        adapter.onSessionTimeout()
        advance(2_000)

        verify(atLeast = 1) { bleManager.sendCommand(OssmCommand.Stop) }
        assertNoNonZeroSpeedOrLiveCommand()
        assertSafeLocalState()
    }

    @Test
    fun `session timeout alone stops safely`() {
        val adapter = startSessionDrivenAtFullSpeed()

        adapter.onSessionTimeout()
        runCurrent()
        assertEquals(0f, store.uiState.value.speed, 0f)
        verify(atLeast = 1) { bleManager.emergencyStop() }

        adapter.endSession()
        advance(2_000)
        assertNoNonZeroSpeedOrLiveCommand()
        assertSafeLocalState()
    }

    @Test
    fun `service destroyed stops safely`() {
        val adapter = startSessionDrivenAtFullSpeed()

        adapter.onServiceDestroyed()
        adapter.endSession()
        advance(2_000)

        verify(atLeast = 1) { bleManager.emergencyStop() }
        verify(atLeast = 1) { bleManager.sendCommand(OssmCommand.Stop) }
        assertNoNonZeroSpeedOrLiveCommand()
        assertSafeLocalState()
    }

    @Test
    fun `lease expiry stops and leaves the speed at zero`() {
        val adapter = startSessionDrivenAtFullSpeed()

        now = 60_000L
        adapter.expireIfNeeded()
        advance(2_000)

        verify(exactly = 1) { bleManager.emergencyStop() }
        assertNoNonZeroSpeedOrLiveCommand()
        assertEquals(0f, store.uiState.value.speed, 0f)
        assertFalse(store.uiState.value.isRunning)
        // The expired controller cannot drive again.
        assertFalse(adapter.acceptAction("controller", 2, RemoteControlAction.SetSpeed(1f)))

        adapter.endSession()
        advance(2_000)
        assertNoNonZeroSpeedOrLiveCommand()
        assertSafeLocalState()
    }

    @Test
    fun `controller lease loss stops and leaves the speed at zero`() {
        val adapter = startSessionDrivenAtFullSpeed()

        assertTrue(adapter.loseLease("controller"))
        advance(2_000)

        verify(exactly = 1) { bleManager.emergencyStop() }
        assertNoNonZeroSpeedOrLiveCommand()
        assertEquals(0f, store.uiState.value.speed, 0f)

        adapter.endSession()
        advance(2_000)
        assertNoNonZeroSpeedOrLiveCommand()
        assertSafeLocalState()
    }

    @Test
    fun `remote commands after the end are refused and send nothing`() {
        val adapter = startSessionDrivenAtFullSpeed()
        adapter.onTransportLost()
        adapter.endSession()
        advance(2_000)
        clearRecordedCalls()

        adapter.grantLease("late", 120_000L)
        assertFalse(adapter.acceptAction("late", 1, RemoteControlAction.SetSpeed(1f)))
        assertFalse(adapter.acceptAction("late", 2, RemoteControlAction.SetSensation(1f)))
        store.requestRemoteSpeedChange(1f)
        store.requestRemoteSensationChange(1f)
        assertFalse(store.setRemoteStreamPosition(80))
        advance(2_000)

        assertNoNonZeroSpeedOrLiveCommand()
        assertEquals(0f, store.uiState.value.speed, 0f)
    }

    @Test
    fun `producers are cancelled by the end of the session and do not resume`() {
        store.beginRemoteSession(localSpeedMaximum = 1f, localDepthMinimum = 0f, localDepthMaximum = 1f)
        val producers = listOf("progressiveJob", "chaosJob", "teasingRandomJob", "autoJob", "liveReplayJob")
            .associateWith { Job() }
        producers.forEach { (name, job) -> setPrivateJob(name, job) }

        store.endRemoteSession()
        advance(2_000)

        producers.forEach { (name, job) -> assertTrue("$name still active", job.isCancelled) }
        assertNoNonZeroSpeedOrLiveCommand()
        assertSafeLocalState()
    }

    @Test
    fun `no Live position is replayed after the end of the session`() {
        connectionStateFlow.value = BleConnectionState.Connected("OSSM", "AA:BB:CC:DD:EE:FF")
        runCurrent()
        store.activatePattern(KnownStreamingPattern.key)
        streamingReadyFlow.value = true
        runCurrent()

        // A local Live recording is active when the session opens; explicit STOP must cancel
        // it rather than starting replay.
        store.toggleLiveRecording()
        store.setStreamPositionDrag(45)
        advance(25)
        store.setStreamPositionDrag(60)
        store.stopLiveTouch()
        runCurrent()
        assertEquals(LiveRecordingState.IDLE, store.uiState.value.liveRecordingState)

        store.beginRemoteSession(localSpeedMaximum = 1f, localDepthMinimum = 0f, localDepthMaximum = 1f)
        store.setRemoteStreamPosition(90)
        runCurrent()
        clearRecordedCalls()

        store.endRemoteSession()
        advance(10_000)

        assertEquals(LiveRecordingState.IDLE, store.uiState.value.liveRecordingState)
        assertNoNonZeroSpeedOrLiveCommand()
        verify(atLeast = 1) { bleManager.sendCommand(OssmCommand.Stop) }
        assertSafeLocalState()
    }

    // ---- sensation envelope (2.3.16: bipolar, 0.5 = neutral, symmetric intensity) ----

    @Test
    fun `remote sensation is clamped into the symmetric range on both sides`() {
        store.beginRemoteSession(
            localSpeedMaximum = 0.5f,
            localDepthMinimum = 0f,
            localDepthMaximum = 1f,
            localSensation = 0.7f, // d = 0.2 -> range 0.3..0.7
        )
        val view0 = store.remoteSessionView.value
        assertEquals(0.3f, view0.sensationMin, 0.0001f)
        assertEquals(0.7f, view0.sensationMax, 0.0001f)

        store.requestRemoteSensationChange(0.9f)
        runCurrent()
        assertEquals(0.7f, store.uiState.value.sensation, 0.0001f)
        assertEquals(0.7f, store.remoteSessionView.value.remoteSensation!!, 0.0001f)
        verify { bleManager.liveSet("sensation", 70, any()) }
        verify(exactly = 0) { bleManager.liveSet("sensation", more(70), any()) }

        store.requestRemoteSensationChange(0.05f)
        runCurrent()
        assertEquals(0.3f, store.uiState.value.sensation, 0.0001f)
        assertEquals(0.3f, store.remoteSessionView.value.remoteSensation!!, 0.0001f)
        verify(exactly = 0) { bleManager.liveSet("sensation", less(30), any()) }

        // A full stroke command for the browser also carries the clamped sensation.
        store.requestRemoteSpeedChange(0.5f)
        runCurrent()
        val sent = strokeCommands().last().command.sensation
        assertTrue(sent in 0.3f - 0.0001f..0.7f + 0.0001f)
    }

    @Test
    fun `slider on either side of neutral gives the same intensity`() {
        store.beginRemoteSession(
            localSpeedMaximum = 0.5f,
            localDepthMinimum = 0f,
            localDepthMaximum = 1f,
            localSensation = 0.2f, // left side, d = 0.3
        )
        val view = store.remoteSessionView.value
        assertEquals(0.2f, view.sensationMin, 0.0001f)
        assertEquals(0.8f, view.sensationMax, 0.0001f)
        assertEquals(0.3f, store.remoteSessionLimits()!!.sensationIntensity, 0.0001f)
    }

    @Test
    fun `local sensation in a session only sets the intensity and commands nothing when widening`() {
        store.beginRemoteSession(
            localSpeedMaximum = 0.5f,
            localDepthMinimum = 0f,
            localDepthMaximum = 1f,
            localSensation = 0.6f, // d = 0.1
        )
        val before = store.uiState.value.sensation
        clearRecordedCalls()

        store.setSensationLive(0.9f)
        store.requestSensationChange(0.05f) // left side, d = 0.45
        runCurrent()

        assertTrue(sentCommands().isEmpty())
        verify(exactly = 0) { bleManager.liveSet(any(), any(), any()) }
        assertEquals(before, store.uiState.value.sensation, 0f)
        val view = store.remoteSessionView.value
        assertEquals(0.05f, view.sensationMin, 0.0001f)
        assertEquals(0.95f, view.sensationMax, 0.0001f)
        assertEquals(0.45f, store.remoteSessionLimits()!!.sensationIntensity, 0.0001f)
    }

    @Test
    fun `narrowing the intensity recentres the remote value to its nearest bound never the slider value`() {
        store.beginRemoteSession(
            localSpeedMaximum = 0.5f,
            localDepthMinimum = 0f,
            localDepthMaximum = 1f,
            localSensation = 0.9f, // d = 0.4 -> 0.1..0.9
        )
        store.requestRemoteSensationChange(0.85f)
        runCurrent()
        assertEquals(0.85f, store.uiState.value.sensation, 0.0001f)
        clearRecordedCalls()

        // Slider moved to the LEFT (0.3): d = 0.2 -> 0.3..0.7. The remote value (right side)
        // goes to 0.7, not to the slider position 0.3.
        store.requestSensationChange(0.3f)
        runCurrent()

        assertEquals(0.7f, store.uiState.value.sensation, 0.0001f)
        verify { bleManager.liveSet("sensation", 70, any()) }
        verify(exactly = 0) { bleManager.liveSet("sensation", 30, any()) }
        val view = store.remoteSessionView.value
        assertEquals(0.3f, view.sensationMin, 0.0001f)
        assertEquals(0.7f, view.sensationMax, 0.0001f)
        assertEquals(0.7f, view.remoteSensation!!, 0.0001f)
        // No speed/stroke command for a sensation bound change.
        assertTrue(strokeCommands().isEmpty())

        // The other side: a value below the new minimum goes up to the minimum.
        store.requestSensationChange(0.9f) // widen back
        store.requestRemoteSensationChange(0.15f)
        runCurrent()
        store.requestSensationChange(0.6f) // d = 0.1 -> 0.4..0.6
        runCurrent()
        assertEquals(0.4f, store.uiState.value.sensation, 0.0001f)
    }

    @Test
    fun `end of session keeps the last effective sensation never a bound`() {
        store.beginRemoteSession(
            localSpeedMaximum = 0.5f,
            localDepthMinimum = 0f,
            localDepthMaximum = 1f,
            localSensation = 0.8f, // 0.2..0.8
        )
        store.requestRemoteSensationChange(0.35f)
        runCurrent()

        store.endRemoteSession()
        advance(2_000)

        assertEquals(0.35f, store.uiState.value.sensation, 0.0001f)
        assertFalse(store.remoteSessionView.value.active)
    }

    @Test
    fun `outside a session sensation stays a direct command`() {
        store.setSensationLive(0.7f)
        runCurrent()
        verify { bleManager.liveSet("sensation", 70, any()) }

        store.requestSensationChange(0.6f)
        runCurrent()
        assertEquals(0.6f, strokeCommands().last().command.sensation, 0.0001f)
        assertEquals(0.6f, store.uiState.value.sensation, 0.0001f)
    }

    private fun setPrivateJob(name: String, job: Job) {
        val field = ControlSessionStore::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(store, job)
    }
}
