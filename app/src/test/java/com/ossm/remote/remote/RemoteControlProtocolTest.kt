package com.ossm.remote.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlProtocolTest {
    @Test
    fun `valid controls are parsed inside safety bounds`() {
        assertEquals(
            RemoteControlAction.SetSpeed(0.42f),
            RemoteControlProtocol.parse("""{"type":"set_speed","value":0.42}"""),
        )
        assertEquals(
            RemoteControlAction.SetDepth(0.2f, 0.8f),
            RemoteControlProtocol.parse(
                """{"type":"set_depth","minimum":0.2,"maximum":0.8}""",
            ),
        )
        assertEquals(
            RemoteControlAction.SelectPattern("simpleStroke"),
            RemoteControlProtocol.parse(
                """{"type":"select_pattern","key":"simpleStroke"}""",
            ),
        )
        assertTrue(RemoteControlProtocol.parse("""{"type":"stop"}""") is RemoteControlAction.Stop)
        assertTrue(RemoteControlProtocol.parse("""{"type":"emergency_stop"}""") is RemoteControlAction.EmergencyStop)
    }

    @Test
    fun `invalid or unsafe values are rejected`() {
        assertNull(RemoteControlProtocol.parse("""{"type":"set_speed","value":1.2}"""))
        assertNull(
            RemoteControlProtocol.parse(
                """{"type":"set_depth","minimum":0.9,"maximum":0.2}""",
            ),
        )
        assertNull(RemoteControlProtocol.parse("""{"type":"raw","value":"set:speed:100"}"""))
        assertNull(RemoteControlProtocol.parse("not-json"))
    }

    @Test
    fun `cloud session proof stays in the URL fragment while the websocket uses the broker route`() {
        val session = RemoteBrokerSession.parse(
            "https://remote.example/s/session_01#k=abcdefghijklmnopqrstuvwxyz012345",
        )

        assertEquals(
            "wss://remote.example/api/session/session_01/ws",
            session?.websocketUrl,
        )
        assertEquals(
            "https://remote.example/api/session/session_01",
            session?.sessionUrl,
        )
        assertEquals("abcdefghijklmnopqrstuvwxyz012345", session?.secret)
        assertNull(RemoteBrokerSession.parse("https://remote.example/s/session_01?k=abcdefghijklmnopqrstuvwxyz012345"))
        assertNull(RemoteBrokerSession.parse("https://remote.example/s/session_01#other=1&k=abcdefghijklmnopqrstuvwxyz012345"))
        assertNull(RemoteBrokerSession.parse("https://remote.example/s/session_01#k=short"))
    }

    @Test
    fun `broker session response creates a host websocket session`() {
        val session = RemoteBrokerSession.parseCreationResponse(
            """{"url":"https://remote.example/s/session_02#k=abcdefghijklmnopqrstuvwxyz012345"}""",
        )

        assertEquals(
            "wss://remote.example/api/session/session_02/ws",
            session?.websocketUrl,
        )
        assertEquals("abcdefghijklmnopqrstuvwxyz012345", session?.secret)
        assertNull(RemoteBrokerSession.parseCreationResponse("""{"url":"https://remote.example/s/session_02#k=short"}"""))
        assertNull(RemoteBrokerSession.parseCreationResponse("""{"url":42}"""))
    }

    @Test
    fun `stored session capability remains stable and can be renewed`() {
        val credentials = InMemoryRemoteSessionCredentials()
        val lifecycle = RemoteSessionLifecycle(credentials)
        val session = RemoteBrokerSession.parse(
            "https://remote.example/s/session_03#k=abcdefghijklmnopqrstuvwxyz012345",
        ) ?: error("test session is valid")

        lifecycle.persist(session)

        val restored = lifecycle.restore() as StoredRemoteSession.Available
        assertEquals(session.shareUrl, restored.session.shareUrl)
        assertEquals("https://remote.example/api/session/session_03", restored.session.sessionUrl)
    }

    @Test
    fun `unreadable stored capability requires explicit regeneration`() {
        val lifecycle = RemoteSessionLifecycle(InMemoryRemoteSessionCredentials("not-a-session"))

        assertTrue(lifecycle.restore() is StoredRemoteSession.Unreadable)
    }

    private class InMemoryRemoteSessionCredentials(initial: String? = null) : RemoteSessionCredentialPersistence {
        private var value = initial
        override fun save(value: String) { this.value = value }
        override fun read(): String? = value
        override fun hasCredential(): Boolean = value != null
        override fun clear() { value = null }
    }
}
