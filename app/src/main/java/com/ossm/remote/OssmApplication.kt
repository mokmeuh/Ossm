package com.ossm.remote

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ossm.remote.session.SessionStatsTracker
import com.ossm.remote.updater.PeriodicUpdateScheduler
import com.ossm.remote.updater.UpdateRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class OssmApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var updateRepository: UpdateRepository
    @Inject lateinit var periodicUpdateScheduler: PeriodicUpdateScheduler
    // Éager : la simple injection crée le tracker, qui démarre sa collecte (init).
    @Inject lateinit var sessionStatsTracker: SessionStatsTracker

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Synchronise la vérification périodique au démarrage : enqueue si activée, cancel sinon.
        applicationScope.launch {
            periodicUpdateScheduler.sync(updateRepository.periodicCheckEnabled.first())
        }
        registerActivityLifecycleCallbacks(AppForegroundTracker)
    }
}

object AppForegroundTracker : Application.ActivityLifecycleCallbacks {
    @Volatile private var startedCount = 0
    @Volatile private var resumedCount = 0

    private val _isAppInForeground = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isAppInForegroundFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _isAppInForeground
    val isAppInForeground: Boolean get() = _isAppInForeground.value
    val isAppStarted: Boolean get() = startedCount > 0

    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
    override fun onActivityStarted(activity: android.app.Activity) { startedCount++ }
    override fun onActivityResumed(activity: android.app.Activity) {
        resumedCount++
        _isAppInForeground.value = true
    }
    override fun onActivityPaused(activity: android.app.Activity) {
        resumedCount = (resumedCount - 1).coerceAtLeast(0)
        _isAppInForeground.value = resumedCount > 0
    }
    override fun onActivityStopped(activity: android.app.Activity) { startedCount = (startedCount - 1).coerceAtLeast(0) }
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
    override fun onActivityDestroyed(activity: android.app.Activity) {}
}
