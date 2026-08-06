package com.ossm.remote.updater

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ossm.remote.BuildConfig
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class PeriodicUpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: UpdateRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // La bascule a pu être coupée pendant que le travail était planifié.
        if (!PeriodicUpdatePolicy.shouldRun(repository.periodicCheckEnabled.first())) {
            return Result.success()
        }
        return when (val result = repository.checkForUpdate(BuildConfig.VERSION_CODE)) {
            is UpdateCheckResult.Available -> {
                UpdateNotification.notifyUpdateAvailable(applicationContext, result.manifest)
                Result.success()
            }
            else -> Result.success()
        }
    }
}
