package com.ossm.remote.voice

import java.io.File
import java.io.FileInputStream

/**
 * Voix 2.3.27: model files expected in `getExternalFilesDir("models")`. They are passed to the
 * native engines as absolute paths (never through the AssetManager).
 */
data class VoiceModelPaths(val root: File) {
    val gemma: File get() = File(root, "gemma/gemma3-1b-it-int4.litertlm")
    val vad: File get() = File(root, "asr/silero_vad.int8.onnx")

    /** Parakeet TDT v3 (fallback only, language cannot be forced). */
    val encoder: File get() = File(root, "asr/parakeet/encoder.int8.onnx")
    val decoder: File get() = File(root, "asr/parakeet/decoder.int8.onnx")
    val joiner: File get() = File(root, "asr/parakeet/joiner.int8.onnx")
    val tokens: File get() = File(root, "asr/parakeet/tokens.txt")

    /** 2.3.30: Whisper small int8 (original sherpa-onnx-whisper-small file names). */
    val whisperEncoder: File get() = File(root, "asr/whisper/small-encoder.int8.onnx")
    val whisperDecoder: File get() = File(root, "asr/whisper/small-decoder.int8.onnx")
    val whisperTokens: File get() = File(root, "asr/whisper/small-tokens.txt")

    val parakeetFiles: List<File> get() = listOf(encoder, decoder, joiner, tokens)
    val whisperFiles: List<File> get() = listOf(whisperEncoder, whisperDecoder, whisperTokens)

    fun asrFiles(engine: AsrEngine): List<File> = when (engine) {
        AsrEngine.WHISPER_SMALL_FR -> whisperFiles
        AsrEngine.PARAKEET -> parakeetFiles
    }

    /** Files required to run the Voix AI with [engine] (VAD + ASR + LLM). */
    fun required(engine: AsrEngine): List<File> = listOf(vad) + asrFiles(engine) + gemma

    /** Default layout (Parakeet), kept for the 2.3.27 checks. */
    val all: List<File> get() = required(AsrEngine.PARAKEET)
}

/** 2.3.30: speech-to-text engine actually used, shown as "Transcription : ...". */
enum class AsrEngine(val label: String) {
    WHISPER_SMALL_FR("Whisper small (français)"),
    PARAKEET("Parakeet (langue non forcée)"),
}

/**
 * 2.3.30: Whisper small with French imposed is the engine. Parakeet is only a fallback when NO
 * Whisper file is present; a partial or unreadable Whisper install stays Whisper so the check
 * reports the missing/unreadable Whisper file instead of silently transcribing in English.
 */
object AsrEngineSelector {
    fun select(paths: VoiceModelPaths, probe: (File) -> ModelFileAccess = VoiceModelChecker::probe): AsrEngine =
        if (paths.whisperFiles.all { probe(it) == ModelFileAccess.MISSING }) AsrEngine.PARAKEET
        else AsrEngine.WHISPER_SMALL_FR
}

/** Pure description of the sherpa-onnx Whisper configuration (mapped in `engine/SherpaSpeech.kt`). */
data class WhisperAsrSpec(
    val encoder: String,
    val decoder: String,
    val tokens: String,
    val language: String,
    val task: String,
    /** -1 = sherpa-onnx default padding (the 30 s Whisper window is padded natively). */
    val tailPaddings: Int,
    val numThreads: Int,
    val provider: String,
    val modelType: String,
    val decodingMethod: String,
)

object VoiceAsrConfig {
    const val WHISPER_LANGUAGE = "fr"
    const val WHISPER_TASK = "transcribe"
    const val NUM_THREADS = 4
    const val PROVIDER = "cpu"
    /** Whisper is not streaming: each VAD segment is decoded once, so segments stay short. */
    const val MAX_SEGMENT_SECONDS = 10f

    fun whisper(paths: VoiceModelPaths): WhisperAsrSpec = WhisperAsrSpec(
        encoder = paths.whisperEncoder.absolutePath,
        decoder = paths.whisperDecoder.absolutePath,
        tokens = paths.whisperTokens.absolutePath,
        language = WHISPER_LANGUAGE,
        task = WHISPER_TASK,
        tailPaddings = -1,
        numThreads = NUM_THREADS,
        provider = PROVIDER,
        modelType = "whisper",
        decodingMethod = "greedy_search",
    )
}

enum class ModelFileAccess { OK, MISSING, UNREADABLE }

sealed interface VoiceModelCheck {
    data object Ok : VoiceModelCheck
    data class Missing(val paths: List<String>) : VoiceModelCheck
    data class Unreadable(val paths: List<String>) : VoiceModelCheck
}

object VoiceModelChecker {
    /** Real probe: exists, is a non-empty file, and one byte can actually be read. */
    fun probe(file: File): ModelFileAccess {
        if (!file.exists()) return ModelFileAccess.MISSING
        if (!file.isFile) return ModelFileAccess.UNREADABLE
        return try {
            FileInputStream(file).use { if (it.read() < 0) ModelFileAccess.UNREADABLE else ModelFileAccess.OK }
        } catch (_: Exception) {
            ModelFileAccess.UNREADABLE
        }
    }

    /** Missing files are reported before unreadable ones. */
    fun check(
        paths: VoiceModelPaths,
        engine: AsrEngine = AsrEngine.PARAKEET,
        probe: (File) -> ModelFileAccess = ::probe,
    ): VoiceModelCheck {
        val results = paths.required(engine).map { it to probe(it) }
        val missing = results.filter { it.second == ModelFileAccess.MISSING }.map { it.first.absolutePath }
        if (missing.isNotEmpty()) return VoiceModelCheck.Missing(missing)
        val unreadable = results.filter { it.second == ModelFileAccess.UNREADABLE }.map { it.first.absolutePath }
        if (unreadable.isNotEmpty()) return VoiceModelCheck.Unreadable(unreadable)
        return VoiceModelCheck.Ok
    }
}

/** What the Voix screen shows about the on-device AI. */
sealed interface VoiceAiStatus {
    /** Not warmed up yet (Voix never entered). */
    data object Idle : VoiceAiStatus
    data object Loading : VoiceAiStatus
    data class Ready(val llmBackend: String, val asr: AsrEngine = AsrEngine.WHISPER_SMALL_FR) : VoiceAiStatus
    data class ModelMissing(val paths: List<String>) : VoiceAiStatus
    data class ModelUnreadable(val paths: List<String>) : VoiceAiStatus
    data class Error(val message: String) : VoiceAiStatus
}

fun VoiceModelCheck.toStatusOrNull(): VoiceAiStatus? = when (this) {
    VoiceModelCheck.Ok -> null
    is VoiceModelCheck.Missing -> VoiceAiStatus.ModelMissing(paths)
    is VoiceModelCheck.Unreadable -> VoiceAiStatus.ModelUnreadable(paths)
}

/** Voix AI state for the panel (microphone, last transcript, measured latencies). */
data class VoiceAiUiState(
    val status: VoiceAiStatus = VoiceAiStatus.Idle,
    /** 2.3.30: engine selected from the installed files (null before the first check). */
    val asrEngine: AsrEngine? = null,
    val listening: Boolean = false,
    val microphoneDenied: Boolean = false,
    val lastTranscript: String? = null,
    val speechToTextMs: Long? = null,
    val textToStepsMs: Long? = null,
    /** 2.3.31: who translated the last phrase (deterministic rules or Gemma). */
    val interpretSource: VoiceInterpretSource? = null,
    /** 2.3.31: why the last transcript was ignored (null when it was interpreted). */
    val lastIgnoredReason: VoiceIgnoreReason? = null,
)
