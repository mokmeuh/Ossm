package com.ossm.remote.voice

import android.view.KeyEvent

/** Where a STOP came from (journal: "STOP vocal (mot-clé|mot court|touche volume)"). */
enum class VoiceStopSource(val label: String) {
    BUTTON("bouton"),
    KEYWORD("mot-clé"),
    SHORT_WORD("mot court"),
    VOLUME_KEY("touche volume"),
}

/**
 * 2.3.29 "STOP rapide par mot court": a very short isolated VAD segment spoken WHILE a Voix
 * sequence/motif runs is treated as STOP without waiting for speech recognition or the LLM
 * (safety bias: "stop" is short, and was mis-transcribed). Instructions are longer phrases.
 */
object VoiceShortWordStop {
    /** Upper bound of a "short word" segment. */
    const val MAX_SHORT_SEGMENT_MS = 800L
    /** Shorter blips (clicks) are ignored; Silero already drops speech under 250 ms. */
    const val MIN_SHORT_SEGMENT_MS = 100L

    fun segmentMs(sampleCount: Int, sampleRateHz: Int): Long =
        if (sampleRateHz <= 0) 0L else sampleCount * 1000L / sampleRateHz

    fun shouldStop(enabled: Boolean, motionRunning: Boolean, sampleCount: Int, sampleRateHz: Int): Boolean {
        if (!enabled || !motionRunning) return false
        val ms = segmentMs(sampleCount, sampleRateHz)
        return ms in MIN_SHORT_SEGMENT_MS until MAX_SHORT_SEGMENT_MS
    }
}

/**
 * 2.3.29 hardware STOP: volume up AND down stop immediately while the Control screen is in
 * the foreground and a motion mode is active; the key event is then consumed (volume
 * unchanged). Otherwise the keys keep their normal volume behaviour.
 */
object VolumeKeyStop {
    fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    fun shouldConsume(keyCode: Int, controlScreenVisible: Boolean, motionActive: Boolean): Boolean =
        isVolumeKey(keyCode) && controlScreenVisible && motionActive
}
