package com.ossm.remote.remote

import com.ossm.remote.BuildConfig
import com.ossm.remote.model.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.3.16: camera share diagnostics are readable and never carry a link or a key. */
class CameraShareDiagnosticsTest {
    private val origin = "https://ossm-remote-2.ossm-remote.workers.dev"
    private val id = "AbCdEf012345_-xy"
    private val secret = "Zx9_kQ2-mN7pL4rT8vW1yB3cD5fG6hJ0"
    private val realFormat = "$origin/s/$id?role=camera#k=$secret"

    private fun assertNoSecret(text: String) {
        assertFalse(text, text.contains(secret))
        assertFalse(text, text.contains("#k="))
        assertFalse(text, text.contains("https://"))
        assertFalse(text, text.contains(id))
    }

    @Test
    fun `the real broker camera link format is loadable`() {
        assertTrue(CameraSharePolicy.isLoadableCameraUrl(realFormat, origin))
        assertNull(CameraSharePolicy.cameraUrlProblem(realFormat, origin))
        // Same with the broker origin the app is actually built with.
        val built = "${BuildConfig.REMOTE_BROKER_BASE_URL.trimEnd('/')}/s/$id?role=camera#k=$secret"
        assertTrue(CameraSharePolicy.isLoadableCameraUrl(built, BuildConfig.REMOTE_BROKER_BASE_URL))
    }

    @Test
    fun `each refusal reason is explicit and contains no link`() {
        val cases = mapOf(
            null to "absent",
            "$origin/s/$id?role=camera" to "fragment",
            "$origin/s/$id#k=$secret" to "role=camera",
            "$origin/x/$id?role=camera#k=$secret" to "chemin",
            "https://evil.example/s/$id?role=camera#k=$secret" to "origine",
            "http://ossm-remote-2.ossm-remote.workers.dev/s/$id?role=camera#k=$secret" to "HTTPS",
        )
        cases.forEach { (url, expected) ->
            val problem = CameraSharePolicy.cameraUrlProblem(url, origin)
            assertTrue("$expected / $problem", problem != null && problem.contains(expected))
            assertFalse(CameraSharePolicy.isLoadableCameraUrl(url, origin))
            assertNoSecret(problem!!)
            assertNoSecret(CameraShareDiagnostics.linkCheck(url, origin))
        }
        assertNoSecret(CameraShareDiagnostics.linkCheck(realFormat, origin))
    }

    @Test
    fun `sanitize removes links keys and opaque tokens but keeps error names`() {
        val dirty = "Échec $realFormat puis k=$secret et jeton $secret ; net::ERR_NAME_NOT_RESOLVED"
        val clean = CameraShareDiagnostics.sanitize(dirty)
        assertNoSecret(clean)
        assertTrue(clean, clean.contains("ERR_NAME_NOT_RESOLVED"))
        assertTrue(CameraShareDiagnostics.sanitize("x".repeat(500)).length <= 180)
    }

    @Test
    fun `journal sanitizes caps and mirrors every event to the diagnostics sink`() {
        val entries = mutableListOf<CameraJournalEntry>()
        val sink = mutableListOf<Pair<LogLevel, String>>()
        val journal = CameraShareJournal(entries, { level, message -> sink += level to message }, capacity = 3)

        journal.record(LogLevel.INFO, CameraShareDiagnostics.pageStarted())
        journal.record(LogLevel.ERROR, CameraShareDiagnostics.loadError(-2, "net::ERR_NAME_NOT_RESOLVED $realFormat", true))
        journal.record(LogLevel.WARNING, CameraShareDiagnostics.console("error", "NotReadableError at $realFormat"))
        journal.record(LogLevel.ERROR, CameraShareDiagnostics.renderProcessGone(didCrash = true))

        assertEquals(3, entries.size)
        assertEquals(4, sink.size)
        (entries.map { it.message } + sink.map { it.second }).forEach(::assertNoSecret)
        assertTrue(sink[1].second.contains("code -2"))
        assertTrue(sink[1].second.contains("ERR_NAME_NOT_RESOLVED"))
        assertTrue(entries.last().message.contains("planté"))
    }

    @Test
    fun `permission and state messages are readable`() {
        val video = CameraSharePolicy.RESOURCE_VIDEO_CAPTURE
        val audio = CameraSharePolicy.RESOURCE_AUDIO_CAPTURE
        assertTrue(CameraShareDiagnostics.webPermission(true, arrayOf(video, audio), arrayOf(video, audio)).contains("accordée"))
        assertTrue(CameraShareDiagnostics.webPermission(true, arrayOf(video, audio), arrayOf(video)).contains("partielle"))
        assertTrue(CameraShareDiagnostics.webPermission(true, arrayOf(video), emptyArray()).contains("refusée"))
        assertTrue(CameraShareDiagnostics.webPermission(false, arrayOf(video), emptyArray()).contains("origine"))
        assertEquals(
            "permissions Android : caméra=accordée, micro=refusé",
            CameraShareDiagnostics.androidPermissions(camera = true, microphone = false),
        )
        assertTrue(CameraShareDiagnostics.pageFinished(fragmentKept = true).contains("oui"))
        assertTrue(CameraShareDiagnostics.httpError(404, mainFrame = true).contains("404"))
        assertTrue(CameraShareDiagnostics.webViewSettings(true, true, false).contains("média sans geste=oui"))
        assertEquals(LogLevel.ERROR, CameraShareDiagnostics.consoleLevel("ERROR"))
    }

    @Test
    fun `camera WebView keeps the required settings and loads the complete link unchanged`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first(File::isDirectory)
        val source = File(root, "com/ossm/remote/ui/screens/CameraShareScreen.kt").readText()
        assertTrue(source.contains("javaScriptEnabled = true"))
        assertTrue(source.contains("domStorageEnabled = true"))
        assertTrue(source.contains("mediaPlaybackRequiresUserGesture = false"))
        // The exact link string (query and #k= fragment included) is passed to loadUrl.
        assertTrue(source.contains("loadUrl(cameraUrl)"))
        assertFalse(Regex("""loadUrl\(cameraUrl\.""").containsMatchIn(source))
        // No direct logcat in the camera screen; everything goes through the sanitizing journal.
        assertFalse(Regex("""\bLog\.[a-z]+\(""").containsMatchIn(source))
        // Each journal event is a CameraShareDiagnostics message, never the raw link.
        source.lines().filter { "journal.record(" in it || "diagnosticSink(" in it }.forEach { line ->
            assertFalse(line, line.contains("cameraUrl"))
        }
    }

    @Test
    fun `WebView audio capture has its normal manifest permission`() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first(File::isFile).readText()
        assertTrue(manifest.contains("android.permission.CAMERA"))
        assertTrue(manifest.contains("android.permission.RECORD_AUDIO"))
        assertTrue(manifest.contains("android.permission.MODIFY_AUDIO_SETTINGS"))
    }
}
