package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ossm.remote.data.repository.SessionStatsRepository
import com.ossm.remote.data.repository.StatsSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Statistiques de sessions pour la section dédiée de l'écran Profils.
 * Le [snapshot] est recomputé par [SessionStatsRepository] à chaque changement en base ;
 * les actions reset/export/import modifient la base et la section se met à jour en direct.
 */
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val statsRepository: SessionStatsRepository
) : ViewModel() {

    val snapshot: StateFlow<StatsSnapshot> = statsRepository.snapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsSnapshot())

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _pendingExportJson = MutableStateFlow<String?>(null)
    val pendingExportJson: StateFlow<String?> = _pendingExportJson.asStateFlow()

    fun resetAll() {
        viewModelScope.launch { statsRepository.resetAll() }
    }

    /** Génère le JSON d'export ; l'UI écrit le fichier puis appelle [consumePendingExport]. */
    fun prepareExport() {
        viewModelScope.launch {
            _exporting.value = true
            _pendingExportJson.value = statsRepository.exportSessions()
            _exporting.value = false
        }
    }

    fun consumePendingExport() {
        _pendingExportJson.value = null
    }

    fun importJson(json: String) {
        viewModelScope.launch {
            _importing.value = true
            statsRepository.importSessions(json)
                .onSuccess { _importError.value = null }
                .onFailure { _importError.value = it.message }
            _importing.value = false
        }
    }
}
