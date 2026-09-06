package com.ossm.remote.updater

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"

fun installApk(context: Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    context.startActivity(createApkInstallIntent(uri))
}

internal fun createApkInstallIntent(apkUri: android.net.Uri): Intent {
    return Intent(Intent.ACTION_VIEW)
        .setDataAndType(apkUri, APK_MIME_TYPE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
