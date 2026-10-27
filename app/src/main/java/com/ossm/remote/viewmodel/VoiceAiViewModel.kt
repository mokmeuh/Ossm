package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import com.ossm.remote.voice.VoiceAiUiState
import com.ossm.remote.voice.engine.VoiceAiController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Voix 2.3.27: thin delegate to the on-device voice AI controller (singleton). */
@HiltViewModel
class VoiceAiViewModel @Inject constructor(
    private val controller: VoiceAiController,
) : ViewModel() {
    val state: StateFlow<VoiceAiUiState> = controller.state

    fun modelRoot(): String? = controller.modelRoot()

    fun setListening(listen: Boolean) = controller.setListening(listen)

    fun hasMicrophonePermission(): Boolean = controller.hasMicrophonePermission()

    fun onMicrophonePermissionResult(granted: Boolean) = controller.onMicrophonePermissionResult(granted)
}
