package com.ossm.remote.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStopKeywordsTest {
    @Test
    fun `stop variants are detected`() {
        val stops = listOf(
            "stop", "Stop.", "STOP !", "stoppe", "Stopper", "stoppez tout",
            "arrête", "Arrête !", "arrêter", "Arrêtez", "arrete", "ARRÊTE-TOI",
            "pause", "Pause.", "euh… stop", "non, arrête maintenant",
        )
        for (phrase in stops) assertTrue(phrase, VoiceStopKeywords.isStop(phrase))
    }

    @Test
    fun `movement phrases are not a stop`() {
        val moves = listOf(
            "va au fond lentement et reste-y 2 secondes, puis reviens vite",
            "descends à la moitié",
            "plus vite",
            "reviens au début",
            "",
            "   ",
        )
        for (phrase in moves) assertFalse(phrase, VoiceStopKeywords.isStop(phrase))
    }

    @Test
    fun `stop word inside another word does not count`() {
        assertFalse(VoiceStopKeywords.isStop("pauser"))
        assertFalse(VoiceStopKeywords.isStop("stopwatch"))
    }

    @Test
    fun `normalization removes accents and punctuation`() {
        assertEquals("arrete toi", VoiceStopKeywords.normalize("  Arrête-toi ! "))
        assertEquals("c est l arret", VoiceStopKeywords.normalize("C'est l'arrêt."))
    }
}
