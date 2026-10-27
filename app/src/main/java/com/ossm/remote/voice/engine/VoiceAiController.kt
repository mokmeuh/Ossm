package com.ossm.remote.voice.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ossm.remote.model.isVoicePattern
import com.ossm.remote.session.ControlSessionStore
import com.ossm.remote.voice.AsrEngine
import com.ossm.remote.voice.AsrEngineSelector
import com.ossm.remote.voice.AudioRecordVoiceAudioSource
import com.ossm.remote.voice.NotInstalledVoiceCommandInterpreter
import com.ossm.remote.voice.OnDeviceVoiceInterpreter
import com.ossm.remote.voice.VoiceAiStatus
import com.ossm.remote.voice.VoiceAiTelemetry
import com.ossm.remote.voice.VoiceAiUiState
import com.ossm.remote.voice.VoiceAsrConfig
import com.ossm.remote.voice.VoiceAudioSource
import com.ossm.remote.voice.VoiceIgnoreReason
import com.ossm.remote.voice.VoiceInput
import com.ossm.remote.voice.VoiceInterpretSource
import com.ossm.remote.voice.VoiceInterpretResult
import com.ossm.remote.voice.VoiceModelChecker
import com.ossm.remote.voice.VoiceModelPaths
import com.ossm.remote.voice.toStatusOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voix 2.3.27 — on-device voice AI, 100 % on the phone (no network):
 * microphone 16 kHz -> Silero VAD -> Whisper small "fr" (2.3.30; Parakeet fallback) per segment -> STOP keyword
 * -> Gemma 3 1B (LiteRT-LM, GPU then CPU) -> JSON steps -> strict validation in the store.
 *
 * Models load in the background when the Voix mode is entered; the interpreter is only handed
 * to the store once everything is ready, and the microphone can only start then, so nothing can
 * move while loading. Audio stays in memory; transcripts are shown, never journaled.
 */
@Singleton
class VoiceAiController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ControlSessionStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(VoiceAiUiState())
    val state: StateFlow<VoiceAiUiState> = _state.asStateFlow()

    private val audio: VoiceAudioSource = AudioRecordVoiceAudioSource(context)
    private var loadJob: Job? = null
    @Volatile private var vad: SherpaVadSegmenter? = null
    private var transcriber: SherpaTranscriber? = null
    private var transcriberEngine: AsrEngine? = null
    private var llm: LiteRtStepGenerator? = null

    private val telemetry = object : VoiceAiTelemetry {
        override fun onTranscript(text: String, speechToTextMs: Long) {
            _state.update { it.copy(lastTranscript = text, speechToTextMs = speechToTextMs, lastIgnoredReason = null) }
        }

        override fun onPlanned(result: VoiceInterpretResult, textToStepsMs: Long, source: VoiceInterpretSource) {
            _state.update { it.copy(textToStepsMs = textToStepsMs, interpretSource = source, lastIgnoredReason = null) }
        }

        override fun onIgnored(reason: VoiceIgnoreReason) {
            _state.update { it.copy(lastIgnoredReason = reason) }
        }
    }

    init {
        scope.launch {
            store.uiState
                .map { it.rangeWizard == null && it.activePattern.isVoicePattern() }
                .distinctUntilChanged()
                .collect { active -> if (active) warmUp() else stopListening() }
        }
    }

    /** Expected model directory, shown when a model is missing. */
    fun modelRoot(): String? = context.getExternalFilesDir(MODELS_DIR)?.absolutePath

    /** Loads the models in the background (no-op while loading or once ready). */
    fun warmUp() {
        val status = _state.value.status
        if (status is VoiceAiStatus.Loading || status is VoiceAiStatus.Ready) return
        _state.update { it.copy(status = VoiceAiStatus.Loading) }
        loadJob = scope.launch {
            val result = withContext(Dispatchers.IO) { loadBlocking() }
            if (result is VoiceAiStatus.Ready) {
                llm?.let { generator -> runCatching { generator.prepare() } }
                val asr = transcriber
                val generator = llm
                if (asr != null && generator != null) {
                    store.voiceInterpreter = OnDeviceVoiceInterpreter(
                        asr,
                        generator,
                        telemetry,
                        ruleContext = { store.voiceRuleContext() },
                    )
                }
            } else {
                store.voiceInterpreter = NotInstalledVoiceCommandInterpreter
            }
            _state.update { it.copy(status = result) }
        }
    }

    private fun loadBlocking(): VoiceAiStatus {
        val root = context.getExternalFilesDir(MODELS_DIR)
            ?: return VoiceAiStatus.Error("Stockage de l'application indisponible")
        val paths = VoiceModelPaths(root)
        val engine = AsrEngineSelector.select(paths)
        _state.update { it.copy(asrEngine = engine) }
        VoiceModelChecker.check(paths, engine = engine).toStatusOrNull()?.let { return it }
        return try {
            if (vad == null) vad = SherpaVadSegmenter(paths)
            if (transcriber == null || transcriberEngine != engine) {
                transcriber?.let { old -> runCatching { old.release() } }
                transcriber = when (engine) {
                    AsrEngine.WHISPER_SMALL_FR -> SherpaWhisperTranscriber(VoiceAsrConfig.whisper(paths))
                    AsrEngine.PARAKEET -> SherpaParakeetTranscriber(paths)
                }
                transcriberEngine = engine
                Log.i(TAG, "Transcription: ${engine.label}")
            }
            val generator = llm ?: LiteRtStepGenerator.load(paths.gemma, context.cacheDir).also { llm = it }
            VoiceAiStatus.Ready(generator.backendLabel, engine)
        } catch (t: Throwable) {
            // Cause only (class + short message), never audio or phrase text.
            Log.w(TAG, "Chargement IA vocale impossible: ${t.javaClass.simpleName}")
            VoiceAiStatus.Error(t.javaClass.simpleName + (t.message?.let { " : " + it.take(160) } ?: ""))
        }
    }

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Opens the microphone; only once the AI is ready and the Voix mode is active. */
    fun startListening() {
        if (_state.value.listening) return
        if (_state.value.status !is VoiceAiStatus.Ready) return
        val segmenter = vad ?: return
        if (!hasMicrophonePermission()) {
            _state.update { it.copy(microphoneDenied = true, listening = false) }
            return
        }
        segmenter.reset()
        val started = audio.start { frame -> onFrame(segmenter, frame) }
        _state.update { it.copy(listening = started, microphoneDenied = false) }
    }

    fun stopListening() {
        if (audio.isCapturing) audio.stop()
        if (_state.value.listening) _state.update { it.copy(listening = false) }
    }

    fun setListening(listen: Boolean) {
        if (listen) startListening() else stopListening()
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        _state.update { it.copy(microphoneDenied = !granted) }
        if (granted) startListening()
    }

    /** Audio thread: VAD only; each finished segment goes to the store on the main thread. */
    private fun onFrame(segmenter: SherpaVadSegmenter, frame: ShortArray) {
        val samples = FloatArray(frame.size) { frame[it] / 32768f }
        val segments = try {
            segmenter.accept(samples)
        } catch (_: Throwable) {
            emptyList()
        }
        for (segment in segments) {
            val pcm = ShortArray(segment.size) { (segment[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
            scope.launch { store.interpretVoiceInput(VoiceInput.Audio(pcm)) }
        }
    }

    companion object {
        private const val TAG = "VOICE_AI"
        const val MODELS_DIR = "models"
    }
}
