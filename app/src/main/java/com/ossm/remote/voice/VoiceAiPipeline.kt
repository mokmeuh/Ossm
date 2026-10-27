package com.ossm.remote.voice

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Voix 2.3.27 pipeline seams. Native engines (sherpa-onnx, LiteRT-LM) live behind these
 * interfaces so the JVM tests never load a native library.
 */

/** Offline speech-to-text for one VAD segment (16 kHz mono float samples in -1..1). Blocking. */
interface SpeechTranscriber {
    fun transcribe(samples: FloatArray, sampleRateHz: Int): String
}

/** On-device LLM: one French phrase -> raw model answer (expected JSON). */
interface VoiceStepGenerator {
    suspend fun generate(utterance: String): String
}

/** Telemetry for the screen. Transcripts are shown, never journaled by default. */
interface VoiceAiTelemetry {
    /** [speechToTextMs]: end of speech (segment delivered by the VAD) -> text available. */
    fun onTranscript(text: String, speechToTextMs: Long) {}

    /** [textToStepsMs]: text -> parsed steps (or "not understood"). */
    fun onPlanned(result: VoiceInterpretResult, textToStepsMs: Long) {}

    /** 2.3.31: same as [onPlanned] with the translator that answered (rules or Gemma). */
    fun onPlanned(result: VoiceInterpretResult, textToStepsMs: Long, source: VoiceInterpretSource) =
        onPlanned(result, textToStepsMs)

    /** 2.3.31: the transcript was ignored by [VoiceTranscriptGuard] (nothing moves). */
    fun onIgnored(reason: VoiceIgnoreReason) {}

    fun onStopKeyword() {}
}

/**
 * Speech -> text -> STOP keyword (before anything else) -> anti-hallucination guard ->
 * deterministic French rules (2.3.31, latency ~0) -> LLM only when the rules are not certain
 * -> tolerant parse. The store then validates the result strictly and runs it. A failing LLM
 * call is "not understood".
 */
class OnDeviceVoiceInterpreter(
    private val transcriber: SpeechTranscriber,
    private val generator: VoiceStepGenerator,
    private val telemetry: VoiceAiTelemetry = object : VoiceAiTelemetry {},
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val asrContext: CoroutineContext = Dispatchers.Default,
    /** 2.3.31: store context for the rules ("un peu plus", "encore"); null = unknown. */
    private val ruleContext: () -> VoiceRuleContext? = { null },
) : VoiceCommandInterpreter {
    override val isInstalled: Boolean = true

    override suspend fun interpret(input: VoiceInput): VoiceInterpretResult {
        var tooQuiet = false
        val text = when (input) {
            is VoiceInput.Text -> input.text
            is VoiceInput.Audio -> {
                val started = clockMs()
                tooQuiet = VoiceTranscriptGuard.isTooQuiet(input.pcm16)
                val samples = FloatArray(input.pcm16.size) { input.pcm16[it] / 32768f }
                val transcript = withContext(asrContext) {
                    transcriber.transcribe(samples, input.sampleRateHz)
                }.trim()
                telemetry.onTranscript(transcript, clockMs() - started)
                transcript
            }
        }
        if (text.isBlank()) {
            telemetry.onIgnored(VoiceIgnoreReason.EMPTY)
            return VoiceInterpretResult.Ignored
        }
        // STOP first: never wait for anything, and it wins over every guard below.
        if (VoiceStopKeywords.isStop(text)) {
            telemetry.onStopKeyword()
            return VoiceInterpretResult.Stop
        }
        val ignored = VoiceTranscriptGuard.textReason(text) ?: VoiceIgnoreReason.LOW_ENERGY.takeIf { tooQuiet }
        if (ignored != null) {
            telemetry.onIgnored(ignored)
            return VoiceInterpretResult.Ignored
        }
        val ruleStarted = clockMs()
        val byRules = try {
            VoiceRuleParser.parse(text, ruleContext())
        } catch (_: RuntimeException) {
            null
        }
        if (byRules != null) {
            telemetry.onPlanned(byRules, clockMs() - ruleStarted, VoiceInterpretSource.RULES)
            return byRules
        }
        val started = clockMs()
        val raw = try {
            generator.generate(text)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val result = raw?.let(VoiceLlmJsonParser::toInterpretResult) ?: VoiceInterpretResult.NotUnderstood
        telemetry.onPlanned(result, clockMs() - started, VoiceInterpretSource.GEMMA)
        return result
    }
}
