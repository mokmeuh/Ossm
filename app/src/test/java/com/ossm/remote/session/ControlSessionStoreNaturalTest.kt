package com.ossm.remote.session

import com.ossm.remote.ble.BleManager
import com.ossm.remote.data.repository.ControlSafetySettings
import com.ossm.remote.data.repository.ControlSafetySettingsRepository
import com.ossm.remote.data.repository.SessionDefaults
import com.ossm.remote.data.repository.SliderGuardSettings
import com.ossm.remote.data.repository.UserHabitsRepository
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.MachineState
import com.ossm.remote.model.NATURAL_PATTERN_KEY
import com.ossm.remote.model.OssmCommand
import com.ossm.remote.natural.NaturalAiFailureKind
import com.ossm.remote.natural.NaturalAiModelState
import com.ossm.remote.natural.NaturalAiProblem
import com.ossm.remote.natural.NaturalPlan
import com.ossm.remote.natural.OllamaHttpException
import com.ossm.remote.natural.OllamaModelLoader
import com.ossm.remote.natural.NaturalPlanInput
import com.ossm.remote.natural.NaturalPlanner
import com.ossm.remote.natural.OllamaNaturalPlanner
import com.ossm.remote.natural.OllamaTransport
import com.ossm.remote.remote.RemoteControlAction
import com.ossm.remote.remote.RemoteControlProtocol
import com.ossm.remote.viewmodel.NaturalPlannerStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class ControlSessionStoreNaturalTest {

    private sealed interface Sent {
        data class Live(val param: String, val value: Int) : Sent
        data class Range(val depth: Int, val stroke: Int) : Sent
        data class Command(val command: OssmCommand) : Sent
    }

    private val testDispatcher = StandardTestDispatcher()
    private val sent = mutableListOf<Sent>()
    private lateinit var bleManager: BleManager
    private lateinit var store: ControlSessionStore
    private lateinit var connection: MutableStateFlow<BleConnectionState>
    private lateinit var machine: MutableStateFlow<MachineState>
    private var activationSucceeds = true
    private var activationCalls = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        bleManager = mockk(relaxed = true)
        val safety = mockk<ControlSafetySettingsRepository>(relaxed = true)
        val habits = mockk<UserHabitsRepository>(relaxed = true)
        connection = MutableStateFlow(BleConnectionState.Disconnected)
        machine = MutableStateFlow(MachineState(state = "strokeEngine_idle"))

        every { bleManager.availablePatterns } returns MutableStateFlow(KnownFallbackPatterns)
        every { bleManager.machineState } returns machine
        every { bleManager.postSetupReady } returns MutableStateFlow(true)
        every { bleManager.streamingReady } returns MutableStateFlow(false)
        every { bleManager.connectionState } returns connection
        every { bleManager.liveSet(any(), any(), any()) } answers {
            sent += Sent.Live(firstArg(), secondArg())
        }
        every { bleManager.liveSetDepthRange(any(), any()) } answers {
            sent += Sent.Range(firstArg(), secondArg())
        }
        every { bleManager.sendCommand(any()) } answers { sent += Sent.Command(firstArg()) }
        every { bleManager.activatePatternVerified(any(), any(), any(), any()) } answers {
            activationCalls++
            arg<(Boolean) -> Unit>(3).invoke(activationSucceeds)
        }
        every { safety.settings } returns MutableStateFlow(
            ControlSafetySettings(
                speed = SliderGuardSettings(enabled = false, thresholdPercent = 10),
                depth = SliderGuardSettings(enabled = false, thresholdPercent = 5),
            )
        )
        every { safety.depthLimitMin } returns MutableStateFlow(0f)
        every { safety.depthLimitMax } returns MutableStateFlow(1f)
        every { safety.liveInvertEnabled } returns MutableStateFlow(false)
        every { safety.liveAccelerationPercent } returns MutableStateFlow(33)
        every { safety.precisionOverrides } returns MutableStateFlow(emptyMap())
        every { safety.naturalAiEnabled } returns MutableStateFlow(false)
        every { safety.naturalAiAddress } returns MutableStateFlow("")
        every { safety.naturalAiReasoningRelay } returns MutableStateFlow(false)
        coEvery { safety.setNaturalAiReasoningRelay(any()) } returns Unit
        coEvery { safety.setNaturalAiEnabled(any()) } returns Unit
        coEvery { safety.setNaturalAiAddress(any()) } returns Unit
        every { habits.sessionDefaults } returns flowOf(SessionDefaults(null, null, null))
        every { habits.patternOrder } returns flowOf(emptyList())

        store = ControlSessionStore(bleManager, safety, habits)
        store.naturalSeedProvider = { 42L }
        testDispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun advance(ms: Long) {
        testDispatcher.scheduler.advanceTimeBy(ms)
        testDispatcher.scheduler.runCurrent()
    }

    /** OllamaNaturalPlanner hops to Dispatchers.IO: poll real time until the result lands. */
    private fun awaitStatus(expected: NaturalPlannerStatus) {
        repeat(200) {
            if (store.uiState.value.naturalPlannerStatus == expected) return
            Thread.sleep(10)
            testDispatcher.scheduler.runCurrent()
        }
    }

    private fun connect() {
        connection.value = BleConnectionState.Connected("OSSM", "AA:BB:CC:DD:EE:FF")
        testDispatcher.scheduler.runCurrent()
    }

    private fun speeds(): List<Int> = sent.filterIsInstance<Sent.Live>().filter { it.param == "speed" }.map { it.value }

    private fun motionCommands(): List<Sent> = sent.filter {
        when (it) {
            is Sent.Live -> it.param == "speed" && it.value > 0
            is Sent.Command -> it.command is OssmCommand.Stream ||
                (it.command is OssmCommand.UpdateStrokeEngine &&
                    (it.command as OssmCommand.UpdateStrokeEngine).command.speed > 0f)
            is Sent.Range -> false
        }
    }

    private fun selectNatural() {
        store.activatePattern(NATURAL_PATTERN_KEY)
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `no motion at launch, connection or mode selection without explicit start`() {
        connect()
        selectNatural()
        advance(30_000)

        assertTrue(store.uiState.value.naturalReady)
        assertFalse(store.uiState.value.naturalRunning)
        assertEquals(emptyList<Sent>(), motionCommands())
    }

    @Test
    fun `explicit start ramps progressively and respects the user maximum`() {
        connect()
        selectNatural()
        store.setNaturalMaxSpeed(0.35f)
        store.startNatural()
        advance(120_000)

        val speeds = speeds()
        assertTrue(speeds.isNotEmpty())
        assertTrue(speeds.all { it in 1..35 })
        // 6 %/s ramp at 200 ms ticks: never more than +2 points between consecutive writes.
        speeds.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", b - a <= 2) }
        val ranges = sent.filterIsInstance<Sent.Range>()
        assertTrue(ranges.isNotEmpty())
        assertTrue(ranges.all { it.depth <= 100 && it.stroke in 5..it.depth })
    }

    @Test
    fun `start before mode entry waits for the verified entry`() {
        connect()
        selectNatural()
        val before = activationCalls
        store.home() // homing invalidates the entry
        store.startNatural()
        testDispatcher.scheduler.runCurrent()

        assertEquals(before + 1, activationCalls)
        advance(2_000)
        assertTrue(store.uiState.value.naturalRunning)
    }

    @Test
    fun `failed mode entry never starts motion`() {
        activationSucceeds = false
        connect()
        selectNatural()
        store.startNatural()
        advance(10_000)

        assertFalse(store.uiState.value.naturalRunning)
        assertEquals(emptyList<Sent>(), motionCommands())
    }

    @Test
    fun `stop cuts immediately and no command follows`() {
        connect()
        selectNatural()
        store.startNatural()
        advance(20_000)
        assertTrue(speeds().isNotEmpty())

        store.stop()
        val sizeAfterStop = sent.size
        assertTrue(sent.last() == Sent.Command(OssmCommand.Stop))
        advance(60_000)

        assertEquals(sizeAfterStop, sent.size)
        assertFalse(store.uiState.value.naturalRunning)
    }

    @Test
    fun `emergency stop, disconnect, homing state, mode exit and pause halt the producer`() {
        connect()
        selectNatural()

        fun runThen(settleMs: Long = 0L, halt: () -> Unit) {
            store.startNatural()
            advance(5_000)
            assertTrue(store.uiState.value.naturalRunning)
            halt()
            testDispatcher.scheduler.runCurrent()
            // A machine-state halt is detected by the next producer tick (<= 200 ms).
            if (settleMs > 0L) advance(settleMs)
            val size = sent.size
            advance(30_000)
            assertEquals(size, sent.size)
            assertFalse(store.uiState.value.naturalRunning)
        }

        runThen { store.emergencyStop() }
        runThen { store.pause() }
        store.resume() // explicit resume restarts
        testDispatcher.scheduler.runCurrent()
        assertTrue(store.uiState.value.naturalRunning)
        store.stop()
        runThen(settleMs = 250L) { machine.value = MachineState(state = "homing") }
        assertEquals(Sent.Command(OssmCommand.Stop), sent.last())
        machine.value = MachineState(state = "strokeEngine_idle")
        runThen { store.stopNatural() }
        runThen { connection.value = BleConnectionState.Disconnected }

        connect()
        selectNatural()
        runThen { store.activatePattern("simpleStroke") }
    }

    @Test
    fun `lowering the maximum to zero stops at once`() {
        connect()
        selectNatural()
        store.startNatural()
        advance(10_000)
        store.setNaturalMaxSpeed(0f)
        val size = sent.size
        assertEquals(Sent.Command(OssmCommand.Stop), sent.last())
        advance(10_000)
        assertEquals(size, sent.size)
    }

    @Test
    fun `remote select_pattern natural is bounded by the envelope and session end stops`() {
        connect()
        store.beginRemoteSession(
            localSpeedMaximum = 0.25f,
            localDepthMinimum = 0.3f,
            localDepthMaximum = 0.7f,
            localSensation = 0.6f,
        )
        store.activateRemotePattern(NATURAL_PATTERN_KEY)
        testDispatcher.scheduler.runCurrent()
        advance(5_000)
        assertEquals(emptyList<Sent>(), motionCommands())

        // Local start is ignored during a session; the browser speed is the explicit start.
        store.startNatural()
        advance(5_000)
        assertEquals(emptyList<Sent>(), motionCommands())

        store.requestRemoteSpeedChange(0.9f)
        advance(120_000)
        val speeds = speeds()
        assertTrue(speeds.isNotEmpty())
        assertTrue(speeds.all { it in 1..25 })
        val ranges = sent.filterIsInstance<Sent.Range>()
        assertTrue(ranges.all { it.depth <= 70 && it.depth - it.stroke >= 30 })
        val sensations = sent.filterIsInstance<Sent.Live>().filter { it.param == "sensation" }.map { it.value }
        assertTrue(sensations.all { it in 40..60 })

        store.endRemoteSession()
        val size = sent.size
        advance(60_000)
        // Only the scheduled confirmation STOP may follow.
        assertTrue(sent.drop(size).all { it == Sent.Command(OssmCommand.Stop) })
        assertFalse(store.uiState.value.naturalRunning)
    }

    @Test
    fun `remote zero speed stops natural`() {
        connect()
        store.beginRemoteSession(localSpeedMaximum = 0.5f)
        store.activateRemotePattern(NATURAL_PATTERN_KEY)
        store.requestRemoteSpeedChange(0.4f)
        advance(10_000)
        assertTrue(store.uiState.value.naturalRunning)
        store.requestRemoteSpeedChange(0f)
        val size = sent.size
        advance(10_000)
        assertEquals(size, sent.size)
        assertFalse(store.uiState.value.naturalRunning)
    }

    // ---- AI model pre-load (2.3.20) ----

    /** Simulated Ollama: tags/ps reads and a single load POST that waits on [loadGate]. */
    private class FakeOllama : OllamaTransport {
        var tags = "{\"models\":[{\"name\":\"qwen3:8b\"}]}"
        var tagsError: Exception? = null
        var psLoaded = false
        var loadError: Exception? = null
        val loadGate = CompletableDeferred<String>()
        var loadPosts = 0
        var psGets = 0
        var loadCancelled = false

        override suspend fun get(path: String): String = when (path) {
            "/api/tags" -> tagsError?.let { throw it } ?: tags
            "/api/ps" -> {
                psGets++
                if (psLoaded) "{\"models\":[{\"name\":\"qwen3:8b\",\"model\":\"qwen3:8b\"}]}" else "{\"models\":[]}"
            }
            else -> error("unexpected path")
        }

        override suspend fun post(path: String, body: String): String {
            assertEquals("/api/generate", path)
            loadPosts++
            loadError?.let { throw it }
            return try {
                loadGate.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                loadCancelled = true
                throw e
            }
        }
    }

    private fun useFakeOllama(fake: FakeOllama = FakeOllama()): FakeOllama {
        store.naturalAiLoaderFactory = { OllamaModelLoader(fake) }
        return fake
    }

    /** Address + model already in memory on the PC: load completes on the first ps check. */
    private fun loadReadyModel(address: String = "100.65.88.40"): FakeOllama {
        val fake = useFakeOllama(FakeOllama().apply { psLoaded = true })
        store.setNaturalAiAddress(address)
        store.loadNaturalAiModel()
        testDispatcher.scheduler.runCurrent()
        assertEquals(NaturalAiModelState.LOADED, store.uiState.value.naturalAiModelState)
        return fake
    }

    private fun countingPlanner(onPlan: suspend () -> NaturalPlan): () -> Int {
        var calls = 0
        store.naturalPlannerFactory = {
            object : NaturalPlanner {
                override suspend fun plan(input: NaturalPlanInput): NaturalPlan {
                    calls++
                    return onPlan()
                }
            }
        }
        return { calls }
    }

    private val validPlan by lazy {
        com.ossm.remote.natural.NaturalPlanJsonParser().parse(
            "{\"schemaVersion\":1,\"phrases\":[{\"motif\":\"wave\",\"intensity\":0.6,\"tempo\":0.3,\"variation\":0.7,\"rest\":0.1}]}"
        )
    }

    @Test
    fun `preheat survives stop, pause and mode exit and sends a single load request`() {
        val fake = useFakeOllama()
        connect()
        selectNatural()
        store.setNaturalAiAddress("100.65.88.40")
        store.loadNaturalAiModel()
        testDispatcher.scheduler.runCurrent()
        assertEquals(NaturalAiModelState.LOADING, store.uiState.value.naturalAiModelState)
        assertEquals(true, store.uiState.value.naturalAiModelInstalled)
        assertEquals(1, fake.loadPosts)

        store.startNatural()
        advance(5_000)
        store.stop()
        store.pause()
        store.stopNatural()
        store.activatePattern("simpleStroke")
        store.loadNaturalAiModel() // already loading: no second request
        advance(40_000)

        assertEquals(NaturalAiModelState.LOADING, store.uiState.value.naturalAiModelState)
        assertFalse(fake.loadCancelled)
        assertEquals(1, fake.loadPosts)
        assertTrue(store.uiState.value.naturalAiLoadSeconds >= 44)
        // ps every 15 s only (initial check + 3 polls in 45 s), never once per second.
        assertTrue("ps=${fake.psGets}", fake.psGets in 2..5)

        fake.loadGate.complete("{\"model\":\"qwen3:8b\",\"done\":true,\"done_reason\":\"load\"}")
        testDispatcher.scheduler.runCurrent()
        assertEquals(NaturalAiModelState.LOADED, store.uiState.value.naturalAiModelState)
        assertEquals(1, fake.loadPosts)
    }

    @Test
    fun `ps listing marks the model loaded and retry never resends the load`() {
        val fake = useFakeOllama()
        store.setNaturalAiAddress("100.65.88.40")
        store.loadNaturalAiModel()
        advance(10_000)
        assertEquals(NaturalAiModelState.LOADING, store.uiState.value.naturalAiModelState)
        fake.psLoaded = true
        advance(6_000)
        assertEquals(NaturalAiModelState.LOADED, store.uiState.value.naturalAiModelState)

        store.loadNaturalAiModel()
        testDispatcher.scheduler.runCurrent()
        assertEquals(NaturalAiModelState.LOADED, store.uiState.value.naturalAiModelState)
        assertEquals(1, fake.loadPosts)
    }

    @Test
    fun `plans are requested only once the model is ready, then connect automatically`() {
        val calls = countingPlanner { validPlan }
        val fake = useFakeOllama()
        connect()
        selectNatural()
        store.setNaturalAiAddress("100.65.88.40")
        store.setNaturalAiEnabled(true) // "Utiliser l'IA" chosen before the load
        store.startNatural()
        advance(10_000)
        assertEquals(0, calls())
        assertEquals(NaturalPlannerStatus.AI_WAITING_MODEL, store.uiState.value.naturalPlannerStatus)

        store.loadNaturalAiModel()
        advance(10_000)
        assertEquals(0, calls())
        assertTrue(speeds().isNotEmpty()) // motion never waited for the AI

        fake.loadGate.complete("{\"model\":\"qwen3:8b\",\"done\":true}")
        testDispatcher.scheduler.runCurrent()
        assertEquals(NaturalPlannerStatus.AI_READY, store.uiState.value.naturalPlannerStatus)
        advance(1_000)
        assertTrue(calls() >= 1)
        assertEquals(NaturalPlannerStatus.AI_CONNECTED, store.uiState.value.naturalPlannerStatus)
    }

    @Test
    fun `retry relaunches the preheat after a failure`() {
        val fake = useFakeOllama().apply { tagsError = java.net.ConnectException("refused") }
        store.setNaturalAiAddress("100.65.88.40")
        store.setNaturalAiEnabled(true)
        store.loadNaturalAiModel()
        testDispatcher.scheduler.runCurrent()
        assertEquals(NaturalAiModelState.FAILED, store.uiState.value.naturalAiModelState)
        assertEquals(NaturalAiFailureKind.UNREACHABLE, store.uiState.value.naturalAiProblem?.kind)
        assertEquals(NaturalPlannerStatus.AI_UNAVAILABLE, store.uiState.value.naturalPlannerStatus)

        fake.tagsError = null
        fake.psLoaded = true
        store.loadNaturalAiModel()
        testDispatcher.scheduler.runCurrent()
        assertEquals(NaturalAiModelState.LOADED, store.uiState.value.naturalAiModelState)
        assertEquals(null, store.uiState.value.naturalAiProblem)
        assertEquals(NaturalPlannerStatus.AI_READY, store.uiState.value.naturalPlannerStatus)
    }

    @Test
    fun `load timeout is reported with the elapsed seconds`() {
        useFakeOllama()
        store.setNaturalAiAddress("100.65.88.40")
        store.loadNaturalAiModel()
        advance(299_000)
        assertEquals(NaturalAiModelState.LOADING, store.uiState.value.naturalAiModelState)
        advance(3_000)
        val problem = store.uiState.value.naturalAiProblem
        assertEquals(NaturalAiModelState.FAILED, store.uiState.value.naturalAiModelState)
        assertEquals(NaturalAiFailureKind.LOAD_TIMEOUT, problem?.kind)
        assertTrue("${problem?.seconds}", problem!!.seconds in 299..302)
    }

    @Test
    fun `missing model, http 404, http 500 and invalid address are readable causes`() {
        val missing = useFakeOllama(FakeOllama().apply { tags = "{\"models\":[{\"name\":\"llama3:8b\"}]}" })
        store.setNaturalAiAddress("100.65.88.40")
        store.loadNaturalAiModel()
        testDispatcher.scheduler.runCurrent()
        assertEquals(NaturalAiFailureKind.MODEL_MISSING, store.uiState.value.naturalAiProblem?.kind)
        assertEquals(false, store.uiState.value.naturalAiModelInstalled)
        assertEquals(0, missing.loadPosts)

        useFakeOllama(FakeOllama().apply { loadError = OllamaHttpException(404) })
        store.setNaturalAiAddress("100.65.88.41") // new address: clean loader
        store.loadNaturalAiModel()
        testDispatcher.scheduler.runCurrent()
        assertEquals(NaturalAiFailureKind.MODEL_MISSING, store.uiState.value.naturalAiProblem?.kind)

        useFakeOllama(FakeOllama().apply { loadError = OllamaHttpException(500) })
        store.setNaturalAiAddress("10.0.0.2")
        store.loadNaturalAiModel()
        testDispatcher.scheduler.runCurrent()
        assertEquals(NaturalAiProblem(NaturalAiFailureKind.HTTP_ERROR, httpCode = 500), store.uiState.value.naturalAiProblem)

        store.setNaturalAiAddress("8.8.8.8")
        store.loadNaturalAiModel()
        testDispatcher.scheduler.runCurrent()
        assertEquals(NaturalAiFailureKind.INVALID_ADDRESS, store.uiState.value.naturalAiProblem?.kind)
        assertEquals(NaturalAiModelState.FAILED, store.uiState.value.naturalAiModelState)
    }

    @Test
    fun `disabling the ai or changing the address cancels the preheat`() {
        val fake = useFakeOllama()
        store.setNaturalAiAddress("100.65.88.40")
        store.setNaturalAiEnabled(true)
        store.loadNaturalAiModel()
        advance(5_000)
        store.setNaturalAiEnabled(false)
        testDispatcher.scheduler.runCurrent()
        assertTrue(fake.loadCancelled)
        assertEquals(NaturalAiModelState.IDLE, store.uiState.value.naturalAiModelState)
        assertEquals(NaturalPlannerStatus.LOCAL, store.uiState.value.naturalPlannerStatus)
    }

    @Test
    fun `an unloaded model returns to the load step with a notice`() {
        val fake = loadReadyModel()
        fake.psLoaded = false
        advance(31_000)
        assertEquals(NaturalAiModelState.IDLE, store.uiState.value.naturalAiModelState)
        assertTrue(store.uiState.value.naturalAiModelUnloaded)
    }

    @Test
    fun `ai failure falls back silently and motion continues`() {
        val calls = countingPlanner { throw java.net.ConnectException("unreachable") }
        connect()
        selectNatural()
        loadReadyModel("192.168.1.20")
        store.setNaturalAiEnabled(true)
        store.startNatural()
        advance(30_000)

        assertTrue(calls() >= 1)
        assertEquals(NaturalPlannerStatus.AI_UNAVAILABLE, store.uiState.value.naturalPlannerStatus)
        assertEquals(NaturalAiFailureKind.UNREACHABLE, store.uiState.value.naturalAiProblem?.kind)
        assertTrue(store.uiState.value.naturalRunning)
        val before = speeds().size
        advance(30_000)
        assertTrue(speeds().size > before || store.uiState.value.naturalSpeedCurrent!! > 0f)
    }

    @Test
    fun `invalid ai json is rejected and the local generator stays in charge`() {
        val transport = object : OllamaTransport {
            override suspend fun get(path: String) = "{\"models\":[{\"name\":\"qwen3:8b\"}]}"
            override suspend fun post(path: String, body: String) =
                "{\"model\":\"qwen3:8b\",\"done\":true,\"response\":\"{\\\"schemaVersion\\\":1,\\\"phrases\\\":[{\\\"motif\\\":\\\"wave\\\",\\\"positionPercent\\\":90}]}\"}"
        }
        store.naturalPlannerFactory = { OllamaNaturalPlanner(transport) }
        connect()
        selectNatural()
        loadReadyModel("100.100.1.2")
        store.setNaturalAiEnabled(true)
        store.startNatural()
        advance(1_000)
        awaitStatus(NaturalPlannerStatus.AI_UNAVAILABLE)
        advance(1_000)

        assertEquals(NaturalPlannerStatus.AI_UNAVAILABLE, store.uiState.value.naturalPlannerStatus)
        assertEquals(NaturalAiFailureKind.INVALID_JSON, store.uiState.value.naturalAiProblem?.kind)
        assertEquals(NaturalAiModelState.LOADED, store.uiState.value.naturalAiModelState)
        assertTrue(store.uiState.value.naturalRunning)
    }

    @Test
    fun `a slow ai never delays motion and times out to local`() {
        val never = CompletableDeferred<NaturalPlan>()
        countingPlanner { never.await() }
        connect()
        selectNatural()
        loadReadyModel("192.168.1.20")
        store.setNaturalAiEnabled(true)
        store.startNatural()
        advance(30_000)
        // AI active: no local gesture, speed 0 while waiting for the first plan.
        assertTrue(speeds().isEmpty())
        assertTrue(store.uiState.value.naturalAiWaitingFirstPlan)
        assertTrue(store.uiState.value.naturalAiWaitSeconds >= 29)
        assertEquals(NaturalPlannerStatus.AI_READY, store.uiState.value.naturalPlannerStatus)

        advance(310_000)
        // Timeout = AI failing: explicit, visible fallback to the local generator.
        assertEquals(NaturalPlannerStatus.AI_UNAVAILABLE, store.uiState.value.naturalPlannerStatus)
        assertEquals(
            com.ossm.remote.natural.NaturalAiFallbackKind.AI_ERROR,
            store.uiState.value.naturalAiFallback?.kind,
        )
        assertFalse(store.uiState.value.naturalAiWaitingFirstPlan)
        assertTrue(store.uiState.value.naturalRunning)
        assertTrue(speeds().isNotEmpty())
    }

    @Test
    fun `stop during a plan request is not an availability failure`() {
        val plan = CompletableDeferred<NaturalPlan>()
        var cancelled = false
        countingPlanner {
            try {
                plan.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
        connect()
        selectNatural()
        loadReadyModel("pc.tail1234.ts.net")
        store.setNaturalAiEnabled(true)
        store.startNatural()
        advance(5_000)
        store.stop()
        testDispatcher.scheduler.runCurrent()
        assertTrue(cancelled)
        advance(10_000)
        assertEquals(NaturalPlannerStatus.AI_READY, store.uiState.value.naturalPlannerStatus)
        assertEquals(NaturalAiModelState.LOADED, store.uiState.value.naturalAiModelState)
        assertEquals(null, store.uiState.value.naturalAiProblem)

        countingPlanner { validPlan }
        loadReadyModel("pc2.tail1234.ts.net")
        store.startNatural()
        advance(2_000)
        assertEquals(NaturalPlannerStatus.AI_CONNECTED, store.uiState.value.naturalPlannerStatus)
    }

    @Test
    fun `ai disabled by default uses the local generator`() {
        assertFalse(store.uiState.value.naturalAiEnabled)
        assertEquals("", store.uiState.value.naturalAiAddress)
        assertEquals(NaturalPlannerStatus.LOCAL, store.uiState.value.naturalPlannerStatus)
    }

    @Test
    fun `browser select_pattern natural parses to the stable mode key`() {
        assertEquals(
            RemoteControlAction.SelectPattern(NATURAL_PATTERN_KEY),
            RemoteControlProtocol.parse("{\"type\":\"select_pattern\",\"key\":\"natural\"}"),
        )
        assertTrue(KnownFallbackPatterns.any { it.key == "natural" })
    }

    @Test
    fun `applying a natural preset restores settings without motion`() {
        connect()
        store.applyPreset(
            com.ossm.remote.model.Preset(
                name = "n",
                patternKey = NATURAL_PATTERN_KEY,
                patternName = "Naturel",
                speed = 0.8f,
                depthMin = 0.2f,
                depthMax = 0.6f,
            )
        )
        advance(20_000)
        assertEquals(NATURAL_PATTERN_KEY, store.uiState.value.activePatternKey)
        assertEquals(0.8f, store.uiState.value.naturalMaxSpeed)
        assertFalse(store.uiState.value.naturalRunning)
        assertEquals(emptyList<Sent>(), motionCommands())
    }

    // ---- 2.3.23: reasoning relay + adaptive AI look-ahead ----

    @Test
    fun `reasoning relay uses port 11435 on the same host with think, off keeps 11434`() {
        val endpoints = mutableListOf<com.ossm.remote.natural.NaturalAiEndpoint>()
        store.naturalPlannerFactory = { endpoint ->
            endpoints += endpoint
            object : NaturalPlanner {
                override suspend fun plan(input: NaturalPlanInput): NaturalPlan = validPlan
            }
        }
        val loaderEndpoints = mutableListOf<com.ossm.remote.natural.NaturalAiEndpoint>()
        val fake = FakeOllama().apply { psLoaded = true }
        store.naturalAiLoaderFactory = { endpoint -> loaderEndpoints += endpoint; OllamaModelLoader(fake) }
        connect()
        selectNatural()
        store.setNaturalAiAddress("100.65.88.40")
        store.setNaturalAiReasoningRelay(true)
        store.loadNaturalAiModel()
        testDispatcher.scheduler.runCurrent()
        store.setNaturalAiEnabled(true)
        store.startNatural()
        advance(2_000)

        assertTrue(store.uiState.value.naturalAiReasoningRelay)
        assertEquals(11435, loaderEndpoints.last().url.port)
        val endpoint = endpoints.last()
        assertEquals(11435, endpoint.url.port)
        assertEquals("100.65.88.40", endpoint.url.host)
        assertTrue(endpoint.reasoningRelay)
        assertTrue(com.ossm.remote.natural.NaturalAiPlannerFactory.plannerConfigFor(endpoint).think)

        store.setNaturalAiReasoningRelay(false)
        advance(2_000)
        val plain = endpoints.last()
        assertEquals(11434, plain.url.port)
        assertFalse(plain.reasoningRelay)
        assertFalse(com.ossm.remote.natural.NaturalAiPlannerFactory.plannerConfigFor(plain).think)
    }

    @Test
    fun `a 30 s ai latency keeps one request at a time and a reserve ahead`() {
        var inFlight = 0
        var maxInFlight = 0
        var calls = 0
        var completed = 0
        store.naturalPlannerFactory = {
            object : NaturalPlanner {
                override suspend fun plan(input: NaturalPlanInput): NaturalPlan {
                    calls++
                    inFlight++
                    maxInFlight = maxOf(maxInFlight, inFlight)
                    try {
                        kotlinx.coroutines.delay(30_000)
                    } finally {
                        inFlight--
                    }
                    completed++
                    return com.ossm.remote.natural.localNaturalPlan(1_000L + calls, input.phraseCount)
                }
            }
        }
        connect()
        selectNatural()
        loadReadyModel()
        store.setNaturalAiEnabled(true)
        store.startNatural()
        // Wait for the first AI plan, then watch the look-ahead for 10 minutes.
        var waited = 0
        while (completed == 0 && waited < 60) { advance(1_000); waited++ }
        assertEquals(1, completed)
        var minReserve = Int.MAX_VALUE
        repeat(600) {
            advance(1_000)
            minReserve = minOf(minReserve, store.uiState.value.naturalAiReserveSeconds ?: 0)
            // Any local gesture while the AI feeds the queue would raise a visible fallback.
            assertEquals(null, store.uiState.value.naturalAiFallback)
        }
        assertEquals(1, maxInFlight)
        assertTrue("reserve min=$minReserve", minReserve >= 10)
        assertEquals(30, store.uiState.value.naturalAiLastLatencySeconds)
        assertTrue(store.uiState.value.naturalRunning)
        assertTrue("calls=$calls", calls in 2..40)
    }

    // ---- 2.3.23: AI active means no local filler ----

    private fun gatedPlanner(firstImmediate: Boolean): Pair<() -> Int, () -> CompletableDeferred<NaturalPlan>> {
        var calls = 0
        var gate = CompletableDeferred<NaturalPlan>()
        store.naturalPlannerFactory = {
            object : NaturalPlanner {
                override suspend fun plan(input: NaturalPlanInput): NaturalPlan {
                    calls++
                    if (firstImmediate && calls == 1) return validPlan
                    return gate.await().also { gate = CompletableDeferred() }
                }
            }
        }
        return { calls } to { gate }
    }

    @Test
    fun `start with ai active waits for the first plan at speed 0`() {
        val (calls, gate) = gatedPlanner(firstImmediate = false)
        connect()
        selectNatural()
        loadReadyModel()
        store.setNaturalAiEnabled(true)
        store.startNatural()
        advance(20_000)
        assertEquals(1, calls())
        assertTrue(store.uiState.value.naturalRunning)
        assertTrue(store.uiState.value.naturalAiWaitingFirstPlan)
        assertTrue(store.uiState.value.naturalAiWaitSeconds >= 19)
        assertEquals(emptyList<Sent>(), motionCommands())

        gate().complete(validPlan)
        advance(2_000)
        assertFalse(store.uiState.value.naturalAiWaitingFirstPlan)
        assertTrue(speeds().isNotEmpty())
        assertEquals(NaturalPlannerStatus.AI_CONNECTED, store.uiState.value.naturalPlannerStatus)
        assertEquals(null, store.uiState.value.naturalAiFallback)
    }

    @Test
    fun `exhausted ai reserve switches visibly to local then back to ai on the next plan`() {
        val (calls, gate) = gatedPlanner(firstImmediate = true)
        connect()
        selectNatural()
        loadReadyModel()
        store.setNaturalAiEnabled(true)
        store.startNatural()
        advance(1_000)
        assertEquals(null, store.uiState.value.naturalAiFallback)
        advance(120_000) // the second plan never comes: the AI reserve runs out
        assertEquals(2, calls()) // asked once the reserve fell under the target, still pending
        val fallback = store.uiState.value.naturalAiFallback
        assertEquals(com.ossm.remote.natural.NaturalAiFallbackKind.RESERVE_EXHAUSTED, fallback?.kind)
        assertTrue("${fallback?.latencySeconds}", fallback!!.latencySeconds >= fallback.reserveSeconds)
        assertTrue(store.uiState.value.naturalRunning)
        val before = speeds().size
        advance(20_000)
        assertTrue(speeds().size > before || store.uiState.value.naturalSpeedCurrent!! > 0f)
        assertEquals(2, calls()) // still one request at a time

        gate().complete(validPlan)
        advance(1_000)
        assertEquals(null, store.uiState.value.naturalAiFallback)
        assertEquals(NaturalPlannerStatus.AI_CONNECTED, store.uiState.value.naturalPlannerStatus)
        assertTrue(store.uiState.value.naturalRunning)
    }

    @Test
    fun `stop cuts everything while waiting, feeding and in fallback`() {
        val (_, gate) = gatedPlanner(firstImmediate = false)
        connect()
        selectNatural()
        loadReadyModel()
        store.setNaturalAiEnabled(true)

        store.startNatural() // waiting for the first plan
        advance(5_000)
        sent.clear()
        store.stopNatural()
        advance(10_000)
        assertFalse(store.uiState.value.naturalRunning)
        assertFalse(store.uiState.value.naturalAiWaitingFirstPlan)
        assertTrue(sent.contains(Sent.Command(OssmCommand.Stop)))
        assertEquals(emptyList<Sent>(), motionCommands())

        store.startNatural() // feeding
        advance(1_000)
        gate().complete(validPlan)
        advance(3_000)
        assertTrue(speeds().isNotEmpty())
        sent.clear()
        store.stopNatural()
        advance(10_000)
        assertFalse(store.uiState.value.naturalRunning)
        assertEquals(emptyList<Sent>(), motionCommands())

        store.startNatural() // first plan fails: visible local fallback
        advance(1_000)
        gate().completeExceptionally(java.net.ConnectException("refused"))
        advance(3_000)
        assertTrue(store.uiState.value.naturalAiFallback != null)
        sent.clear()
        store.stopNatural()
        advance(10_000)
        assertFalse(store.uiState.value.naturalRunning)
        assertEquals(null, store.uiState.value.naturalAiFallback)
        assertTrue(sent.contains(Sent.Command(OssmCommand.Stop)))
        assertEquals(emptyList<Sent>(), motionCommands())
    }
}
