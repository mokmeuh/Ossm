package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ossm.remote.data.repository.OverlayPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Réglages de l'overlay exposés à l'écran Profils (« Reglages ») :
 * activation (démarre/arrête le OverlayService) et transparence de la bulle.
 * Lit/écrit [OverlayPreferencesRepository] ; l'état est reflété en direct.
 */
@HiltViewModel
class OverlaySettingsViewModel @Inject constructor(
    private val overlayPreferencesRepository: OverlayPreferencesRepository
) : ViewModel() {

    private val _overlayEnabled = MutableStateFlow(false)
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    private val _transparencyPercent = MutableStateFlow(DEFAULT_TRANSPARENCY_PERCENT)
    val transparencyPercent: StateFlow<Int> = _transparencyPercent.asStateFlow()

    init {
        viewModelScope.launch {
            overlayPreferencesRepository.overlayEnabled.collect { _overlayEnabled.value = it }
        }
        viewModelScope.launch {
            overlayPreferencesRepository.transparencyPercent.collect {
                _transparencyPercent.value = it
            }
        }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        _overlayEnabled.value = enabled
        viewModelScope.launch { overlayPreferencesRepository.setOverlayEnabled(enabled) }
    }

    fun setTransparencyPercent(percent: Int) {
        val clamped = percent.coerceIn(MIN_TRANSPARENCY, MAX_TRANSPARENCY)
        _transparencyPercent.value = clamped
        viewModelScope.launch { overlayPreferencesRepository.setTransparencyPercent(clamped) }
    }

    private companion object {
        const val DEFAULT_TRANSPARENCY_PERCENT = 100
        const val MIN_TRANSPARENCY = 20
        const val MAX_TRANSPARENCY = 100
    }
}
