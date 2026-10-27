package com.ossm.remote.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

enum class FlashFailure {
    NO_CABLE,
    PERMISSION_DENIED,
    OPEN_FAILED,
    NO_BOOTLOADER,
    INVALID_IMAGE,
    REJECTED,
    WRITE_FAILED
}

sealed interface FirmwareFlashState {
    data object Idle : FirmwareFlashState
    data object Preparing : FirmwareFlashState
    data object Erasing : FirmwareFlashState
    data class Writing(val percent: Int) : FirmwareFlashState
    data object Complete : FirmwareFlashState
    data class Failed(val failure: FlashFailure, val block: Int = 0) : FirmwareFlashState
}

private class FlashAbort(val failure: FlashFailure, val block: Int = 0) : Exception(failure.name)

/**
 * Writes the machine firmware over the OSSM USB bridge (CP210x) from the phone.
 *
 * The application goes to the first OTA slot, then the OTA selector is reset so the
 * bootloader starts that slot (and not an older image left in the second one). The selector
 * is written last: until the whole image is in, the previous selection stays valid.
 */
@Singleton
class UsbFirmwareFlasher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controlTransport: UsbSerialTransport,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _state = MutableStateFlow<FirmwareFlashState>(FirmwareFlashState.Idle)
    val state: StateFlow<FirmwareFlashState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    /** Timestamped steps of the current or last flash, shown in Réglages for diagnosis. */
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    @Volatile private var startedAt = 0L

    fun readBundledImage(): ByteArray = context.assets.open(BUNDLED_ASSET).use { it.readBytes() }

    fun isValidImage(image: ByteArray): Boolean =
        Esp32BootloaderProtocol.isEsp32AppImage(image, APP_PARTITION_SIZE)

    fun reject() {
        synchronized(this) { if (!isBusy()) _state.value = FirmwareFlashState.Failed(FlashFailure.INVALID_IMAGE) }
    }

    fun reset() {
        synchronized(this) { if (!isBusy()) _state.value = FirmwareFlashState.Idle }
    }

    fun start(image: ByteArray) {
        val started = synchronized(this) {
            if (isBusy()) false else {
                _logs.value = emptyList()
                _state.value = FirmwareFlashState.Preparing
                true
            }
        }
        if (started) scope.launch { flash(image) }
    }

    private fun isBusy(): Boolean = _state.value.let {
        it is FirmwareFlashState.Preparing ||
            it is FirmwareFlashState.Erasing ||
            it is FirmwareFlashState.Writing
    }

    private fun log(message: String) {
        val line = "+${SystemClock.elapsedRealtime() - startedAt} ms  $message"
        Log.i(LOG_TAG, "[FLASH] $line")
        _logs.update { (it + line).takeLast(MAX_LOG_LINES) }
    }

    private fun hex(bytes: ByteArray, limit: Int = 16): String =
        bytes.take(limit).joinToString(" ") { "%02X".format(it) }

    private fun printable(bytes: ByteArray, limit: Int = 160): String =
        bytes.take(limit).joinToString("") { byte ->
            val c = byte.toInt() and 0xFF
            if (c in 32..126) c.toChar().toString() else "."
        }

    private suspend fun flash(image: ByteArray) {
        startedAt = SystemClock.elapsedRealtime()
        var connection: UsbDeviceConnection? = null
        var port: UsbSerialPort? = null
        var suspended = false
        var resetDone = false
        try {
            log("début : image de ${image.size} octets")
            if (!isValidImage(image)) throw FlashAbort(FlashFailure.INVALID_IMAGE)
            val device = findDevice() ?: throw FlashAbort(FlashFailure.NO_CABLE)
            if (!ensurePermission(device)) throw FlashAbort(FlashFailure.PERMISSION_DENIED)
            log("accès USB accordé")

            controlTransport.suspendForFirmwareUpdate()
            suspended = true
            connection = usbManager.openDevice(device) ?: throw FlashAbort(FlashFailure.OPEN_FAILED)
            val serial = UsbSerialProber.getDefaultProber().probeDevice(device)?.ports?.firstOrNull()
                ?: throw FlashAbort(FlashFailure.OPEN_FAILED)
            serial.open(connection)
            port = serial
            serial.setParameters(ROM_BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            log("port ouvert à $ROM_BAUD bauds")
            val link = BootloaderLink(serial)

            if (!connectBootloader(serial, link)) throw FlashAbort(FlashFailure.NO_BOOTLOADER)
            log("bootloader synchronisé")
            raiseSpeed(serial, link)
            attachFlash(link)

            val otaSelector = ByteArray(OTA_SELECTOR_SIZE) { 0xFF.toByte() }
            val totalBlocks = Esp32BootloaderProtocol.blockCount(image.size) +
                Esp32BootloaderProtocol.blockCount(otaSelector.size)
            var written = 0
            var lastPercent = -1
            val onBlock = {
                written++
                val percent = written * 100 / totalBlocks
                if (percent != lastPercent) {
                    lastPercent = percent
                    _state.value = FirmwareFlashState.Writing(percent)
                }
            }
            _state.value = FirmwareFlashState.Erasing
            writeRegion(link, image, APP_OFFSET, onBlock) {
                _state.value = FirmwareFlashState.Writing(0)
            }
            writeRegion(link, otaSelector, OTA_SELECTOR_OFFSET, onBlock)

            link.exchange(Esp32BootloaderProtocol.flashEnd(reboot = true), Esp32BootloaderProtocol.OP_FLASH_END, 1_000)
            hardReset(serial)
            resetDone = true
            log("terminé, redémarrage de la machine")
            _state.value = FirmwareFlashState.Complete
        } catch (abort: FlashAbort) {
            log("échec : ${abort.failure} (bloc ${abort.block})")
            _state.value = FirmwareFlashState.Failed(abort.failure, abort.block)
        } catch (error: Exception) {
            log("erreur inattendue : ${error::class.java.simpleName} ${error.message}")
            _state.value = FirmwareFlashState.Failed(FlashFailure.WRITE_FAILED)
        } finally {
            val openPort = port
            if (openPort != null && !resetDone) runCatching { hardReset(openPort) }
            runCatching { openPort?.close() }
            runCatching { connection?.close() }
            if (suspended) {
                delay(POST_FLASH_DELAY_MS)
                controlTransport.resumeAfterFirmwareUpdate()
            }
            log("liaison USB rendue au contrôle")
        }
    }

    private fun writeRegion(
        link: BootloaderLink,
        data: ByteArray,
        offset: Int,
        onBlock: () -> Unit,
        onErased: () -> Unit = {}
    ) {
        val begun = SystemClock.elapsedRealtime()
        val eraseTimeout = max(MIN_ERASE_TIMEOUT_MS, ERASE_TIMEOUT_MS_PER_MB * data.size / 1_000_000L)
        log("flash_begin 0x${offset.toString(16)} : ${data.size} octets, attente max $eraseTimeout ms")
        val begin = link.exchange(
            Esp32BootloaderProtocol.flashBegin(data.size, offset),
            Esp32BootloaderProtocol.OP_FLASH_BEGIN,
            eraseTimeout
        )
        if (begin == null) {
            log("flash_begin : aucune réponse après ${SystemClock.elapsedRealtime() - begun} ms")
            throw FlashAbort(FlashFailure.REJECTED)
        }
        if (!Esp32BootloaderProtocol.succeeded(begin)) {
            log("flash_begin refusé : ${hex(begin)}")
            throw FlashAbort(FlashFailure.REJECTED)
        }
        log("flash_begin accepté après ${SystemClock.elapsedRealtime() - begun} ms")
        onErased()
        val blocks = Esp32BootloaderProtocol.blockCount(data.size)
        for (index in 0 until blocks) {
            val response = link.exchange(
                Esp32BootloaderProtocol.flashData(index, data, index * Esp32BootloaderProtocol.BLOCK_SIZE),
                Esp32BootloaderProtocol.OP_FLASH_DATA,
                BLOCK_TIMEOUT_MS
            )
            if (response == null) {
                log("bloc ${index + 1}/$blocks : aucune réponse")
                throw FlashAbort(FlashFailure.WRITE_FAILED, index + 1)
            }
            if (!Esp32BootloaderProtocol.succeeded(response)) {
                log("bloc ${index + 1}/$blocks refusé : ${hex(response)}")
                throw FlashAbort(FlashFailure.WRITE_FAILED, index + 1)
            }
            if (index == 0 || (index + 1) % LOG_EVERY_BLOCKS == 0 || index + 1 == blocks) {
                log("bloc ${index + 1}/$blocks écrit")
            }
            onBlock()
        }
    }

    /** Reset sequence on the USB bridge's DTR/RTS lines, then sync until the ROM answers. */
    private suspend fun connectBootloader(port: UsbSerialPort, link: BootloaderLink): Boolean {
        for (attempt in 0 until RESET_ATTEMPTS) {
            port.dtr = false
            port.rts = true
            delay(100)
            port.dtr = true
            port.rts = false
            delay(if (attempt % 2 == 0) 50L else 500L)
            port.dtr = false
            val banner = link.flushInput()
            log("reset ${attempt + 1}/$RESET_ATTEMPTS, reçu : \"${printable(banner)}\"")
            repeat(SYNC_TRIES) {
                link.send(Esp32BootloaderProtocol.syncPacket())
                if (link.awaitResponse(Esp32BootloaderProtocol.OP_SYNC, SYNC_TIMEOUT_MS) != null) {
                    delay(100)
                    link.flushInput()
                    return true
                }
            }
            log("reset ${attempt + 1} : pas de réponse à la synchronisation")
        }
        return false
    }

    /** Faster serial speed for the transfer; falls back to the ROM speed if the switch fails. */
    private suspend fun raiseSpeed(port: UsbSerialPort, link: BootloaderLink) {
        val answer = link.exchange(
            Esp32BootloaderProtocol.changeBaud(FAST_BAUD), Esp32BootloaderProtocol.OP_CHANGE_BAUD, 1_000
        )
        if (answer == null || !Esp32BootloaderProtocol.succeeded(answer)) {
            log("changement de vitesse refusé, on reste à $ROM_BAUD")
            return
        }
        port.setParameters(FAST_BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        delay(50)
        link.flushInput()
        link.send(Esp32BootloaderProtocol.syncPacket())
        if (link.awaitResponse(Esp32BootloaderProtocol.OP_SYNC, SYNC_TIMEOUT_MS) != null) {
            delay(100)
            link.flushInput()
            log("vitesse portée à $FAST_BAUD bauds")
            return
        }
        log("pas de réponse à $FAST_BAUD bauds, retour à $ROM_BAUD")
        port.setParameters(ROM_BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        if (!connectBootloader(port, link)) throw FlashAbort(FlashFailure.NO_BOOTLOADER)
    }

    /** The ESP32 ROM ignores flash commands until the SPI flash has been attached. */
    private fun attachFlash(link: BootloaderLink) {
        val answer = link.exchange(
            Esp32BootloaderProtocol.spiAttach(), Esp32BootloaderProtocol.OP_SPI_ATTACH, SPI_ATTACH_TIMEOUT_MS
        )
        if (answer == null) {
            log("spi_attach : aucune réponse")
            throw FlashAbort(FlashFailure.REJECTED)
        }
        if (!Esp32BootloaderProtocol.succeeded(answer)) {
            log("spi_attach refusé : ${hex(answer)}")
            throw FlashAbort(FlashFailure.REJECTED)
        }
        log("mémoire flash attachée")
    }

    private suspend fun hardReset(port: UsbSerialPort) {
        port.dtr = false
        port.rts = true
        delay(100)
        port.rts = false
    }

    private fun findDevice(): UsbDevice? =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).map { it.device }
            .firstOrNull { it.vendorId == BRIDGE_VENDOR && it.productId == BRIDGE_PRODUCT }

    private suspend fun ensurePermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        runCatching { context.unregisterReceiver(this) }
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                val filter = IntentFilter(ACTION_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }
                continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                usbManager.requestPermission(
                    device,
                    PendingIntent.getBroadcast(
                        context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags
                    )
                )
            }
        }
        // The broadcast only signals that the question was answered; the system holds the answer.
        return usbManager.hasPermission(device)
    }

    /** Request/response exchange on an open port, with its own SLIP decoder. */
    private class BootloaderLink(private val port: UsbSerialPort) {
        private var decoder = Esp32BootloaderProtocol.SlipDecoder()
        private val buffer = ByteArray(2048)

        fun send(packet: ByteArray) = port.write(packet, WRITE_TIMEOUT_MS)

        /** Drops pending input and returns what was pending, for diagnostics. */
        fun flushInput(): ByteArray {
            decoder = Esp32BootloaderProtocol.SlipDecoder()
            val captured = ByteArrayOutputStream()
            repeat(MAX_FLUSH_READS) {
                val count = port.read(buffer, FLUSH_READ_MS)
                if (count <= 0) return captured.toByteArray()
                captured.write(buffer, 0, count)
            }
            return captured.toByteArray()
        }

        fun awaitResponse(operation: Int, timeoutMs: Long): ByteArray? {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) return null
                val count = port.read(buffer, min(remaining, READ_SLICE_MS).toInt())
                if (count <= 0) continue
                for (frame in decoder.push(buffer.copyOf(count))) {
                    if (Esp32BootloaderProtocol.isResponse(frame, operation)) return frame
                }
            }
        }

        fun exchange(packet: ByteArray, operation: Int, timeoutMs: Long): ByteArray? {
            send(packet)
            return awaitResponse(operation, timeoutMs)
        }
    }

    private companion object {
        const val LOG_TAG = "OSSM"
        const val BUNDLED_ASSET = "firmware/ossm-1.2.0.bin"
        const val ACTION_PERMISSION = "com.ossm.remote.FIRMWARE_USB_PERMISSION"
        const val BRIDGE_VENDOR = 0x10C4
        const val BRIDGE_PRODUCT = 0xEA60

        // Partition layout (min_spiffs): selector at 0xE000 (8 KB), first OTA slot at 0x10000.
        const val OTA_SELECTOR_OFFSET = 0xE000
        const val OTA_SELECTOR_SIZE = 0x2000
        const val APP_OFFSET = 0x10000
        const val APP_PARTITION_SIZE = 0x1E0000

        const val ROM_BAUD = 115_200
        const val FAST_BAUD = 460_800
        const val RESET_ATTEMPTS = 4
        const val SYNC_TRIES = 6
        const val SYNC_TIMEOUT_MS = 300L
        const val SPI_ATTACH_TIMEOUT_MS = 2_000L
        const val BLOCK_TIMEOUT_MS = 5_000L
        const val MIN_ERASE_TIMEOUT_MS = 5_000L
        const val ERASE_TIMEOUT_MS_PER_MB = 30_000L
        const val PERMISSION_TIMEOUT_MS = 30_000L
        const val POST_FLASH_DELAY_MS = 1_500L
        const val WRITE_TIMEOUT_MS = 1_000
        const val READ_SLICE_MS = 200L
        const val FLUSH_READ_MS = 50
        const val MAX_FLUSH_READS = 40
        const val LOG_EVERY_BLOCKS = 200
        const val MAX_LOG_LINES = 400
    }
}
