package com.ossm.remote.updater

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
