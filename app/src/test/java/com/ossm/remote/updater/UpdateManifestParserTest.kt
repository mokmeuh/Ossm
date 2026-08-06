package com.ossm.remote.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestParserTest {
    @Test
    fun `parses a valid update manifest from an explicit URL`() {
        val manifest = UpdateManifestParser.parse(
            """{"versionCode":115,"versionName":"1.28.23","apkPath":"/OSSM-Remote-v1.28.23-debug.apk","sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","releaseNotes":"Local debug build"}""",
            "http://192.168.1.2:8080/update.json"
        )

        requireNotNull(manifest)
        assertEquals(115, manifest.versionCode)
        assertEquals("1.28.23", manifest.versionName)
        assertEquals("http://192.168.1.2:8080/OSSM-Remote-v1.28.23-debug.apk", manifest.apkUrl)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", manifest.sha256)
    }

    @Test
    fun `normalizes a trimmed explicit manifest URL`() {
        assertEquals(
            "https://updates.example.com:8443/update.json",
            normalizeManifestUrl("  https://updates.example.com:8443/update.json  ")
        )
    }

    @Test
    fun `rejects invalid explicit manifest URLs`() {
        listOf(
            "",
            "   ",
            "not a URL",
            "/update.json",
            "https:///update.json",
            "https://updates.example.com:99999/update.json",
            "ftp://updates.example.com/update.json",
            "http://user:password@updates.example.com/update.json",
            "https://updates.example.com/update.json?channel=debug",
            "https://updates.example.com/update.json#release"
        ).forEach { url -> assertNull(url, normalizeManifestUrl(url)) }
    }

    @Test
    fun `rejects incomplete and malformed manifests`() {
        assertNull(UpdateManifestParser.parse("{}", "http://192.168.1.2:8080/update.json"))
        assertNull(
            UpdateManifestParser.parse(
                """{"versionCode":114,"versionName":"1.28.22","apkPath":"/ossm.apk","sha256":"short"}""",
                "http://192.168.1.2:8080/update.json"
            )
        )
    }

    @Test
    fun `rejects unsafe manifest URLs`() {
        assertNull(
            UpdateManifestParser.parse(
                """{"versionCode":115,"versionName":"1.28.23","apkPath":"/ossm.apk","sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}""",
                "http://user:password@192.168.1.2:8080/update.json#release"
            )
        )
    }

    @Test
    fun `rejects APK paths that escape the manifest origin`() {
        assertNull(
            UpdateManifestParser.parse(
                """{"versionCode":115,"versionName":"1.28.23","apkPath":"//evil.example/OSSM-Remote-v1.28.23-debug.apk","sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}""",
                "http://192.168.1.2:8080/update.json"
            )
        )
    }

    @Test
    fun `only treats a greater adjacent version code as newer`() {
        val manifest = requireNotNull(
            UpdateManifestParser.parse(
                """{"versionCode":115,"versionName":"1.28.23","apkPath":"/OSSM-Remote-v1.28.23-debug.apk","sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}""",
                "http://192.168.1.2:8080/update.json"
            )
        )

        assertTrue(UpdateManifestParser.isNewer(manifest, 114))
        assertFalse(UpdateManifestParser.isNewer(manifest, 115))
    }
}
