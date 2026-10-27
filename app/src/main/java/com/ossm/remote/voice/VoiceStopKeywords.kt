package com.ossm.remote.voice

import java.text.Normalizer
import java.util.Locale

/**
 * Voix 2.3.27: STOP keyword detection on the transcribed text, checked BEFORE any LLM call.
 * Any occurrence wins, even inside a longer phrase ("ne t'arrête pas" also stops): a false STOP
 * is safe, a missed one is not.
 */
object VoiceStopKeywords {
    // 2.3.29 SAFETY: Parakeet (multilingual) transcribed a spoken "stop" as "right",
    // "I right", "all right". The list includes observed and likely mis-transcriptions; a
    // false positive only stops. Any occurrence of one of these words wins.
    internal val STOP_WORDS = setOf(
        "stop", "stoppe", "stoppes", "stopper", "stoppez", "stopp", "stops",
        "top", "tops", "stup", "stap", "step",
        "right", "alright", "ride", "write", "wait", "halt", "enough",
        "arrete", "arretes", "arreter", "arretez", "arretons", "arret",
        "assez", "pause", "non", "fini", "suffit",
    )

    /** Lowercase, accents removed, apostrophes/hyphens/punctuation turned into spaces. */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val noAccents = decomposed.replace(Regex("\\p{Mn}+"), "")
        return noAccents.replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    fun isStop(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        return normalized.split(' ').any { it in STOP_WORDS }
    }
}
