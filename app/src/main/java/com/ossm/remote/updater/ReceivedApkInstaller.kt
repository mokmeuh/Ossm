package com.ossm.remote.updater

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream

private const val RECEIVED_APK_FILE_PREFIX = "received-ossm-"
private const val RECEIVED_APK_FILE_EXTENSION = ".apk"
private const val RECEIVED_APK_STAGING_FILE_SUFFIX = "$RECEIVED_APK_FILE_EXTENSION.part"
private const val RECEIVED_APK_CACHE_DIRECTORY = "updates"

fun receiveAndInstallSharedApk(context: Context, sharedIntent: Intent): Intent? {
    val sourceUri = sharedApkUri(sharedIntent) ?: return null
    val source = context.contentResolver.openInputStream(sourceUri) ?: return null
    val copiedApk = copyReceivedApkToCache(
        source,
        File(context.cacheDir, RECEIVED_APK_CACHE_DIRECTORY).apply { mkdirs() }
    )
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
    val cacheDirectoryReady = cacheDir.isDirectory ||
        (cacheDir.mkdirs() && cacheDir.isDirectory)
    check(cacheDirectoryReady) { "Unable to create APK cache directory: $cacheDir" }

    val staging = File.createTempFile(RECEIVED_APK_FILE_PREFIX, RECEIVED_APK_STAGING_FILE_SUFFIX, cacheDir)
    val destination = File(
        cacheDir,
        "${staging.name.removeSuffix(RECEIVED_APK_STAGING_FILE_SUFFIX)}$RECEIVED_APK_FILE_EXTENSION"
    )

    try {
        source.use { input ->
            staging.outputStream().use(input::copyTo)
        }
        check(staging.renameTo(destination)) { "Unable to finalize received APK" }
        return destination
    } finally {
        staging.delete()
    }
}
