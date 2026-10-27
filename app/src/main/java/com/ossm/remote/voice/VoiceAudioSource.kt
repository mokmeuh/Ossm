package com.ossm.remote.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Microphone capture for the future on-device voice model: 16 kHz, mono, PCM 16-bit.
 * Audio is delivered in memory to [start]'s callback only; nothing is written to disk or sent.
 */
interface VoiceAudioSource {
    val isCapturing: Boolean

    /** Starts capture; returns false without permission or when the microphone is unavailable. */
    fun start(onFrame: (ShortArray) -> Unit): Boolean

    fun stop()

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
    }
}

/** AudioRecord implementation. Not wired to the UI in 2.3.26 (no model installed yet). */
class AudioRecordVoiceAudioSource(private val context: Context) : VoiceAudioSource {
    @Volatile private var record: AudioRecord? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    override val isCapturing: Boolean get() = running

    @SuppressLint("MissingPermission")
    override fun start(onFrame: (ShortArray) -> Unit): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val minBuffer = AudioRecord.getMinBufferSize(
            VoiceAudioSource.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val created = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                VoiceAudioSource.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
        } catch (_: RuntimeException) {
            return false
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            return false
        }
        record = created
        running = true
        created.startRecording()
        val frameSamples = VoiceAudioSource.SAMPLE_RATE_HZ / 10 // 100 ms frames
        worker = thread(name = "voice-audio", isDaemon = true) {
            val buffer = ShortArray(frameSamples)
            while (running) {
                val read = created.read(buffer, 0, buffer.size)
                if (read > 0) onFrame(buffer.copyOf(read))
                else if (read < 0) break
            }
        }
        return true
    }

    override fun stop() {
        running = false
        worker?.join(300)
        worker = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}
