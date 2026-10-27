package com.ossm.remote.ui.screens

import com.ossm.remote.R
import com.ossm.remote.voice.AsrEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAsrLabelTest {
    @Test
    fun `transcription engine maps to its visible label`() {
        assertEquals(R.string.voice_asr_whisper, voiceAsrLabelRes(AsrEngine.WHISPER_SMALL_FR))
        assertEquals(R.string.voice_asr_parakeet, voiceAsrLabelRes(AsrEngine.PARAKEET))
    }
}
