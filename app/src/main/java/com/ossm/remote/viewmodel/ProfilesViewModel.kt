package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ossm.remote.data.repository.PresetRepository
import com.ossm.remote.model.Preset
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val repository: PresetRepository
) : ViewModel() {

    val presets: StateFlow<List<Preset>> = repository.presets
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun savePreset(preset: Preset) = viewModelScope.launch {
        repository.save(preset)
    }

    fun deletePreset(preset: Preset) = viewModelScope.launch {
        repository.delete(preset)
    }
}
