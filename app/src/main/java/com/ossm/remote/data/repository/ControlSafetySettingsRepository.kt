package com.ossm.remote.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.controlSafetyDataStore by preferencesDataStore(name = "control_safety_settings")

data class SliderGuardSettings(
    val enabled: Boolean,
    val thresholdPercent: Int
)

data class ControlSafetySettings(
    val speed: SliderGuardSettings,
    val depth: SliderGuardSettings
)

@Singleton
class ControlSafetySettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val speedGuardEnabled: Flow<Boolean> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[SPEED_GUARD_ENABLED] ?: true
    }

    val speedGuardThresholdPercent: Flow<Int> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[SPEED_GUARD_THRESHOLD_PERCENT] ?: 10
    }

    val depthGuardEnabled: Flow<Boolean> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[DEPTH_GUARD_ENABLED] ?: true
    }

    val depthGuardThresholdPercent: Flow<Int> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[DEPTH_GUARD_THRESHOLD_PERCENT] ?: 5
    }

    val depthLimitMin: Flow<Float> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[DEPTH_LIMIT_MIN] ?: 0f
    }

    val depthLimitMax: Flow<Float> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[DEPTH_LIMIT_MAX] ?: 1f
    }

    /** Mode « à l'écoute » : le micro biaise le mode aléatoire vers le haut. Désactivé par défaut. */
    val listeningModeEnabled: Flow<Boolean> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[LISTENING_MODE_ENABLED] ?: false
    }

    /** Inversion du sens du mode Live (streaming). false = mapping direct (stream:0=home). */
    val liveInvertEnabled: Flow<Boolean> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[LIVE_INVERT_ENABLED] ?: false
    }

    val liveAccelerationPercent: Flow<Int> = context.controlSafetyDataStore.data.map { prefs ->
        normalizeLiveAcceleration(prefs[LIVE_ACCELERATION_PERCENT] ?: DEFAULT_LIVE_ACCELERATION_PERCENT)
    }

    val listeningCeiling: Flow<Float> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[LISTENING_SPEED_MAXIMUM_V2] ?: 1f
    }

    val listeningTargetSpeedEnabled: Flow<Boolean> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[LISTENING_TARGET_SPEED_ENABLED] ?: false
    }

    val listeningTargetDepthEnabled: Flow<Boolean> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[LISTENING_TARGET_DEPTH_ENABLED] ?: false
    }

    val settings: Flow<ControlSafetySettings> = combine(
        speedGuardEnabled,
        speedGuardThresholdPercent,
        depthGuardEnabled,
        depthGuardThresholdPercent
    ) { speedEnabled, speedThreshold, depthEnabled, depthThreshold ->
        ControlSafetySettings(
            speed = SliderGuardSettings(speedEnabled, speedThreshold),
            depth = SliderGuardSettings(depthEnabled, depthThreshold)
        )
    }

    suspend fun setSpeedGuardEnabled(enabled: Boolean) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[SPEED_GUARD_ENABLED] = enabled
        }
    }

    suspend fun setDepthGuardEnabled(enabled: Boolean) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[DEPTH_GUARD_ENABLED] = enabled
        }
    }

    /**
     * Stores an ordered range in [0, 1]. Collapsed inputs are widened to the documented
     * five-percent minimum span; crossed handles are ordered before that rule is applied.
     */
    suspend fun setDepthLimits(min: Float, max: Float) {
        val (normalizedMin, normalizedMax) = normalizeDepthLimits(min, max)
        context.controlSafetyDataStore.edit { prefs ->
            prefs[DEPTH_LIMIT_MIN] = normalizedMin
            prefs[DEPTH_LIMIT_MAX] = normalizedMax
        }
    }

    suspend fun setListeningModeEnabled(enabled: Boolean) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[LISTENING_MODE_ENABLED] = enabled
        }
    }

    suspend fun setLiveInvertEnabled(enabled: Boolean) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[LIVE_INVERT_ENABLED] = enabled
        }
    }

    suspend fun setLiveAccelerationPercent(percent: Int) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[LIVE_ACCELERATION_PERCENT] = normalizeLiveAcceleration(percent)
        }
    }

    suspend fun setListeningCeiling(value: Float) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[LISTENING_SPEED_MAXIMUM_V2] = value.coerceIn(0f, 1f)
        }
    }

    suspend fun setListeningTargetSpeedEnabled(enabled: Boolean) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[LISTENING_TARGET_SPEED_ENABLED] = enabled
        }
    }

    suspend fun setListeningTargetDepthEnabled(enabled: Boolean) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[LISTENING_TARGET_DEPTH_ENABLED] = enabled
        }
    }

    private fun normalizeDepthLimits(min: Float, max: Float): Pair<Float, Float> {
        val low = min.coerceIn(0f, 1f)
        val high = max.coerceIn(0f, 1f)
        val orderedMin = minOf(low, high)
        val orderedMax = maxOf(low, high)
        if (orderedMax - orderedMin >= MIN_DEPTH_LIMIT_SPAN) return orderedMin to orderedMax

        return if (orderedMin + MIN_DEPTH_LIMIT_SPAN <= 1f) {
            orderedMin to orderedMin + MIN_DEPTH_LIMIT_SPAN
        } else {
            1f - MIN_DEPTH_LIMIT_SPAN to 1f
        }
    }

    companion object {
        private val SPEED_GUARD_ENABLED = booleanPreferencesKey("speed_abrupt_change_guard_enabled")
        private val SPEED_GUARD_THRESHOLD_PERCENT = intPreferencesKey("speed_abrupt_change_guard_threshold_percent")
        private val DEPTH_GUARD_ENABLED = booleanPreferencesKey("depth_abrupt_change_guard_enabled")
        private val DEPTH_GUARD_THRESHOLD_PERCENT = intPreferencesKey("depth_abrupt_change_guard_threshold_percent")
        private val DEPTH_LIMIT_MIN = floatPreferencesKey("depth_limit_min")
        private val DEPTH_LIMIT_MAX = floatPreferencesKey("depth_limit_max")
        private val LISTENING_MODE_ENABLED = booleanPreferencesKey("listening_mode_enabled")
        private val LIVE_INVERT_ENABLED = booleanPreferencesKey("live_invert_enabled")
        private val LIVE_ACCELERATION_PERCENT = intPreferencesKey("live_acceleration_percent")
        private val LISTENING_SPEED_MAXIMUM_V2 = floatPreferencesKey("listening_speed_maximum_v2")
        private val LISTENING_TARGET_SPEED_ENABLED = booleanPreferencesKey("listening_target_speed_enabled")
        private val LISTENING_TARGET_DEPTH_ENABLED = booleanPreferencesKey("listening_target_depth_enabled")
        private const val MIN_DEPTH_LIMIT_SPAN = 0.05f
        const val DEFAULT_LIVE_ACCELERATION_PERCENT = 33
        const val MIN_LIVE_ACCELERATION_PERCENT = 10
        const val MAX_LIVE_ACCELERATION_PERCENT = 100

        internal fun normalizeLiveAcceleration(percent: Int): Int =
            percent.coerceIn(MIN_LIVE_ACCELERATION_PERCENT, MAX_LIVE_ACCELERATION_PERCENT)
    }
}
