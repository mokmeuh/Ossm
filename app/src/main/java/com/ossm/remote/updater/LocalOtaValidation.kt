package com.ossm.remote.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

internal sealed interface LocalOtaFileCheck {
    data object NotAnApk : LocalOtaFileCheck
    data class Rejected(val reason: LocalOtaRejection) : LocalOtaFileCheck
}

internal fun validateLocalOtaApkFile(context: Context, apk: File): LocalOtaFileCheck? {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }
    val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        ?: return LocalOtaFileCheck.NotAnApk
    val installed = context.packageManager.getPackageInfo(context.packageName, flags)
    val rejection = validateLocalOtaApk(
        LocalOtaApkMetadata(archive.packageName, versionCodeOf(archive), signerIds(archive)),
        context.packageName,
        versionCodeOf(installed),
        signerIds(installed),
    )
    return rejection?.let(LocalOtaFileCheck::Rejected)
}

internal fun installedLocalOtaVersion(context: Context): Pair<Long, String> {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return versionCodeOf(info) to (info.versionName ?: versionCodeOf(info).toString())
}

@Suppress("DEPRECATION")
private fun versionCodeOf(info: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

@Suppress("DEPRECATION")
private fun signerIds(info: PackageInfo): Set<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
    } else {
        info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
    }
