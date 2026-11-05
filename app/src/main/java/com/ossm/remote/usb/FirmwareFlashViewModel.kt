package com.ossm.remote.usb

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ossm.remote.updater.PcFirmwareManifest
import com.ossm.remote.updater.PcFirmwareManifestResult
import com.ossm.remote.updater.UpdateRepository
import com.ossm.remote.updater.shouldOfferPcFirmware
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Image waiting for the person's confirmation. */
class PendingFirmware(val label: String, val image: ByteArray, val sha256: String = "") {
    val sizeKb: Int get() = (image.size + 1023) / 1024
}

/** What the firmware card shows about the PC server; quiet states show nothing. */
sealed interface PcFirmwareState {
    data object Idle : PcFirmwareState
    data object Checking : PcFirmwareState
    data class Available(val manifest: PcFirmwareManifest) : PcFirmwareState
    data object UpToDate : PcFirmwareState
    data object Unreachable : PcFirmwareState
    data object NoAddress : PcFirmwareState
    data object Downloading : PcFirmwareState
    data object Failed : PcFirmwareState
}

@HiltViewModel
class FirmwareFlashViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flasher: UsbFirmwareFlasher,
    private val repository: UpdateRepository,
) : ViewModel() {
    val state: StateFlow<FirmwareFlashState> = flasher.state
    val logs: StateFlow<List<String>> = flasher.logs

    private val _pending = MutableStateFlow<PendingFirmware?>(null)
    val pending: StateFlow<PendingFirmware?> = _pending.asStateFlow()

    private val _pcFirmware = MutableStateFlow<PcFirmwareState>(PcFirmwareState.Idle)
    val pcFirmware: StateFlow<PcFirmwareState> = _pcFirmware.asStateFlow()

    /** Digest of the image handed to the flasher, saved only if the write ends successfully. */
    @Volatile private var flashingSha: String? = null

    init {
        viewModelScope.launch {
            flasher.state.collect { current ->
                when (current) {
                    FirmwareFlashState.Complete -> flashingSha?.takeIf { it.isNotEmpty() }?.let { sha ->
                        repository.saveLastFlashedFirmwareSha(sha)
                        // The image just flashed is no longer news.
                        _pcFirmware.value = PcFirmwareState.Idle
                    }
                    is FirmwareFlashState.Failed -> flashingSha = null
                    else -> Unit
                }
                if (current == FirmwareFlashState.Complete) flashingSha = null
            }
        }
    }

    /**
     * Looks for a firmware on the PC server. [manual] only decides whether a quiet outcome
     * (server off, nothing new, no address) is reported; the automatic check stays silent.
     * Never blocks: the repository gives up within about a second.
     */
    fun checkPcFirmware(manual: Boolean) {
        val current = _pcFirmware.value
        if (current is PcFirmwareState.Checking || current is PcFirmwareState.Downloading) return
        viewModelScope.launch {
            val server = repository.pcServerUrl.first()
            if (server.isBlank()) {
                _pcFirmware.value = if (manual) PcFirmwareState.NoAddress else PcFirmwareState.Idle
                return@launch
            }
            if (manual) _pcFirmware.value = PcFirmwareState.Checking
            _pcFirmware.value = when (val result = repository.fetchPcFirmwareManifest(server)) {
                PcFirmwareManifestResult.Unreachable ->
                    if (manual) PcFirmwareState.Unreachable else PcFirmwareState.Idle
                PcFirmwareManifestResult.Invalid ->
                    if (manual) PcFirmwareState.Failed else PcFirmwareState.Idle
                is PcFirmwareManifestResult.Found -> {
                    val last = repository.lastFlashedFirmwareSha.first()
                    when {
                        shouldOfferPcFirmware(result.manifest.sha256, last) -> PcFirmwareState.Available(result.manifest)
                        manual -> PcFirmwareState.UpToDate
                        else -> PcFirmwareState.Idle
                    }
                }
            }
        }
    }

    /** Downloads and verifies the announced image, then hands it to the usual confirmation dialog. */
    fun fetchPcFirmware(manifest: PcFirmwareManifest) {
        if (_pcFirmware.value is PcFirmwareState.Downloading) return
        viewModelScope.launch(Dispatchers.IO) {
            _pcFirmware.value = PcFirmwareState.Downloading
            val server = repository.pcServerUrl.first()
            val image = repository.downloadPcFirmware(server, manifest)
            if (image == null || !flasher.isValidImage(image)) {
                _pcFirmware.value = PcFirmwareState.Failed
                return@launch
            }
            _pcFirmware.value = PcFirmwareState.Idle
            stage(manifest.label, image)
        }
    }

    fun dismissPcFirmwareMessage() {
        val current = _pcFirmware.value
        if (current !is PcFirmwareState.Available && current !is PcFirmwareState.Checking &&
            current !is PcFirmwareState.Downloading
        ) _pcFirmware.value = PcFirmwareState.Idle
    }

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
        val firmware = _pending.value ?: return
        _pending.value = null
        flashingSha = firmware.sha256
        flasher.start(firmware.image)
    }

    fun cancel() {
        _pending.value = null
    }

    fun reset() = flasher.reset()

    private fun stage(label: String, image: ByteArray) {
        if (flasher.isValidImage(image)) _pending.value = PendingFirmware(label, image, sha256Of(image)) else flasher.reject()
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

    private fun sha256Of(image: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: "firmware.bin"

    private companion object {
        const val BUNDLED_LABEL = "ossm-1.2.0.bin"
        const val MAX_IMAGE_BYTES = 0x1E0000
    }
}
