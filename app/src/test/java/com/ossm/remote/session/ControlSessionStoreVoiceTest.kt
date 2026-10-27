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
import com.ossm.remote.model.VOICE_PATTERN_KEY
import com.ossm.remote.voice.FakeVoiceCommandInterpreter
import com.ossm.remote.voice.OnDeviceVoiceInterpreter
import com.ossm.remote.voice.SpeechTranscriber
import com.ossm.remote.voice.VoiceStepGenerator
import com.ossm.remote.voice.VoiceInput
import com.ossm.remote.voice.VoiceInterpretResult
import com.ossm.remote.voice.VoiceInterpretation
import com.ossm.remote.voice.VoiceStep
import com.ossm.remote.voice.VoiceModulation
import com.ossm.remote.voice.VoiceMotif
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
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
class ControlSessionStoreVoiceTest {
    private val dispatcher = StandardTestDispatcher()
    private val sent = mutableListOf<OssmCommand>()
    private lateinit var store: ControlSessionStore
    private lateinit var connection: MutableStateFlow<BleConnectionState>
    private lateinit var streamingReady: MutableStateFlow<Boolean>
    private lateinit var depthLimitMax: MutableStateFlow<Float>
    private lateinit var ble: BleManager
    private lateinit var safety: ControlSafetySettingsRepository
    private val voiceMin = MutableStateFlow(0f)
    private val voiceMax = MutableStateFlow(1f)
    private val journal = mutableListOf<String>()
    private val shortWordStop = MutableStateFlow(true)
    private val linkSuspect = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ble = mockk<BleManager>(relaxed = true)
        safety = mockk<ControlSafetySettingsRepository>(relaxed = true)
        every { ble.armLiveSpeed(any()) } returns true
        every { ble.appendExternalDiagnostic(any(), any(), any()) } answers { journal += thirdArg<String>() }
        every { safety.voiceDepthMin } returns voiceMin
        every { safety.voiceDepthMax } returns voiceMax
        every { safety.voiceShortWordStop } returns shortWordStop
        every { ble.linkSuspectEvents } returns linkSuspect
        val habits = mockk<UserHabitsRepository>(relaxed = true)
        connection = MutableStateFlow(BleConnectionState.Disconnected)
        streamingReady = MutableStateFlow(false)
        depthLimitMax = MutableStateFlow(1f)
        every { ble.availablePatterns } returns MutableStateFlow(KnownFallbackPatterns)
        every { ble.machineState } returns MutableStateFlow(MachineState(state = "streaming"))
        every { ble.postSetupReady } returns MutableStateFlow(true)
        every { ble.streamingReady } returns streamingReady
        every { ble.connectionState } returns connection
        every { ble.sendCommand(any()) } answers { sent += firstArg<OssmCommand>() }
        every { safety.settings } returns MutableStateFlow(
            ControlSafetySettings(
                speed = SliderGuardSettings(enabled = false, thresholdPercent = 10),
                depth = SliderGuardSettings(enabled = false, thresholdPercent = 5),
            )
        )
        every { safety.depthLimitMin } returns MutableStateFlow(0f)
        every { safety.depthLimitMax } returns depthLimitMax
        every { safety.liveInvertEnabled } returns MutableStateFlow(false)
        every { safety.liveAccelerationPercent } returns MutableStateFlow(33)
        every { safety.precisionOverrides } returns MutableStateFlow(emptyMap())
        every { safety.naturalAiEnabled } returns MutableStateFlow(false)
        every { safety.naturalAiAddress } returns MutableStateFlow("")
        every { safety.naturalAiReasoningRelay } returns MutableStateFlow(false)
        every { habits.sessionDefaults } returns flowOf(SessionDefaults(null, null, null))
        every { habits.patternOrder } returns flowOf(emptyList())
        store = ControlSessionStore(ble, safety, habits)
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun advance(ms: Long) {
        dispatcher.scheduler.advanceTimeBy(ms)
        dispatcher.scheduler.runCurrent()
    }

    private fun enterVoice() {
        connection.value = BleConnectionState.Connected("OSSM", "AA:BB:CC:DD:EE:FF")
        dispatcher.scheduler.runCurrent()
        store.activatePattern(VOICE_PATTERN_KEY)
        dispatcher.scheduler.runCurrent()
        streamingReady.value = true
        dispatcher.scheduler.runCurrent()
    }

    private fun streams() = sent.filterIsInstance<OssmCommand.Stream>()

