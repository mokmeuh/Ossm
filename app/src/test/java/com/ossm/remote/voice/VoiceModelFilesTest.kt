package com.ossm.remote.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceModelFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun paths() = VoiceModelPaths(File(temp.root, "models"))

    private fun createAll(paths: VoiceModelPaths) {
        for (file in paths.all) {
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun `expected layout matches the files pushed to the phone`() {
        val p = paths()
        val root = p.root
        assertEquals(File(root, "gemma/gemma3-1b-it-int4.litertlm"), p.gemma)
        assertEquals(File(root, "asr/silero_vad.int8.onnx"), p.vad)
        assertEquals(File(root, "asr/parakeet/encoder.int8.onnx"), p.encoder)
        assertEquals(File(root, "asr/parakeet/decoder.int8.onnx"), p.decoder)
        assertEquals(File(root, "asr/parakeet/joiner.int8.onnx"), p.joiner)
        assertEquals(File(root, "asr/parakeet/tokens.txt"), p.tokens)
    }

    @Test
    fun `nothing installed reports every missing path`() {
        val p = paths()
        val check = VoiceModelChecker.check(p)
        assertEquals(VoiceModelCheck.Missing(p.all.map { it.absolutePath }), check)
        val status = check.toStatusOrNull()
        assertTrue(status is VoiceAiStatus.ModelMissing)
        assertEquals(6, (status as VoiceAiStatus.ModelMissing).paths.size)
    }

    @Test
    fun `one missing model is named`() {
        val p = paths()
        createAll(p)
        p.gemma.delete()
        assertEquals(VoiceModelCheck.Missing(listOf(p.gemma.absolutePath)), VoiceModelChecker.check(p))
    }

    @Test
    fun `empty or unreadable file is reported as unreadable`() {
        val p = paths()
        createAll(p)
        p.tokens.writeBytes(ByteArray(0))
        assertEquals(VoiceModelCheck.Unreadable(listOf(p.tokens.absolutePath)), VoiceModelChecker.check(p))
        val denied = VoiceModelChecker.check(p) { file ->
            if (file == p.encoder) ModelFileAccess.UNREADABLE else ModelFileAccess.OK
        }
        assertEquals(VoiceAiStatus.ModelUnreadable(listOf(p.encoder.absolutePath)), denied.toStatusOrNull())
    }

    @Test
    fun `all present and readable is ok`() {
        val p = paths()
        createAll(p)
        assertEquals(VoiceModelCheck.Ok, VoiceModelChecker.check(p))
        assertEquals(null, VoiceModelCheck.Ok.toStatusOrNull())
    }

    private fun touch(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `whisper layout uses the original sherpa-onnx-whisper-small names`() {
        val p = paths()
        assertEquals(File(p.root, "asr/whisper/small-encoder.int8.onnx"), p.whisperEncoder)
        assertEquals(File(p.root, "asr/whisper/small-decoder.int8.onnx"), p.whisperDecoder)
        assertEquals(File(p.root, "asr/whisper/small-tokens.txt"), p.whisperTokens)
        assertEquals(listOf(p.vad) + p.whisperFiles + p.gemma, p.required(AsrEngine.WHISPER_SMALL_FR))
    }

    @Test
    fun `whisper config imposes french transcription on cpu with 4 threads`() {
        val p = paths()
        val spec = VoiceAsrConfig.whisper(p)
        assertEquals(p.whisperEncoder.absolutePath, spec.encoder)
        assertEquals(p.whisperDecoder.absolutePath, spec.decoder)
        assertEquals(p.whisperTokens.absolutePath, spec.tokens)
        assertEquals("fr", spec.language)
        assertEquals("transcribe", spec.task)
        assertEquals("whisper", spec.modelType)
        assertEquals("cpu", spec.provider)
        assertEquals(4, spec.numThreads)
        assertEquals("greedy_search", spec.decodingMethod)
        assertEquals(-1, spec.tailPaddings)
        assertTrue(VoiceAsrConfig.MAX_SEGMENT_SECONDS in 1f..15f)
    }

    @Test
    fun `whisper is chosen when installed, parakeet only when no whisper file exists`() {
        val p = paths()
        createAll(p)
        assertEquals(AsrEngine.PARAKEET, AsrEngineSelector.select(p))
        p.whisperFiles.forEach(::touch)
        assertEquals(AsrEngine.WHISPER_SMALL_FR, AsrEngineSelector.select(p))
        assertEquals(VoiceModelCheck.Ok, VoiceModelChecker.check(p, engine = AsrEngine.WHISPER_SMALL_FR))
        // Whisper alone (Parakeet removed) is enough.
        p.parakeetFiles.forEach { it.delete() }
        assertEquals(AsrEngine.WHISPER_SMALL_FR, AsrEngineSelector.select(p))
        assertEquals(VoiceModelCheck.Ok, VoiceModelChecker.check(p, engine = AsrEngine.WHISPER_SMALL_FR))
    }

    @Test
    fun `partial whisper install stays whisper and names the missing file`() {
        val p = paths()
        createAll(p)
        touch(p.whisperEncoder)
        touch(p.whisperDecoder)
        val engine = AsrEngineSelector.select(p)
        assertEquals(AsrEngine.WHISPER_SMALL_FR, engine)
        assertEquals(
            VoiceModelCheck.Missing(listOf(p.whisperTokens.absolutePath)),
            VoiceModelChecker.check(p, engine = engine),
        )
    }

    @Test
    fun `engine labels shown on the voice screen`() {
        assertEquals("Whisper small (français)", AsrEngine.WHISPER_SMALL_FR.label)
        assertEquals("Parakeet (langue non forcée)", AsrEngine.PARAKEET.label)
        assertEquals(AsrEngine.WHISPER_SMALL_FR, VoiceAiStatus.Ready("GPU").asr)
        assertEquals(AsrEngine.PARAKEET, VoiceAiStatus.Ready("CPU", AsrEngine.PARAKEET).asr)
        assertEquals(null, VoiceAiUiState().asrEngine)
    }
}
