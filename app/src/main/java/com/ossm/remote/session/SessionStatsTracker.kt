package com.ossm.remote.session

import com.ossm.remote.data.db.SessionEntity
import com.ossm.remote.data.db.epochDayOf
import com.ossm.remote.data.repository.SessionStatsRepository
import com.ossm.remote.viewmodel.ControlUiState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Accumule une session en cours : segments de temps réellement passés en mouvement
 * (pauses exclues) + échantillons vitesse/sensation. Logique pure, sans Room, pour
 * être testée de façon déterministe.
 */
internal class SessionAccumulator(
    val startTimeMillis: Long
) {
    /** Début du segment courant en mouvement, null si en pause. */
    private var segmentStartMillis: Long? = startTimeMillis
    private var runningMillis: Long = 0L
    private var speedSum: Float = 0f
    private var sensationSum: Float = 0f
    private var maxSpeed: Float = 0f
    private var sampleCount: Int = 0

    /** True tant que la machine est en mouvement (pas en pause). */
    val isMoving: Boolean get() = segmentStartMillis != null

    /** Clôt le segment en cours (pause ou fin). Sans effet si déjà en pause. */
    fun pause(nowMillis: Long) {
        val start = segmentStartMillis ?: return
        runningMillis += (nowMillis - start).coerceAtLeast(0L)
        segmentStartMillis = null
    }

    /** Reprend le comptage (résumé). Sans effet si déjà en mouvement. */
    fun resume(nowMillis: Long) {
        if (segmentStartMillis != null) return
        segmentStartMillis = nowMillis
    }

    /** Ajoute un échantillon vitesse/sensation prélevé pendant le mouvement. */
    fun addSample(speed: Float, sensation: Float) {
        speedSum += speed
        sensationSum += sensation
        maxSpeed = maxOf(maxSpeed, speed)
        sampleCount += 1
    }

    /** Durée totale en mouvement, pause exclue (inclut le segment courant s'il est actif). */
    fun durationMillis(nowMillis: Long): Long {
        val start = segmentStartMillis
        val activeElapsed = start?.let { (nowMillis - it).coerceAtLeast(0L) } ?: 0L
        return runningMillis + activeElapsed
    }

    fun avgSpeedPercent(): Float = if (sampleCount == 0) 0f else speedSum / sampleCount * 100f

    fun maxSpeedPercent(): Float = maxSpeed * 100f

    fun avgSensationPercent(): Float = if (sampleCount == 0) 0f else sensationSum / sampleCount * 100f
}

/**
 * Enregistre les sessions de contrôle dans la base de statistiques en observant
 * uniquement [ControlSessionStore.uiState] (lecture seule : aucune mutation du store).
 *
 * Détecte les transitions isRunning false→true (début) / true→false (fin). Une pause
 * (isPaused=true) ne clôt PAS la session : la durée comptée est la somme des périodes
 * réellement en mouvement. La collecte démarre automatiquement à la création (éager,
 * sans UI) et échantillonne vitesse/sensation à ~1 Hz pendant le mouvement.
 */
@Singleton
class SessionStatsTracker @Inject constructor(
    private val controlSessionStore: ControlSessionStore,
    private val statsRepository: SessionStatsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var accumulator: SessionAccumulator? = null
    private var patternKey: String = ""
    private var patternName: String = ""
    private var sampleJob: Job? = null
    private var lastRunning: Boolean = false

    init {
        scope.launch {
            controlSessionStore.uiState.collect { state -> handleState(state) }
        }
    }

    private fun handleState(state: ControlUiState) {
        val now = System.currentTimeMillis()
        val running = state.isRunning
        when {
            !lastRunning && running -> onSessionStart(state, now)
            lastRunning && !running -> onSessionEndOrPause(state, now)
        }
        lastRunning = running
    }

    private fun onSessionStart(state: ControlUiState, now: Long) {
        val active = accumulator
        if (active == null) {
            accumulator = SessionAccumulator(startTimeMillis = now)
            // Pattern capturé au début : un changement de pattern coupe isRunning
            // (activatePattern), donc chaque session porte le pattern utilisé.
            patternKey = state.activePatternKey
            patternName = state.activePattern?.name ?: state.activePatternKey
            startSampling()
        } else {
            active.resume(now)
        }
    }

    private fun onSessionEndOrPause(state: ControlUiState, now: Long) {
        val active = accumulator ?: return
        active.pause(now)
        if (state.isPaused) return // pause : la session reste ouverte
        finalizeSession(active, state)
        accumulator = null
    }

    private fun finalizeSession(active: SessionAccumulator, state: ControlUiState) {
        sampleJob?.cancel()
        sampleJob = null
        val endTime = System.currentTimeMillis()
        val entity = SessionEntity(
            startTimeMillis = active.startTimeMillis,
            endTimeMillis = endTime,
            durationMillis = active.durationMillis(endTime),
            patternKey = patternKey,
            patternName = patternName,
            avgSpeedPercent = active.avgSpeedPercent(),
            maxSpeedPercent = active.maxSpeedPercent(),
            depthMin = state.depthMin,
            depthMax = state.depthMax,
            avgSensationPercent = active.avgSensationPercent(),
            complete = true,
            dateEpochDay = epochDayOf(active.startTimeMillis)
        )
        scope.launch { statsRepository.recordSession(entity) }
    }

    /** Échantillonne vitesse/sensation à ~1 Hz pendant que la session bouge. */
    private fun startSampling() {
        if (sampleJob?.isActive == true) return
        sampleJob = scope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                val state = controlSessionStore.uiState.value
                val active = accumulator ?: break
                if (!state.isRunning) continue // pause : pas d'échantillon
                active.addSample(state.speed, state.sensation)
            }
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
    }
}