    private val example = listOf(VoiceStep(100, 2_500, 2_000), VoiceStep(0, 600, 0))

    @Test
    fun `voice is listed and entering the mode sends no target`() {
        assertTrue(store.uiState.value.availablePatterns.any { it.key == VOICE_PATTERN_KEY })
        enterVoice()
        advance(30_000)
        assertEquals(VOICE_PATTERN_KEY, store.uiState.value.activePatternKey)
        assertEquals(emptyList<OssmCommand.Stream>(), streams())
        assertFalse(store.uiState.value.voiceModelInstalled)
    }

    @Test
    fun `accepted plan runs through Live targets and stays in place at the end`() {
        enterVoice()
        assertTrue(store.voiceExecuteSteps(example))
        advance(2_600)
        assertEquals(100, store.uiState.value.streamPositionPercent)
        advance(2_000 + 700)
        assertEquals(0, store.uiState.value.streamPositionPercent)
        assertEquals(0, streams().last().positionPercent)
        assertFalse(store.uiState.value.voiceRunning)
        val count = streams().size
        advance(10_000)
        assertEquals(count, streams().size)
    }

    @Test
    fun `stopNow cancels the sequence and sends STOP with no further target`() {
        enterVoice()
        store.voiceExecuteSteps(example)
        advance(1_000)
        store.voiceStopNow()
        val count = streams().size
        assertTrue(sent.last() is OssmCommand.Stop)
        advance(10_000)
        assertEquals(count, streams().size)
        assertEquals(VoiceInterpretation.STOPPED, store.uiState.value.voiceInterpretation)
    }

    @Test
    fun `interpreter STOP result interrupts a running sequence`() {
        enterVoice()
        val fake = FakeVoiceCommandInterpreter(VoiceInterpretResult.Steps(example))
        store.voiceInterpreter = fake
        assertTrue(store.uiState.value.voiceModelInstalled)
        store.interpretVoiceInput(VoiceInput.Text("va au fond"))
        advance(800)
        assertTrue(store.uiState.value.voiceRunning)
        fake.next = VoiceInterpretResult.Stop
        store.interpretVoiceInput(VoiceInput.Text("stop"))
        dispatcher.scheduler.runCurrent()
        val count = streams().size
        advance(10_000)
        assertEquals(count, streams().size)
        assertTrue(sent.any { it is OssmCommand.Stop })
    }

    @Test
    fun `invalid plan is rejected and nothing moves`() {
        enterVoice()
        assertFalse(store.voiceExecuteSteps(listOf(VoiceStep(120, 1_000, 0))))
        assertFalse(store.voiceExecuteSteps(listOf(VoiceStep(50, 100, 0))))
        advance(5_000)
        assertEquals(emptyList<OssmCommand.Stream>(), streams())
        assertEquals(VoiceInterpretation.REJECTED, store.uiState.value.voiceInterpretation)
    }

    @Test
    fun `speed maximum and depth limit are respected`() {
        depthLimitMax.value = 0.6f
        enterVoice()
        store.setVoiceMaxSpeed(0.1f)
        store.voiceExecuteSteps(listOf(VoiceStep(100, 300, 0)))
        assertEquals(2_500, store.uiState.value.voicePlan.single().moveMs)
        advance(1_000)
        assertTrue(store.uiState.value.streamPositionPercent < 60)
        advance(2_000)
        assertEquals(60, store.uiState.value.streamPositionPercent)
        assertTrue(streams().all { it.positionPercent in 0..60 }) // never beyond the hard depth limit
    }

    @Test
    fun `zero maximum, not ready or other mode never move`() {
        enterVoice()
        store.setVoiceMaxSpeed(0f)
        assertFalse(store.voiceExecuteSteps(example))
        store.setVoiceMaxSpeed(0.5f)
        store.activatePattern(KnownStreamingPattern.key)
        dispatcher.scheduler.runCurrent()
        assertFalse(store.voiceExecuteSteps(example))
        advance(5_000)
        assertEquals(emptyList<OssmCommand.Stream>(), streams())
    }

    @Test
    fun `BLE loss and mode exit stop the sequence`() {
        enterVoice()
        store.voiceExecuteSteps(example)
        advance(500)
        connection.value = BleConnectionState.Disconnected
        dispatcher.scheduler.runCurrent()
        val count = streams().size
        advance(10_000)
        assertEquals(count, streams().size)
        assertFalse(store.uiState.value.voiceRunning)
    }

