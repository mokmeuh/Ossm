package com.ossm.remote.updater

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UpdaterStatus {
    data object Idle : UpdaterStatus
    data object Checking : UpdaterStatus
    data object UpToDate : UpdaterStatus
    data class Available(val manifest: UpdateManifest) : UpdaterStatus
    data object Downloading : UpdaterStatus
    data class ReadyToInstall(val apk: File) : UpdaterStatus
    data class Error(val message: String) : UpdaterStatus
}

@HiltViewModel
class UpdaterViewModel @Inject constructor(
    private val repository: UpdateRepository,
    @ApplicationContext private val context: android.content.Context
) : ViewModel() {
    private val status = MutableStateFlow<UpdaterStatus>(UpdaterStatus.Idle)
    val uiState: StateFlow<UpdaterStatus> = status
    val manualUrl = repository.manualUrl.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.Eagerly,
        ""
    )

    fun checkOnStartup() = viewModelScope.launch {
        if (repository.shouldCheckOnStartup()) check()
    }

    fun checkNow() = viewModelScope.launch { check() }

    fun saveManualUrl(url: String) = viewModelScope.launch {
        repository.saveManualUrl(url)
    }

    fun download(manifest: UpdateManifest) = viewModelScope.launch {
        status.value = UpdaterStatus.Downloading
        val apk = repository.downloadVerifiedApk(manifest)
        status.value = apk?.let(UpdaterStatus::ReadyToInstall)
            ?: UpdaterStatus.Error("Download verification failed")
    }

    private suspend fun check() {
        status.value = UpdaterStatus.Checking
        when (val result = repository.checkForUpdate(installedVersionCode())) {
            UpdateCheckResult.NoUpdate -> status.value = UpdaterStatus.UpToDate
            is UpdateCheckResult.Available -> status.value = UpdaterStatus.Available(result.manifest)
            is UpdateCheckResult.InvalidManifest -> status.value = UpdaterStatus.Error(result.message)
            UpdateCheckResult.ServerUnavailable -> status.value = UpdaterStatus.Idle
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Int {
        val packageInfo: PackageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES
        )
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            packageInfo.versionCode
        }
    }
}
