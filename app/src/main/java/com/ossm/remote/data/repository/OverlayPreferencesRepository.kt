package com.ossm.remote.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.overlayDataStore by preferencesDataStore(name = "overlay_settings")

/**
 * Réglages persistés de l'overlay flottant (bulle + panneau) :
 * position de la bulle (x/y en pixels, gravity TOP|START) et transparence (20..100 %).
 * Le DataStore est distinct des autres pour ne pas mélanger ces préférences UI avec
 * la sécurité (control_safety_settings) ou les habitudes (user_habits).
 */
@Singleton
class OverlayPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Position X de la bulle (pixels depuis le bord gauche). */
    val bubbleX: Flow<Int> = context.overlayDataStore.data.map { prefs ->
        prefs[BUBBLE_X] ?: DEFAULT_X
    }

    /** Position Y de la bulle (pixels depuis le haut de l'écran). */
    val bubbleY: Flow<Int> = context.overlayDataStore.data.map { prefs ->
        prefs[BUBBLE_Y] ?: DEFAULT_Y
    }

    /** Opacité du fond de la bulle/panneau (20..100, 100 = opaque). */
    val transparencyPercent: Flow<Int> = context.overlayDataStore.data.map { prefs ->
        prefs[TRANSPARENCY_PERCENT] ?: DEFAULT_TRANSPARENCY_PERCENT
    }

    /** Vrai quand l'utilisateur a activé l'overlay dans les réglages. */
    val overlayEnabled: Flow<Boolean> = context.overlayDataStore.data.map { prefs ->
        prefs[OVERLAY_ENABLED] ?: true
    }

    suspend fun setBubblePosition(x: Int, y: Int) {
        context.overlayDataStore.edit { prefs ->
            prefs[BUBBLE_X] = x
            prefs[BUBBLE_Y] = y
        }
    }

    suspend fun setTransparencyPercent(percent: Int) {
        context.overlayDataStore.edit { prefs ->
            prefs[TRANSPARENCY_PERCENT] = percent.coerceIn(MIN_TRANSPARENCY, MAX_TRANSPARENCY)
        }
    }

    suspend fun setOverlayEnabled(enabled: Boolean) {
        context.overlayDataStore.edit { prefs ->
            prefs[OVERLAY_ENABLED] = enabled
        }
    }

    companion object {
        private val BUBBLE_X = intPreferencesKey("bubble_x")
        private val BUBBLE_Y = intPreferencesKey("bubble_y")
        private val TRANSPARENCY_PERCENT = intPreferencesKey("transparency_percent")
        private val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")

        private const val DEFAULT_X = 24
        private const val DEFAULT_Y = 160
        private const val DEFAULT_TRANSPARENCY_PERCENT = 100
        private const val MIN_TRANSPARENCY = 20
        private const val MAX_TRANSPARENCY = 100
    }
}