    @Test
    fun `browser cannot select voice`() {
        store.activateRemotePattern(VOICE_PATTERN_KEY)
        dispatcher.scheduler.runCurrent()
        assertFalse(store.uiState.value.activePatternKey == VOICE_PATTERN_KEY)
    }
    // ---- 2.3.27: on-device pipeline (fake ASR / fake LLM, no native library) ----

    private class ScriptedAsr(var text: String) : SpeechTranscriber {
        override fun transcribe(samples: FloatArray, sampleRateHz: Int): String = text
    }

    private class ScriptedLlm(var answer: String) : VoiceStepGenerator {
        val calls = mutableListOf<String>()
        override suspend fun generate(utterance: String): String {
            calls += utterance
            return answer
        }
    }

    private fun onDevice(asr: SpeechTranscriber, llm: VoiceStepGenerator) =
        OnDeviceVoiceInterpreter(asr, llm, asrContext = dispatcher)

    private val exampleJson =
        """[{"cible":100,"duree":2500,"maintien":2000},{"cible":0,"duree":600,"maintien":0}]"""

    @Test
    fun `spoken phrase understood by the rules runs without the LLM`() {
        enterVoice()
        val llm = ScriptedLlm("```json\n$exampleJson\n```")
        store.voiceInterpreter = onDevice(ScriptedAsr("va au fond lentement et reste-y 2 secondes, puis reviens vite"), llm)
        store.interpretVoiceInput(VoiceInput.Audio(ShortArray(1600) { 8_000 }))
        dispatcher.scheduler.runCurrent()
        // 2.3.31: deterministic rules answer first; Gemma is not called.
        assertTrue(llm.calls.isEmpty())
        assertEquals(VoiceInterpretation.PLANNED, store.uiState.value.voiceInterpretation)
        assertEquals(example, store.uiState.value.voicePlan)
        advance(2_600)
        assertEquals(100, store.uiState.value.streamPositionPercent)
    }

    @Test
    fun `spoken STOP keyword stops at once without calling the LLM`() {
        enterVoice()
        assertTrue(store.voiceExecuteSteps(example))
        advance(800)
        val llm = ScriptedLlm(exampleJson)
        store.voiceInterpreter = onDevice(ScriptedAsr("Arrête !"), llm)
        store.interpretVoiceInput(VoiceInput.Audio(ShortArray(16_000)))
        dispatcher.scheduler.runCurrent()
        assertTrue(llm.calls.isEmpty())
        assertEquals(VoiceInterpretation.STOPPED, store.uiState.value.voiceInterpretation)
        assertTrue(sent.last() is OssmCommand.Stop)
        val count = streams().size
        advance(10_000)
        assertEquals(count, streams().size)
    }

    @Test
    fun `invalid LLM answer is not understood and nothing moves`() {
        enterVoice()
        store.voiceInterpreter = onDevice(ScriptedAsr("fais quelque chose"), ScriptedLlm("Bien sûr ! Je vais au fond."))
        store.interpretVoiceInput(VoiceInput.Audio(ShortArray(1600) { 8_000 }))
        dispatcher.scheduler.runCurrent()
        assertEquals(VoiceInterpretation.NOT_UNDERSTOOD, store.uiState.value.voiceInterpretation)
        advance(5_000)
        assertEquals(emptyList<OssmCommand.Stream>(), streams())
    }

    @Test
    fun `out of bounds LLM plan is rejected and nothing moves`() {
        enterVoice()
        store.voiceInterpreter = onDevice(
            ScriptedAsr("va au fond en un éclair"),
            ScriptedLlm("""[{"cible":100,"duree":50,"maintien":0}]"""),
        )
        store.interpretVoiceInput(VoiceInput.Audio(ShortArray(1600) { 8_000 }))
        dispatcher.scheduler.runCurrent()
        assertEquals(VoiceInterpretation.REJECTED, store.uiState.value.voiceInterpretation)
        advance(5_000)
        assertEquals(emptyList<OssmCommand.Stream>(), streams())
    }

    @Test
    fun `empty transcript changes nothing`() {
        enterVoice()
        val llm = ScriptedLlm(exampleJson)
        store.voiceInterpreter = onDevice(ScriptedAsr("  "), llm)
        store.interpretVoiceInput(VoiceInput.Audio(ShortArray(1600)))
        dispatcher.scheduler.runCurrent()
        assertTrue(llm.calls.isEmpty())
        assertEquals(VoiceInterpretation.NONE, store.uiState.value.voiceInterpretation)
        assertEquals(emptyList<OssmCommand.Stream>(), streams())
    }

