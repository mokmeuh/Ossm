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
import com.ossm.remote.model.KnownAutoRandomPattern
import com.ossm.remote.model.KnownProgressivePattern
import com.ossm.remote.model.KnownStreamingPattern
import com.ossm.remote.model.KnownSimplePenetrationPattern
import com.ossm.remote.model.KnownStrokeEnginePattern
import com.ossm.remote.model.KnownStrokeEnginePatterns
import com.ossm.remote.model.LogLevel
import com.ossm.remote.model.MachineState
import com.ossm.remote.model.OssmCommand
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.PatternControlMode
import com.ossm.remote.model.StrokeEngineCommand
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private var speedKnobCharacteristic: BluetoothGattCharacteristic? = null
    // Vrai quand la séquence post-connexion (MTU → CCCD → lectures/config) est finie.
    @Volatile private var postSetupDone = false
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

    // Vrai uniquement quand la machine a CONFIRMÉ (via l'état NOTIFY) être en mode
    // streaming avec stroke=100/depth=100 — c.-à-d. que le mapping linéaire
    // « slider 0-100 = home→fond » est effectif. Tant que c'est faux, aucun
    // stream:<pos>:<time> ne part (la bande de course serait imprévisible).
    private val _streamingReady = MutableStateFlow(false)
    val streamingReady: StateFlow<Boolean> = _streamingReady.asStateFlow()

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
            // Code 1 (SCAN_FAILED_ALREADY_STARTED) : un scan précédent avec le même
            // callback est encore enregistré — on l'arrête toujours avant de relancer.
            try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}
            delay(150)
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
        healthCheckJob?.cancel()
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: run {
            log(LogLevel.ERROR, "CONNECT", "Appareil introuvable: $address")
            return
        }
        val name = device.name ?: address
        _connectionState.value = BleConnectionState.Connecting(name)
        log(LogLevel.INFO, "CONNECT", "Connexion à $name ($address)")

        // A half-closed previous gatt (disconnect() never called on it, only close()) is a
        // known Android BLE trap: the native stack can keep believing a connection is live,
        // and the next connectGatt() then silently fails or hangs. Always tear down fully first.
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
        commandCharacteristic = null
        speedKnobCharacteristic = null
        postSetupDone = false
        notifyCharacteristic = null
        patternListCharacteristic = null
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        sendStop()
        healthCheckJob?.cancel()
        gatt?.disconnect()
        log(LogLevel.INFO, "CONNECT", "Déconnexion demandée")
    }

    // ---- Silent-connection-loss detection ----
    // Some OSSM idle timeouts / range drops never trigger onConnectionStateChange promptly (or
    // at all) — the app then shows "Connected" forever while every write silently goes nowhere,
    // and a fresh connect() attempt fights the stale native connection. We periodically ping the
    // link (readRemoteRssi is cheap and side-effect-free); if it goes unanswered a few times in a
    // row, we force a clean reset back to Disconnected so the user can reconnect normally.
    private var healthCheckJob: Job? = null
    private var lastRssiReplyMs: Long = 0L

    private fun startConnectionHealthCheck() {
        healthCheckJob?.cancel()
        lastRssiReplyMs = System.currentTimeMillis()
        healthCheckJob = scope.launch {
            var misses = 0
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                val activeGatt = gatt
                if (activeGatt == null || _connectionState.value !is BleConnectionState.Connected) break
                val requested = try { activeGatt.readRemoteRssi() } catch (e: Exception) { false }
                delay(HEALTH_CHECK_REPLY_TIMEOUT_MS)
                val staleFor = System.currentTimeMillis() - lastRssiReplyMs
                if (!requested || staleFor > HEALTH_CHECK_INTERVAL_MS + HEALTH_CHECK_REPLY_TIMEOUT_MS) {
                    misses++
                    log(LogLevel.WARNING, "GATT", "Ping connexion sans réponse ($misses/$HEALTH_CHECK_MAX_MISSES)")
                } else {
                    misses = 0
                }
                if (misses >= HEALTH_CHECK_MAX_MISSES) {
                    log(LogLevel.ERROR, "GATT", "Connexion perdue silencieusement — réinitialisation")
                    forceResetConnection()
                    break
                }
            }
        }
    }

    private fun forceResetConnection() {
        healthCheckJob?.cancel()
        streamingEntryJob?.cancel()
        _streamingReady.value = false
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
        commandCharacteristic = null
        speedKnobCharacteristic = null
        postSetupDone = false
        notifyCharacteristic = null
        patternListCharacteristic = null
        _availablePatterns.value = KnownFallbackPatterns
        _machineState.value = MachineState()
        _connectionState.value = BleConnectionState.Disconnected
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
                    speedKnobCharacteristic = null
                    postSetupDone = false
                    notifyCharacteristic = null
                    patternListCharacteristic = null
                    emergencyRestoreJob?.cancel()
                    healthCheckJob?.cancel()
                    streamingEntryJob?.cancel()
                    _streamingReady.value = false
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
                    healthCheckJob?.cancel()
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

            speedKnobCharacteristic = ossmService?.getCharacteristic(BleConstants.SPEED_KNOB_UUID)

            val deviceName = gatt.device.name ?: gatt.device.address
            _connectionState.value = BleConnectionState.Connected(deviceName, gatt.device.address)
            log(LogLevel.INFO, "GATT", "Connecté et prêt: $deviceName")
            startConnectionHealthCheck()

            // requestConnectionPriority n'occupe pas la file GATT — sûr ici.
            try {
                val ok = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                log(LogLevel.INFO, "GATT", "Priorité connexion HIGH demandée: $ok")
            } catch (e: Exception) {
                log(LogLevel.WARNING, "GATT", "requestConnectionPriority échoué: ${e.message}")
            }

            // SÉQUENÇAGE GATT STRICT (Android n'a PAS de file d'attente : toute
            // opération lancée pendant qu'une autre est en vol est perdue) :
            //   1. requestMtu(247)  → onMtuChanged
            //   2. abonnement notifications (CCCD) → onDescriptorWrite
            //   3. lecture liste patterns + config bouton vitesse
            // L'ancien code faisait CCCD puis MTU en même temps : le MTU restait à 23
            // et chaque notification d'état arrivait TRONQUÉE (JSON illisible) → l'app
            // ne connaissait jamais l'état machine (cause du Live « Préparation » infini).
            if (!gatt.requestMtu(247)) {
                log(LogLevel.WARNING, "GATT", "requestMtu refusé — enchaîne sans MTU étendu")
                finishSetupAfterMtu(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log(LogLevel.INFO, "GATT", "MTU négocié: $mtu (status=$status)")
            currentMtu = mtu
            finishSetupAfterMtu(gatt)
        }

        // Étape 2/3 du séquençage : notifications après le MTU, puis le reste après
        // la confirmation du CCCD (onDescriptorWrite).
        private fun finishSetupAfterMtu(gatt: BluetoothGatt) {
            val notifyChar = notifyCharacteristic
            if (notifyChar != null) {
                enableNotifications(gatt, notifyChar)
                // onDescriptorWrite enchaînera. Filet de sécurité si jamais il ne vient pas :
                scope.launch {
                    delay(2_000)
                    if (!postSetupDone) finishSetupAfterCccd(gatt)
                }
            } else {
                finishSetupAfterCccd(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log(LogLevel.DEBUG, "GATT", "CCCD écrit (status=$status)")
            finishSetupAfterCccd(gatt)
        }

        private fun finishSetupAfterCccd(gatt: BluetoothGatt) {
            if (postSetupDone) return
            postSetupDone = true
            scope.launch {
                delay(200)
                if (patternListCharacteristic != null) {
                    gatt.readCharacteristic(patternListCharacteristic)
                } else {
                    _availablePatterns.value = KnownFallbackPatterns
                    log(LogLevel.WARNING, "GATT", "Liste de patterns indisponible, Stroke Engine seulement")
                }
                delay(500)
                // Bouton vitesse → indépendant (réécrit aussi à chaque entrée streaming).
                writeSpeedKnobIndependent()
                // No forced mode entry on connect: the machine keeps the homing/state the
                // user left it in. The user picks a pattern when ready.
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

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                lastRssiReplyMs = System.currentTimeMillis()
            }
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
                    // La machine a quitté le streaming (menu, erreur, long-press physique…):
                    // le mapping live n'est plus garanti, on désarme le pad.
                    if (_streamingReady.value && !newState.state.contains("streaming", ignoreCase = true)) {
                        _streamingReady.value = false
                        log(LogLevel.WARNING, "STATE", "Sortie du streaming — live désarmé")
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
                pattern = obj.get("pattern")?.takeIf { !it.isJsonNull }?.asInt,
                positionMm = obj.get("position")?.takeIf { !it.isJsonNull }?.asFloat
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
        // Même composition que KnownFallbackPatterns : les modes applicatifs
        // (Auto Random, Progressif, Live) existent TOUJOURS, la liste machine ne
        // fait que rafraîchir les noms des patterns stroke-engine. Simple
        // Penetration reste hors du sélecteur (convention établie).
        return listOf(KnownAutoRandomPattern, KnownProgressivePattern, KnownStreamingPattern) + updatedStrokeEngine
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
            // Jamais de position tant que le setup streaming n'est pas vérifié :
            // sans stroke=100/depth=100 confirmés, la bande de course est imprévisible
            // (cause du bug « slider 0-100 ≠ 0-100 du home »).
            if (!_streamingReady.value) return
            val rawPos = command.positionPercent.coerceIn(0, 100)
            // MAPPING DIRECT (doc officielle OSSM + report utilisateur v1.24.2) :
            // stream:0 = home/rétracté, stream:100 = fond. Le pad est au repos à
            // logicalPos 0 → on veut le HOME, donc pos = rawPos (slider 0 % = home →
            // stream:2 ; slider 100 % = fond → stream:98). L'INVERSION (100-rawPos) de
            // la v1.21.4 envoyait le chariot au FOND au repos (gros coup). Marge 2 %
            // aux deux butées.
            val pos = rawPos.coerceIn(2, 98)
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
                        scope.launch { navigateToMode("simplePenetration") }
                        _lastCommand.value = "menu → simplePenetration"
                        log(LogLevel.INFO, "CMD", "menu → simplePenetration")
                    }
                    PatternControlMode.STROKE_ENGINE, PatternControlMode.PROGRESSIVE -> {
                        val id = pattern.id ?: 0
                        scope.launch {
                            navigateToMode("strokeEngine")
                            writeRaw("set:pattern:$id")
                        }
                        val label = "menu → strokeEngine pattern=$id (${pattern.name})"
                        _lastCommand.value = label
                        log(LogLevel.INFO, "CMD", label)
                    }
                    PatternControlMode.STREAMING -> {
                        launchStreamingEntry()
                    }
                    PatternControlMode.AUTO_RANDOM -> {
                        log(LogLevel.INFO, "CMD", "Auto Random selected in app; waiting for mix start")
                    }
                    PatternControlMode.LAUNCH_ONLY -> {
                        scope.launch { navigateToMode(pattern.key) }
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
                    // CRITICAL ORDER: depth BEFORE stroke. The stroke engine oscillates between
                    // (depth - stroke) and depth, so `depth` is the DEEP end of the travel. If we
                    // lowered stroke first while depth was still at its previous (often 100%)
                    // value, the machine would briefly run to [depth-newStroke, depth] — i.e.
                    // slam to full depth ("au fond") until depth caught up. Setting the deep end
                    // first can never overshoot.
                    writeRaw("set:depth:$depthMax")
                    delay(40)
                    writeRaw("set:stroke:$stroke")
                    delay(40)
                    writeRaw("set:sensation:$sensation")
                }
                val label = "spd=$speed depth=$depthMax stroke=$stroke sens=$sensation"
                _lastCommand.value = label
                log(LogLevel.INFO, "CMD", label)
            }
            is OssmCommand.EnterStreaming -> {
                launchStreamingEntry()
            }
            is OssmCommand.Stream -> {
                // already handled above
            }
        }
    }

    private var streamingEntryJob: Job? = null

    /** Entrée streaming dédupliquée : un seul cycle d'entrée à la fois. */
    /**
     * Navigation menu → mode pilotée par l'ÉTAT RÉEL (remplace les délais aveugles
     * hérités de l'époque où les notifications d'état arrivaient tronquées).
     * Retourne vrai si la machine a confirmé l'état cible.
     */
    private suspend fun navigateToMode(target: String): Boolean {
        writeRaw("set:speed:0")
        delay(60)
        if (!_machineState.value.state.contains("menu", ignoreCase = true)) {
            writeRaw("go:menu")
            if (!awaitMachineState(4_000) { it.state.contains("menu", ignoreCase = true) }) {
                log(LogLevel.WARNING, "CMD", "Menu non confirmé (état=${_machineState.value.state}) — go:$target quand même")
            }
        }
        writeRaw("go:$target")
        val ok = awaitMachineState(8_000) {
            it.state.contains(target, ignoreCase = true) && !it.isPreflight && !it.isHoming
        }
        if (!ok) {
            log(LogLevel.WARNING, "CMD", "$target non confirmé (état=${_machineState.value.state}) — si 'preflight', baisser le bouton vitesse physique")
        }
        return ok
    }

    private fun launchStreamingEntry() {
        if (streamingEntryJob?.isActive == true) return
        streamingEntryJob = scope.launch { triggerStreamingEntry() }
    }

    // Entrée en mode streaming pilotée par l'ÉTAT RÉEL de la machine (doc BLE
    // « operating-modes » : go:streaming se lance depuis le menu ; le firmware se
    // replace en position 0 = home/rétracté puis attend les stream:<pos>:<time>).
    // Les anciens délais aveugles (600/400ms) laissaient parfois les set: partir
    // pendant la transition → rejetés (fail:) → stroke/depth restaient sur la bande
    // du mode précédent et le slider 0-100 ne couvrait plus home→fond. Chaque étape
    // attend désormais la confirmation NOTIFY, et le setup est vérifié puis réessayé.
    // NOTE: pas de re-homing complet (emergencyStop garde isHomed=true côté firmware).
    /**
     * Bouton de vitesse physique → mode "indépendant" : la vitesse BLE (set:speed)
     * est utilisée telle quelle au lieu d'être plafonnée par la position du bouton.
     * Indispensable en streaming : le bouton doit être à ~0 pour ENTRER dans le mode
     * (preflight), donc en mode "limite" la vitesse effective resterait 0 et chaque
     * stream:pos:time serait ignoré → « le Live ne fait rien ».
     */
    @SuppressLint("MissingPermission")
    private fun writeSpeedKnobIndependent() {
        val activeGatt = gatt ?: return
        val char = speedKnobCharacteristic ?: run {
            log(LogLevel.WARNING, "GATT", "Caractéristique speed-knob absente (firmware ancien ?)")
            return
        }
        val bytes = "false".toByteArray(Charsets.UTF_8)
        val writeType = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(char, bytes, writeType)
        } else {
            @Suppress("DEPRECATION") char.value = bytes
            @Suppress("DEPRECATION") char.writeType = writeType
            @Suppress("DEPRECATION") activeGatt.writeCharacteristic(char)
        }
        log(LogLevel.INFO, "GATT", "Speed knob → independent (BLE speed non plafonné)")
    }

    private suspend fun sendStreamingSetup() {
        // speed = PLAFOND de vitesse des moves streaming : 100 pour que la machine
        // puisse suivre les gestes rapides (la vitesse réelle vient de pos+time).
        writeRaw("set:speed:80")
        delay(60)
        writeRaw("set:sensation:50")
        delay(60)
        writeRaw("set:stroke:100")
        delay(60)
        writeRaw("set:depth:100")
        delay(60)
    }

    private fun bandVerified(state: MachineState): Boolean =
        state.state.contains("streaming", ignoreCase = true) &&
            (state.stroke ?: 0) >= 99 && (state.depth ?: 0) >= 99

    private suspend fun triggerStreamingEntry() {
        _streamingReady.value = false
        lastStreamPos = -1

        _lastCommand.value = "go:menu (→ streaming)"
        val inStreaming = navigateToMode("streaming")
        if (!inStreaming) {
            log(LogLevel.ERROR, "CMD", "Entrée streaming échouée — live désarmé")
            return
        }

        // Réécrit la config bouton→indépendant maintenant qu'aucune autre opération
        // GATT n'est en vol (l'écriture faite à la connexion peut avoir été perdue).
        // Sans ça, bouton physique à 0 (requis pour entrer) = vitesse effective 0 =
        // tous les stream:pos:time ignorés.
        writeSpeedKnobIndependent()
        delay(150)

        // Mapping firmware (streaming_logic.h) :
        //   stroke=100 & depth=100 → mapping LINÉAIRE : slider 0% = home, 100% = fond
        //   (seule marge : clamp pos >= 3 dans sendCommand, ~97%).
        // speed/sensation > 0 requis sinon les stream:pos:time sont ignorés (speed est
        // renvoyé possiblement rééchelonné par le bouton physique → pas de gate dessus).
        var verified = awaitMachineState(2_500, ::bandVerified)
        var attempt = 0
        while (!verified && attempt < 3) {
            attempt++
            // L'entrée dans un mode peut réinitialiser les réglages : on repousse le
            // setup une fois DANS streaming, puis on revérifie via l'état NOTIFY.
            sendStreamingSetup()
            verified = awaitMachineState(2_500, ::bandVerified)
            if (!verified) {
                val stt = _machineState.value
                log(
                    LogLevel.WARNING, "CMD",
                    "Bande non confirmée (essai $attempt/3) — état=${stt.state} stroke=${stt.stroke} depth=${stt.depth} speed=${stt.speed}"
                )
            }
        }

        if (verified) {
            _streamingReady.value = true
            _lastCommand.value = "streaming prêt (stroke=100 depth=100)"
            log(LogLevel.INFO, "CMD", "Streaming vérifié : plage live 0-100 = home→fond (speed=${_machineState.value.speed})")
        } else {
            val stt = _machineState.value
            log(
                LogLevel.ERROR, "CMD",
                "Bande jamais confirmée — live désarmé. Machine: état=${stt.state} stroke=${stt.stroke} depth=${stt.depth} speed=${stt.speed}"
            )
        }
    }

    /**
     * Application VÉRIFIÉE des paramètres stroke-engine après un changement de mode.
     * Attendre l'état strokeEngine réel → envoyer (depth AVANT stroke) → vérifier via
     * l'état NOTIFY → réessayer jusqu'à 3 fois. Aucune limite artificielle : on
     * garantit juste que ce que la machine utilise = ce que l'écran affiche.
     */
    fun applyStrokeEngineVerified(command: StrokeEngineCommand) {
        scope.launch {
            val inMode = awaitMachineState(5_000) { it.state.contains("strokeEngine", ignoreCase = true) }
            if (!inMode) {
                log(LogLevel.WARNING, "CMD", "strokeEngine non confirmé (état=${_machineState.value.state}) — envoi des paramètres quand même")
            }
            val speed = toPercent(command.speed)
            val depthMax = toPercent(command.depthMax)
            val stroke = toPercent(command.depthMax - command.depthMin)
            val sensation = toPercent(command.sensation)
            var attempt = 0
            while (attempt < 3) {
                attempt++
                writeRaw("set:speed:$speed")
                delay(40)
                writeRaw("set:depth:$depthMax")
                delay(40)
                writeRaw("set:stroke:$stroke")
                delay(40)
                writeRaw("set:sensation:$sensation")
                val verified = awaitMachineState(2_000) {
                    it.depth == depthMax && it.stroke == stroke
                }
                if (verified) {
                    _lastCommand.value = "params confirmés: depth=$depthMax stroke=$stroke spd=$speed"
                    log(LogLevel.INFO, "CMD", "Paramètres confirmés par la machine : depth=$depthMax stroke=$stroke")
                    return@launch
                }
                val st = _machineState.value
                log(
                    LogLevel.WARNING, "CMD",
                    "Paramètres non confirmés (essai $attempt/3) — machine: depth=${st.depth} stroke=${st.stroke} état=${st.state}"
                )
            }
            log(LogLevel.ERROR, "CMD", "Paramètres JAMAIS confirmés — la machine n'utilise peut-être pas la plage affichée !")
        }
    }

    /** Attend (avec timeout) que l'état machine notifié satisfasse le prédicat. */
    private suspend fun awaitMachineState(timeoutMs: Long, predicate: (MachineState) -> Boolean): Boolean {
        if (predicate(_machineState.value)) return true
        return withTimeoutOrNull(timeoutMs) { machineState.first(predicate) } != null
    }

    @SuppressLint("MissingPermission")
    private fun writeRaw(text: String) {
        val activeGatt = gatt ?: return
        val char = commandCharacteristic ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
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

    /** Écriture mono-paramètre légère pour le drag temps réel des sliders. */
    fun liveSet(param: String, percent: Int) {
        val p = percent.coerceIn(0, 100)
        writeRaw("set:$param:$p")
        _lastCommand.value = "set:$param:$p"
    }

    /** Change le pattern stroke-engine actif sans repasser par le menu. */
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
        // Piloté par l'état réel — plus de faux état « homing » fabriqué côté app
        // (il entrait en conflit avec les vraies notifications depuis le fix MTU).
        _lastCommand.value = "go:menu ($targetPage)"
        val ok = navigateToMode(targetPage)
        if (ok && targetPage == "strokeEngine") {
            writeRaw("set:pattern:$patternId")
            log(LogLevel.INFO, "CMD", "set:pattern:$patternId")
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

    companion object {
        // Détection de perte de connexion silencieuse (ping RSSI périodique).
        private const val HEALTH_CHECK_INTERVAL_MS = 3_000L
        private const val HEALTH_CHECK_REPLY_TIMEOUT_MS = 1_500L
        private const val HEALTH_CHECK_MAX_MISSES = 3
    }
}
