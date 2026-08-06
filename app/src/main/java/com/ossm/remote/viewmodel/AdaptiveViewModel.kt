package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ossm.remote.adaptive.AdaptiveEngine
import com.ossm.remote.adaptive.AdaptivePace
import com.ossm.remote.adaptive.AdaptiveUiState
import com.ossm.remote.data.repository.AdaptiveSettings
import com.ossm.remote.data.repository.AdaptiveSettingsRepository
import com.ossm.remote.session.ControlSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel du mode adaptatif. L'état décisionnel (verrouillage, progression, confiance,
 * résumé d'apprentissage) est exposé tel quel par [ControlSessionStore.adaptiveUiState] ;
 * les réglages sont dérivés du même état pour ne jamais diverger. Les écritures passent par
 * l'[AdaptiveEngine], qui met à jour son état local puis persiste dans le DataStore.
 */
@HiltViewModel
class AdaptiveViewModel @Inject constructor(
    private val controlSessionStore: ControlSessionStore,
    private val adaptiveEngine: AdaptiveEngine,
    settingsRepository: AdaptiveSettingsRepository
) : ViewModel() {

    /** État adaptatif exposé par le moteur (cadenas, progression, confiance, résumé). */
    val adaptiveUiState: StateFlow<AdaptiveUiState> = controlSessionStore.adaptiveUiState

    /** Réglages persistés (enabled, pace, diversity), synchronisés avec l'état du moteur. */
    val settings: StateFlow<AdaptiveSettings> = adaptiveUiState
        .map {
            AdaptiveSettings(
                enabled = it.enabled,
                pace = it.pace,
                diversity = it.diversity
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdaptiveSettings())

    /** Nombre d'ajustements manuels cumulés (source : apprentissage persisté). */
    val manualAdjustments: StateFlow<Long> = settingsRepository.learning
        .map { it.manualAdjustments }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setEnabled(enabled: Boolean) = adaptiveEngine.setEnabled(enabled)

    fun setPace(pace: AdaptivePace) = adaptiveEngine.setPace(pace)

    fun setDiversity(diversity: Float) = adaptiveEngine.setDiversity(diversity)

    /** Démarre le mix adaptatif sur le pattern ADAPTIVE sélectionné (geste explicite). */
    fun startAdaptive() = controlSessionStore.startAdaptive()

    /** Arrête le mix adaptatif en cours et la machine. */
    fun stopAdaptive() = controlSessionStore.stopAdaptive()
}
