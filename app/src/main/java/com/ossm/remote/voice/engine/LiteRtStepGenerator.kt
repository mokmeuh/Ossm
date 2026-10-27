package com.ossm.remote.voice.engine

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.ossm.remote.voice.VoiceLlmPrompt
import com.ossm.remote.voice.VoiceStepGenerator
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Voix 2.3.27 — Gemma 3 1B int4 through LiteRT-LM (native, on the phone only). One fresh
 * conversation per phrase (system instruction + few-shot turns, no history), prepared in advance
 * so its preface is already prefilled when the next phrase arrives. Only one conversation is
 * alive at a time. Never referenced by JVM tests.
 */
internal class LiteRtStepGenerator private constructor(
    private val engine: Engine,
    val backendLabel: String,
) : VoiceStepGenerator {
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-llm").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var spare: Conversation? = null

    private fun newConversation(): Conversation = engine.createConversation(
        ConversationConfig(
            systemInstruction = Contents.of(VoiceLlmPrompt.SYSTEM),
            initialMessages = VoiceLlmPrompt.FEW_SHOTS.flatMap { (user, model) ->
                listOf(Message.user(user), Message.model(model))
            },
            samplerConfig = SamplerConfig(
                topK = VoiceLlmPrompt.TOP_K,
                topP = VoiceLlmPrompt.TOP_P,
                temperature = VoiceLlmPrompt.TEMPERATURE,
            ),
            prefillPrefaceOnInit = true,
            maxOutputToken = VoiceLlmPrompt.MAX_OUTPUT_TOKENS,
        )
    )

    /** Prefills the next conversation (warm-up). Failures are retried at the next phrase. */
    suspend fun prepare() = mutex.withLock {
        withContext(dispatcher) {
            if (spare == null) spare = runCatching { newConversation() }.getOrNull()
        }
    }

    override suspend fun generate(utterance: String): String = mutex.withLock {
        val conversation = withContext(dispatcher) { spare?.also { spare = null } ?: newConversation() }
        try {
            withTimeoutOrNull(GENERATION_TIMEOUT_MS) { conversation.reply(utterance) } ?: ""
        } finally {
            withContext(NonCancellable + dispatcher) {
                runCatching { conversation.close() }
                spare = runCatching { newConversation() }.getOrNull()
            }
        }
    }

    private suspend fun Conversation.reply(utterance: String): String {
        val done = CompletableDeferred<String>()
        val text = StringBuilder()
        sendMessageAsync(utterance, object : MessageCallback {
            override fun onMessage(message: Message) {
                synchronized(text) { text.append(message.plainText()) }
            }

            override fun onDone() {
                done.complete(synchronized(text) { text.toString() })
            }

            override fun onError(throwable: Throwable) {
                done.completeExceptionally(throwable)
            }
        })
        return try {
            done.await()
        } catch (cancelled: CancellationException) {
            // Stop native decoding, then wait (bounded) for it before the conversation is closed.
            runCatching { cancelProcess() }
            withContext(NonCancellable) { withTimeoutOrNull(CANCEL_WAIT_MS) { runCatching { done.await() } } }
            throw cancelled
        }
    }

    fun close() {
        runCatching { spare?.close() }
        spare = null
        runCatching { engine.close() }
        dispatcher.close()
    }

    companion object {
        private const val GENERATION_TIMEOUT_MS = 20_000L
        private const val CANCEL_WAIT_MS = 3_000L

        private fun Message.plainText(): String =
            contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

        /** Loads the model on the GPU, falling back to the CPU. Blocking (several seconds). */
        fun load(model: File, cacheDir: File): LiteRtStepGenerator {
            var lastError: Throwable? = null
            val backends: List<Pair<() -> Backend, String>> = listOf(
                { Backend.GPU() } to "GPU",
                { Backend.CPU() } to "CPU",
            )
            for ((backend, label) in backends) {
                var engine: Engine? = null
                try {
                    engine = Engine(
                        EngineConfig(
                            modelPath = model.absolutePath,
                            backend = backend(),
                            cacheDir = cacheDir.absolutePath,
                        )
                    )
                    engine.initialize()
                    return LiteRtStepGenerator(engine, label)
                } catch (t: Throwable) {
                    lastError = t
                    runCatching { engine?.close() }
                }
            }
            throw IllegalStateException("LiteRT-LM : ${lastError?.javaClass?.simpleName ?: "erreur"}", lastError)
        }
    }
}