    // ---- 2.3.28: speed arming, Voix depth range, continuous motif ----

    @Test
    fun `plan accepted without any prior touch arms the Live speed and sends targets`() {
        enterVoice()
        assertTrue(store.voiceExecuteSteps(listOf(VoiceStep(60, 1_000, 0))))
        verify(exactly = 1) { ble.armLiveSpeed(any()) }
        advance(1_200)
        assertTrue(streams().isNotEmpty())
        assertEquals(60, streams().last().positionPercent)
        assertTrue(journal.any { it.startsWith("Voix : étape 1/1 cible 60 % en") })
    }

    @Test
    fun `transport refusing to arm is journaled and nothing moves`() {
        every { ble.armLiveSpeed(any()) } returns false
        enterVoice()
        assertFalse(store.voiceExecuteSteps(example))
        advance(5_000)
        assertEquals(emptyList<OssmCommand.Stream>(), streams())
        assertEquals(VoiceInterpretation.NOT_READY, store.uiState.value.voiceInterpretation)
        assertTrue(journal.any { it.contains("non armée") })
    }

    @Test
    fun `voice range maps 0 to the minimum and 100 to the maximum without a jump`() {
        enterVoice()
        store.setVoiceDepthRange(0.2f, 0.8f)
        assertTrue(store.voiceExecuteSteps(listOf(VoiceStep(100, 2_000, 500), VoiceStep(0, 2_000, 0))))
        advance(2_600)
        assertEquals(80, store.uiState.value.streamPositionPercent)
        advance(3_000)
        assertEquals(20, store.uiState.value.streamPositionPercent)
        val positions = streams().map { it.positionPercent }
        assertTrue(positions.all { it in 0..80 })
        // Entry at 0 joins the range minimum through the bounded ramp, never as a jump.
        positions.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", kotlin.math.abs(b - a) <= 6) }
    }

    @Test
    fun `voice range is persisted and clamped into the hard limits`() {
        depthLimitMax.value = 0.6f
        dispatcher.scheduler.runCurrent()
        store.setVoiceDepthRange(0.9f, 0.1f)
        dispatcher.scheduler.runCurrent()
        coVerify { safety.setVoiceDepthRange(0.1f, 0.9f) }
        enterVoice()
        store.voiceExecuteSteps(listOf(VoiceStep(100, 1_000, 0)))
        advance(3_000)
        assertEquals(60, store.uiState.value.streamPositionPercent)
        assertTrue(streams().all { it.positionPercent <= 60 })
    }

    @Test
    fun `stored voice range is loaded at start`() {
        voiceMin.value = 0.3f
        voiceMax.value = 0.7f
        dispatcher.scheduler.runCurrent()
        assertEquals(0.3f, store.uiState.value.voiceDepthMin, 0.0001f)
        assertEquals(0.7f, store.uiState.value.voiceDepthMax, 0.0001f)
    }

    @Test
    fun `changing the range during a plan cancels it cleanly then the next plan has no jump`() {
        enterVoice()
        store.voiceExecuteSteps(listOf(VoiceStep(100, 4_000, 0)))
        advance(2_000)
        val reached = store.uiState.value.streamPositionPercent
        assertTrue(reached in 40..60)
        store.setVoiceDepthRange(0f, 0.3f)
        assertFalse(store.uiState.value.voiceRunning)
        assertFalse(sent.any { it is OssmCommand.Stop })
        val count = streams().size
        advance(5_000)
        assertEquals("cancel holds in place", count, streams().size)
        store.voiceExecuteSteps(listOf(VoiceStep(0, 3_000, 0)))
        advance(6_000)
        val after = streams().drop(count - 1).map { it.positionPercent }
        after.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", kotlin.math.abs(b - a) <= 6) }
        assertEquals(0, store.uiState.value.streamPositionPercent)
    }

