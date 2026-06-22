package com.ossm.remote.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.BleDevice
import com.ossm.remote.model.DiagnosticsLog
import com.ossm.remote.model.LogLevel
import com.ossm.remote.model.OssmCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@SuppressLint("MissingPermission")
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var usesNus = false

    // Debounce
    private var lastCommandTime = 0L

    // State flows
    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _logs = MutableSharedFlow<DiagnosticsLog>(replay = 200, extraBufferCapacity = 50)
    val logs: SharedFlow<DiagnosticsLog> = _logs.asSharedFlow()

    private val _lastCommand = MutableStateFlow<String>("")
    val lastCommand: StateFlow<String> = _lastCommand.asStateFlow()

    // Scan timeout job
    private var scanJob: Job? = null
    private var watchdogJob: Job? = null

    // ──────────────────────────────────────────────
    //  Scanning
    // ──────────────────────────────────────────────

    fun startScan() {
        if (!hasPermissions()) { log(LogLevel.ERROR, "SCAN", "Permissions BLE manquantes"); return }
        if (bluetoothAdapter?.isEnabled != true) { log(LogLevel.ERROR, "SCAN", "Bluetooth désactivé"); return }

        _scannedDevices.value = emptyList()
        _connectionState.value = BleConnectionState.Scanning
        log(LogLevel.INFO, "SCAN", "Démarrage du scan BLE")

        val scanFilters = buildScanFilters()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanJob?.cancel()
        scanJob = scope.launch {
            bleScanner?.startScan(scanFilters, scanSettings, scanCallback)
            delay(BleConstants.SCAN_TIMEOUT_MS)
            stopScan()
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
                log(LogLevel.INFO, "SCAN", "Scan terminé — ${_scannedDevices.value.size} appareils trouvés")
            }
        }
    }

    fun stopScan() {
        bleScanner?.stopScan(scanCallback)
        scanJob?.cancel()
        if (_connectionState.value is BleConnectionState.Scanning) {
            _connectionState.value = BleConnectionState.Disconnected
        }
    }

    private fun buildScanFilters(): List<ScanFilter> {
        val filters = mutableListOf<ScanFilter>()
        // Service UUID filters
        filters.add(ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(BleConstants.OSSM_SERVICE_UUID)).build())
        filters.add(ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(BleConstants.NUS_SERVICE_UUID)).build())
        // Name prefix filters
        BleConstants.OSSM_NAME_PREFIXES.forEach { prefix ->
            try { filters.add(ScanFilter.Builder().setDeviceName(prefix).build()) } catch (_: Exception) {}
        }
        return filters
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Inconnu"
            val address = device.address
            val rssi = result.rssi
            val isOssm = BleConstants.OSSM_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) } ||
                    result.scanRecord?.serviceUuids?.any {
                        it.uuid == BleConstants.OSSM_SERVICE_UUID || it.uuid == BleConstants.NUS_SERVICE_UUID
                    } == true

            val bleDevice = BleDevice(name, address, rssi, isOssm)
            val current = _scannedDevices.value.toMutableList()
            val idx = current.indexOfFirst { it.address == address }
            if (idx >= 0) current[idx] = bleDevice else current.add(bleDevice)
            _scannedDevices.value = current.sortedByDescending { it.rssi }
            log(LogLevel.DEBUG, "SCAN", "Trouvé: $name ($address) RSSI=$rssi OSSM=$isOssm")
        }

        override fun onScanFailed(errorCode: Int) {
            log(LogLevel.ERROR, "SCAN", "Scan échoué, code=$errorCode")
            _connectionState.value = BleConnectionState.Error("Scan échoué ($errorCode)")
        }
    }

    // ──────────────────────────────────────────────
    //  Connection
    // ──────────────────────────────────────────────

    fun connect(address: String) {
        stopScan()
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: run {
            log(LogLevel.ERROR, "CONNECT", "Appareil introuvable: $address")
            return
        }
        val name = device.name ?: address
        _connectionState.value = BleConnectionState.Connecting(name)
        log(LogLevel.INFO, "CONNECT", "Connexion à $name ($address)")

        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        sendStop()
        gatt?.disconnect()
        log(LogLevel.INFO, "CONNECT", "Déconnexion demandée")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceName = gatt.device.name ?: gatt.device.address
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    log(LogLevel.INFO, "GATT", "Connecté à $deviceName, découverte services...")
                    gatt.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = _connectionState.value is BleConnectionState.Connected
                    writeCharacteristic = null
                    notifyCharacteristic = null
                    watchdogJob?.cancel()
                    if (wasConnected) {
                        log(LogLevel.WARNING, "GATT", "Déconnexion inattendue de $deviceName")
                        _connectionState.value = BleConnectionState.EmergencyStop
                        scope.launch {
                            delay(2000)
                            _connectionState.value = BleConnectionState.Disconnected
                        }
                    } else {
                        _connectionState.value = BleConnectionState.Disconnected
                    }
                    this@BleManager.gatt?.close()
                    this@BleManager.gatt = null
                    log(LogLevel.INFO, "GATT", "État: Déconnecté (status=$status)")
                }
                status != BluetoothGatt.GATT_SUCCESS -> {
                    log(LogLevel.ERROR, "GATT", "Erreur connexion status=$status")
                    _connectionState.value = BleConnectionState.Error("GATT error $status")
                    gatt.close()
                    this@BleManager.gatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log(LogLevel.ERROR, "GATT", "Découverte services échouée: $status")
                _connectionState.value = BleConnectionState.Error("Services non trouvés")
                return
            }

            log(LogLevel.INFO, "GATT", "Services découverts:")
            gatt.services.forEach { svc ->
                log(LogLevel.DEBUG, "GATT", "  Service: ${svc.uuid}")
                svc.characteristics.forEach { char ->
                    log(LogLevel.DEBUG, "GATT", "    Char: ${char.uuid} props=${char.properties}")
                }
            }

            // Try primary OSSM service first
            val ossmService = gatt.getService(BleConstants.OSSM_SERVICE_UUID)
            val nusService  = gatt.getService(BleConstants.NUS_SERVICE_UUID)

            when {
                ossmService != null -> {
                    usesNus = false
                    writeCharacteristic = ossmService.getCharacteristic(BleConstants.POSITION_CHAR_UUID)
                        ?: ossmService.getCharacteristic(BleConstants.SPEED_CHAR_UUID)
                    notifyCharacteristic = ossmService.getCharacteristic(BleConstants.STATUS_CHAR_UUID)
                    log(LogLevel.INFO, "GATT", "Protocol: OSSM natif")
                }
                nusService != null -> {
                    usesNus = true
                    writeCharacteristic = nusService.getCharacteristic(BleConstants.NUS_TX_CHAR_UUID)
                    notifyCharacteristic = nusService.getCharacteristic(BleConstants.NUS_RX_CHAR_UUID)
                    log(LogLevel.INFO, "GATT", "Protocol: Nordic UART Service")
                }
                else -> {
                    // Try any writable characteristic as fallback
                    gatt.services.flatMap { it.characteristics }
                        .firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 }
                        ?.let { writeCharacteristic = it }
                    log(LogLevel.WARNING, "GATT", "Service OSSM non trouvé, tentative caractéristique générique")
                }
            }

            if (writeCharacteristic == null) {
                log(LogLevel.ERROR, "GATT", "Aucune caractéristique d'écriture trouvée")
                _connectionState.value = BleConnectionState.Error("Périphérique incompatible")
                return
            }

            // Enable notifications
            notifyCharacteristic?.let { enableNotifications(gatt, it) }

            val deviceName = gatt.device.name ?: gatt.device.address
            _connectionState.value = BleConnectionState.Connected(deviceName, gatt.device.address)
            log(LogLevel.INFO, "GATT", "Connecté et prêt: $deviceName")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val msg = value.toString(Charsets.UTF_8).trim()
            log(LogLevel.DEBUG, "NOTIFY", "Reçu: $msg")
        }

        @Deprecated("Used for API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val msg = characteristic.value?.toString(Charsets.UTF_8)?.trim() ?: ""
            log(LogLevel.DEBUG, "NOTIFY", "Reçu: $msg")
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log(LogLevel.ERROR, "WRITE", "Écriture échouée status=$status, envoi STOP")
                emergencyStop()
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(BleConstants.CCCD_UUID)
        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Command sending
    // ──────────────────────────────────────────────

    fun sendCommand(command: OssmCommand) {
        val now = System.currentTimeMillis()
        if (now - lastCommandTime < BleConstants.DEBOUNCE_MIN_MS && command !is OssmCommand.Stop) return
        lastCommandTime = now

        val bytes = buildPacket(command)
        writeGatt(bytes)

        val label = when (command) {
            is OssmCommand.Stop -> "STOP"
            is OssmCommand.Move -> "Move speed=${command.command.speed} depth=${command.command.depth}"
            is OssmCommand.Pattern -> "Pattern ${command.id}"
            is OssmCommand.Position -> "Pos=${command.position} speed=${command.speed}"
        }
        _lastCommand.value = label
        log(LogLevel.INFO, "CMD", label)
    }

    private fun buildPacket(command: OssmCommand): ByteArray {
        return if (usesNus) {
            // NUS: text protocol
            when (command) {
                is OssmCommand.Stop -> "stop\n".toByteArray()
                is OssmCommand.Move -> {
                    val s = (command.command.speed * 100).toInt().coerceIn(0, 100)
                    val d = (command.command.depth * 100).toInt().coerceIn(0, 100)
                    val l = (command.command.strokeLength * 100).toInt().coerceIn(0, 100)
                    "spd:$s;dep:$d;str:$l\n".toByteArray()
                }
                is OssmCommand.Pattern -> "pat:${command.id}\n".toByteArray()
                is OssmCommand.Position -> {
                    val p = (command.position * 100).toInt().coerceIn(0, 100)
                    val s = (command.speed * 100).toInt().coerceIn(0, 100)
                    "pos:$p;spd:$s\n".toByteArray()
                }
            }
        } else {
            // OSSM native: binary protocol [cmd, speed, position, depth, strokeLen, sensation]
            when (command) {
                is OssmCommand.Stop -> byteArrayOf(0x00, 0, 0, 0, 0, 0)
                is OssmCommand.Move -> byteArrayOf(
                    0x01,
                    (command.command.speed * 255).toInt().coerceIn(0, 255).toByte(),
                    (command.command.depth * 255).toInt().coerceIn(0, 255).toByte(),
                    (command.command.strokeLength * 255).toInt().coerceIn(0, 255).toByte(),
                    (command.command.sensation * 255).toInt().coerceIn(0, 255).toByte(),
                    0
                )
                is OssmCommand.Pattern -> byteArrayOf(0x02, command.id.toByte(), 0, 0, 0, 0)
                is OssmCommand.Position -> byteArrayOf(
                    0x03,
                    (command.speed * 255).toInt().coerceIn(0, 255).toByte(),
                    (command.position * 255).toInt().coerceIn(0, 255).toByte(),
                    0, 0, 0
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeGatt(bytes: ByteArray) {
        val gatt = gatt ?: return
        val char = writeCharacteristic ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    fun sendStop() {
        val bytes = if (usesNus) "stop\n".toByteArray() else byteArrayOf(0x00, 0, 0, 0, 0, 0)
        writeGatt(bytes)
        _lastCommand.value = "STOP"
        log(LogLevel.WARNING, "CMD", "STOP envoyé")
    }

    fun emergencyStop() {
        sendStop()
        _connectionState.value = BleConnectionState.EmergencyStop
        log(LogLevel.ERROR, "SAFETY", "ARRÊT D'URGENCE")
        scope.launch {
            delay(3000)
            if (_connectionState.value is BleConnectionState.EmergencyStop) {
                _connectionState.value = if (gatt != null) {
                    val name = gatt?.device?.name ?: "OSSM"
                    BleConnectionState.Connected(name, gatt?.device?.address ?: "")
                } else BleConnectionState.Disconnected
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    fun isBluetoothEnabled() = bluetoothAdapter?.isEnabled == true

    private fun hasPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun log(level: LogLevel, tag: String, message: String) {
        scope.launch {
            _logs.emit(DiagnosticsLog(level = level, tag = tag, message = message))
        }
    }
}
