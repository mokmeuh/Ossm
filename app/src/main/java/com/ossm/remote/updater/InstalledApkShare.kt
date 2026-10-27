package com.ossm.remote.updater

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

private const val SHARED_APK_FILE_NAME = "ossm.apk"

fun shareInstalledApk(context: Context) {
    val apk = copyInstalledApkToCache(File(context.applicationInfo.sourceDir), context.cacheDir)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    context.startActivity(Intent.createChooser(createInstalledApkShareIntent(uri), null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

internal fun copyInstalledApkToCache(sourceApk: File, cacheDir: File): File {
    return File(cacheDir, SHARED_APK_FILE_NAME).also { sharedApk ->
        sourceApk.copyTo(sharedApk, overwrite = true)
    }
}

internal fun createInstalledApkShareIntent(apkUri: Uri): Intent {
    return Intent(Intent.ACTION_SEND).apply {
        type = APK_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, apkUri)
        clipData = ClipData.newRawUri("OSSM APK", apkUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
