package com.ossm.remote.updater

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeriodicUpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    fun sync(enabled: Boolean) {
        if (PeriodicUpdatePolicy.shouldRun(enabled)) enqueue() else cancel()
    }

    fun enqueue() {
        val request = PeriodicWorkRequestBuilder<PeriodicUpdateCheckWorker>(
            PeriodicUpdatePolicy.INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(Constraints(NetworkType.CONNECTED))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                PeriodicUpdatePolicy.BACKOFF_MINUTES,
                TimeUnit.MINUTES
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            PeriodicUpdatePolicy.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(PeriodicUpdatePolicy.UNIQUE_WORK_NAME)
    }
}
