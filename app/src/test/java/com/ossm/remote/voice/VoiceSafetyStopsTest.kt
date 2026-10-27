package com.ossm.remote.voice

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSafetyStopsTest {
    @Test
    fun `observed and probable mis-transcriptions of stop are a STOP`() {
        val variants = listOf(
            "stop", "Stop !", "stoppe", "stopp", "stops", "top", "Tops.", "stup", "stap", "step",
            "right", "Right.", "all right", "All right!", "alright", "I right", "i right", "ride", "write",
            "wait", "halt", "enough", "arrête", "arrete", "arrêter", "arrêt", "assez", "pause",
            "non", "Non !", "fini", "ça suffit", "Ça suffit.", "STOP STOP", "euh right",
            "stop plus vite", "plus vite stop",
        )
        for (phrase in variants) assertTrue(phrase, VoiceStopKeywords.isStop(phrase))
    }

    @Test
    fun `motion instructions of the prompt are not swallowed by the wider list`() {
        val moves = VoiceLlmPrompt.FEW_SHOTS.map { it.first }.filterNot { it.contains("temps") } + listOf(
            "plus vite", "plus lentement", "moins profond", "garde ce rythme", "descends à la moitié",
        )
        for (phrase in moves) assertFalse(phrase, VoiceStopKeywords.isStop(phrase))
    }

    @Test
    fun `short isolated segment while moving is a STOP`() {
        assertTrue(VoiceShortWordStop.shouldStop(true, true, 8_000, 16_000)) // 0.5 s
        assertTrue(VoiceShortWordStop.shouldStop(true, true, 1_600, 16_000)) // 0.1 s
        assertFalse("long phrase", VoiceShortWordStop.shouldStop(true, true, 12_800, 16_000)) // 0.8 s
        assertFalse("click", VoiceShortWordStop.shouldStop(true, true, 800, 16_000))
        assertFalse("not moving", VoiceShortWordStop.shouldStop(true, false, 8_000, 16_000))
        assertFalse("disabled", VoiceShortWordStop.shouldStop(false, true, 8_000, 16_000))
        assertEquals(500L, VoiceShortWordStop.segmentMs(8_000, 16_000))
        assertEquals(0L, VoiceShortWordStop.segmentMs(8_000, 0))
    }

    @Test
    fun `volume keys stop only on the Control screen with a motion mode`() {
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
            assertTrue(VolumeKeyStop.shouldConsume(key, controlScreenVisible = true, motionActive = true))
            assertFalse(VolumeKeyStop.shouldConsume(key, controlScreenVisible = false, motionActive = true))
            assertFalse(VolumeKeyStop.shouldConsume(key, controlScreenVisible = true, motionActive = false))
        }
        assertFalse(VolumeKeyStop.shouldConsume(KeyEvent.KEYCODE_BACK, true, true))
        assertEquals("touche volume", VoiceStopSource.VOLUME_KEY.label)
    }
}
