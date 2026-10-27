package com.ossm.remote.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceVoiceInterpreterTest {
    private class FakeAsr(var text: String) : SpeechTranscriber {
        var samplesSeen = 0
        override fun transcribe(samples: FloatArray, sampleRateHz: Int): String {
            samplesSeen = samples.size
            return text
        }
    }

    private class FakeLlm(var answer: String = "", var failure: Exception? = null) : VoiceStepGenerator {
        val calls = mutableListOf<String>()
        override suspend fun generate(utterance: String): String {
            calls += utterance
            failure?.let { throw it }
            return answer
        }
    }

    private class Recorder : VoiceAiTelemetry {
        val transcripts = mutableListOf<Pair<String, Long>>()
        val planned = mutableListOf<Pair<VoiceInterpretResult, Long>>()
        var stops = 0
        override fun onTranscript(text: String, speechToTextMs: Long) { transcripts += text to speechToTextMs }
        override fun onPlanned(result: VoiceInterpretResult, textToStepsMs: Long) { planned += result to textToStepsMs }
        override fun onStopKeyword() { stops++ }
    }

    private val json = """[{"cible":100,"duree":2500,"maintien":2000},{"cible":0,"duree":600,"maintien":0}]"""

    /** Clock advancing 100 ms per reading: latencies are deterministic. */
    private fun steppingClock(): () -> Long {
        var now = 0L
        return { now += 100; now }
    }

    private fun interpreter(asr: FakeAsr, llm: FakeLlm, recorder: Recorder = Recorder()) =
        OnDeviceVoiceInterpreter(asr, llm, recorder, steppingClock(), Dispatchers.Unconfined)

    @Test
    fun `STOP keyword wins before the LLM`() = runTest {
        val llm = FakeLlm(json)
        val recorder = Recorder()
        val result = interpreter(FakeAsr("Arrête, stop !"), llm, recorder)
            .interpret(VoiceInput.Audio(ShortArray(3200)))
        assertEquals(VoiceInterpretResult.Stop, result)
        assertTrue(llm.calls.isEmpty())
        assertEquals(1, recorder.stops)
        assertEquals("Arrête, stop !", recorder.transcripts.single().first)
    }

    @Test
    fun `STOP keyword in text input also skips the LLM`() = runTest {
        val llm = FakeLlm(json)
        assertEquals(VoiceInterpretResult.Stop, interpreter(FakeAsr(""), llm).interpret(VoiceInput.Text("pause")))
        assertTrue(llm.calls.isEmpty())
    }

    /** Audible segment (constant 0.25 amplitude): passes the low-energy guard. */
    private fun loud(size: Int) = ShortArray(size) { 8_000 }

    @Test
    fun `phrase handled by the rules skips the LLM with measured latencies`() = runTest {
        val asr = FakeAsr(" va au fond lentement et reste-y 2 secondes, puis reviens vite ")
        val llm = FakeLlm("```json\n$json\n```")
        val recorder = Recorder()
        val sources = mutableListOf<VoiceInterpretSource>()
        val telemetry = object : VoiceAiTelemetry by recorder {
            override fun onPlanned(result: VoiceInterpretResult, textToStepsMs: Long, source: VoiceInterpretSource) {
                sources += source
                recorder.onPlanned(result, textToStepsMs)
            }
        }
        val result = OnDeviceVoiceInterpreter(asr, llm, telemetry, steppingClock(), Dispatchers.Unconfined)
            .interpret(VoiceInput.Audio(loud(16_000)))
        assertEquals(
            VoiceInterpretResult.Steps(listOf(VoiceStep(100, 2_500, 2_000), VoiceStep(0, 600, 0))),
            result,
        )
        assertEquals(16_000, asr.samplesSeen)
        assertTrue(llm.calls.isEmpty())
        assertEquals(listOf(VoiceInterpretSource.RULES), sources)
        assertEquals(100L, recorder.transcripts.single().second)
        assertEquals(100L, recorder.planned.single().second)
    }

    @Test
    fun `phrase unknown to the rules goes to the LLM`() = runTest {
        val llm = FakeLlm("```json\n$json\n```")
        val recorder = Recorder()
        val result = interpreter(FakeAsr("fais-moi un truc sympa"), llm, recorder).interpret(VoiceInput.Audio(loud(1_600)))
        assertEquals(
            VoiceInterpretResult.Steps(listOf(VoiceStep(100, 2_500, 2_000), VoiceStep(0, 600, 0))),
            result,
        )
        assertEquals(listOf("fais-moi un truc sympa"), llm.calls)
        assertEquals(100L, recorder.planned.single().second)
    }

    @Test
    fun `invalid JSON, explicit refusal or LLM failure are not understood`() = runTest {
        for (answer in listOf("Je ne sais pas", """{"erreur":"non compris"}""", "[{\"cible\":")) {
            assertEquals(
                answer,
                VoiceInterpretResult.NotUnderstood,
                interpreter(FakeAsr("fais un truc"), FakeLlm(answer)).interpret(VoiceInput.Audio(loud(160))),
            )
        }
        val failing = FakeLlm(failure = IllegalStateException("gpu"))
        assertEquals(
            VoiceInterpretResult.NotUnderstood,
            interpreter(FakeAsr("fais un truc"), failing).interpret(VoiceInput.Audio(loud(160))),
        )
    }

    @Test
    fun `empty transcript is ignored without calling the LLM`() = runTest {
        val llm = FakeLlm(json)
        assertEquals(
            VoiceInterpretResult.Ignored,
            interpreter(FakeAsr("   "), llm).interpret(VoiceInput.Audio(loud(160))),
        )
        assertTrue(llm.calls.isEmpty())
    }

    @Test
    fun `out of bounds plan is passed on for strict rejection`() = runTest {
        val result = interpreter(FakeAsr("fais un truc"), FakeLlm("""[{"cible":150,"duree":1000,"maintien":0}]"""))
            .interpret(VoiceInput.Audio(loud(160)))
        val steps = (result as VoiceInterpretResult.Steps).steps
        assertEquals(null, VoiceStepValidator.validate(steps))
    }

    // ---- 2.3.31 anti-hallucination guard ----

    private class IgnoreRecorder : VoiceAiTelemetry {
        val reasons = mutableListOf<VoiceIgnoreReason>()
        override fun onIgnored(reason: VoiceIgnoreReason) { reasons += reason }
    }

    private suspend fun ignoredReason(transcript: String, audio: ShortArray = loud(1_600)): VoiceIgnoreReason? {
        val llm = FakeLlm(json)
        val recorder = IgnoreRecorder()
        val result = OnDeviceVoiceInterpreter(FakeAsr(transcript), llm, recorder, steppingClock(), Dispatchers.Unconfined)
            .interpret(VoiceInput.Audio(audio))
        if (result == VoiceInterpretResult.Ignored) {
            assertTrue("LLM must not run for an ignored transcript", llm.calls.isEmpty())
            return recorder.reasons.single()
        }
        return null
    }

    @Test
    fun `typical Whisper hallucinations never move`() = runTest {
        assertEquals(VoiceIgnoreReason.HALLUCINATION, ignoredReason("Sous-titres réalisés par la communauté d'Amara.org"))
        assertEquals(VoiceIgnoreReason.HALLUCINATION, ignoredReason("Merci d'avoir regardé cette vidéo !"))
        assertEquals(VoiceIgnoreReason.HALLUCINATION, ignoredReason("Abonnez-vous à la chaîne"))
        assertEquals(VoiceIgnoreReason.HALLUCINATION, ignoredReason("Merci."))
        assertEquals(VoiceIgnoreReason.PUNCTUATION_ONLY, ignoredReason("…"))
        assertEquals(VoiceIgnoreReason.PUNCTUATION_ONLY, ignoredReason("."))
        assertEquals(VoiceIgnoreReason.REPETITION, ignoredReason("fond fond fond fond"))
        assertEquals(VoiceIgnoreReason.TOO_LONG, ignoredReason("va au fond ".repeat(25)))
    }

    @Test
    fun `too quiet segment is ignored`() = runTest {
        assertEquals(VoiceIgnoreReason.LOW_ENERGY, ignoredReason("va au fond", ShortArray(1_600) { 20 }))
    }

    @Test
    fun `STOP word wins over every guard`() = runTest {
        val cases = listOf(
            "Sous-titres réalisés par stop" to loud(1_600),
            "stop stop stop stop stop" to loud(1_600),
            "stop" to ShortArray(1_600),
            ("va au fond ".repeat(25) + " stop") to loud(1_600),
        )
        for ((text, audio) in cases) {
            val llm = FakeLlm(json)
            val result = interpreter(FakeAsr(text), llm).interpret(VoiceInput.Audio(audio))
            assertEquals(text, VoiceInterpretResult.Stop, result)
            assertTrue(llm.calls.isEmpty())
        }
    }

    @Test
    fun `normal phrase passes the guard`() {
        assertEquals(null, VoiceTranscriptGuard.textReason("Va au fond lentement, puis reviens."))
        assertEquals(null, VoiceTranscriptGuard.textReason("va va va"))
        assertTrue(VoiceTranscriptGuard.rms(loud(10)) > VoiceTranscriptGuard.MIN_RMS)
    }
}