    @Test
    fun `motif is accepted, strokes continuously inside the range and STOP cuts it`() {
        enterVoice()
        store.setVoiceDepthRange(0.1f, 0.9f)
        assertTrue(store.voiceExecuteMotif(VoiceMotif(30, 80, 20, 60, 10)))
        verify { ble.armLiveSpeed(any()) }
        advance(20_000)
        val positions = streams().map { it.positionPercent }
        assertTrue(positions.size > 50)
        assertTrue(positions.all { it in 0..90 })
        assertTrue("returns towards the minimum", positions.drop(20).any { it <= 15 })
        assertTrue("reaches the final peak (80 % of 10..90 = 74)", positions.any { it >= 70 })
        positions.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", kotlin.math.abs(b - a) <= 20) }
        assertTrue(store.uiState.value.voiceRunning)
        store.voiceStopNow()
        val count = streams().size
        assertTrue(sent.last() is OssmCommand.Stop)
        advance(10_000)
        assertEquals(count, streams().size)
        assertEquals(null, store.uiState.value.voiceMotif)
    }

    @Test
    fun `out of bounds motif is not understood and nothing moves`() {
        enterVoice()
        assertFalse(store.voiceExecuteMotif(VoiceMotif(30, 120, 20, 60, 10)))
        assertFalse(store.voiceExecuteMotif(VoiceMotif(30, 80, 0, 60, 10)))
        assertFalse(store.voiceExecuteMotif(VoiceMotif(30, 80, 20, 60, 301)))
        advance(5_000)
        assertEquals(emptyList<OssmCommand.Stream>(), streams())
        assertEquals(VoiceInterpretation.NOT_UNDERSTOOD, store.uiState.value.voiceInterpretation)
    }

    @Test
    fun `modulation changes the running motif and is refused without one`() {
        enterVoice()
        assertFalse(store.voiceModulate(VoiceModulation.FASTER))
        store.voiceExecuteMotif(VoiceMotif(40, 60, 20, 40, 1))
        advance(3_000)
        assertTrue(store.voiceModulate(VoiceModulation.FASTER))
        assertEquals(55, store.uiState.value.voiceMotif?.speedStart)
        assertTrue(store.voiceModulate(VoiceModulation.DEEPER))
        assertEquals(70, store.uiState.value.voiceMotif?.depthEnd)
        assertTrue(store.uiState.value.voiceRunning)
    }

    @Test
    fun `spoken motif then spoken modulation go through the LLM path`() {
        enterVoice()
        val llm = ScriptedLlm("""{"motif":"va-et-vient","profondeur_debut":20,"profondeur_fin":60,"vitesse_debut":10,"vitesse_fin":30,"montee_s":60}""")
        store.voiceInterpreter = onDevice(ScriptedAsr("commence par un rythme timide"), llm)
        store.interpretVoiceInput(VoiceInput.Audio(ShortArray(1600) { 8_000 }))
        dispatcher.scheduler.runCurrent()
        // Floors raise a timid LLM answer (2.3.29).
        assertEquals(VoiceMotif(30, 60, 20, 40, 60), store.uiState.value.voiceMotif)
        advance(2_000)
        llm.answer = """{"modulation":"garder"}"""
        store.interpretVoiceInput(VoiceInput.Audio(ShortArray(16_000) { 8_000 }))
        dispatcher.scheduler.runCurrent()
        val held = store.uiState.value.voiceMotif!!
        assertEquals(held.depthStart, held.depthEnd)
        assertEquals(held.speedStart, held.speedEnd)
        assertTrue(store.uiState.value.voiceRunning)
    }

    // ---- 2.3.29: speed-sized targets, scale, floors, safety stops ----

    private fun voiceStreams() = streams().filter { it.producer == com.ossm.remote.model.LiveTargetProducer.VOICE }

    @Test
    fun `voice targets carry their duration and a speed sized step`() {
        enterVoice()
        store.voiceExecuteSteps(listOf(VoiceStep(100, 1_000, 0)))
        advance(1_200)
        val v = voiceStreams()
        assertEquals(listOf(20, 40, 60, 80, 100), v.map { it.positionPercent })
        assertTrue(v.all { it.timeMs == 200 })
    }

    @Test
    fun `motif depth covers the whole default voice range`() {
        enterVoice()
        assertEquals(0f, store.uiState.value.voiceDepthMin, 0f)
        assertEquals(1f, store.uiState.value.voiceDepthMax, 0f)
        store.voiceExecuteMotif(VoiceMotif(100, 100, 100, 100, 1))
        advance(10_000)
        val p = voiceStreams().map { it.positionPercent }
        // Logical 100 = scalar 100 (fond); the BLE layer applies the Live inverse mapping.
        assertTrue(p.contains(100))
        assertTrue(p.contains(0))
    }

