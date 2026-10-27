package com.ossm.remote.natural

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NaturalAiEndpointTest {
    @Test
    fun `private and tailscale addresses are accepted with the ollama default port`() {
        val tailscale = NaturalAiEndpoint.parse("100.101.102.103")
        assertNotNull(tailscale)
        assertEquals(11434, tailscale!!.url.port)
        assertEquals("http", tailscale.url.scheme)

        assertEquals(8080, NaturalAiEndpoint.parse("http://192.168.1.20:8080")!!.url.port)
        assertNotNull(NaturalAiEndpoint.parse("10.0.0.5:11434"))
        assertNotNull(NaturalAiEndpoint.parse("pc-bureau"))
        assertNotNull(NaturalAiEndpoint.parse("pc.tail1234.ts.net"))
        assertNotNull(NaturalAiEndpoint.parse("localhost"))
        assertNotNull(NaturalAiEndpoint.parse("http://[fd7a:115c:a1e0::1]:11434"))
    }

    @Test
    fun `empty, public or credentialed addresses are refused`() {
        assertNull(NaturalAiEndpoint.parse(""))
        assertNull(NaturalAiEndpoint.parse("   "))
        assertNull(NaturalAiEndpoint.parse("8.8.8.8"))
        assertNull(NaturalAiEndpoint.parse("https://api.example.com"))
        assertNull(NaturalAiEndpoint.parse("http://user:pass@192.168.1.2:11434"))
        assertNull(NaturalAiEndpoint.parse("100.200.1.1")) // outside 100.64.0.0/10
        assertNull(NaturalAiEndpoint.parse("ftp://192.168.1.2"))
    }

    @Test
    fun `path and query are stripped`() {
        val endpoint = NaturalAiEndpoint.parse("http://192.168.1.20:11434/api/generate?x=1")!!
        assertEquals("/", endpoint.url.encodedPath)
        assertNull(endpoint.url.query)
    }
}
