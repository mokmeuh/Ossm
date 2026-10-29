package com.ossm.remote.updater

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ossm.remote.AppForegroundTracker
import com.ossm.remote.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface PcUpdateStatus {
    data object Idle : PcUpdateStatus
    data object Checking : PcUpdateStatus
    data object Unreachable : PcUpdateStatus
    data object UpToDate : PcUpdateStatus
    data class Downloading(val versionName: String) : PcUpdateStatus
    data object Rejected : PcUpdateStatus
    data object Error : PcUpdateStatus
    data class Ready(val apk: File, val versionName: String) : PcUpdateStatus
}

/**
 * Pulls the latest APK from the PC server (tools/apk-pull-server). Only the phone ever opens a
 * connection; the PC server is off most of the time, so a failed poll is the normal case and
 * stays silent. The Android install confirmation is never bypassed: the installer is only
 * opened (or offered by a notification), the user still confirms.
 */
@Singleton
class PcUpdateController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: UpdateRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val statusFlow = MutableStateFlow<PcUpdateStatus>(PcUpdateStatus.Idle)
    val status: StateFlow<PcUpdateStatus> = statusFlow
    val serverUrl: StateFlow<String> = repository.pcServerUrl.stateIn(scope, SharingStarted.Eagerly, "")
    val listening: StateFlow<Boolean> = repository.pcListening.stateIn(scope, SharingStarted.Eagerly, false)

    private val gate = PcCheckGate()
    @Volatile private var handledVersionCode: Int? = null

    init {
        // One quiet check each time the app comes to the foreground (launch included), whether or
        // not the listening mode is on. A server that does not answer within ~2 s is simply off.
        scope.launch {
            AppForegroundTracker.isAppInForegroundFlow.collect { foreground ->
                if (foreground) run(manual = false)
            }
        }
        scope.launch {
            combine(listening, serverUrl) { on, url -> on && url.isNotBlank() }.collectLatest { active ->
                while (active && isActive) {
                    run(manual = false)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    /** Saves the address; false when it is not a usable http(s) address. */
    fun saveServerUrl(text: String): Boolean {
        val normalized = normalizePcServerUrl(text) ?: return false
        scope.launch { repository.savePcServerUrl(normalized) }
        return true
    }

    fun setListening(enabled: Boolean) {
        if (enabled) handledVersionCode = null
        scope.launch { repository.savePcListening(enabled) }
    }

    /** The button: look now, download, verify, open the installer. */
    fun installLatestNow(addressText: String) {
        val normalized = normalizePcServerUrl(addressText)
        if (normalized == null) {
            statusFlow.value = PcUpdateStatus.Error
            return
        }
        scope.launch {
            repository.savePcServerUrl(normalized)
            handledVersionCode = null
            run(manual = true, knownServer = normalized)
        }
    }

    fun openInstaller(apk: File) = installApk(context, apk)

    private suspend fun run(manual: Boolean, knownServer: String? = null) {
        val server = knownServer ?: repository.pcServerUrl.first()
        if (!gate.tryBegin(server)) return
        try {
            val manifestUrl = pcManifestUrl(server, context.packageName) ?: return
            if (manual) statusFlow.value = PcUpdateStatus.Checking
            val manifest = when (val result = repository.fetchPcManifest(manifestUrl)) {
                PcManifestResult.Unreachable -> {
                    if (manual) statusFlow.value = PcUpdateStatus.Unreachable
                    return
                }
                PcManifestResult.Invalid -> {
                    if (manual) statusFlow.value = PcUpdateStatus.Error
                    return
                }
                is PcManifestResult.Found -> result.manifest
            }
            val installed = installedLocalOtaVersion(context).first.toInt()
            if (manifest.versionCode <= installed) {
                if (manual) statusFlow.value = PcUpdateStatus.UpToDate
                return
            }
            if (!shouldDownloadPcUpdate(manifest.versionCode, installed, handledVersionCode)) return

            statusFlow.value = PcUpdateStatus.Downloading(manifest.versionName)
            handledVersionCode = manifest.versionCode
            val apk = repository.downloadVerifiedApk(manifest)
            if (apk == null) {
                statusFlow.value = PcUpdateStatus.Error
                return
            }
            val check = validateLocalOtaApkFile(context, apk)
            if (check != null) {
                apk.delete()
                val upToDate = check is LocalOtaFileCheck.Rejected &&
                    check.reason == LocalOtaRejection.AlreadyUpToDate
                statusFlow.value = if (upToDate) PcUpdateStatus.UpToDate else PcUpdateStatus.Rejected
                return
            }
            statusFlow.value = PcUpdateStatus.Ready(apk, manifest.versionName)
            // Android refuses an activity start from the background: offer a notification instead.
            if (manual || AppForegroundTracker.isAppInForeground) installApk(context, apk)
            else notifyReady(apk, manifest.versionName)
        } finally {
            gate.end()
        }
    }

    private fun notifyReady(apk: File, versionName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.pc_update_channel))
                    .build()
            )
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val tap = PendingIntent.getActivity(
                context, 0, createApkInstallIntent(uri),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.pc_update_notification_title))
                .setContentText(context.getString(R.string.pc_update_notification_text, versionName))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 15_000L
        const val CHANNEL_ID = "pc_update"
        const val NOTIFICATION_ID = 4710
    }
}
