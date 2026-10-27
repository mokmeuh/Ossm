package com.ossm.remote.voice

import kotlin.math.sqrt

/** Why a transcript was ignored (never moves anything; shown on the Voix panel). */
enum class VoiceIgnoreReason { EMPTY, PUNCTUATION_ONLY, HALLUCINATION, REPETITION, TOO_LONG, LOW_ENERGY }

/**
 * Voix 2.3.31 — Whisper anti-hallucination guard, applied AFTER the STOP keyword check (a STOP
 * word always wins, even inside a hallucinated or too quiet segment) and BEFORE the rules and
 * Gemma. A guarded transcript is [VoiceInterpretResult.Ignored]: no state change, no movement.
 */
object VoiceTranscriptGuard {
    const val MAX_CHARS = 200
    /** Consecutive repetitions of the same word above this count = decoding loop. */
    const val MAX_REPEATS = 3
    /** Segment RMS (samples in -1..1) below which speech is too faint to be an order (~ -48 dBFS). */
    const val MIN_RMS = 0.004f

    /** Typical Whisper hallucinations on silence/noise, normalized (see [VoiceStopKeywords.normalize]). */
    internal val HALLUCINATION_FRAGMENTS = listOf(
        "sous titres", "sous titre", "sous titrage", "soustitres", "amara org", "merci d avoir regarde",
        "merci de votre attention", "merci d avoir ecoute", "abonnez vous", "abonne toi", "abonnez",
        "n oubliez pas de vous abonner", "likez", "cette video", "a la prochaine", "radio canada",
        "transcription par", "traduit par",
    )
    internal val HALLUCINATION_EXACT = setOf("merci", "merci beaucoup", "merci a tous", "au revoir", "bonsoir", "bonjour")

    fun rms(pcm16: ShortArray): Float {
        if (pcm16.isEmpty()) return 0f
        var sum = 0.0
        for (sample in pcm16) {
            val x = sample / 32768.0
            sum += x * x
        }
        return sqrt(sum / pcm16.size).toFloat()
    }

    fun isTooQuiet(pcm16: ShortArray): Boolean = rms(pcm16) < MIN_RMS

    /** Reason to ignore [text], or null when it may be interpreted. */
    fun textReason(text: String): VoiceIgnoreReason? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return VoiceIgnoreReason.EMPTY
        if (trimmed.length > MAX_CHARS) return VoiceIgnoreReason.TOO_LONG
        val normalized = VoiceStopKeywords.normalize(trimmed)
        if (normalized.isEmpty()) return VoiceIgnoreReason.PUNCTUATION_ONLY
        if (normalized in HALLUCINATION_EXACT) return VoiceIgnoreReason.HALLUCINATION
        val padded = " $normalized "
        if (HALLUCINATION_FRAGMENTS.any { padded.contains(" $it ") }) return VoiceIgnoreReason.HALLUCINATION
        var run = 1
        val words = normalized.split(' ')
        for (k in 1 until words.size) {
            run = if (words[k] == words[k - 1]) run + 1 else 1
            if (run > MAX_REPEATS) return VoiceIgnoreReason.REPETITION
        }
        return null
    }
}
