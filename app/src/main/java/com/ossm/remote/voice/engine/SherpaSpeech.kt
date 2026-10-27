package com.ossm.remote.voice.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.ossm.remote.voice.SpeechTranscriber
import com.ossm.remote.voice.VoiceAsrConfig
import com.ossm.remote.voice.VoiceAudioSource
import com.ossm.remote.voice.VoiceModelPaths
import com.ossm.remote.voice.WhisperAsrSpec

/**
 * Voix 2.3.27 — sherpa-onnx (native). Never referenced by JVM tests: loading these classes loads
 * libsherpa-onnx-jni.so. Models are opened from absolute file paths (AssetManager = null).
 */

/** Releasable offline transcriber (Whisper or Parakeet). */
internal interface SherpaTranscriber : SpeechTranscriber {
    fun release()
}

/** One offline decode of a VAD segment, serialized (one recognizer, one stream at a time). */
private fun OfflineRecognizer.decodeOnce(samples: FloatArray, sampleRateHz: Int): String {
    val stream = createStream()
    try {
        stream.acceptWaveform(samples, sampleRateHz)
        decode(stream)
        return getResult(stream).text
    } finally {
        stream.release()
    }
}

/**
 * 2.3.30 — Whisper small int8 with the language IMPOSED ("fr", task "transcribe"): Parakeet v3
 * has no language parameter and turned French into English. Whisper is not streaming: one
 * decode per VAD segment (segments capped at [VoiceAsrConfig.MAX_SEGMENT_SECONDS]).
 */
internal class SherpaWhisperTranscriber(spec: WhisperAsrSpec) : SherpaTranscriber {
    private val lock = Any()
    private val recognizer: OfflineRecognizer

    init {
        val whisper = OfflineWhisperModelConfig().apply {
            encoder = spec.encoder
            decoder = spec.decoder
            language = spec.language
            task = spec.task
            tailPaddings = spec.tailPaddings
        }
        val model = OfflineModelConfig().apply {
            this.whisper = whisper
            tokens = spec.tokens
            modelType = spec.modelType
            numThreads = spec.numThreads
            provider = spec.provider
            debug = false
        }
        val config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = VoiceAudioSource.SAMPLE_RATE_HZ
                featureDim = 80
            }
            modelConfig = model
            decodingMethod = spec.decodingMethod
        }
        recognizer = OfflineRecognizer(null, config)
    }

    override fun transcribe(samples: FloatArray, sampleRateHz: Int): String = synchronized(lock) {
        recognizer.decodeOnce(samples, sampleRateHz)
    }

    override fun release() = synchronized(lock) { recognizer.release() }
}

/** Parakeet TDT v3 int8 (NeMo transducer), fallback only when no Whisper file is installed. */
internal class SherpaParakeetTranscriber(paths: VoiceModelPaths, numThreads: Int = VoiceAsrConfig.NUM_THREADS) : SherpaTranscriber {
    private val lock = Any()
    private val recognizer: OfflineRecognizer

    init {
        val transducer = OfflineTransducerModelConfig().apply {
            encoder = paths.encoder.absolutePath
            decoder = paths.decoder.absolutePath
            joiner = paths.joiner.absolutePath
        }
        val model = OfflineModelConfig().apply {
            this.transducer = transducer
            tokens = paths.tokens.absolutePath
            modelType = "nemo_transducer"
            this.numThreads = numThreads
            provider = VoiceAsrConfig.PROVIDER
            debug = false
        }
        val config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = VoiceAudioSource.SAMPLE_RATE_HZ
                featureDim = 80
            }
            modelConfig = model
            decodingMethod = "greedy_search"
        }
        recognizer = OfflineRecognizer(null, config)
    }

    override fun transcribe(samples: FloatArray, sampleRateHz: Int): String = synchronized(lock) {
        recognizer.decodeOnce(samples, sampleRateHz)
    }

    override fun release() = synchronized(lock) { recognizer.release() }
}

/** Silero VAD: turns 16 kHz frames into complete speech segments (end of speech detected). */
internal class SherpaVadSegmenter(paths: VoiceModelPaths) {
    private val vad: Vad
    private val window: Int
    private var pending = FloatArray(0)

    init {
        val silero = SileroVadModelConfig().apply {
            model = paths.vad.absolutePath
            threshold = 0.5f
            minSilenceDuration = 0.35f
            minSpeechDuration = 0.25f
            windowSize = 512
            maxSpeechDuration = VoiceAsrConfig.MAX_SEGMENT_SECONDS
        }
        window = silero.windowSize
        val config = VadModelConfig().apply {
            sileroVadModelConfig = silero
            sampleRate = VoiceAudioSource.SAMPLE_RATE_HZ
            numThreads = 1
            provider = "cpu"
            debug = false
        }
        vad = Vad(null, config)
    }

    /** Feeds one frame; returns the speech segments completed by it (usually none). */
    @Synchronized
    fun accept(frame: FloatArray): List<FloatArray> {
        val buffer = if (pending.isEmpty()) frame else pending + frame
        var offset = 0
        while (buffer.size - offset >= window) {
            vad.acceptWaveform(buffer.copyOfRange(offset, offset + window))
            offset += window
        }
        pending = buffer.copyOfRange(offset, buffer.size)
        val segments = ArrayList<FloatArray>(1)
        while (!vad.empty()) {
            segments += vad.front().samples
            vad.pop()
        }
        return segments
    }

    @Synchronized
    fun reset() {
        pending = FloatArray(0)
        vad.reset()
    }

    @Synchronized
    fun release() = vad.release()
}
