package com.ossm.remote.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

    /** Inversion du sens du mode Live (streaming). false = mapping inverse (100 - raw). */
    val liveInvertEnabled: Flow<Boolean> = context.controlSafetyDataStore.data
        .map { prefs ->
            prefs[LIVE_INVERT_ENABLED] ?: false
        }

    val precisionOverrides: Flow<Map<String, Boolean>> = context.controlSafetyDataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (key.name.startsWith(PRECISION_ENABLED_PREFIX) && value is Boolean) {
                key.name.removePrefix(PRECISION_ENABLED_PREFIX) to value
            } else {
                null
            }
        }.toMap()
    }

    val liveAccelerationPercent: Flow<Int> = context.controlSafetyDataStore.data.map { prefs ->
        normalizeLiveAcceleration(prefs[LIVE_ACCELERATION_PERCENT] ?: DEFAULT_LIVE_ACCELERATION_PERCENT)
    }

    /** Naturel optional AI planner: disabled by default, no built-in address. */
    val naturalAiEnabled: Flow<Boolean> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[NATURAL_AI_ENABLED] ?: false
    }

    val naturalAiAddress: Flow<String> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[NATURAL_AI_ADDRESS] ?: ""
    }

    /** "Passer par le relais de raisonnement (PC)": port 11435 + think:true. Off by default. */
    val naturalAiReasoningRelay: Flow<Boolean> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[NATURAL_AI_REASONING_RELAY] ?: false
    }

    /** Voix depth range (2.3.28), fractions of the full stroke; intersected with the limits. */
    val voiceDepthMin: Flow<Float> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[VOICE_DEPTH_MIN] ?: 0f
    }

    val voiceDepthMax: Flow<Float> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[VOICE_DEPTH_MAX] ?: 1f
    }

    /** 2.3.29 "STOP rapide par mot court" (Voix): ON by default (safety). */
    val voiceShortWordStop: Flow<Boolean> = context.controlSafetyDataStore.data.map { prefs ->
        prefs[VOICE_SHORT_WORD_STOP] ?: true
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

    suspend fun setVoiceDepthRange(min: Float, max: Float) {
        val (low, high) = normalizeVoiceDepthRange(min, max)
        context.controlSafetyDataStore.edit { prefs ->
            prefs[VOICE_DEPTH_MIN] = low
            prefs[VOICE_DEPTH_MAX] = high
        }
    }

    suspend fun setVoiceShortWordStop(enabled: Boolean) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[VOICE_SHORT_WORD_STOP] = enabled
        }
    }

    suspend fun setLiveInvertEnabled(enabled: Boolean) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[LIVE_INVERT_ENABLED] = enabled
        }
    }

    suspend fun setPrecisionEnabled(patternKey: String, enabled: Boolean) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[booleanPreferencesKey(PRECISION_ENABLED_PREFIX + patternKey)] = enabled
        }
    }

    suspend fun setLiveAccelerationPercent(percent: Int) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[LIVE_ACCELERATION_PERCENT] = normalizeLiveAcceleration(percent)
        }
    }

    suspend fun setNaturalAiEnabled(enabled: Boolean) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[NATURAL_AI_ENABLED] = enabled
        }
    }

    suspend fun setNaturalAiReasoningRelay(enabled: Boolean) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[NATURAL_AI_REASONING_RELAY] = enabled
        }
    }

    suspend fun setNaturalAiAddress(address: String) {
        context.controlSafetyDataStore.edit { prefs ->
            prefs[NATURAL_AI_ADDRESS] = address.trim().take(200)
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
        private val LIVE_INVERT_ENABLED = booleanPreferencesKey("live_invert_enabled")
        private const val PRECISION_ENABLED_PREFIX = "control_precision_enabled_"
        private val LIVE_ACCELERATION_PERCENT = intPreferencesKey("live_acceleration_percent")
        private const val MIN_DEPTH_LIMIT_SPAN = 0.05f
        private val VOICE_SHORT_WORD_STOP = booleanPreferencesKey("voice_short_word_stop")
        private val VOICE_DEPTH_MIN = floatPreferencesKey("voice_depth_min")
        private val VOICE_DEPTH_MAX = floatPreferencesKey("voice_depth_max")
        private val NATURAL_AI_ENABLED = booleanPreferencesKey("natural_ai_enabled")
        private val NATURAL_AI_ADDRESS = stringPreferencesKey("natural_ai_address")
        private val NATURAL_AI_REASONING_RELAY = booleanPreferencesKey("natural_ai_reasoning_relay")
        const val DEFAULT_LIVE_ACCELERATION_PERCENT = 33
        const val MIN_LIVE_ACCELERATION_PERCENT = 10
        const val MAX_LIVE_ACCELERATION_PERCENT = 100

        /** Stored Voix range: ordered, inside 0..1, at least the 5 % span (limits applied later). */
        internal fun normalizeVoiceDepthRange(min: Float, max: Float): Pair<Float, Float> =
            com.ossm.remote.voice.VoiceDepthRange.effective(min, max, 0f, 1f)

        internal fun normalizeLiveAcceleration(percent: Int): Int =
            percent.coerceIn(MIN_LIVE_ACCELERATION_PERCENT, MAX_LIVE_ACCELERATION_PERCENT)
    }
}
