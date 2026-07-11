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

    /** Mode « à l'écoute » : le micro biaise le mode aléatoire vers le haut. Désactivé par défaut. */
    val listeningModeEnabled: Flow<Boolean> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[LISTENING_MODE_ENABLED] ?: false
    }

    /** Inversion du sens du mode Live (streaming). false = mapping direct (stream:0=home). */
    val liveInvertEnabled: Flow<Boolean> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[LIVE_INVERT_ENABLED] ?: false
    }

    /** Plafond ROUGE du mode à l'écoute : vitesse max que le micro peut atteindre (0..1). */
    val listeningCeiling: Flow<Float> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[LISTENING_CEILING] ?: 0.6f
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

    suspend fun setListeningCeiling(value: Float) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[LISTENING_CEILING] = value.coerceIn(0.01f, 1f)
        }
    }

    companion object {
        private val SPEED_GUARD_ENABLED = booleanPreferencesKey("speed_abrupt_change_guard_enabled")
        private val SPEED_GUARD_THRESHOLD_PERCENT = intPreferencesKey("speed_abrupt_change_guard_threshold_percent")
        private val DEPTH_GUARD_ENABLED = booleanPreferencesKey("depth_abrupt_change_guard_enabled")
        private val DEPTH_GUARD_THRESHOLD_PERCENT = intPreferencesKey("depth_abrupt_change_guard_threshold_percent")
        private val LISTENING_MODE_ENABLED = booleanPreferencesKey("listening_mode_enabled")
        private val LIVE_INVERT_ENABLED = booleanPreferencesKey("live_invert_enabled")
        private val LISTENING_CEILING = floatPreferencesKey("listening_ceiling")
    }
}