    @Test
    fun `too timid motif gets the start floors and a journal line per motif`() {
        enterVoice()
        store.voiceExecuteMotif(VoiceMotif(5, 10, 1, 5, 300))
        assertEquals(VoiceMotif(30, 60, 20, 40, 300), store.uiState.value.voiceMotif)
        assertEquals(1, journal.count { it.startsWith("Voix : motif prof 30→60 %, vitesse 20→40 %, montée 300 s") })
        advance(5_000)
        // One line per motif / level change, never one per target.
        assertTrue(journal.size < 10)
    }

    @Test
    fun `app in background cancels the motif with STOP and nothing resumes`() {
        enterVoice()
        store.voiceExecuteMotif(VoiceMotif(30, 90, 20, 70, 25))
        advance(2_000)
        store.onAppBackground()
        assertTrue(sent.last() is OssmCommand.Stop)
        assertFalse(store.uiState.value.voiceRunning)
        val count = streams().size
        advance(60_000)
        assertEquals(count, streams().size)
        assertTrue(journal.any { it.contains("arrière-plan") })
    }

    @Test
    fun `missed connection ping cancels the motif with STOP and nothing resumes`() {
        enterVoice()
        store.voiceExecuteMotif(VoiceMotif(30, 90, 20, 70, 25))
        advance(2_000)
        linkSuspect.tryEmit(Unit)
        dispatcher.scheduler.runCurrent()
        assertTrue(sent.last() is OssmCommand.Stop)
        val count = streams().size
        advance(60_000)
        assertEquals(count, streams().size)
        assertFalse(store.uiState.value.voiceRunning)
    }

    @Test
    fun `short isolated word while moving is a STOP without ASR nor LLM`() {
        enterVoice()
        val fake = FakeVoiceCommandInterpreter(VoiceInterpretResult.NotUnderstood)
        store.voiceInterpreter = fake
        store.voiceExecuteMotif(VoiceMotif(30, 90, 20, 70, 25))
        advance(1_000)
        store.interpretVoiceInput(VoiceInput.Audio(ShortArray(8_000))) // 0.5 s
        dispatcher.scheduler.runCurrent()
        assertTrue(fake.inputs.isEmpty())
        assertTrue(sent.last() is OssmCommand.Stop)
        assertTrue(journal.any { it == "STOP vocal (mot court)" })
        // Idle: a short segment is interpreted normally.
        store.interpretVoiceInput(VoiceInput.Audio(ShortArray(8_000)))
        dispatcher.scheduler.runCurrent()
        assertEquals(1, fake.inputs.size)
    }

    @Test
    fun `short word STOP can be disabled`() {
        shortWordStop.value = false
        dispatcher.scheduler.runCurrent()
        enterVoice()
        val fake = FakeVoiceCommandInterpreter(VoiceInterpretResult.NotUnderstood)
        store.voiceInterpreter = fake
        store.voiceExecuteMotif(VoiceMotif(30, 90, 20, 70, 25))
        advance(1_000)
        store.interpretVoiceInput(VoiceInput.Audio(ShortArray(8_000)))
        dispatcher.scheduler.runCurrent()
        assertEquals(1, fake.inputs.size)
        assertTrue(store.uiState.value.voiceRunning)
    }

    @Test
    fun `misheard stop keyword from the ASR stops a motif`() {
        enterVoice()
        val llm = ScriptedLlm(exampleJson)
        store.voiceInterpreter = onDevice(ScriptedAsr("I right"), llm)
        store.voiceExecuteMotif(VoiceMotif(30, 90, 20, 70, 25))
        advance(1_000)
        store.interpretVoiceInput(VoiceInput.Audio(ShortArray(16_000)))
        dispatcher.scheduler.runCurrent()
        assertTrue(llm.calls.isEmpty())
        assertTrue(sent.last() is OssmCommand.Stop)
        assertTrue(journal.any { it == "STOP vocal (mot-clé)" })
    }

    @Test
    fun `volume key STOP stops the voice motif and reports a motion mode`() {
        enterVoice()
        assertTrue(store.isMotionModeActive())
        store.voiceExecuteMotif(VoiceMotif(30, 90, 20, 70, 25))
        advance(1_000)
        store.hardwareStop()
        assertTrue(sent.last() is OssmCommand.Stop)
        assertTrue(journal.any { it == "STOP vocal (touche volume)" })
        val count = streams().size
        advance(10_000)
        assertEquals(count, streams().size)
    }
}
