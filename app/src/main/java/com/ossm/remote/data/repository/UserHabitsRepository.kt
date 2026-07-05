package com.ossm.remote.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.userHabitsDataStore by preferencesDataStore(name = "user_habits")

data class SessionDefaults(
    val patternKey: String?,
    val depthMin: Float?,
    val depthMax: Float?
)

/**
 * Learns the user's typical "first action of the day": which pattern they tend to pick first,
 * and what depth range they tend to start with. Each is captured at most once per calendar day,
 * from whichever manual action happens first that day, then averaged/majority-voted over the
 * last few days to produce a default for the NEXT session.
 */
@Singleton
class UserHabitsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val sessionDefaults: Flow<SessionDefaults> = context.userHabitsDataStore.data.map { prefs ->
        val patternHistory = parseHistory(prefs[PATTERN_HISTORY])
        val depthHistory = parseDepthHistory(prefs[DEPTH_HISTORY])

        val patternDefault = if (patternHistory.size >= MIN_SAMPLES) {
            patternHistory.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        } else null

        val depthDefault = if (depthHistory.size >= MIN_SAMPLES) {
            depthHistory.map { it.first }.average().toFloat() to depthHistory.map { it.second }.average().toFloat()
        } else null

        SessionDefaults(
            patternKey = patternDefault,
            depthMin = depthDefault?.first,
            depthMax = depthDefault?.second
        )
    }

    /** Ordre personnalisé des patterns dans la grille (liste de keys). */
    val patternOrder: Flow<List<String>> = context.userHabitsDataStore.data.map { prefs ->
        prefs[PATTERN_ORDER]?.split(";")?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun savePatternOrder(keys: List<String>) {
        context.userHabitsDataStore.edit { prefs ->
            prefs[PATTERN_ORDER] = keys.joinToString(";")
        }
    }

    suspend fun recordPatternHabit(patternKey: String) {
        val today = LocalDate.now().toEpochDay()
        context.userHabitsDataStore.edit { prefs ->
            if (prefs[PATTERN_LAST_DAY] == today) return@edit
            prefs[PATTERN_LAST_DAY] = today
            val history = parseHistory(prefs[PATTERN_HISTORY]).toMutableList()
            history.add(patternKey)
            while (history.size > MAX_HISTORY) history.removeAt(0)
            prefs[PATTERN_HISTORY] = history.joinToString(";")
        }
    }

    suspend fun recordDepthHabit(depthMin: Float, depthMax: Float) {
        val today = LocalDate.now().toEpochDay()
        context.userHabitsDataStore.edit { prefs ->
            if (prefs[DEPTH_LAST_DAY] == today) return@edit
            prefs[DEPTH_LAST_DAY] = today
            val history = parseDepthHistory(prefs[DEPTH_HISTORY]).toMutableList()
            history.add(depthMin to depthMax)
            while (history.size > MAX_HISTORY) history.removeAt(0)
            prefs[DEPTH_HISTORY] = history.joinToString(";") { "${it.first}:${it.second}" }
        }
    }

    private fun parseHistory(raw: String?): List<String> =
        raw?.split(";")?.filter { it.isNotBlank() } ?: emptyList()

    private fun parseDepthHistory(raw: String?): List<Pair<Float, Float>> =
        raw?.split(";")?.mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) return@mapNotNull null
            val min = parts[0].toFloatOrNull() ?: return@mapNotNull null
            val max = parts[1].toFloatOrNull() ?: return@mapNotNull null
            min to max
        } ?: emptyList()

    companion object {
        private val PATTERN_HISTORY = stringPreferencesKey("pattern_habit_history")
        private val PATTERN_ORDER = stringPreferencesKey("pattern_order")
        private val PATTERN_LAST_DAY = longPreferencesKey("pattern_habit_last_day")
        private val DEPTH_HISTORY = stringPreferencesKey("depth_habit_history")
        private val DEPTH_LAST_DAY = longPreferencesKey("depth_habit_last_day")
        private const val MAX_HISTORY = 8
        private const val MIN_SAMPLES = 3
    }
}
