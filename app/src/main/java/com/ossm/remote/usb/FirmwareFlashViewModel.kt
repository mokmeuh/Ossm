package com.ossm.remote.usb

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Image waiting for the person's confirmation. */
class PendingFirmware(val label: String, val image: ByteArray) {
    val sizeKb: Int get() = (image.size + 1023) / 1024
}

@HiltViewModel
class FirmwareFlashViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flasher: UsbFirmwareFlasher,
) : ViewModel() {
    val state: StateFlow<FirmwareFlashState> = flasher.state
    val logs: StateFlow<List<String>> = flasher.logs

    private val _pending = MutableStateFlow<PendingFirmware?>(null)
    val pending: StateFlow<PendingFirmware?> = _pending.asStateFlow()

    fun prepareBundled() {
        viewModelScope.launch(Dispatchers.IO) {
            stage(BUNDLED_LABEL, flasher.readBundledImage())
        }
    }

    fun prepareFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val image = runCatching { readLimited(uri) }.getOrNull()
            if (image == null) flasher.reject() else stage(displayName(uri), image)
        }
    }

    fun confirm() {
        val image = _pending.value?.image ?: return
        _pending.value = null
        flasher.start(image)
    }

    fun cancel() {
        _pending.value = null
    }

    fun reset() = flasher.reset()

    private fun stage(label: String, image: ByteArray) {
        if (flasher.isValidImage(image)) _pending.value = PendingFirmware(label, image) else flasher.reject()
    }

    /** Reads at most one slot's worth of bytes; anything larger cannot be a valid image. */
    private fun readLimited(uri: Uri): ByteArray? {
        val limit = MAX_IMAGE_BYTES
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
                if (out.size() > limit) return null
            }
            return out.toByteArray()
        }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: "firmware.bin"

    private companion object {
        const val BUNDLED_LABEL = "ossm-1.2.0.bin"
        const val MAX_IMAGE_BYTES = 0x1E0000
    }
}
