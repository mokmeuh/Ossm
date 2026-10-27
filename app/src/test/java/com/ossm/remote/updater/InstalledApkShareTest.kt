package com.ossm.remote.updater

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstalledApkShareTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `copies the installed APK into the cache directory`() {
        val sourceApk = File(temporaryFolder.root, "installed.apk").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val cacheDir = temporaryFolder.newFolder("cache")

        val sharedApk = copyInstalledApkToCache(sourceApk, cacheDir)

        assertEquals(File(cacheDir, "ossm.apk"), sharedApk)
        assertTrue(sharedApk.isFile)
        assertArrayEquals(sourceApk.readBytes(), sharedApk.readBytes())
    }
}
