package com.ossm.remote.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
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

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _frames = MutableSharedFlow<UsbControlFrame>(extraBufferCapacity = 32)
    internal val frames: SharedFlow<UsbControlFrame> = _frames

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice() ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(device)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectIfSupported(intent.usbDevice())
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (intent.usbDevice()?.deviceId == port?.device?.deviceId) close()
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
        close()
    }

    fun resumeAfterFirmwareUpdate() {
        controlSuspended = false
        connectIfSupported(usbManager.deviceList.values.firstOrNull(::isSupportedDevice))
    }

    private fun sendFrame(type: Int, payload: ByteArray = byteArrayOf()): Boolean {
        if (!_ready.value && type != UsbControlProtocol.TYPE_HELLO) return false
        val activePort = port ?: return false
        return runCatching {
            activePort.write(UsbControlProtocol.encode(type, payload), WRITE_TIMEOUT_MS)
            true
        }.getOrElse {
            close()
            false
        }
    }

    override fun onNewData(data: ByteArray) {
        synchronized(receiveBuffer) {
            receiveBuffer.write(data)
            val buffered = receiveBuffer.toByteArray().takeLast(MAX_RECEIVE_BUFFER).toByteArray()
            val (frames, remainder) = UsbControlProtocol.decodeFrames(buffered)
            receiveBuffer.reset()
            receiveBuffer.write(remainder)
            frames.forEach { frame ->
                if (frame.type == UsbControlProtocol.TYPE_READY && frame.isOssmReady()) {
                    _ready.value = true
                }
                _frames.tryEmit(frame)
            }
        }
    }

    override fun onRunError(error: Exception) = close()

    private fun connectIfSupported(device: UsbDevice?) {
        if (controlSuspended || device == null || !isSupportedDevice(device) || port != null) return
        if (usbManager.hasPermission(device)) {
            open(device)
            return
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun open(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: return
        val connection = usbManager.openDevice(device) ?: return
        val openedPort = driver.ports.firstOrNull() ?: run {
            connection.close()
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
            sendFrame(UsbControlProtocol.TYPE_HELLO)
        }.onFailure {
            runCatching { openedPort.close() }
            connection.close()
            close()
        }
    }

    private fun close() {
        _ready.value = false
        ioManager?.stop()
        ioManager = null
        runCatching { port?.close() }
        port = null
        synchronized(receiveBuffer) { receiveBuffer.reset() }
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
    }
}
