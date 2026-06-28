package com.ossm.remote.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.BleDevice
import com.ossm.remote.model.DiagnosticsLog
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.KnownSimplePenetrationPattern
import com.ossm.remote.model.KnownStrokeEnginePattern
import com.ossm.remote.model.KnownStrokeEnginePatterns
import com.ossm.remote.model.LogLevel
import com.ossm.remote.model.MachineState
import com.ossm.remote.model.OssmCommand
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.PatternControlMode
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Singleton
@SuppressLint("MissingPermission")
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val bleScanner get() = bluetoothAdapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var patternListCharacteristic: BluetoothGattCharacteristic? = null
    private var lastCommandTime = 0L
    private var emergencyRestoreJob: Job? = null
    private var scanJob: Job? = null

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _logs = MutableSharedFlow<DiagnosticsLog>(replay = 200, extraBufferCapacity = 50)
    val logs: SharedFlow<DiagnosticsLog> = _logs.asSharedFlow()

    private val _lastCommand = MutableStateFlow("")
    val lastCommand: StateFlow<String> = _lastCommand.asStateFlow()

    private val _availablePatterns = MutableStateFlow(KnownFallbackPatterns)
    val availablePatterns: StateFlow<List<OssmPattern>> = _availablePatterns.asStateFlow()

    private val _machineState = MutableStateFlow(MachineState())
    val machineState: StateFlow<MachineState> = _machineState.asStateFlow()

    fun startScan() {
        if (!hasPermissions()) {
            log(LogLevel.ERROR, "SCAN", "Permissions BLE manquantes")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            log(LogLevel.ERROR, "SCAN", "Bluetooth désactivé")
            return
        }

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
                log(LogLevel.INFO, "SCAN", "Scan terminé - ${_scannedDevices.value.size} appareils trouvés")
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
        filters.add(ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(BleConstants.OSSM_SERVICE_UUID)).build())
        filters.add(ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(BleConstants.NUS_SERVICE_UUID)).build())
        BleConstants.OSSM_NAME_PREFIXES.forEach { prefix ->
            try {
                filters.add(ScanFilter.Builder().setDeviceName(prefix).build())
            } catch (_: Exception) {
            }
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
                    commandCharacteristic = null
                    notifyCharacteristic = null
                    patternListCharacteristic = null
                    emergencyRestoreJob?.cancel()
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
                    _availablePatterns.value = KnownFallbackPatterns
                    _machineState.value = MachineState()
                    log(LogLevel.INFO, "GATT", "Déconnecté (status=$status)")
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

            val ossmService = gatt.getService(BleConstants.OSSM_SERVICE_UUID)
            val nusService = gatt.getService(BleConstants.NUS_SERVICE_UUID)

            when {
                ossmService != null -> {
                    commandCharacteristic = ossmService.getCharacteristic(BleConstants.COMMAND_CHAR_UUID)
                    notifyCharacteristic = ossmService.getCharacteristic(BleConstants.STATE_CHAR_UUID)
                    patternListCharacteristic = ossmService.getCharacteristic(BleConstants.PATTERN_LIST_UUID)
                    log(LogLevel.INFO, "GATT", "Protocol: OSSM natif (text)")
                }
                nusService != null -> {
                    commandCharacteristic = nusService.getCharacteristic(BleConstants.NUS_TX_CHAR_UUID)
                    notifyCharacteristic = nusService.getCharacteristic(BleConstants.NUS_RX_CHAR_UUID)
                    patternListCharacteristic = null
                    log(LogLevel.INFO, "GATT", "Protocol: Nordic UART Service")
                }
                else -> {
                    gatt.services.flatMap { it.characteristics }
                        .firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 }
                        ?.let { commandCharacteristic = it }
                    log(LogLevel.WARNING, "GATT", "Service OSSM non trouvé, tentative générique")
                }
            }

            if (commandCharacteristic == null) {
                log(LogLevel.ERROR, "GATT", "Aucune caractéristique d'écriture trouvée")
                _connectionState.value = BleConnectionState.Error("Périphérique incompatible")
                return
            }

            notifyCharacteristic?.let { enableNotifications(gatt, it) }

            val deviceName = gatt.device.name ?: gatt.device.address
            _connectionState.value = BleConnectionState.Connected(deviceName, gatt.device.address)
            log(LogLevel.INFO, "GATT", "Connecté et prêt: $deviceName")

            // Negotiate larger MTU so JSON state notifications fit in a single BLE packet.
            gatt.requestMtu(247)

            // Ask for the fastest BLE connection interval (~7.5-15ms vs default ~30-50ms).
            // This is the single biggest software win for live sync/responsiveness — every
            // set:/stream: write reaches the machine ~3-5× sooner.
            try {
                val ok = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                log(LogLevel.INFO, "GATT", "Priorité connexion HIGH demandée: $ok")
            } catch (e: Exception) {
                log(LogLevel.WARNING, "GATT", "requestConnectionPriority échoué: ${e.message}")
            }

            // Force the physical speed knob to "independent" mode so BLE-set speed
            // is not capped by the knob position.
            scope.launch {
                delay(400)
                val speedKnobChar = ossmService?.getCharacteristic(BleConstants.SPEED_KNOB_UUID)
                if (speedKnobChar != null) {
                    val bytes = "false".toByteArray(Charsets.UTF_8)
                    val writeType = if (speedKnobChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(speedKnobChar, bytes, writeType)
                    } else {
                        @Suppress("DEPRECATION") speedKnobChar.value = bytes
                        @Suppress("DEPRECATION") speedKnobChar.writeType = writeType
                        @Suppress("DEPRECATION") gatt.writeCharacteristic(speedKnobChar)
                    }
                    log(LogLevel.INFO, "GATT", "Speed knob → independent (BLE speed non plafonné)")
                }
                // No forced mode entry on connect: the machine keeps the homing/state the user
                // left it in (e.g. after a physical restart-homing). The user picks a pattern when
                // ready — that's when we navigate. Forcing go:strokeEngine here re-triggered an
                // unsolicited mode entry / preflight that could disturb the existing homing.
            }

            if (patternListCharacteristic != null) {
                gatt.readCharacteristic(patternListCharacteristic)
            } else {
                _availablePatterns.value = KnownFallbackPatterns
                log(LogLevel.WARNING, "GATT", "Liste de patterns indisponible, Stroke Engine seulement")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log(LogLevel.WARNING, "READ", "Lecture échouée ${characteristic.uuid} status=$status")
                return
            }
            handleCharacteristicPayload(characteristic.uuid.toString(), value)
        }

        @Deprecated("Used for API < 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log(LogLevel.WARNING, "READ", "Lecture échouée ${characteristic.uuid} status=$status")
                return
            }
            handleCharacteristicPayload(characteristic.uuid.toString(), characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicPayload(characteristic.uuid.toString(), value)
        }

        @Deprecated("Used for API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristicPayload(characteristic.uuid.toString(), characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log(LogLevel.WARNING, "WRITE", "Écriture échouée status=$status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log(LogLevel.INFO, "GATT", "MTU négocié: $mtu (status=$status)")
            currentMtu = mtu
        }
    }

    @Volatile
    private var currentMtu: Int = 23

    private fun handleCharacteristicPayload(source: String, value: ByteArray) {
        val message = value.toString(Charsets.UTF_8).trim()
        if (message.isBlank()) return

        when {
            source.equals(BleConstants.STATE_CHAR_UUID.toString(), ignoreCase = true) -> {
                parseStateJson(message)?.let { newState ->
                    val prev = _machineState.value
                    _machineState.value = newState
                    if (prev.state != newState.state) {
                        log(LogLevel.INFO, "STATE", "${prev.state} → ${newState.state}")
                    }
                }
            }
            source.equals(BleConstants.PATTERN_LIST_UUID.toString(), ignoreCase = true) -> {
                val parsed = parsePatternListJson(message)
                if (parsed.isNotEmpty()) {
                    _availablePatterns.value = mergeKnownPatterns(parsed)
                    log(LogLevel.INFO, "PATTERN", "Patterns: ${parsed.joinToString { "${it.id}:${it.name}" }}")
                }
            }
            source.equals(BleConstants.COMMAND_CHAR_UUID.toString(), ignoreCase = true) -> {
                when {
                    message.startsWith("ok:") -> log(LogLevel.DEBUG, "ACK", message)
                    message.startsWith("fail:") -> log(LogLevel.WARNING, "ACK", message)
                    else -> log(LogLevel.DEBUG, "NOTIFY", "[CMD] $message")
                }
            }
            else -> log(LogLevel.DEBUG, "NOTIFY", "[$source] $message")
        }
    }

    private var lastStateParseErrorMs: Long = 0L

    private fun parseStateJson(payload: String): MachineState? {
        return try {
            val obj = JsonParser.parseString(payload).asJsonObject
            MachineState(
                state = obj.get("state")?.takeIf { !it.isJsonNull }?.asString ?: "unknown",
                speed = obj.get("speed")?.takeIf { !it.isJsonNull }?.asInt,
                stroke = obj.get("stroke")?.takeIf { !it.isJsonNull }?.asInt,
                sensation = obj.get("sensation")?.takeIf { !it.isJsonNull }?.asInt,
                depth = obj.get("depth")?.takeIf { !it.isJsonNull }?.asInt,
                pattern = obj.get("pattern")?.takeIf { !it.isJsonNull }?.asInt
            )
        } catch (e: Exception) {
            // Truncated JSON is normal when MTU < payload size. Throttle log to once / 5s.
            val now = System.currentTimeMillis()
            if (now - lastStateParseErrorMs > 5000) {
                lastStateParseErrorMs = now
                val preview = payload.take(64).replace("\n", " ")
                log(LogLevel.WARNING, "STATE", "JSON tronqué (MTU=$currentMtu): $preview")
            }
            null
        }
    }

    private fun parsePatternListJson(payload: String): List<OssmPattern> {
        return try {
            val array = JsonParser.parseString(payload).asJsonArray
            array.mapNotNull { element ->
                val obj = element.asJsonObject
                val idx = obj.get("idx")?.takeIf { !it.isJsonNull }?.asInt ?: return@mapNotNull null
                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "Pattern $idx"
                val key = sanitizePatternKey(name) + "_$idx"
                OssmPattern(
                    key = key,
                    name = name,
                    mode = PatternControlMode.STROKE_ENGINE,
                    id = idx
                )
            }
        } catch (e: Exception) {
            log(LogLevel.WARNING, "PATTERN", "JSON invalide: ${e.message}")
            emptyList()
        }
    }

    private fun mergeKnownPatterns(devicePatterns: List<OssmPattern>): List<OssmPattern> {
        val deviceById = devicePatterns.associateBy { it.id }
        val updatedStrokeEngine = KnownStrokeEnginePatterns.map { fallback ->
            val fromDevice = deviceById[fallback.id]
            if (fromDevice != null) fallback.copy(name = fromDevice.name) else fallback
        }
        return listOf(KnownSimplePenetrationPattern) + updatedStrokeEngine
    }

    private fun sanitizePatternKey(raw: String): String {
        return raw
            .trim()
            .replace(" ", "")
            .replace(Regex("[^A-Za-z0-9_-]"), "")
            .ifBlank { "pattern" }
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

    private var lastStreamPos: Int = -1

    fun sendCommand(command: OssmCommand) {
        if (command is OssmCommand.Stream) {
            val rawPos = command.positionPercent.coerceIn(0, 100)
            // Firmware convention: stream:0 = mechanically extended forward (-measuredStrokeSteps),
            // stream:100 = retracted home (0). User slider: top 100% = forward, bottom 0% = home.
            // So we invert. Clamp the forward end to 3 (target ≈ -0.97M) so the actuator never
            // commands the exact mechanical limit and bumps; home (100→0) is the calibrated
            // reference and is safe to reach fully.
            val pos = (100 - rawPos).coerceIn(3, 100)
            // Firmware crashes on division-by-zero if two consecutive stream commands have
            // the same position (streaming.cpp line 57: direction = distance/abs(distance)).
            if (pos == lastStreamPos) return
            lastStreamPos = pos
            val t = command.timeMs.coerceAtLeast(1)
            writeRaw("stream:$pos:$t")
            _lastCommand.value = "stream:$pos:$t (slider=$rawPos%)"
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastCommandTime < BleConstants.DEBOUNCE_MIN_MS && command !is OssmCommand.Stop) return
        lastCommandTime = now

        when (command) {
            is OssmCommand.Stop -> {
                writeRaw("set:speed:0")
                _lastCommand.value = "set:speed:0"
                log(LogLevel.INFO, "CMD", "set:speed:0")
            }
            is OssmCommand.ActivatePattern -> {
                val pattern = command.pattern
                when (pattern.mode) {
                    PatternControlMode.SIMPLE_PENETRATION -> {
                        scope.launch {
                            writeRaw("set:speed:0")
                            delay(60)
                            writeRaw("go:menu")
                            delay(800)
                            writeRaw("go:simplePenetration")
                        }
                        _lastCommand.value = "menu → simplePenetration"
                        log(LogLevel.INFO, "CMD", "menu → simplePenetration")
                    }
                    PatternControlMode.STROKE_ENGINE, PatternControlMode.PROGRESSIVE -> {
                        val id = pattern.id ?: 0
                        scope.launch {
                            writeRaw("set:speed:0")
                            delay(60)
                            writeRaw("go:menu")
                            delay(800)
                            writeRaw("go:strokeEngine")
                            delay(120)
                            writeRaw("set:pattern:$id")
                        }
                        val label = "menu → strokeEngine pattern=$id (${pattern.name})"
                        _lastCommand.value = label
                        log(LogLevel.INFO, "CMD", label)
                    }
                    PatternControlMode.STREAMING -> {
                        scope.launch { triggerStreamingEntry() }
                    }
                    PatternControlMode.LAUNCH_ONLY -> {
                        scope.launch {
                            writeRaw("set:speed:0")
                            delay(60)
                            writeRaw("go:menu")
                            delay(800)
                            writeRaw("go:${pattern.key}")
                        }
                        val label = "menu → ${pattern.key}"
                        _lastCommand.value = label
                        log(LogLevel.INFO, "CMD", label)
                    }
                }
            }
            is OssmCommand.UpdateStrokeEngine -> {
                val speed = toPercent(command.command.speed)
                val depthMax = toPercent(command.command.depthMax)
                val stroke = toPercent(command.command.depthMax - command.command.depthMin)
                val sensation = toPercent(command.command.sensation)
                scope.launch {
                    writeRaw("set:speed:$speed")
                    delay(40)
                    writeRaw("set:stroke:$stroke")
                    delay(40)
                    writeRaw("set:depth:$depthMax")
                    delay(40)
                    writeRaw("set:sensation:$sensation")
                }
                val label = "spd=$speed stroke=$stroke depth=$depthMax sens=$sensation"
                _lastCommand.value = label
                log(LogLevel.INFO, "CMD", label)
            }
            is OssmCommand.EnterStreaming -> {
                scope.launch {
                    triggerStreamingEntry()
                }
            }
            is OssmCommand.Stream -> {
                // already handled above
            }
        }
    }

    private suspend fun triggerStreamingEntry() {
        lastStreamPos = -1
        // NOTE: this does NOT re-home. Entering streaming keeps the machine's existing homing
        // (firmware: ReturnToMenu/emergencyStop does forceStop+disableOutputs but leaves
        // calibration.isHomed = true). We just navigate menu → streaming as fast as possible to
        // minimise the window where the motor is disabled (less chance of position drift).
        writeRaw("go:menu")
        _lastCommand.value = "go:menu (→ streaming)"
        log(LogLevel.INFO, "CMD", "go:menu (→ streaming)")
        delay(600)
        writeRaw("go:streaming")
        log(LogLevel.INFO, "CMD", "go:streaming")
        delay(400)
        // From firmware streaming_logic.h:
        //   maxStroke   = min(stroke,depth)/100 * measuredStrokeSteps
        //   depthOffset = (measuredStrokeSteps - maxStroke) * depth/100
        //   target(pos) = -(1 - pos/100) * maxStroke - depthOffset
        // Wider live band than v1.13.1: keep the no-rehome entry that preserves homing, but let
        // Live reach almost the full forward depth. stroke=90/depth=95 gives roughly a [-99.5%,
        // -9.5%] physical range: near-bottom travel with a small safety margin instead of the
        // old centered [-75%, -25%] band. speed/sensation must be > 0 or every stream:pos:time
        // is silently dropped.
        writeRaw("set:speed:60")
        delay(60)
        writeRaw("set:sensation:50")
        delay(60)
        writeRaw("set:stroke:90")
        delay(60)
        writeRaw("set:depth:95")
        log(LogLevel.INFO, "CMD", "Streaming setup: speed=60 sens=50 stroke=90 depth=95")
    }

    @SuppressLint("MissingPermission")
    private fun writeRaw(text: String) {
        val activeGatt = gatt ?: return
        val char = commandCharacteristic ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        // Prefer NO_RESPONSE so rapid sequences (set:speed → set:stroke → set:depth → set:sensation
        // within 40ms) don't get dropped while waiting for ACK of the previous write.
        val writeType = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(char, bytes, writeType)
        } else {
            @Suppress("DEPRECATION")
            char.value = bytes
            @Suppress("DEPRECATION")
            char.writeType = writeType
            @Suppress("DEPRECATION")
            activeGatt.writeCharacteristic(char)
        }
    }

    /** Lightweight single-parameter write for real-time slider dragging (no 4-write sequence). */
    fun liveSet(param: String, percent: Int) {
        val p = percent.coerceIn(0, 100)
        writeRaw("set:$param:$p")
        _lastCommand.value = "set:$param:$p"
    }

    /** Change the active stroke-engine pattern in place (already in strokeEngine, no menu round-trip). */
    fun setStrokeEnginePattern(id: Int) {
        writeRaw("set:pattern:$id")
        _lastCommand.value = "set:pattern:$id"
        log(LogLevel.INFO, "CMD", "set:pattern:$id (chaos)")
    }

    fun sendStop() {
        writeRaw("set:speed:0")
        _lastCommand.value = "set:speed:0"
        log(LogLevel.WARNING, "CMD", "STOP envoyé")
    }

    fun home(targetPage: String = "strokeEngine", patternId: Int = 0) {
        scope.launch {
            log(LogLevel.INFO, "HOME", "Calibration demandée: menu → $targetPage")
            triggerHomingCycle(targetPage, patternId)
        }
    }

    private suspend fun triggerHomingCycle(targetPage: String, patternId: Int) {
        _machineState.value = _machineState.value.copy(state = "homing")
        writeRaw("set:speed:0")
        delay(80)
        writeRaw("go:menu")
        _lastCommand.value = "go:menu (homing)"
        log(LogLevel.INFO, "CMD", "go:menu")
        delay(1500)
        writeRaw("go:$targetPage")
        log(LogLevel.INFO, "CMD", "go:$targetPage")
        if (targetPage == "strokeEngine") {
            delay(200)
            writeRaw("set:pattern:$patternId")
            log(LogLevel.INFO, "CMD", "set:pattern:$patternId")
        }
        delay(800)
        // Best-effort: assume ready after the firmware's preflight window
        if (_machineState.value.state.equals("homing", ignoreCase = true)) {
            _machineState.value = _machineState.value.copy(state = targetPage)
        }
    }

    fun emergencyStop() {
        sendStop()
        if (_connectionState.value is BleConnectionState.EmergencyStop) return
        _connectionState.value = BleConnectionState.EmergencyStop
        log(LogLevel.ERROR, "SAFETY", "ARRÊT D'URGENCE")
        emergencyRestoreJob?.cancel()
        emergencyRestoreJob = scope.launch {
            delay(3000)
            if (_connectionState.value is BleConnectionState.EmergencyStop) {
                _connectionState.value = if (gatt != null) {
                    val name = gatt?.device?.name ?: "OSSM"
                    BleConnectionState.Connected(name, gatt?.device?.address ?: "")
                } else {
                    BleConnectionState.Disconnected
                }
            }
        }
    }

    fun isBluetoothEnabled() = bluetoothAdapter?.isEnabled == true

    private fun hasPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun toPercent(value: Float): Int = (value * 100f).toInt().coerceIn(0, 100)

    private fun log(level: LogLevel, tag: String, message: String) {
        scope.launch {
            _logs.emit(DiagnosticsLog(level = level, tag = tag, message = message))
        }
    }
}
