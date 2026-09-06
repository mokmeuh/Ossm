package com.ossm.remote.updater

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream

private const val RECEIVED_APK_FILE_NAME = "received-ossm.apk"
private const val RECEIVED_APK_STAGING_FILE_NAME = "received-ossm.apk.part"

fun receiveAndInstallSharedApk(context: Context, sharedIntent: Intent): Intent? {
    val sourceUri = sharedApkUri(sharedIntent) ?: return null
    val source = context.contentResolver.openInputStream(sourceUri) ?: return null
    val copiedApk = copyReceivedApkToCache(source, context.cacheDir)
    val copiedApkUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        copiedApk
    )
    return createReceivedApkInstallerIntent(sharedIntent, copiedApkUri)
}

internal fun createReceivedApkInstallerIntent(
    sharedIntent: Intent,
    copiedApkUri: Uri
): Intent? {
    return sharedApkUri(sharedIntent)?.let { createApkInstallIntent(copiedApkUri) }
}

internal fun sharedApkUri(intent: Intent): Uri? {
    if (intent.action != Intent.ACTION_SEND || intent.type != APK_MIME_TYPE) return null
    @Suppress("DEPRECATION")
    val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    return streamUri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
}

internal fun copyReceivedApkToCache(source: InputStream, cacheDir: File): File {
    val staging = File(cacheDir, RECEIVED_APK_STAGING_FILE_NAME)
    val destination = File(cacheDir, RECEIVED_APK_FILE_NAME)
    source.use { input ->
        staging.outputStream().use(input::copyTo)
    }
    staging.copyTo(destination, overwrite = true)
    staging.delete()
    return destination
}
