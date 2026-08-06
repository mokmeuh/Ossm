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
    }
}
