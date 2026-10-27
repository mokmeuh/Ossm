package com.ossm.remote.updater

import android.content.Intent
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReceivedApkInstallerTest {

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }

    @Test
    fun `creates an installer intent when an APK content URI is shared`() {
        val sourceUri = Uri.parse("content://sender.example/ossm.apk")
        val copiedApkUri = Uri.parse("content://com.ossm.remote.fileprovider/updates/received-ossm.apk")
        val sharedIntent = Intent(Intent.ACTION_SEND).apply {
            type = APK_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, sourceUri)
        }

        val installerIntent = createReceivedApkInstallerIntent(sharedIntent, copiedApkUri)

        requireNotNull(installerIntent)
        assertEquals(Intent.ACTION_VIEW, installerIntent.action)
        assertEquals(APK_MIME_TYPE, installerIntent.type)
        assertEquals(copiedApkUri, installerIntent.data)
    }

    @Test
    fun `rejects a share with a missing or non APK MIME type`() {
        val sourceUri = Uri.parse("content://sender.example/ossm.apk")
        val copiedApkUri = Uri.parse("content://com.ossm.remote.fileprovider/updates/received-ossm.apk")
        val missingMimeIntent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, sourceUri)
        val wrongMimeIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, sourceUri)
        }

        assertNull(createReceivedApkInstallerIntent(missingMimeIntent, copiedApkUri))
        assertNull(createReceivedApkInstallerIntent(wrongMimeIntent, copiedApkUri))
    }

    @Test
    fun `rejects a share without a content stream`() {
        val copiedApkUri = Uri.parse("content://com.ossm.remote.fileprovider/updates/received-ossm.apk")
        val missingStreamIntent = Intent(Intent.ACTION_SEND).apply { type = APK_MIME_TYPE }
        val fileStreamIntent = Intent(Intent.ACTION_SEND).apply {
            type = APK_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/Download/ossm.apk"))
        }

        assertNull(createReceivedApkInstallerIntent(missingStreamIntent, copiedApkUri))
        assertNull(createReceivedApkInstallerIntent(fileStreamIntent, copiedApkUri))
    }

    @Test
    fun `uses unique cache files in the FileProvider safe updates directory`() {
        val context = RuntimeEnvironment.getApplication()
        val cacheRoot = File(context.cacheDir, "updates")
        val firstContents = "first APK".toByteArray()
        val secondContents = "second APK".toByteArray()
        val firstApk = copyReceivedApkToCache(ByteArrayInputStream(firstContents), cacheRoot)
        val secondApk = copyReceivedApkToCache(ByteArrayInputStream(secondContents), cacheRoot)

        try {
            assertNotEquals(firstApk, secondApk)
            assertNotEquals(firstApk.name, secondApk.name)
            assertEquals(cacheRoot, firstApk.parentFile)
            assertEquals(cacheRoot, secondApk.parentFile)
            assertEquals("updates/${firstApk.name}", firstApk.relativeTo(context.cacheDir).invariantSeparatorsPath)
            assertEquals("updates/${secondApk.name}", secondApk.relativeTo(context.cacheDir).invariantSeparatorsPath)
            assertArrayEquals(firstContents, firstApk.readBytes())
        } finally {
            firstApk.delete()
            secondApk.delete()
        }
    }
}
