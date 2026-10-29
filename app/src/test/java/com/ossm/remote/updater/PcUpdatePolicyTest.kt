package com.ossm.remote.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcUpdatePolicyTest {
    @Test
    fun `normalizes the server address to scheme host port and slash`() {
        assertEquals("http://100.65.88.40:8091/", normalizePcServerUrl("  http://100.65.88.40:8091  "))
        assertEquals("http://100.65.88.40:8091/", normalizePcServerUrl("100.65.88.40:8091"))
        assertEquals("https://pc.example.net/", normalizePcServerUrl("HTTPS://pc.example.net/"))
    }

    @Test
    fun `rejects addresses that are not a bare http server`() {
        listOf(
            "", "   ", "ftp://10.0.0.1:8091/", "http://", "http://10.0.0.1:99999/",
            "http://user:pw@10.0.0.1/", "http://10.0.0.1/update.json", "http://10.0.0.1/?a=1", "http://10.0.0.1/#x",
        ).forEach { assertNull(it, normalizePcServerUrl(it)) }
    }

    @Test
    fun `manifest url is built from the app package, never a fixed file name`() {
        assertEquals(
            "http://100.65.88.40:8091/p/com.ossm.remote.debug/update.json",
            pcManifestUrl("http://100.65.88.40:8091", "com.ossm.remote.debug"),
        )
        assertNull(pcManifestUrl("not an address", "com.ossm.remote.debug"))
        assertNull(pcManifestUrl("http://10.0.0.1:8091/", "../etc"))
    }

    @Test
    fun `manifest published by the server is understood by the existing parser`() {
        val sha = "a".repeat(64)
        val json = """{"packageName":"com.ossm.remote.debug","versionCode":194,"versionName":"2.3.46","apkPath":"/apk/$sha.apk","sha256":"$sha","size":10,"fileName":"any name.apk"}"""
        val manifest = requireNotNull(
            UpdateManifestParser.parse(json, "http://100.65.88.40:8091/p/com.ossm.remote.debug/update.json")
        )
        assertEquals(194, manifest.versionCode)
        assertEquals("http://100.65.88.40:8091/apk/$sha.apk", manifest.apkUrl)
    }

    @Test
    fun `downloads only a newer version that was not already handled`() {
        assertTrue(shouldDownloadPcUpdate(194, 193, null))
        assertFalse(shouldDownloadPcUpdate(193, 193, null))
        assertFalse(shouldDownloadPcUpdate(192, 193, null))
        assertFalse(shouldDownloadPcUpdate(194, 193, 194))
        assertTrue(shouldDownloadPcUpdate(195, 193, 194))
    }
}
