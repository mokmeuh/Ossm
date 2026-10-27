package com.ossm.remote.remote

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSessionLimitsPayloadTest {
    private val sixKeys = setOf("type", "speedMax", "depthMin", "depthMax", "sensationMin", "sensationMax")
    private val view = RemoteSessionView(
        active = true,
        speedMax = 0.25f,
        depthMin = 0.1f,
        depthMax = 0.9f,
        sensationMin = 0.4f,
        sensationMax = 0.6f,
        remoteSpeed = 0.2f,
        remoteSensation = 0.5f,
    )

    @Test
    fun `limits message is enabled by default and matches the broker allowlist`() {
        assertEquals(setOf("limits"), BROKER_ACCEPTED_HOST_MESSAGE_TYPES)
        assertTrue(limitsPayload(view) != null)
        assertNull(limitsPayload(view, emptySet()))
    }

    @Test
    fun `limits message is a strict six key object with the symmetric sensation range`() {
        val json = JsonParser.parseString(limitsPayload(view, setOf("limits"))!!).asJsonObject
        assertEquals(sixKeys, json.keySet())
        assertEquals(6, json.size())
        assertEquals(0.4f, json.get("sensationMin").asFloat, 0.0001f)
        assertEquals(0.6f, json.get("sensationMax").asFloat, 0.0001f)
        assertEquals("limits", json.get("type").asString)
        assertEquals(0.25f, json.get("speedMax").asFloat, 0.0001f)
        assertEquals(0.1f, json.get("depthMin").asFloat, 0.0001f)
        assertEquals(0.9f, json.get("depthMax").asFloat, 0.0001f)
    }

    @Test
    fun `no limits message without an active session`() {
        assertNull(limitsPayload(RemoteSessionView(), setOf("limits")))
    }

    @Test
    fun `sensation bounds are clamped and ordered and remote values are never sent`() {
        val json = JsonParser.parseString(
            limitsPayload(view.copy(sensationMin = -0.3f, sensationMax = 1.7f), setOf("limits"))!!
        ).asJsonObject
        assertEquals(0f, json.get("sensationMin").asFloat, 0f)
        assertEquals(1f, json.get("sensationMax").asFloat, 0f)
        assertEquals(sixKeys, json.keySet())

        // The broker requires sensationMin <= sensationMax.
        val inverted = JsonParser.parseString(
            limitsPayload(view.copy(sensationMin = 0.7f, sensationMax = 0.3f), setOf("limits"))!!
        ).asJsonObject
        assertTrue(inverted.get("sensationMin").asFloat <= inverted.get("sensationMax").asFloat)
    }

    @Test
    fun `envelope from limits is symmetric around neutral`() {
        val limits = PhoneHostMotionLimits(0.5f, 0f, 1f, sensationIntensity = 0.2f)
        val json = JsonParser.parseString(
            limitsPayload(
                RemoteSessionView(
                    active = true,
                    speedMax = 0.5f,
                    sensationMin = limits.sensationMinimum,
                    sensationMax = limits.sensationMaximum,
                ),
                setOf("limits"),
            )!!
        ).asJsonObject
        assertEquals(0.3f, json.get("sensationMin").asFloat, 0.0001f)
        assertEquals(0.7f, json.get("sensationMax").asFloat, 0.0001f)
    }
}
