package com.ossm.remote.updater

import android.net.wifi.WifiManager
import java.io.ByteArrayInputStream
import java.io.BufferedInputStream
import java.io.File
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalOtaTransferTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `accepts only a strictly newer version with same package and signer`() {
        val metadata = LocalOtaApkMetadata("com.ossm.remote.debug", 152, setOf("debug-key"))

        assertEquals(
            LocalOtaRejection.AlreadyUpToDate,
            validateLocalOtaApk(metadata, "com.ossm.remote.debug", 152, setOf("debug-key")),
        )
        assertNull(validateLocalOtaApk(metadata.copy(versionCode = 153), "com.ossm.remote.debug", 152, setOf("debug-key")))
    }

    @Test
    fun `rejects a different package`() {
        val error = validateLocalOtaApk(
            LocalOtaApkMetadata("com.example.other", 153, setOf("debug-key")),
            "com.ossm.remote.debug",
            152,
            setOf("debug-key"),
        )

        assertEquals(LocalOtaRejection.WrongPackage, error)
    }

    @Test
    fun `rejects an older version or a different signer`() {
        val older = validateLocalOtaApk(
            LocalOtaApkMetadata("com.ossm.remote.debug", 151, setOf("debug-key")),
            "com.ossm.remote.debug",
            152,
            setOf("debug-key"),
        )
        val signer = validateLocalOtaApk(
            LocalOtaApkMetadata("com.ossm.remote.debug", 153, setOf("other-key")),
            "com.ossm.remote.debug",
            152,
            setOf("debug-key"),
        )

        assertEquals(LocalOtaRejection.AlreadyUpToDate, older)
        assertEquals(LocalOtaRejection.WrongSigner, signer)
    }

    @Test
    fun `reads and copies a bounded OTA request body`() {
        val body = "apk-bytes".toByteArray()
        val request = "POST /v1/apk HTTP/1.1\r\nContent-Length: ${body.size}\r\n\r\n"
            .toByteArray() + body
        val parsed = readLocalOtaHttpRequest(BufferedInputStream(ByteArrayInputStream(request)))
        val destination = temporaryFolder.newFile("received.apk")

        assertEquals("POST", parsed?.method)
        assertEquals("/v1/apk", parsed?.path)
        assertEquals(body.size.toLong(), parsed?.contentLength)
        assertTrue(copyLocalOtaBody(
            BufferedInputStream(ByteArrayInputStream(body)),
            destination,
            body.size.toLong(),
        ))
        assertTrue(destination.readBytes().contentEquals(body))
    }

    @Test
    fun `rejects malformed request headers`() {
        val request = "POST /v1/apk HTTP/1.1\r\nContent-Length: nope\r\n\r\n".toByteArray()

        assertFalse(readLocalOtaHttpRequest(BufferedInputStream(ByteArrayInputStream(request))) != null)
    }

    @Test
    fun `accepts a GET without body and strips the query`() {
        val request = "GET /ossm-ota/info?x=1 HTTP/1.1\r\nX-OSSM-Session: abc\r\n\r\n".toByteArray()
        val parsed = readLocalOtaHttpRequest(BufferedInputStream(ByteArrayInputStream(request)))

        assertEquals("GET", parsed?.method)
        assertEquals(LocalOtaProtocol.LEGACY_INFO_PATH, parsed?.path)
        assertEquals(0L, parsed?.contentLength)
        assertEquals("abc", parsed?.headers?.get(LocalOtaProtocol.SESSION_HEADER))
    }

    @Test
    fun `still requires a length for POST uploads`() {
        val request = "POST /v1/apk HTTP/1.1\r\n\r\n".toByteArray()

        assertNull(readLocalOtaHttpRequest(BufferedInputStream(ByteArrayInputStream(request))))
    }

    @Test
    fun `keeps multicast enabled until both OTA roles finish`() {
        val lock = mockk<WifiManager.MulticastLock>(relaxed = true)
        val lifecycle = LocalOtaMulticastLock(lock)

        lifecycle.acquire(LocalOtaMulticastUser.RECEIVER)
        lifecycle.acquire(LocalOtaMulticastUser.SENDER)
        lifecycle.release(LocalOtaMulticastUser.RECEIVER)

        verify(exactly = 1) { lock.acquire() }
        verify(exactly = 0) { lock.release() }

        lifecycle.release(LocalOtaMulticastUser.SENDER)

        verify(exactly = 1) { lock.release() }
    }
}
