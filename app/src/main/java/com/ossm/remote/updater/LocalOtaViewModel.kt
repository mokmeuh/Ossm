package com.ossm.remote.updater

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class LocalOtaViewModel @Inject constructor(
    @ApplicationContext context: Context,
) : ViewModel() {
    private val transfer = LocalOtaTransfer(context)
    val state: StateFlow<LocalOtaState> = transfer.state

    fun toggleReceiver() {
        if (state.value.receiverActive) transfer.stopReceiver() else transfer.startReceiver()
    }

    fun sendInstalledVersion() = transfer.sendInstalledVersion()

    /** Manual address (IP or IP:port); no code, the 2-digit comparison authenticates. */
    fun sendToAddress(address: String) = transfer.sendToAddress(address)

    /** Raw text of a scanned QR code (null when the scan was cancelled). */
    fun onQrScanned(contents: String?) {
        if (contents != null) transfer.sendFromQr(contents)
    }

    fun cancelPairing() = transfer.cancelPairing()

    /** Receiver: Oui / Non for the number shown. */
    fun answerIncoming(accepted: Boolean) = transfer.answerIncoming(accepted)

    override fun onCleared() {
        transfer.close()
        super.onCleared()
    }
}
