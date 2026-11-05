package com.ossm.remote.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcFirmwarePolicyTest {
    private val sha = "69fef523db225d9a60665d37e2df1e871ea22f6741258f29cd0fc0ba9f36858f"

    @Test
    fun `parses the manifest published by the server`() {
        val json = """
            {
              "sha256": "$sha",
              "size": 1951568,
              "modifiedMs": 1793491168375,
              "label": "anything-name.bin"
            }
        """.trimIndent()
        assertEquals(PcFirmwareManifest(sha, 1951568, 1793491168375L, "anything-name.bin"), parsePcFirmwareManifest(json))
    }

    @Test
    fun `refuses a manifest with a bad digest or an impossible size`() {
        assertNull(parsePcFirmwareManifest("""{"sha256":"abc","size":100000,"modifiedMs":1,"label":"x"}"""))
        assertNull(parsePcFirmwareManifest("""{"sha256":"$sha","size":0,"modifiedMs":1,"label":"x"}"""))
        assertNull(parsePcFirmwareManifest("""{"sha256":"$sha","size":${PC_FIRMWARE_MAX_BYTES + 1},"modifiedMs":1,"label":"x"}"""))
        assertNull(parsePcFirmwareManifest("""{"error":"Aucune image firmware valide"}"""))
        assertNull(parsePcFirmwareManifest(""))
    }

    @Test
    fun `falls back to a generic label`() {
        val parsed = parsePcFirmwareManifest("""{"sha256":"$sha","size":100000,"modifiedMs":5}""")
        assertEquals("firmware.bin", parsed?.label)
    }

    @Test
    fun `urls are built from the saved server address`() {
        assertEquals("http://100.65.88.40:8091/firmware/update.json", pcFirmwareManifestUrl("100.65.88.40:8091"))
        assertEquals("http://100.65.88.40:8091/firmware/$sha.bin", pcFirmwareDownloadUrl("http://100.65.88.40:8091/", sha))
        assertNull(pcFirmwareManifestUrl("not a url ://"))
    }

    @Test
    fun `download url refuses anything that is not a digest`() {
        assertNull(pcFirmwareDownloadUrl("http://100.65.88.40:8091/", "../../secret"))
        assertNull(pcFirmwareDownloadUrl("http://100.65.88.40:8091/", sha.uppercase()))
        assertNull(pcFirmwareDownloadUrl("http://100.65.88.40:8091/", "firmware"))
    }

    @Test
    fun `offers the image unless it is the one last flashed`() {
        assertTrue(shouldOfferPcFirmware(sha, null))
        assertTrue(shouldOfferPcFirmware(sha, ""))
        assertTrue(shouldOfferPcFirmware(sha, "0".repeat(64)))
        assertFalse(shouldOfferPcFirmware(sha, sha))
        assertFalse(shouldOfferPcFirmware(sha, sha.uppercase()))
    }
}
