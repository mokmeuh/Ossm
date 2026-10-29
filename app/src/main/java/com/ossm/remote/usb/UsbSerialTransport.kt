package com.ossm.remote.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class UsbLogSeverity { INFO, WARNING, ERROR }

data class UsbLogLine(val severity: UsbLogSeverity, val message: String)

@Singleton
class UsbSerialTransport @Inject constructor(
    @ApplicationContext private val context: Context,
) : SerialInputOutputManager.Listener {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receiveBuffer = ByteArrayOutputStream()
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    @Volatile private var controlSuspended = false
    @Volatile private var consecutiveWriteFailures = 0
    private var reopenJob: kotlinx.coroutines.Job? = null
    @Volatile private var reopening = false
    @Volatile private var failed = false
    @Volatile private var permissionAskedFor: Int? = null
    private val autoEntry = UsbLiveAutoEntry()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _linkState = MutableStateFlow(UsbLinkState.Absent)
    val linkState: StateFlow<UsbLinkState> = _linkState.asStateFlow()
    private val _logs = MutableSharedFlow<UsbLogLine>(extraBufferCapacity = 64)
    internal val logs: SharedFlow<UsbLogLine> = _logs
    private val _frames = MutableSharedFlow<UsbControlFrame>(extraBufferCapacity = 32)
    internal val frames: SharedFlow<UsbControlFrame> = _frames

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    // The extras can be missing on some builds: fall back to the attached device and
                    // the permission the system actually recorded.
                    val device = intent.usbDevice() ?: findSupportedDevice() ?: run {
                        note(UsbLogSeverity.WARNING, "Réponse de permission USB reçue sans appareil")
                        return
                    }
                    val extraGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val systemGranted = usbManager.hasPermission(device)
                    val granted = extraGranted || systemGranted
                    note(
                        UsbLogSeverity.INFO,
                        "Permission USB ${if (granted) "accordée" else "refusée"} (extra=$extraGranted, système=$systemGranted)",
                    )
                    if (granted) {
                        failed = false
                        open(device)
                    } else {
                        failed = true
                        refreshState()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.usbDevice()
                    if (device != null && isSupportedDevice(device)) {
                        note(UsbLogSeverity.INFO, "OSSM branché en USB-C")
                        noteAttach()
                    }
                    connectIfSupported(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDevice()
                    val concerned = device == null || isSupportedDevice(device) ||
                        device.deviceId == port?.device?.deviceId
                    if (concerned && findSupportedDevice() == null) onCableRemoved()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        connectIfSupported(usbManager.deviceList.values.firstOrNull(::isSupportedDevice))
        refreshState()
        scope.launch {
            ready.collect { connected ->
                while (connected && _ready.value) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    if (!sendFrame(UsbControlProtocol.TYPE_HEARTBEAT)) break
                }
            }
        }
    }

    fun sendCommand(command: String): Boolean =
        sendFrame(UsbControlProtocol.TYPE_COMMAND, command.toByteArray(Charsets.UTF_8))

    fun sendLiveTarget(payload: ByteArray): Boolean =
        sendFrame(UsbControlProtocol.TYPE_LIVE_TARGET, payload)

    fun sendStop(): Boolean = sendFrame(UsbControlProtocol.TYPE_STOP)

    fun suspendForFirmwareUpdate() {
        controlSuspended = true
        reopenJob?.cancel()
        reopening = false
        close()
    }

    fun resumeAfterFirmwareUpdate() {
        controlSuspended = false
        connectIfSupported(usbManager.deviceList.values.firstOrNull(::isSupportedDevice))
    }

    /** Re-checks the bus, e.g. when the app returns to the foreground after a cable was plugged in. */
    fun rescan() {
        connectIfSupported(findSupportedDevice())
        refreshState()
    }

    /** The system delivered an attach for the OSSM (intent or broadcast); arms one Live auto-entry. */
    fun noteAttach() {
        if (autoEntry.onAttach(SystemClock.elapsedRealtime())) {
            note(UsbLogSeverity.INFO, "Entrée automatique en Live armée pour ce branchement")
        }
    }

    /** True once per plug-in when the link and the machine are both ready. */
    fun consumeLiveAutoEntry(machineReady: Boolean): Boolean =
        autoEntry.tryConsume(SystemClock.elapsedRealtime(), _ready.value, machineReady)

    /**
     * True when the Bluetooth link must stay idle: the cable is still plugged in and a USB
     * reopen is running (or has not failed), see [UsbFallbackPolicy].
     */
    fun blocksBleFallback(userRequested: Boolean): Boolean = !UsbFallbackPolicy.allowBle(
        cablePresent = findSupportedDevice() != null,
        reopening = reopening,
        reopenFailed = failed,
        userRequested = userRequested,
    )

    private fun onCableRemoved() {
        note(UsbLogSeverity.WARNING, "OSSM débranché du USB-C")
        reopenJob?.cancel()
        reopening = false
        failed = false
        permissionAskedFor = null
        autoEntry.onDetach()
        close()
    }

    private fun note(severity: UsbLogSeverity, message: String) {
        _logs.tryEmit(UsbLogLine(severity, message))
    }

    private fun refreshState() {
        _linkState.value = UsbLinkPolicy.linkState(
            cablePresent = runCatching { findSupportedDevice() != null }.getOrDefault(false),
            ready = _ready.value,
            reopening = reopening,
            failed = failed,
        )
    }

    private fun sendFrame(type: Int, payload: ByteArray = byteArrayOf()): Boolean {
        if (!_ready.value && type != UsbControlProtocol.TYPE_HELLO) return false
        val activePort = port ?: return false
        return runCatching {
            activePort.write(UsbControlProtocol.encode(type, payload), WRITE_TIMEOUT_MS)
            consecutiveWriteFailures = 0
            true
        }.getOrElse {
            consecutiveWriteFailures++
            if (UsbLinkPolicy.shouldDropLink(consecutiveWriteFailures)) {
                note(
                    UsbLogSeverity.WARNING,
                    "Écritures USB en échec ($consecutiveWriteFailures) : ${it.message ?: "erreur"} — liaison fermée",
                )
                close()
                scheduleReopen()
            }
            false
        }
    }

    /** A dropped link is reopened while the same OSSM is still on the bus, instead of giving up. */
    private fun scheduleReopen() {
        if (controlSuspended) return
        reopenJob?.cancel()
        failed = false
        reopening = true
        refreshState()
        note(UsbLogSeverity.WARNING, "USB perdu, reconnexion…")
        reopenJob = scope.launch {
            for ((index, waitMs) in REOPEN_DELAYS_MS.withIndex()) {
                delay(waitMs)
                if (controlSuspended || port != null) {
                    reopening = false
                    refreshState()
                    return@launch
                }
                val device = findSupportedDevice() ?: run {
                    reopening = false
                    refreshState()
                    return@launch
                }
                if (usbManager.hasPermission(device)) {
                    note(UsbLogSeverity.INFO, "Réouverture USB, essai ${index + 1}/${REOPEN_DELAYS_MS.size}")
                    open(device)
                } else {
                    note(UsbLogSeverity.WARNING, "Réouverture USB impossible : permission absente")
                }
                if (port != null) {
                    reopening = false
                    refreshState()
                    return@launch
                }
            }
            reopening = false
            failed = true
            refreshState()
            note(
                UsbLogSeverity.ERROR,
                "Réouverture USB échouée après ${REOPEN_DELAYS_MS.size} essais — Bluetooth seulement sur demande",
            )
        }
    }

    private fun findSupportedDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull(::isSupportedDevice)

    override fun onNewData(data: ByteArray) {
        synchronized(receiveBuffer) {
            receiveBuffer.write(data)
            val buffered = receiveBuffer.toByteArray().takeLast(MAX_RECEIVE_BUFFER).toByteArray()
            val (frames, remainder) = UsbControlProtocol.decodeFrames(buffered)
            receiveBuffer.reset()
            receiveBuffer.write(remainder)
            frames.forEach { frame ->
                if (frame.type == UsbControlProtocol.TYPE_READY && frame.isOssmReady()) {
                    if (!_ready.value) {
                        note(UsbLogSeverity.INFO, "READY reçu : ${frame.payload.toString(Charsets.UTF_8)}")
                    }
                    reopening = false
                    failed = false
                    _ready.value = true
                    refreshState()
                }
                _frames.tryEmit(frame)
            }
        }
    }

    override fun onRunError(error: Exception) {
        note(UsbLogSeverity.WARNING, "Erreur de lecture USB : ${error.message ?: error.javaClass.simpleName}")
        close()
        // The cable being pulled also lands here: only reopen while the OSSM is still on the bus.
        if (findSupportedDevice() != null) scheduleReopen() else onCableRemoved()
    }

    private fun connectIfSupported(device: UsbDevice?) {
        if (controlSuspended || device == null || !isSupportedDevice(device) || port != null) return
        if (usbManager.hasPermission(device)) {
            open(device)
            return
        }
        // Ask once per plug-in: a pending or answered dialog is not repeated on every onStart.
        if (permissionAskedFor == device.deviceId) return
        permissionAskedFor = device.deviceId
        // Must be MUTABLE: the system writes EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED into it.
        // With FLAG_IMMUTABLE the grant arrived empty and the port only opened after an app restart.
        val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or mutability,
        )
        note(UsbLogSeverity.INFO, "Permission USB demandée")
        usbManager.requestPermission(device, permissionIntent)
        refreshState()
    }

    private fun open(device: UsbDevice) {
        if (port != null) return
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: run {
            note(
                UsbLogSeverity.ERROR,
                "Ouverture USB impossible : aucun pilote série pour ${device.vendorId}:${device.productId}",
            )
            refreshState()
            return
        }
        val connection = usbManager.openDevice(device) ?: run {
            note(
                UsbLogSeverity.ERROR,
                "Ouverture USB impossible : openDevice a renvoyé null (permission=${usbManager.hasPermission(device)})",
            )
            refreshState()
            return
        }
        val openedPort = driver.ports.firstOrNull() ?: run {
            note(UsbLogSeverity.ERROR, "Ouverture USB impossible : aucun port série")
            connection.close()
            refreshState()
            return
        }
        runCatching {
            openedPort.open(connection)
            openedPort.setParameters(
                BAUD_RATE,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            port = openedPort
            ioManager = SerialInputOutputManager(openedPort, this).also { it.start() }
            note(UsbLogSeverity.INFO, "Port USB ouvert, attente de READY")
            sendFrame(UsbControlProtocol.TYPE_HELLO)
        }.onFailure {
            note(UsbLogSeverity.ERROR, "Ouverture USB échouée : ${it.message ?: it.javaClass.simpleName}")
            runCatching { openedPort.close() }
            connection.close()
            close()
        }
        refreshState()
    }

    private fun close() {
        consecutiveWriteFailures = 0
        _ready.value = false
        ioManager?.stop()
        ioManager = null
        runCatching { port?.close() }
        port = null
        synchronized(receiveBuffer) { receiveBuffer.reset() }
        refreshState()
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private fun isSupportedDevice(device: UsbDevice): Boolean =
        device.vendorId == OSSM_VENDOR_ID && device.productId == OSSM_PRODUCT_ID

    private fun UsbControlFrame.isOssmReady(): Boolean {
        val identity = payload.toString(Charsets.UTF_8)
        return identity.startsWith("OSSM;") && identity.endsWith(";USB1")
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.ossm.remote.USB_PERMISSION"
        const val OSSM_VENDOR_ID = 0x10C4
        const val OSSM_PRODUCT_ID = 0xEA60
        const val BAUD_RATE = 115_200
        const val WRITE_TIMEOUT_MS = 40
        const val HEARTBEAT_INTERVAL_MS = 100L
        const val MAX_RECEIVE_BUFFER = 1024
        val REOPEN_DELAYS_MS = longArrayOf(250, 500, 1_000, 2_000, 4_000)
    }
}
