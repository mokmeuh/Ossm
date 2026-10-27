package com.ossm.remote.natural

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalAiPacingTest {

    @Test
    fun `target is 3x latency plus 20 s and triggers earlier when latency grows`() {
        val pacing = NaturalAiPacing() // default 15 s -> 65 s
        assertEquals(65_000L, pacing.thresholdMs)
        assertFalse(pacing.shouldRequest(70_000L))
        pacing.record(30_000L)
        assertEquals(110_000L, pacing.thresholdMs)
        assertTrue(pacing.shouldRequest(70_000L)) // same reserve now asks earlier
        // A single fast answer does not lower the look-ahead (max of the recent window).
        pacing.record(2_000L)
        assertEquals(110_000L, pacing.thresholdMs)
        assertEquals(2_000L, pacing.lastLatencyMs)
        repeat(NaturalAiPacing.WINDOW) { pacing.record(5_000L) }
        assertEquals(35_000L, pacing.thresholdMs)
    }

    @Test
    fun `threshold stays bounded and reasoning plans are longer`() {
        val pacing = NaturalAiPacing.forEndpoint(reasoningRelay = true)
        assertEquals(200_000L, pacing.thresholdMs)
        pacing.record(600_000L)
        assertEquals(NaturalAiPacing.MAX_THRESHOLD_MS, pacing.thresholdMs)
        assertEquals(NaturalPlan.MAX_PHRASES, pacing.phrasesPerPlan(reasoningRelay = true))
        val plain = NaturalAiPacing()
        assertEquals(3, plain.phrasesPerPlan(reasoningRelay = false))
        plain.record(1_000L)
        assertEquals(NaturalAiPacing.PHRASES_PER_PLAN, plain.phrasesPerPlan(reasoningRelay = false))
        plain.record(30_000L)
        assertEquals(5, plain.phrasesPerPlan(reasoningRelay = false))
        val info = plain.info(42_400L)
        assertEquals(42, info.reserveSeconds)
        assertEquals(30, info.lastLatencySeconds)
        assertEquals(110, info.thresholdSeconds)
        assertNull(NaturalAiPacing().info(0L).lastLatencySeconds)
    }

    /** Store-like loop: tick 200 ms, one request at a time, each answered 30 s later. */
    @Test
    fun `no gap and no local gesture with a simulated 30 s latency`() {
        val engine = NaturalModeEngine(seed = 3L)
        val settings = NaturalSettings(energy = 0.5f, variety = 0.5f)
        val limits = NaturalLimits.of(0.5f, 0.2f, 0.8f, 0.2f, 0.8f)
        val pacing = NaturalAiPacing()
        val latency = 30_000L
        var now = 0L
        var pendingUntil: Long? = null
        var pendingPhrases = 0
        var requestedAt = 0L
        var received = false
        var refillsAfterFirstPlan = -1
        var requests = 0
        var minReserveAfterFirst = Long.MAX_VALUE
        while (now < 30 * 60_000L) {
            now += 200L
            if (engine.started) engine.tick(200L, limits, settings)
            val due = pendingUntil
            if (due != null && now >= due) {
                pacing.record(now - requestedAt)
                val plan = localNaturalPlan(now, pendingPhrases) // stands for an AI plan
                if (!engine.started) {
                    engine.start(NaturalFrame(0f, 0.2f, 0.8f, 0.5f), limits, settings, firstAiPlan = plan)
                    refillsAfterFirstPlan = engine.localRefillCount
                } else {
                    engine.acceptAiPlan(plan, settings, replaceLocalFuture = false)
                }
                received = true
                pendingUntil = null
            }
            if (received) minReserveAfterFirst = minOf(minReserveAfterFirst, engine.reserveMs)
            if (pendingUntil == null && (!received || pacing.shouldRequest(engine.reserveMs))) {
                requests++
                requestedAt = now
                pendingPhrases = pacing.phrasesPerPlan(reasoningRelay = false)
                pendingUntil = now + latency
            }
        }
        assertTrue(received)
        assertEquals(0, refillsAfterFirstPlan)
        assertEquals("local refills while the AI feeds the queue", 0, engine.localRefillCount)
        assertTrue("min reserve $minReserveAfterFirst", minReserveAfterFirst > 0L)
        assertTrue("requests=$requests", requests in 5..200)
    }

    @Test
    fun `an ai-started engine holds only ai gestures`() {
        val engine = NaturalModeEngine(seed = 5L)
        val settings = NaturalSettings()
        val limits = NaturalLimits.of(0.5f, 0.2f, 0.8f)
        assertFalse(engine.started)
        val plan = localNaturalPlan(9L, 1)
        engine.start(NaturalFrame(0f, 0.2f, 0.8f, 0.5f), limits, settings, firstAiPlan = plan)
        assertTrue(engine.started)
        val expected = NaturalGestureGenerator().generate(plan.phrases[0], settings, kotlin.random.Random(0)).size
        assertTrue(engine.queuedGestureCount in (expected - 1)..(expected + 1))
        assertEquals(plan.phrases[0].motif, engine.tick(200L, limits, settings).let { engine.currentGesture!!.motif })
        assertEquals(0, engine.localRefillCount)
    }

    @Test
    fun `fallback causes are readable in the journal`() {
        assertEquals(
            "IA defaillante -> generateur local (reserve epuisee : latence 42 s > reserve 30 s)",
            NaturalAiFallback(NaturalAiFallbackKind.RESERVE_EXHAUSTED, latencySeconds = 42, reserveSeconds = 30).logText("qwen3:8b"),
        )
        assertTrue(NaturalAiFallback(NaturalAiFallbackKind.MODEL_UNLOADED).logText("qwen3:8b").contains("modele decharge"))
    }

    @Test
    fun `reasoning endpoint keeps the host, moves to 11435 and plans with think`() {
        val endpoint = NaturalAiEndpoint.parse("100.65.88.40")!!
        assertEquals(11434, endpoint.url.port)
        assertFalse(endpoint.reasoningRelay)
        assertEquals(endpoint, endpoint.withReasoningRelay(false))
        val relay = endpoint.withReasoningRelay(true)
        assertEquals("100.65.88.40", relay.url.host)
        assertEquals(NaturalAiEndpoint.REASONING_RELAY_PORT, relay.url.port)
        assertTrue(relay.reasoningRelay)
        assertEquals(11435, NaturalAiEndpoint.parse("100.65.88.40:11434")!!.withReasoningRelay(true).url.port)
        assertFalse(NaturalAiPlannerFactory.plannerConfigFor(endpoint).think)
        assertTrue(NaturalAiPlannerFactory.plannerConfigFor(relay).think)
    }

    private class Transport(private val envelope: String) : OllamaTransport {
        var lastBody = ""
        override suspend fun get(path: String) = "{\"models\":[{\"name\":\"qwen3:8b\"}]}"
        override suspend fun post(path: String, body: String): String {
            lastBody = body
            return envelope
        }
    }

    private val planJson =
        "{\\\"schemaVersion\\\":1,\\\"phrases\\\":[{\\\"motif\\\":\\\"wave\\\",\\\"intensity\\\":0.6,\\\"tempo\\\":0.3,\\\"variation\\\":0.7,\\\"rest\\\":0.1}]}"

    private val input = NaturalPlanInput(
        NaturalConstraints(20, 80, 35, seed = 7L),
        phraseCount = 6,
        pacing = NaturalAiPacingInfo(reserveSeconds = 42, lastLatencySeconds = 31, thresholdSeconds = 72),
    )

    @Test
    fun `think flag follows the config and the relay thinking field is ignored`() = runBlocking {
        val envelope = "{\"model\":\"qwen3:8b\",\"created_at\":\"t\",\"response\":\"$planJson\"," +
            "\"thinking\":\"Je reflechis {pas du JSON}\",\"done\":true,\"done_reason\":\"stop\"}"
        val relayTransport = Transport(envelope)
        val plan = OllamaNaturalPlanner(relayTransport, OllamaNaturalPlannerConfig.reasoning()).plan(input)
        assertEquals(1, plan.phrases.size)
        val request = JsonParser.parseString(relayTransport.lastBody).asJsonObject
        assertTrue(request["think"].asBoolean)
        assertFalse(request["stream"].asBoolean)
        assertEquals(42, request["ossm_pacing"].asJsonObject["reserve_s"].asInt)
        assertTrue(request["prompt"].asString.contains("Choose 6"))

        val plainTransport = Transport(envelope)
        OllamaNaturalPlanner(plainTransport).plan(input)
        val plain = JsonParser.parseString(plainTransport.lastBody).asJsonObject
        assertFalse(plain["think"].asBoolean)
        assertFalse(plain.has("ossm_pacing")) // never sent straight to Ollama
    }
}
