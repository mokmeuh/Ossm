package com.ossm.remote.adaptive

import com.ossm.remote.data.repository.AdaptiveLearning
import com.ossm.remote.data.repository.AdaptiveSettings
import com.ossm.remote.data.repository.AdaptiveSettingsRepository
import com.ossm.remote.model.KnownStrokeEnginePatterns
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.PatternControlMode
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Niveau de capacités du firmware détecté à la connexion. Le firmware actuel (7 patterns) est STANDARD. */
enum class AdaptiveFirmwareLevel { STANDARD, ADVANCED }

/** Rythme des changements du mode adaptatif. */
enum class AdaptivePace { CALME, MODERE, DYNAMIQUE }

/** Résumé de l'apprentissage exposé à l'UI (Phase 2). */
data class AdaptiveLearningSummary(
    val sessionsCompleted: Long = 0,
    val totalActiveMillis: Long = 0,
    val distinctDays: Int = 0,
    val topPatternKey: String? = null
)

/** État exposé pour l'UI : l'écran dessine cadenas / barre / carte de réglages à partir de ça. */
data class AdaptiveUiState(
    val locked: Boolean = true,
    val progressionPercent: Int = 0,
    val confidencePercent: Int = 0,
    val activeAdaptivePatternKey: String? = null,
    val firmwareLevel: AdaptiveFirmwareLevel = AdaptiveFirmwareLevel.STANDARD,
    val learningSummary: AdaptiveLearningSummary = AdaptiveLearningSummary(),
    val enabled: Boolean = false,
    val pace: AdaptivePace = AdaptivePace.MODERE,
    val diversity: Float = 0.5f,
    val running: Boolean = false
)

/** Cible décidée par le moteur : un des 7 patterns firmware + vitesse/profondeur dans les bornes utilisateur. */
data class AdaptiveDecision(
    val pattern: OssmPattern,
    val speed: Float,
    val depthMin: Float,
    val depthMax: Float
)

/**
 * Moteur décisionnel du mode adaptatif : choisit parmi les 7 patterns firmware connus,
 * apprend les préférences locales (DataStore) et expose l'état pour l'UI. Aucun BLE ici :
 * la logique est testable sans matériel. L'exécution firmware vit dans ControlSessionStore,
 * qui interroge ce moteur à chaque itération.
 */
