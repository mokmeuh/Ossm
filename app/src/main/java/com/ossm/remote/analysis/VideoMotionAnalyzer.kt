package com.ossm.remote.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.ossm.remote.model.Funscript
import com.ossm.remote.model.FunscriptAction
import com.ossm.remote.model.FunscriptMetadata
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Analyse de mouvement 100% locale (aucun serveur) pour GÉNÉRER un funscript à
 * partir d'une vidéo brute.
 *
 * Approche (proxy grossier, pas de compréhension de scène) :
 *  1. Échantillonnage clairsemé des images via MediaMetadataRetriever à basse
 *     résolution (~160 px de large), toutes les ~100 ms de vidéo.
 *  2. Différence de luminance image-à-image → on calcule le **centroïde vertical
 *     de la zone qui bouge** (0 = haut de l'image, 1 = bas). Ce signal suit
 *     l'endroit vertical où l'action se produit.
 *  3. Normalisation robuste 0..100 (percentiles 5/95) puis lissage (moyenne
 *     glissante) pour retirer le bruit.
 *  4. Émission d'actions funscript {at, pos} aux extrema locaux (sommets ≈ 100,
 *     creux ≈ 0) + points de départ/fin, avec garde de temps/amplitude minimale
 *     pour éviter le jitter.
 *
 * Tout tourne hors du thread principal (l'appelant utilise Dispatchers.Default).
 */
class VideoMotionAnalyzer(private val context: Context) {

    data class Config(
        /** Intervalle d'échantillonnage cible entre deux images (ms). */
        val sampleIntervalMs: Long = 100L,
        /** Largeur de décodage des images (px). Basse pour rester rapide. */
        val frameWidth: Int = 160,
        /** Plafond du nombre d'images analysées (borne le coût sur vidéos longues). */
        val maxSamples: Int = 1400,
        /** Fenêtre de moyenne glissante (nombre d'échantillons). */
        val smoothingWindow: Int = 3,
        /** Seuil de bruit sur la différence de luminance par pixel (0..255). */
        val pixelDiffThreshold: Int = 14,
        /** Écart temporel minimal entre deux actions émises (ms). */
        val minActionGapMs: Long = 140L,
        /** Amplitude minimale (0..100) entre deux extrema pour émettre. */
        val minActionAmplitude: Int = 8
    )

    fun interface ProgressListener {
        fun onProgress(fraction: Float)
    }

    class AnalysisException(message: String) : Exception(message)

    /**
     * @param uri source locale (content://) OU
     * @param url source distante (http...) — l'un des deux doit être non nul.
     */
    fun analyze(
        uri: Uri?,
        url: String?,
        config: Config = Config(),
        progress: ProgressListener? = null
    ): Funscript {
        val retriever = MediaMetadataRetriever()
        try {
            when {
                uri != null -> retriever.setDataSource(context, uri)
                !url.isNullOrBlank() -> retriever.setDataSource(url, HashMap())
                else -> throw AnalysisException("Aucune source vidéo")
            }

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) throw AnalysisException("Durée vidéo inconnue")

            // Choix de l'intervalle : on borne le nombre d'échantillons.
            var interval = config.sampleIntervalMs.coerceAtLeast(20L)
            var count = (durationMs / interval).toInt() + 1
            if (count > config.maxSamples) {
                interval = durationMs / config.maxSamples
                count = config.maxSamples
            }
            if (count < 2) throw AnalysisException("Vidéo trop courte")

            val targetH = (config.frameWidth * 9 / 16).coerceAtLeast(16)
            var prevGray: IntArray? = null
            var w = 0
            var h = 0

            val times = ArrayList<Long>(count)
            val raw = ArrayList<Float>(count) // centroïde vertical 0..1 (NaN si pas de mouvement)

            for (i in 0 until count) {
                val tMs = i * interval
                val bmp = grabFrame(retriever, tMs, config.frameWidth, targetH) ?: continue
                if (w == 0) { w = bmp.width; h = bmp.height }
                val gray = toGray(bmp, w, h)
                if (bmp != null && !bmp.isRecycled) bmp.recycle()

                val prev = prevGray
                if (prev != null && prev.size == gray.size) {
                    var sumW = 0.0
                    var sumWy = 0.0
                    var idx = 0
                    for (y in 0 until h) {
                        for (x in 0 until w) {
                            val d = abs(gray[idx] - prev[idx])
                            if (d > config.pixelDiffThreshold) {
                                sumW += d
                                sumWy += d.toDouble() * y
                            }
                            idx++
                        }
                    }
                    times.add(tMs)
                    raw.add(if (sumW > 0.0) (sumWy / sumW / (h - 1)).toFloat() else Float.NaN)
                }
                prevGray = gray
                progress?.onProgress((i + 1f) / count)
            }

            if (raw.size < 2) throw AnalysisException("Pas assez de mouvement détecté")

            val filled = fillGaps(raw)
            val smoothed = movingAverage(filled, config.smoothingWindow)
            val normalized = normalize(smoothed) // 0..100
            val actions = extremaToActions(times, normalized, config)

            if (actions.size < 2) throw AnalysisException("Signal trop plat pour générer un script")

            return Funscript(
                actions = actions,
                metadata = FunscriptMetadata(
                    title = "Généré (mouvement local)",
                    duration = durationMs / 1000.0
                )
            )
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun grabFrame(
        retriever: MediaMetadataRetriever,
        tMs: Long,
        width: Int,
        height: Int
    ): Bitmap? {
        val tUs = tMs * 1000
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    tUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    width,
                    height
                )
            } else {
                val full = retriever.getFrameAtTime(
                    tUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: return null
                val scaled = Bitmap.createScaledBitmap(full, width, height, true)
                if (scaled != full) full.recycle()
                scaled
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Convertit un bitmap en niveaux de gris (0..255) en luma perçue. */
    private fun toGray(bmp: Bitmap, w: Int, h: Int): IntArray {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = (r * 77 + g * 150 + b * 29) shr 8
        }
        return out
    }

    /** Remplace les NaN (images sans mouvement) par interpolation / maintien. */
    private fun fillGaps(raw: List<Float>): FloatArray {
        val out = FloatArray(raw.size)
        // Valeur de repli initiale = première valeur valide, sinon 0.5.
        var lastValid = raw.firstOrNull { !it.isNaN() } ?: 0.5f
        for (i in raw.indices) {
            val v = raw[i]
            if (!v.isNaN()) {
                out[i] = v
                lastValid = v
            } else {
                out[i] = lastValid
            }
        }
        return out
    }

    private fun movingAverage(data: FloatArray, window: Int): FloatArray {
        if (window <= 1) return data
        val out = FloatArray(data.size)
        val half = window / 2
        for (i in data.indices) {
            var sum = 0f
            var n = 0
            for (j in (i - half)..(i + half)) {
                if (j in data.indices) { sum += data[j]; n++ }
            }
            out[i] = sum / n
        }
        return out
    }

    /** Normalisation robuste 0..100 via percentiles 5/95. */
    private fun normalize(data: FloatArray): IntArray {
        if (data.isEmpty()) return IntArray(0)
        val sorted = data.sortedArray()
        val lo = sorted[(sorted.size * 0.05f).toInt().coerceIn(0, sorted.size - 1)]
        val hi = sorted[(sorted.size * 0.95f).toInt().coerceIn(0, sorted.size - 1)]
        val range = (hi - lo)
        val out = IntArray(data.size)
        for (i in data.indices) {
            val v = if (range > 1e-4f) ((data[i] - lo) / range) else 0.5f
            out[i] = (v * 100f).roundToInt().coerceIn(0, 100)
        }
        return out
    }

    /**
     * Détecte les points de retournement (extrema locaux) du signal normalisé et
     * en fait des actions funscript. Applique une garde temps + amplitude pour
     * éviter les micro-oscillations.
     */
    private fun extremaToActions(
        times: List<Long>,
        pos: IntArray,
        config: Config
    ): List<FunscriptAction> {
        val n = pos.size
        if (n == 0) return emptyList()

        val out = ArrayList<FunscriptAction>()
        // Point de départ.
        out.add(FunscriptAction(times[0], pos[0]))

        var lastEmittedIdx = 0
        var i = 1
        while (i < n - 1) {
            val prev = pos[i - 1]
            val cur = pos[i]
            val next = pos[i + 1]
            val isPeak = cur >= prev && cur > next
            val isTrough = cur <= prev && cur < next
            if (isPeak || isTrough) {
                val gapMs = times[i] - times[lastEmittedIdx]
                val amp = abs(cur - pos[lastEmittedIdx])
                if (gapMs >= config.minActionGapMs && amp >= config.minActionAmplitude) {
                    out.add(FunscriptAction(times[i], cur))
                    lastEmittedIdx = i
                }
            }
            i++
        }
        // Point de fin.
        val lastIdx = n - 1
        if (times[lastIdx] > times[lastEmittedIdx]) {
            out.add(FunscriptAction(times[lastIdx], pos[lastIdx]))
        }
        return out
    }
}
