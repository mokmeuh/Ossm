package com.ossm.remote.updater

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class PcUpdateViewModel @Inject constructor(
    private val controller: PcUpdateController,
) : ViewModel() {
    val status: StateFlow<PcUpdateStatus> = controller.status
    val serverUrl: StateFlow<String> = controller.serverUrl
    val listening: StateFlow<Boolean> = controller.listening

    fun saveServerUrl(text: String): Boolean = controller.saveServerUrl(text)
    fun setListening(enabled: Boolean) = controller.setListening(enabled)
    fun installLatestNow(addressText: String) = controller.installLatestNow(addressText)
    fun openInstaller(apk: File) = controller.openInstaller(apk)
}
