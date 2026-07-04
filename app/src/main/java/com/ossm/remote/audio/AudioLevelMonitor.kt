package com.ossm.remote.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Niveau sonore du micro, calculé et lissé **entièrement sur l'appareil**.
 * AUCUN audio n'est enregistré, stocké ni transmis : on ne lit que l'amplitude
 * (RMS) des échantillons pour en tirer un niveau 0..1 utilisé par le « mode à
 * l'écoute » (biais d'intensité du mode aléatoire).
 *
 * NOTE PORT iOS : cette classe est spécifique à Android (AudioRecord). Pour un
 * port iOS, remplacer par AVAudioEngine/AVAudioRecorder en exposant le même
 * `StateFlow<Float>` (contrat identique, logique de mapping/lissage réutilisable).
 */
@Singleton
class AudioLevelMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _level = MutableStateFlow(0f)
    /** Niveau sonore lissé, 0 (silence) → 1 (fort). */
    val level: StateFlow<Float> = _level.asStateFlow()

    private var job: Job? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isRunning(): Boolean = job?.isActive == true

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        if (!hasPermission()) return
        job = scope.launch(Dispatchers.IO) {
            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return@launch
            val bufSize = maxOf(minBuf, sampleRate / 5 * 2) // ~200 ms
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
            } catch (e: SecurityException) {
                return@launch
            } catch (e: IllegalArgumentException) {
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@launch
            }
            val buffer = ShortArray(bufSize / 2)
            var smoothed = 0f
            try {
                recorder.startRecording()
                while (isActive) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        var sum = 0.0
                        for (i in 0 until n) {
                            val v = buffer[i].toDouble()
                            sum += v * v
                        }
                        val rms = sqrt(sum / n) // 0..32767
                        // Échelle log empirique : silence rms≈40 (log≈1.6), fort rms≈4000
                        // (log≈3.6). Mappé sur 0..1.
                        val norm = ((log10(rms + 1.0) - 1.7) / (3.6 - 1.7))
                            .coerceIn(0.0, 1.0)
                            .toFloat()
                        // Lissage : attaque rapide (réagit vite au son), relâche lent
                        // (l'intensité redescend en douceur).
                        smoothed = if (norm > smoothed) {
                            smoothed + (norm - smoothed) * 0.5f
                        } else {
                            smoothed + (norm - smoothed) * 0.08f
                        }
                        _level.value = smoothed
                    }
                }
            } catch (e: Exception) {
                // Ignoré : on arrête proprement dans le finally.
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                recorder.release()
                _level.value = 0f
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _level.value = 0f
    }
}