@Singleton
class AdaptiveEngine @Inject constructor(
    private val settingsRepository: AdaptiveSettingsRepository
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _learning = MutableStateFlow(AdaptiveLearning())
    private val _settings = MutableStateFlow(AdaptiveSettings())
    private val _unlocked = MutableStateFlow(false)
    private val _firmwareLevel = MutableStateFlow(AdaptiveFirmwareLevel.STANDARD)
    private val _activePatternKey = MutableStateFlow<String?>(null)
    private val _running = MutableStateFlow(false)

    val uiState: StateFlow<AdaptiveUiState> = combine(
        _learning,
        combine(_settings, _unlocked) { settings, unlocked -> settings to unlocked },
        _firmwareLevel,
        _activePatternKey,
        _running
    ) { learning, settingsAndUnlocked, firmwareLevel, activeKey, running ->
        val (settings, unlocked) = settingsAndUnlocked
        val progression = computeProgressionPercent(
            learning.sessionsCompleted,
            learning.totalActiveMillis,
            learning.distinctDaysUsed,
            learning.manualAdjustments
        )
        AdaptiveUiState(
            locked = !isUnlocked(progression, unlocked),
            progressionPercent = progression,
            confidencePercent = computeConfidencePercent(
                learning.sessionsCompleted,
                learning.distinctDaysUsed,
                learning.patternUses
            ),
            activeAdaptivePatternKey = activeKey,
            firmwareLevel = firmwareLevel,
            learningSummary = AdaptiveLearningSummary(
                sessionsCompleted = learning.sessionsCompleted,
                totalActiveMillis = learning.totalActiveMillis,
                distinctDays = learning.distinctDaysUsed,
                topPatternKey = learning.topPatternKey
            ),
            enabled = settings.enabled,
            pace = settings.pace,
            diversity = settings.diversity,
            running = running
        )
    }.stateIn(engineScope, SharingStarted.Eagerly, AdaptiveUiState())

    init {
        engineScope.launch { settingsRepository.learning.collect { _learning.value = it } }
        engineScope.launch { settingsRepository.unlocked.collect { _unlocked.value = it } }
        engineScope.launch { settingsRepository.settings.collect { _settings.value = it } }
    }

    val currentLearning: AdaptiveLearning get() = _learning.value
    val currentSettings: AdaptiveSettings get() = _settings.value

    // ---- Détection firmware / état exposé ----

    fun setFirmwareLevel(level: AdaptiveFirmwareLevel) {
        _firmwareLevel.value = level
    }

    fun setActivePattern(key: String?) {
        _activePatternKey.value = key
    }

    fun setRunning(running: Boolean) {
        _running.value = running
    }

    // ---- Réglages (persistés) ----

    fun setEnabled(enabled: Boolean) {
        _settings.update { it.copy(enabled = enabled) }
        persistSettings()
    }

    fun setPace(pace: AdaptivePace) {
        _settings.update { it.copy(pace = pace) }
        persistSettings()
    }

    fun setDiversity(diversity: Float) {
        _settings.update { it.copy(diversity = diversity.coerceIn(0f, 1f)) }
        persistSettings()
    }

    private fun persistSettings() {
        engineScope.launch { settingsRepository.saveSettings(_settings.value) }
    }

    // ---- Apprentissage (compteurs cumulés pendant les sessions ADAPTIVE) ----

    fun onSessionStarted() {
        val today = LocalDate.now().toEpochDay()
        _learning.update { current ->
            val firstUseOfDay = current.lastUsedDay != today
            current.copy(
                distinctDaysUsed = current.distinctDaysUsed + if (firstUseOfDay) 1 else 0,
                lastUsedDay = today
            )
        }
        persistLearning()
    }

    fun onSessionStopped(activeMillis: Long) {
        _learning.update {
            it.copy(
                sessionsCompleted = it.sessionsCompleted + 1,
                totalActiveMillis = it.totalActiveMillis + activeMillis.coerceAtLeast(0L)
            )
        }
        checkUnlockAndPersist()
    }

    fun recordPatternUsed(patternKey: String) {
        _learning.update { current ->
            val uses = current.patternUses.toMutableMap()
            uses[patternKey] = (uses[patternKey] ?: 0L) + 1
            current.copy(patternUses = uses)
        }
        persistLearning()
    }

    fun recordIntensitySample(intensity: Float) {
        _learning.update {
            it.copy(
                intensitySum = it.intensitySum + intensity.coerceIn(0f, 1f),
                intensitySamples = it.intensitySamples + 1
            )
        }
        persistLearning()
    }

    fun recordManualAdjustment() {
        _learning.update { it.copy(manualAdjustments = it.manualAdjustments + 1) }
        checkUnlockAndPersist()
    }

    private fun persistLearning() {
        checkUnlockAndPersist()
        engineScope.launch { settingsRepository.saveLearning(_learning.value) }
    }

    private fun checkUnlockAndPersist() {
        val learning = _learning.value
        val progression = computeProgressionPercent(
            learning.sessionsCompleted,
            learning.totalActiveMillis,
            learning.distinctDaysUsed,
            learning.manualAdjustments
        )
        if (progression >= PROGRESSION_CAP && !_unlocked.value) {
            _unlocked.value = true
            engineScope.launch { settingsRepository.setUnlocked(true) }
        }
    }

    // ---- Décision (formules pures et testables) ----

    companion object {
        // Progression : formule déterministe testée. Cible : déblocage ~4-6 sessions.
        private const val PROGRESSION_SESSION_POINTS = 15L
        private const val PROGRESSION_HOUR_POINTS = 10L
        private const val PROGRESSION_DAY_POINTS = 10L
        private const val PROGRESSION_ADJUSTMENT_POINTS = 5L
        private const val PROGRESSION_ADJUSTMENT_CAP = 20L
        const val PROGRESSION_CAP = 100

        private const val CONFIDENCE_SESSION_PLATEAU = 15L
        private const val CONFIDENCE_DAY_PLATEAU = 10
        private const val CONFIDENCE_USE_PREFERENCE_THRESHOLD = 60

        private const val INTENSITY_LOW = 0.4f
        private const val INTENSITY_HIGH = 0.65f
        private const val INTENSE_BONUS = 1.6f
        private const val GENTLE_BONUS = 1.6f
        private const val INTENSE_PENALTY = 0.5f
        private const val GENTLE_PENALTY = 0.5f
        private const val PREFERENCE_MAX_MULTIPLIER = 2.5f
        private const val BASE_WEIGHT = 1f

        private const val DECISION_SPEED_FLOOR_MIN = 0.15f
        private const val DECISION_SPEED_FLOOR_MAX = 0.55f
        private const val DECISION_STROKE_MIN = 0.25f
        private const val DECISION_STROKE_MAX = 0.65f

        private val INTENSE_PATTERN_KEYS = setOf("deeper", "insist")
        private val GENTLE_PATTERN_KEYS = setOf("stopNGo", "teasingPounding")

        /** Formule déterministe de progression (0-100) à partir des compteurs d'apprentissage. */
        fun computeProgressionPercent(
            sessionsCompleted: Long,
            totalActiveMillis: Long,
            distinctDays: Int,
            manualAdjustments: Long
        ): Int {
            val total = (sessionsCompleted * PROGRESSION_SESSION_POINTS) +
                (totalActiveMillis / 3_600_000L) * PROGRESSION_HOUR_POINTS +
                distinctDays * PROGRESSION_DAY_POINTS +
                (manualAdjustments * PROGRESSION_ADJUSTMENT_POINTS).coerceAtMost(PROGRESSION_ADJUSTMENT_CAP)
            return total.coerceIn(0L, PROGRESSION_CAP.toLong()).toInt()
        }

        /** Déblocage persistant : ne re-verrouille jamais une fois 100 atteint. */
        fun isUnlocked(progressionPercent: Int, persistedUnlocked: Boolean): Boolean =
            persistedUnlocked || progressionPercent >= PROGRESSION_CAP

        /**
         * Confiance 0-100 : robustesse de l'apprentissage (sessions, jours distincts,
         * cohérence de la préférence). Un seul pattern dominant = confiance haute.
         */
        fun computeConfidencePercent(
            sessionsCompleted: Long,
            distinctDays: Int,
            patternUses: Map<String, Long>
        ): Int {
            val sessionScore = (sessionsCompleted.coerceAtMost(CONFIDENCE_SESSION_PLATEAU) * 100 / CONFIDENCE_SESSION_PLATEAU).toInt()
            val dayScore = (distinctDays.coerceAtMost(CONFIDENCE_DAY_PLATEAU) * 100 / CONFIDENCE_DAY_PLATEAU)
            val preferenceScore = preferenceConsistencyPercent(patternUses)
            val score = sessionScore * 0.6f + dayScore * 0.25f + preferenceScore * 0.15f
            return score.roundToInt().coerceIn(0, 100)
        }

        /** Part de dominance du pattern le plus utilisé, normalisée 0 (répartition égale) à 100 (un seul). */
        fun preferenceConsistencyPercent(patternUses: Map<String, Long>): Int {
            val total = patternUses.values.sum()
            if (total <= 0) return 0
            val top = patternUses.values.max()
            val n = patternUses.size
            if (n <= 1) return 100
            val share = top.toFloat() / total
            val normalized = (share - 1f / n) / (1f - 1f / n)
            return (normalized * 100).roundToInt().coerceIn(0, 100)
        }

        /** Poids par pattern : biais vers l'intensité et vers la préférence apprise. */
        fun weightPatterns(
            candidates: List<OssmPattern>,
            intensity: Float,
            favoriteKey: String?,
            usePreference: Boolean,
            diversity: Float
        ): List<Pair<OssmPattern, Float>> {
            return candidates.map { pattern ->
                var weight = BASE_WEIGHT
                if (intensity >= INTENSITY_HIGH) {
                    if (pattern.key in INTENSE_PATTERN_KEYS) weight *= INTENSE_BONUS
                    if (pattern.key in GENTLE_PATTERN_KEYS) weight *= GENTLE_PENALTY
                } else if (intensity <= INTENSITY_LOW) {
                    if (pattern.key in GENTLE_PATTERN_KEYS) weight *= GENTLE_BONUS
                    if (pattern.key in INTENSE_PATTERN_KEYS) weight *= INTENSE_PENALTY
                }
                if (usePreference && pattern.key == favoriteKey) {
                    weight *= interpolate(PREFERENCE_MAX_MULTIPLIER, 1f, diversity.coerceIn(0f, 1f))
                }
                pattern to weight
            }
        }

        /**
         * Choisit le prochain pattern parmi les 7 firmware, en évitant la répétition immédiate
         * et en biaisant vers la préférence apprise quand la confiance est suffisante.
         */
        fun nextPattern(
            lastPatternKey: String?,
            intensity: Float,
            learning: AdaptiveLearning,
            settings: AdaptiveSettings,
            random: Random = Random.Default
        ): OssmPattern {
            val withoutLast = KnownStrokeEnginePatterns.filter { it.key != lastPatternKey }
            val candidates = if (withoutLast.isNotEmpty()) withoutLast else KnownStrokeEnginePatterns
            val confidence = computeConfidencePercent(learning.sessionsCompleted, learning.distinctDaysUsed, learning.patternUses)
            val favorite = learning.topPatternKey
            val usePreference = favorite != null && confidence >= CONFIDENCE_USE_PREFERENCE_THRESHOLD
            val weights = weightPatterns(candidates, intensity, favorite, usePreference, settings.diversity)
            return weightedPick(weights, random)
        }

        /** Cible complète (pattern + vitesse/profondeur bornées) pour une itération du moteur. */
        fun decide(
            lastPatternKey: String?,
            intensity: Float,
            speedCeiling: Float,
            depthMin: Float,
            depthMax: Float,
            learning: AdaptiveLearning,
            settings: AdaptiveSettings,
            random: Random = Random.Default
        ): AdaptiveDecision {
            val pattern = nextPattern(lastPatternKey, intensity, learning, settings, random)
            val ceiling = speedCeiling.coerceIn(0.05f, 1f)
            val floor = interpolate(DECISION_SPEED_FLOOR_MIN, DECISION_SPEED_FLOOR_MAX, intensity).coerceAtMost(ceiling)
            val speed = (floor + (ceiling - floor) * random.nextFloat()).coerceIn(0.05f, ceiling)

            val lo = depthMin.coerceIn(0f, 1f)
            val hi = depthMax.coerceIn(0f, 1f)
            val span = (hi - lo).coerceIn(0.05f, 1f)
            val minStroke = span * interpolate(DECISION_STROKE_MIN, DECISION_STROKE_MAX, intensity)
            val stroke = (minStroke + (span - minStroke) * random.nextFloat()).coerceIn(0.05f, span)
            val maxStart = hi - stroke
            val dMin = (lo + (maxStart - lo) * random.nextFloat()).coerceIn(lo, maxStart.coerceAtLeast(lo))
            val dMax = (dMin + stroke).coerceIn(dMin, hi)
            return AdaptiveDecision(pattern, speed, dMin, dMax)
        }

        /** Tirage pondéré déterministe pour un seed donné (testable). */
        fun weightedPick(weighted: List<Pair<OssmPattern, Float>>, random: Random): OssmPattern {
            val total = weighted.sumOf { it.second.coerceAtLeast(0f).toDouble() }
            if (total <= 0.0) return weighted.first().first
            var roll = random.nextDouble() * total
            for ((pattern, weight) in weighted) {
                roll -= weight.coerceAtLeast(0f).toDouble()
                if (roll <= 0.0) return pattern
            }
            return weighted.last().first
        }

        /**
         * Plan de rampe progressive : valeurs intermédiaires entre [from] et [to], chaque pas
         * borné par [maxStep] (respecte la garde anti-saut : jamais de variation brutale).
         */
        fun rampValues(from: Float, to: Float, maxStep: Float): List<Float> {
            val step = maxStep.coerceAtLeast(0.01f)
            val values = mutableListOf<Float>()
            var current = from
            while (abs(to - current) > 1e-6f) {
                current += (to - current).coerceIn(-step, step)
                values.add(current)
            }
            return values
        }

        /** Détection firmware : plus de patterns stroke-engine que les 7 connus → ADVANCED. */
        fun detectFirmwareLevel(patterns: List<OssmPattern>): AdaptiveFirmwareLevel {
            val knownIds = KnownStrokeEnginePatterns.mapNotNull { it.id }.toSet()
            val hasExtraCapability = patterns.any {
                it.mode == PatternControlMode.STROKE_ENGINE && it.id != null && it.id !in knownIds
            }
            return if (hasExtraCapability) AdaptiveFirmwareLevel.ADVANCED else AdaptiveFirmwareLevel.STANDARD
        }

        /** Durée de maintien entre deux décisions selon le rythme réglé. */
        fun holdRangeMillis(pace: AdaptivePace): Pair<Long, Long> = when (pace) {
            AdaptivePace.CALME -> 12_000L to 30_000L
            AdaptivePace.MODERE -> 6_000L to 18_000L
            AdaptivePace.DYNAMIQUE -> 3_000L to 10_000L
        }

        private fun interpolate(min: Float, max: Float, amount: Float): Float =
            min + (max - min) * amount.coerceIn(0f, 1f)
    }
}
