package com.ossm.remote.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ossm.remote.adaptive.AdaptivePace
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.adaptiveDataStore by preferencesDataStore(name = "adaptive_mode")

/**
 * Compteurs cumulés d'apprentissage du mode adaptatif (persistés). La progression et
 * la confiance sont DERIVÉES de ces compteurs par des formules pures (AdaptiveEngine) :
 * ceci ne stocke que la matière première, une source de vérité unique.
 */
data class AdaptiveLearning(
    val sessionsCompleted: Long = 0,
    val totalActiveMillis: Long = 0,
    val distinctDaysUsed: Int = 0,
    val manualAdjustments: Long = 0,
    val patternUses: Map<String, Long> = emptyMap(),
    val intensitySum: Float = 0f,
    val intensitySamples: Long = 0,
    val lastUsedDay: Long? = null
) {
    val averageIntensity: Float
        get() = if (intensitySamples <= 0) 0f else intensitySum / intensitySamples

    val topPatternKey: String?
        get() = patternUses.maxByOrNull { it.value }?.key
}

/** Réglages utilisateur du mode adaptatif (persistés). */
data class AdaptiveSettings(
    val enabled: Boolean = false,
    val pace: AdaptivePace = AdaptivePace.MODERE,
    val diversity: Float = 0.5f
)

/**
 * Persistance locale du mode adaptatif, calquée sur les autres repos DataStore
 * (extension preferencesDataStore locale au fichier, aucune modif d'AppModule).
 */
@Singleton
class AdaptiveSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val learning: Flow<AdaptiveLearning> = context.adaptiveDataStore.data.map { prefs ->
        AdaptiveLearning(
            sessionsCompleted = prefs[SESSIONS_COMPLETED] ?: 0,
            totalActiveMillis = prefs[TOTAL_ACTIVE_MILLIS] ?: 0L,
            distinctDaysUsed = prefs[DISTINCT_DAYS]?.toInt() ?: 0,
            manualAdjustments = prefs[MANUAL_ADJUSTMENTS] ?: 0L,
            patternUses = parsePatternUses(prefs[PATTERN_USES]),
            intensitySum = prefs[INTENSITY_SUM] ?: 0f,
            intensitySamples = prefs[INTENSITY_SAMPLES] ?: 0L,
            lastUsedDay = prefs[LAST_USED_DAY]
        )
    }

    val unlocked: Flow<Boolean> = context.adaptiveDataStore.data.map { prefs ->
        prefs[UNLOCKED] ?: false
    }

    val settings: Flow<AdaptiveSettings> = context.adaptiveDataStore.data.map { prefs ->
        AdaptiveSettings(
            enabled = prefs[ENABLED] ?: false,
            pace = prefs[PACE]?.let { raw -> AdaptivePace.entries.firstOrNull { it.name == raw } }
                ?: AdaptivePace.MODERE,
            diversity = prefs[DIVERSITY] ?: 0.5f
        )
    }

    suspend fun saveLearning(learning: AdaptiveLearning) {
        context.adaptiveDataStore.edit { prefs ->
            prefs[SESSIONS_COMPLETED] = learning.sessionsCompleted
            prefs[TOTAL_ACTIVE_MILLIS] = learning.totalActiveMillis
            prefs[DISTINCT_DAYS] = learning.distinctDaysUsed.toLong()
            prefs[MANUAL_ADJUSTMENTS] = learning.manualAdjustments
            prefs[PATTERN_USES] = encodePatternUses(learning.patternUses)
            prefs[INTENSITY_SUM] = learning.intensitySum
            prefs[INTENSITY_SAMPLES] = learning.intensitySamples
            if (learning.lastUsedDay != null) prefs[LAST_USED_DAY] = learning.lastUsedDay
        }
    }

    suspend fun setUnlocked(unlocked: Boolean) {
        context.adaptiveDataStore.edit { prefs -> prefs[UNLOCKED] = unlocked }
    }

    suspend fun saveSettings(settings: AdaptiveSettings) {
        context.adaptiveDataStore.edit { prefs ->
            prefs[ENABLED] = settings.enabled
            prefs[PACE] = settings.pace.name
            prefs[DIVERSITY] = settings.diversity.coerceIn(0f, 1f)
        }
    }

    private fun parsePatternUses(raw: String?): Map<String, Long> =
        raw?.split(";")?.mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) return@mapNotNull null
            val count = parts[1].toLongOrNull() ?: return@mapNotNull null
            parts[0] to count
        }?.toMap() ?: emptyMap()

    private fun encodePatternUses(uses: Map<String, Long>): String =
        uses.entries.joinToString(";") { "${it.key}:${it.value}" }

    companion object {
        private val SESSIONS_COMPLETED = longPreferencesKey("adaptive_sessions_completed")
        private val TOTAL_ACTIVE_MILLIS = longPreferencesKey("adaptive_total_active_millis")
        private val DISTINCT_DAYS = longPreferencesKey("adaptive_distinct_days")
        private val MANUAL_ADJUSTMENTS = longPreferencesKey("adaptive_manual_adjustments")
        private val PATTERN_USES = stringPreferencesKey("adaptive_pattern_uses")
        private val INTENSITY_SUM = floatPreferencesKey("adaptive_intensity_sum")
        private val INTENSITY_SAMPLES = longPreferencesKey("adaptive_intensity_samples")
        private val LAST_USED_DAY = longPreferencesKey("adaptive_last_used_day")
        private val UNLOCKED = booleanPreferencesKey("adaptive_unlocked")
        private val ENABLED = booleanPreferencesKey("adaptive_enabled")
        private val PACE = stringPreferencesKey("adaptive_pace")
        private val DIVERSITY = floatPreferencesKey("adaptive_diversity")
    }
}
