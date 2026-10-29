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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.BleDevice
import com.ossm.remote.model.DiagnosticsLog
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.KnownAppDrivenPatterns
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
import com.ossm.remote.usb.UsbSerialTransport
import com.ossm.remote.usb.UsbControlProtocol
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.UUID

internal enum class KnownDeviceReconnectAction {
    NONE,
    CONNECT_KNOWN_DEVICE,
}

internal fun chooseKnownDeviceReconnectAction(
    hasConnectPermission: Boolean,
    bluetoothEnabled: Boolean,
    hasKnownAddress: Boolean,
    connectionState: BleConnectionState,
): KnownDeviceReconnectAction = if (
    hasConnectPermission &&
        bluetoothEnabled &&
        hasKnownAddress &&
        connectionState.allowsKnownDeviceReconnect()
) {
    KnownDeviceReconnectAction.CONNECT_KNOWN_DEVICE
} else {
    KnownDeviceReconnectAction.NONE
}

internal fun BleConnectionState.allowsKnownDeviceReconnect(): Boolean =
    this is BleConnectionState.Disconnected ||
        this is BleConnectionState.Error ||
        this is BleConnectionState.EmergencyStop

internal fun isCurrentScanGeneration(expected: Long, active: Long): Boolean = expected == active

/**
 * Identifies the one stroke-engine activation whose writes are still allowed to finish.
 *
 * Mode entry performs several delayed, notification-verified writes. A newer selection must
 * invalidate the older sequence before it can publish its completion callback; otherwise an
 * old retry can consume the newer selection's pending speed and re-apply its captured zero.
 */
internal class StrokeEngineActivationController {
    private var nextToken = 0L
    private var activeToken: Long? = null

    @Synchronized
    fun begin(): Long {
        val token = ++nextToken
        activeToken = token
        return token
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = activeToken == token

    @Synchronized
    fun finish(token: Long): Boolean {
        if (activeToken != token) return false
        activeToken = null
        return true
    }

    @Synchronized
    fun cancel() {
        activeToken = null
    }
}

/**
 * Generation fence for phone-owned Live writes. Invalidating a Live gesture must reject
 * queued arm-speed/target writes that have not reached the GATT call, while leaving unrelated
 * setup and scalar writes alone.
 */
internal class LiveWriteGenerationGate {
    @Volatile
    private var generation = 0L

    fun current(): Long = generation

    @Synchronized
    fun invalidate(): Long {
        generation += 1L
        return generation
    }

    fun accepts(candidate: Long?): Boolean = candidate == null || candidate == generation
}

internal fun isStrokeEngineParamsConfirmed(
    state: MachineState,
    depthPercent: Int,
    strokePercent: Int,
): Boolean = state.state.contains("strokeEngine", ignoreCase = true) &&
    state.isReady &&
    state.depth == depthPercent &&
    state.stroke == strokePercent

internal enum class PostCccdAction {
    READ_STATE,
    CONTINUE_SETUP,
    FAIL,
}

internal fun choosePostCccdAction(
    status: Int,
    hasStateCharacteristic: Boolean,
    stateCharacteristicReadable: Boolean,
): PostCccdAction = when {
    status != BluetoothGatt.GATT_SUCCESS -> PostCccdAction.FAIL
    hasStateCharacteristic && stateCharacteristicReadable -> PostCccdAction.READ_STATE
    else -> PostCccdAction.CONTINUE_SETUP
}

internal fun chooseGattWriteType(properties: Int, preferResponse: Boolean): Int = when {
    preferResponse && properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
}

@Singleton
@SuppressLint("MissingPermission")
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usbSerialTransport: UsbSerialTransport,
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
    private var latencyCompensationCharacteristic: BluetoothGattCharacteristic? = null
    private var capabilityCharacteristic: BluetoothGattCharacteristic? = null
    private var liveSmoothCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var liveSmoothCapabilityPayload: ByteArray? = null
    // Vrai quand la séquence post-connexion (MTU → CCCD → lectures/config) est finie.
    @Volatile private var postSetupDone = false
    private val _postSetupReady = MutableStateFlow(false)
    val postSetupReady: StateFlow<Boolean> = _postSetupReady.asStateFlow()
    @Volatile private var postSetupStarted = false
    @Volatile private var stateBootstrapPending = false
    private var stateBootstrapAttempts = 0
    private var stateBootstrapTimeoutJob: Job? = null
    @Volatile private var connectionEpoch = 0L
    private var lastCommandTime = 0L
    private var emergencyRestoreJob: Job? = null
    private var scanJob: Job? = null
    private val strokeEngineActivationLock = Any()
    private val strokeEngineActivationController = StrokeEngineActivationController()
    private var strokeEngineActivationJob: Job? = null
    private val liveDepthRangeUpdates = Channel<LiveDepthRange>(Channel.CONFLATED)
    private val liveTargetUpdates = Channel<OssmCommand.Stream>(Channel.CONFLATED)
    private val liveParameterWrites = LiveParameterCoalescer()
    private val liveParameterSignal = Channel<Unit>(Channel.CONFLATED)
    private val liveTargetScheduler = LiveTargetScheduler()
    private val liveTargetLock = Any()
    private var liveSpeedArmed = false

    private val _linkSuspect = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** 2.3.29: emitted on each missed connection ping (link suspect, not yet reset). */
    val linkSuspectEvents: kotlinx.coroutines.flow.Flow<Unit> = _linkSuspect
    private val normalGattWrites = Channel<GattWriteRequest>(Channel.UNLIMITED)
    private val urgentGattWrites = Channel<GattWriteRequest>(Channel.CONFLATED)
    private val liveWriteGeneration = LiveWriteGenerationGate()
    private val gattReads = Channel<GattReadRequest>(Channel.UNLIMITED)
    @Volatile private var activeWriteCompletion: CompletableDeferred<Int>? = null
    @Volatile private var activeReadCompletion: CompletableDeferred<ByteArray?>? = null
    @VisibleForTesting
    internal var gattWriteAttemptObserver: ((BluetoothGattCharacteristic, ByteArray, Int) -> Unit)? = null
    private var reconnectJob: Job? = null
    @Volatile private var manualDisconnectRequested = false

    private data class GattWriteRequest(
        val epoch: Long,
        val characteristic: BluetoothGattCharacteristic,
        val value: ByteArray,
        val preferResponse: Boolean,
        val completion: CompletableDeferred<Boolean>? = null,
        val liveGeneration: Long? = null,
    )

    private data class GattReadRequest(
        val epoch: Long,
        val characteristic: BluetoothGattCharacteristic,
        val completion: CompletableDeferred<ByteArray?>,
    )

    // Anti-throttle scan Android : le système ignore SILENCIEUSEMENT les startScan()
    // au-delà d'environ 5 appels / 30 s (BLE app scan quota). Symptôme : après une
    // coupure de la machine, les tentatives de reconnexion enchaînent scan→connect
    // échoué→rescan et épuisent le quota → les scans suivants ne renvoient plus RIEN
    // → « recherche » perpétuelle jusqu'au redémarrage de l'app. On tient une fenêtre
    // glissante des démarrages et on attend si on approche la limite.
    private val recentScanStarts = ArrayDeque<Long>()
    @Volatile private var isScanning = false
    @Volatile private var scanGeneration = 0L
    @Volatile private var activeScanCallback: ScanCallback? = null

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

    private val _firmwareRemoteLeaseReadiness = MutableStateFlow(FirmwareRemoteLeaseReadiness())
    val firmwareRemoteLeaseReadiness: StateFlow<FirmwareRemoteLeaseReadiness> =
        _firmwareRemoteLeaseReadiness.asStateFlow()

    private val _firmwareRemoteLeaseDiagnostics =
        MutableSharedFlow<FirmwareRemoteLeaseDiagnostic>(extraBufferCapacity = 8)
    val firmwareRemoteLeaseDiagnostics: SharedFlow<FirmwareRemoteLeaseDiagnostic> =
        _firmwareRemoteLeaseDiagnostics.asSharedFlow()

    // Vrai uniquement quand la machine a CONFIRMÉ (via l'état NOTIFY) être en mode
    // streaming avec stroke=100/depth=100 — c.-à-d. que le mapping linéaire
    // « slider 0-100 = home→fond » est effectif. Tant que c'est faux, aucun
    // stream:<pos>:<time> ne part (la bande de course serait imprévisible).
    private val _streamingReady = MutableStateFlow(false)
    val streamingReady: StateFlow<Boolean> = _streamingReady.asStateFlow()

    init {
        scope.launch { runGattWriteWorker() }
        scope.launch { runLiveTargetWorker() }
        scope.launch {
            for (range in liveDepthRangeUpdates) {
                // Awaited, one pair at a time: a fast depth drag can no longer pile writes
                // into the normal GATT queue (the conflated channel keeps only the newest pair).
                writeRawAndAwait("set:depth:${range.depthPercent}")
                delay(LIVE_DEPTH_RANGE_WRITE_GAP_MS)
                writeRawAndAwait("set:stroke:${range.strokePercent}")
                _lastCommand.value = "set:depth:${range.depthPercent} set:stroke:${range.strokePercent}"
            }
        }
        scope.launch {
            for (signal in liveParameterSignal) {
                while (true) {
                    val (param, value) = liveParameterWrites.poll() ?: break
                    writeRawAndAwait("set:$param:$value")
                }
            }
        }
        scope.launch {
            usbSerialTransport.frames.collect { frame ->
                if (frame.type == UsbControlProtocol.TYPE_STATE) {
                    handleCharacteristicPayload(BleConstants.STATE_CHAR_UUID.toString(), frame.payload)
                }
            }
        }
        scope.launch {
            usbSerialTransport.ready.collect { ready ->
                if (ready) activateUsbControl() else deactivateUsbControl()
            }
        }
        scope.launch {
            usbSerialTransport.logs.collect { line ->
                val level = when (line.severity) {
                    com.ossm.remote.usb.UsbLogSeverity.ERROR -> LogLevel.ERROR
                    com.ossm.remote.usb.UsbLogSeverity.WARNING -> LogLevel.WARNING
                    else -> LogLevel.INFO
                }
                log(level, "USB", line.message)
            }
        }
        scope.launch {
            var wasPresent = false
            usbSerialTransport.linkState.collect { state ->
                val present = state != com.ossm.remote.usb.UsbLinkState.Absent
                // The cable was pulled for good: Bluetooth may take over again (never before).
                if (wasPresent && !present) scheduleKnownDeviceReconnect(RECONNECT_AFTER_DROP_DELAY_MS)
                wasPresent = present
            }
        }
        scheduleKnownDeviceReconnect(RECONNECT_AFTER_RELAUNCH_DELAY_MS)
    }

    private fun activateUsbControl() {
        reconnectJob?.cancel()
        scanJob?.cancel()
        stopScan()
        healthCheckJob?.cancel()
        cancelStrokeEngineActivation()
        connectionEpoch++
        liveParameterWrites.clear()
        gatt?.let { activeGatt ->
            runCatching { activeGatt.disconnect() }
            runCatching { activeGatt.close() }
        }
        gatt = null
        commandCharacteristic = null
        notifyCharacteristic = null
        liveSmoothCharacteristic = null
        liveSmoothCapabilityPayload = null
        resetPostSetupReadiness()
        _streamingReady.value = false
        cancelLiveTargets()
        _connectionState.value = BleConnectionState.Connected("OSSM USB-C", "USB-C")
        completePostSetupReadiness()
        log(LogLevel.INFO, "USB", "OSSM connecté par USB-C — liaison BLE OSSM désactivée")
    }

    private fun deactivateUsbControl() {
        if ((_connectionState.value as? BleConnectionState.Connected)?.address != "USB-C") return
        cancelStrokeEngineActivation()
        _streamingReady.value = false
        resetPostSetupReadiness()
        cancelLiveTargets()
        _machineState.value = MachineState()
        _firmwareRemoteLeaseReadiness.value = FirmwareRemoteLeaseReadiness()
        _connectionState.value = BleConnectionState.Disconnected
        log(LogLevel.WARNING, "USB", "USB perdu, reconnexion… — contrôle désarmé, rien ne reprend seul")
        // Bluetooth only takes over once the cable is really gone (guard in
        // scheduleKnownDeviceReconnect); while USB recovers both radios must not compete.
        scheduleKnownDeviceReconnect(RECONNECT_AFTER_DROP_DELAY_MS)
    }

    fun startScan() {
        if (usbSerialTransport.ready.value) return
        if (usbSerialTransport.blocksBleFallback(userRequested = true)) {
            log(LogLevel.WARNING, "SCAN", "Scan BLE refusé : câble USB présent, reconnexion USB en cours")
            return
        }
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

        val generation = ++scanGeneration
        val callback = createScanCallback(generation)
        scanJob?.cancel()
        scanJob = scope.launch {
            // Code 1 (SCAN_FAILED_ALREADY_STARTED) : un scan précédent avec le même
            // callback est encore enregistré — on l'arrête toujours avant de relancer.
            val previousCallback = activeScanCallback
            try { bleScanner?.stopScan(previousCallback ?: callback) } catch (_: Exception) {}
            if (activeScanCallback === previousCallback) activeScanCallback = null
            isScanning = false
            // Anti-throttle : attend si on a déjà consommé le quota de scans récents.
            awaitScanThrottleWindow()
            delay(150)
            if (!isCurrentScanGeneration(generation, scanGeneration)) return@launch
            recordScanStart()
            try {
                activeScanCallback = callback
                bleScanner?.startScan(scanFilters, scanSettings, callback)
                isScanning = true
                log(LogLevel.INFO, "SCAN", "Scan actif (${recentScanStarts.size}/$SCAN_THROTTLE_MAX dans la fenêtre 30 s)")
            } catch (e: Exception) {
                log(LogLevel.ERROR, "SCAN", "startScan a échoué: ${e.message}")
            }
            delay(BleConstants.SCAN_TIMEOUT_MS)
            stopScan(generation)
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
                log(LogLevel.INFO, "SCAN", "Scan terminé - ${_scannedDevices.value.size} appareils trouvés")
            }
        }
    }

    private fun pruneScanWindow(now: Long) {
        while (recentScanStarts.isNotEmpty() && now - recentScanStarts.first() > SCAN_THROTTLE_WINDOW_MS) {
            recentScanStarts.removeFirst()
        }
    }

    private fun recordScanStart() {
        val now = System.currentTimeMillis()
        pruneScanWindow(now)
        recentScanStarts.addLast(now)
    }

    /**
     * Bloque tant qu'on est au plafond du quota de scans Android (fenêtre glissante
     * de 30 s). Sans ça, un enchaînement scan→connect échoué→rescan (machine qui
     * redémarre) épuise le quota et les scans suivants renvoient 0 device en silence.
     */
    private suspend fun awaitScanThrottleWindow() {
        val now = System.currentTimeMillis()
        pruneScanWindow(now)
        if (recentScanStarts.size >= SCAN_THROTTLE_MAX) {
            val wait = SCAN_THROTTLE_WINDOW_MS - (now - recentScanStarts.first()) + 250
            if (wait > 0) {
                log(LogLevel.WARNING, "SCAN", "Anti-throttle: ${recentScanStarts.size} scans en 30 s, attente ${wait} ms avant relance")
                delay(wait)
                pruneScanWindow(System.currentTimeMillis())
            }
        }
    }

    fun stopScan() {
        stopScan(scanGeneration)
    }

    private fun stopScan(expectedGeneration: Long) {
        if (!isCurrentScanGeneration(expectedGeneration, scanGeneration)) return
        activeScanCallback?.let { callback ->
            try { bleScanner?.stopScan(callback) } catch (_: Exception) {}
        }
        activeScanCallback = null
        isScanning = false
        scanJob?.cancel()
        scanJob = null
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

    private fun createScanCallback(generation: Long): ScanCallback = object : ScanCallback() {
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
            if (!isCurrentScanGeneration(generation, scanGeneration) ||
                _connectionState.value !is BleConnectionState.Scanning
            ) {
                return
            }
            log(LogLevel.ERROR, "SCAN", "Scan échoué, code=$errorCode")
            stopScan(generation)
            _connectionState.compareAndSet(
                BleConnectionState.Disconnected,
                BleConnectionState.Error("Scan échoué ($errorCode)"),
            )
        }
    }

    fun connect(address: String) {
        if (usbSerialTransport.blocksBleFallback(userRequested = true)) {
            log(LogLevel.WARNING, "CONNECT", "Connexion BLE refusée : câble USB présent, reconnexion USB en cours")
            return
        }
        if (!hasConnectPermission()) {
            log(LogLevel.ERROR, "CONNECT", "Permission Bluetooth connexion manquante")
            return
        }
        manualDisconnectRequested = false
        reconnectJob?.cancel()
        stopScan()
        healthCheckJob?.cancel()
        cancelStrokeEngineActivation()
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
        connectionEpoch++
        liveParameterWrites.clear()
        commandCharacteristic = null
        speedKnobCharacteristic = null
        latencyCompensationCharacteristic = null
        capabilityCharacteristic = null
        liveSmoothCharacteristic = null
        liveSmoothCapabilityPayload = null
        resetPostSetupReadiness()
        postSetupStarted = false
        stateBootstrapPending = false
        stateBootstrapAttempts = 0
        stateBootstrapTimeoutJob?.cancel()
        notifyCharacteristic = null
        patternListCharacteristic = null
        cancelLiveTargets()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        manualDisconnectRequested = true
        reconnectJob?.cancel()
        _streamingReady.value = false
        cancelLiveTargets()
        sendStop()
        forceResetConnection()
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
                    // 2.3.29 safety: the first missed ping already cancels phone producers
                    // (STOP); nothing resumes by itself when the link comes back.
                    _linkSuspect.tryEmit(Unit)
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
        cancelStrokeEngineActivation()
        _streamingReady.value = false
        cancelLiveTargets()
        connectionEpoch++
        liveParameterWrites.clear()
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
        commandCharacteristic = null
        speedKnobCharacteristic = null
        latencyCompensationCharacteristic = null
        capabilityCharacteristic = null
        liveSmoothCharacteristic = null
        liveSmoothCapabilityPayload = null
        resetPostSetupReadiness()
        postSetupStarted = false
        stateBootstrapPending = false
        stateBootstrapAttempts = 0
        stateBootstrapTimeoutJob?.cancel()
        notifyCharacteristic = null
        patternListCharacteristic = null
        _availablePatterns.value = KnownFallbackPatterns
        _machineState.value = MachineState()
        _connectionState.value = BleConnectionState.Disconnected
        if (!manualDisconnectRequested) scheduleKnownDeviceReconnect(RECONNECT_AFTER_DROP_DELAY_MS)
    }

    @Volatile private var serviceDiscoveryRetries = 0

    /**
     * Clears Android's cached GATT services for this device and discovers again, at most
     * [MAX_SERVICE_DISCOVERY_RETRIES] times per connection. Returns true when a retry was started.
     */
    private fun retryServiceDiscovery(gatt: BluetoothGatt, reason: String): Boolean {
        if (serviceDiscoveryRetries >= MAX_SERVICE_DISCOVERY_RETRIES) return false
        serviceDiscoveryRetries++
        log(
            LogLevel.WARNING,
            "GATT",
            "Services OSSM introuvables ($reason) — cache GATT vidé, nouvel essai $serviceDiscoveryRetries/$MAX_SERVICE_DISCOVERY_RETRIES",
        )
        runCatching { gatt.javaClass.getMethod("refresh").invoke(gatt) }
        scope.launch {
            delay(SERVICE_DISCOVERY_RETRY_DELAY_MS)
            if (this@BleManager.gatt === gatt) gatt.discoverServices()
        }
        return true
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== this@BleManager.gatt) {
                log(LogLevel.DEBUG, "GATT", "Callback d'un GATT obsolète ignoré")
                gatt.close()
                return
            }
            val deviceName = gatt.device.name ?: gatt.device.address
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    log(LogLevel.INFO, "GATT", "Connecté à $deviceName, découverte services...")
                    serviceDiscoveryRetries = 0
                    gatt.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = _connectionState.value is BleConnectionState.Connected
                    cancelStrokeEngineActivation()
                    commandCharacteristic = null
                    speedKnobCharacteristic = null
                    latencyCompensationCharacteristic = null
                    capabilityCharacteristic = null
                    liveSmoothCharacteristic = null
                    liveSmoothCapabilityPayload = null
                    resetPostSetupReadiness()
                    postSetupStarted = false
                    stateBootstrapPending = false
                    stateBootstrapAttempts = 0
                    stateBootstrapTimeoutJob?.cancel()
                    notifyCharacteristic = null
                    patternListCharacteristic = null
                    emergencyRestoreJob?.cancel()
                    healthCheckJob?.cancel()
                    streamingEntryJob?.cancel()
                    _streamingReady.value = false
                    cancelLiveTargets()
                    connectionEpoch++
                    liveParameterWrites.clear()
                    if (wasConnected) {
                        log(LogLevel.WARNING, "GATT", "Déconnexion inattendue de $deviceName — nettoyage GATT et retour scan")
                        _connectionState.value = BleConnectionState.EmergencyStop
                        scope.launch {
                            delay(2000)
                            // NE PAS écraser un scan/connexion relancé entre-temps : on ne
                            // repasse à Disconnected que si on est TOUJOURS en EmergencyStop.
                            if (_connectionState.value is BleConnectionState.EmergencyStop) {
                                _connectionState.value = BleConnectionState.Disconnected
                                log(LogLevel.INFO, "GATT", "Prêt pour un nouveau scan après coupure")
                            }
                        }
                    } else {
                        _connectionState.value = BleConnectionState.Disconnected
                    }
                    // Toujours fermer ET libérer le GATT natif : un GATT laissé ouvert bloque
                    // le prochain connectGatt() (la stack native croit la connexion vivante).
                    this@BleManager.gatt?.close()
                    this@BleManager.gatt = null
                    _availablePatterns.value = KnownFallbackPatterns
                    _machineState.value = MachineState()
                    _firmwareRemoteLeaseReadiness.value = FirmwareRemoteLeaseReadiness()
                    log(LogLevel.INFO, "GATT", "GATT fermé et libéré (status=$status) — rescan possible sans redémarrer l'app")
                    if (!manualDisconnectRequested) scheduleKnownDeviceReconnect(RECONNECT_AFTER_DROP_DELAY_MS)
                }
                status != BluetoothGatt.GATT_SUCCESS -> {
                    log(LogLevel.ERROR, "GATT", "Erreur connexion status=$status")
                    healthCheckJob?.cancel()
                    _connectionState.value = BleConnectionState.Error("GATT error $status")
                    gatt.close()
                    this@BleManager.gatt = null
                    if (!manualDisconnectRequested) scheduleKnownDeviceReconnect(RECONNECT_AFTER_DROP_DELAY_MS)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (retryServiceDiscovery(gatt, "découverte en échec (status=$status)")) return
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
                // Android keeps a GATT services cache per device. Right after an app update or a
                // reboot, the first discovery on an automatic reconnect can return an empty or
                // partial list; a manual connection a moment later works. Refresh and retry
                // before reporting the device as incompatible.
                if (retryServiceDiscovery(gatt, "aucune caractéristique d'écriture")) return
                log(LogLevel.ERROR, "GATT", "Aucune caractéristique d'écriture trouvée")
                _connectionState.value = BleConnectionState.Error("Périphérique incompatible")
                return
            }

            speedKnobCharacteristic = ossmService?.getCharacteristic(BleConstants.SPEED_KNOB_UUID)
            latencyCompensationCharacteristic = ossmService?.getCharacteristic(BleConstants.LATENCY_UUID)
            capabilityCharacteristic = ossmService?.getCharacteristic(ResistanceProtocol.CAPABILITY_UUID)
            liveSmoothCharacteristic = ossmService?.getCharacteristic(UUID.fromString(LiveSmoothProtocol.CHARACTERISTIC_UUID))

            val deviceName = gatt.device.name ?: gatt.device.address
            _connectionState.value = BleConnectionState.Connected(deviceName, gatt.device.address)
            if (ossmService != null) rememberKnownDevice(gatt.device.address)
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
                if (!enableNotifications(gatt, notifyChar)) {
                    failGattSetup("Abonnement aux notifications d'état refusé")
                    return
                }
            } else {
                finishSetupAfterCccd(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log(LogLevel.DEBUG, "GATT", "CCCD écrit (status=$status)")
            if (descriptor.uuid != BleConstants.CCCD_UUID) return
            when (
                choosePostCccdAction(
                    status = status,
                    hasStateCharacteristic = notifyCharacteristic != null,
                    stateCharacteristicReadable = notifyCharacteristic?.properties
                        ?.and(BluetoothGattCharacteristic.PROPERTY_READ) != 0,
                )
            ) {
                PostCccdAction.READ_STATE -> bootstrapMachineState(gatt)
                PostCccdAction.CONTINUE_SETUP -> finishSetupAfterCccd(gatt)
                PostCccdAction.FAIL -> failGattSetup("Écriture CCCD échouée (status=$status)")
            }
        }

        private fun bootstrapMachineState(gatt: BluetoothGatt) {
            val state = notifyCharacteristic ?: return finishSetupAfterCccd(gatt)
            if (stateBootstrapAttempts >= STATE_BOOTSTRAP_MAX_ATTEMPTS) {
                failGattSetup("État machine illisible après $stateBootstrapAttempts essais")
                return
            }
            stateBootstrapAttempts++
            stateBootstrapPending = true
            val accepted = runCatching { gatt.readCharacteristic(state) }.getOrDefault(false)
            if (!accepted) {
                stateBootstrapPending = false
                scheduleStateBootstrapRetry(gatt)
                return
            }
            stateBootstrapTimeoutJob?.cancel()
            stateBootstrapTimeoutJob = scope.launch {
                delay(STATE_BOOTSTRAP_READ_TIMEOUT_MS)
                if (stateBootstrapPending && gatt === this@BleManager.gatt) {
                    stateBootstrapPending = false
                    scheduleStateBootstrapRetry(gatt)
                }
            }
        }

        private fun scheduleStateBootstrapRetry(gatt: BluetoothGatt) {
            scope.launch {
                delay(STATE_BOOTSTRAP_RETRY_MS)
                if (!postSetupStarted && gatt === this@BleManager.gatt) bootstrapMachineState(gatt)
            }
        }

        private fun failGattSetup(message: String) {
            stateBootstrapPending = false
            stateBootstrapTimeoutJob?.cancel()
            resetPostSetupReadiness()
            _connectionState.value = BleConnectionState.Error(message)
            log(LogLevel.ERROR, "GATT", message)
        }

        private fun finishSetupAfterCccd(gatt: BluetoothGatt) {
            if (postSetupStarted) return
            postSetupStarted = true
            scope.launch {
                delay(200)
                capabilityCharacteristic?.let { capability ->
                    gatt.readCharacteristic(capability)
                    delay(CAPABILITY_READ_WAIT_MS)
                }
                if (patternListCharacteristic != null) {
                    gatt.readCharacteristic(patternListCharacteristic)
                } else {
                    _availablePatterns.value = KnownFallbackPatterns
                    log(LogLevel.WARNING, "GATT", "Liste de patterns indisponible, Stroke Engine seulement")
                }
                delay(500)
                configureSpeedKnobIndependent()
                completePostSetupReadiness()
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
                if (gatt === this@BleManager.gatt) activeReadCompletion?.complete(null)
                log(LogLevel.WARNING, "READ", "Lecture échouée ${characteristic.uuid} status=$status")
                if (characteristic.uuid == BleConstants.STATE_CHAR_UUID && stateBootstrapPending) {
                    stateBootstrapPending = false
                    stateBootstrapTimeoutJob?.cancel()
                    scheduleStateBootstrapRetry(gatt)
                }
                return
            }
            val validPayload = handleCharacteristicPayload(characteristic.uuid.toString(), value)
            if (gatt === this@BleManager.gatt) activeReadCompletion?.complete(value.copyOf())
            completeStateBootstrapIfReady(gatt, characteristic, validPayload)
        }

        @Deprecated("Used for API < 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (gatt === this@BleManager.gatt) activeReadCompletion?.complete(null)
                log(LogLevel.WARNING, "READ", "Lecture échouée ${characteristic.uuid} status=$status")
                if (characteristic.uuid == BleConstants.STATE_CHAR_UUID && stateBootstrapPending) {
                    stateBootstrapPending = false
                    stateBootstrapTimeoutJob?.cancel()
                    scheduleStateBootstrapRetry(gatt)
                }
                return
            }
            val value = characteristic.value ?: byteArrayOf()
            val validPayload = handleCharacteristicPayload(
                characteristic.uuid.toString(),
                value,
            )
            if (gatt === this@BleManager.gatt) activeReadCompletion?.complete(value.copyOf())
            completeStateBootstrapIfReady(gatt, characteristic, validPayload)
        }

        private fun completeStateBootstrapIfReady(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            validPayload: Boolean,
        ) {
            if (characteristic.uuid != BleConstants.STATE_CHAR_UUID || !stateBootstrapPending) return
            stateBootstrapPending = false
            stateBootstrapTimeoutJob?.cancel()
            if (validPayload) {
                log(LogLevel.INFO, "GATT", "État machine initialisé par lecture")
                finishSetupAfterCccd(gatt)
            } else {
                scheduleStateBootstrapRetry(gatt)
            }
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
            if (gatt === this@BleManager.gatt) activeWriteCompletion?.complete(status)
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

    private fun handleCharacteristicPayload(source: String, value: ByteArray): Boolean {
        if (source.equals(ResistanceProtocol.CAPABILITY_UUID.toString(), ignoreCase = true)) {
            liveSmoothCapabilityPayload = value.copyOf().takeIf {
                ResistanceProtocol.decodeCapability(it).value != null
            }
            val verified = liveSmoothCapabilityPayload != null
            log(
                if (verified) LogLevel.INFO else LogLevel.WARNING,
                "CAPABILITY",
                if (verified) "Marqueur firmware v1 vérifié" else "Marqueur firmware invalide — transport texte conservé",
            )
            return verified
        }
        val message = value.toString(Charsets.UTF_8).trim()
        if (message.isBlank()) return false

        return when {
            source.equals(BleConstants.STATE_CHAR_UUID.toString(), ignoreCase = true) -> {
                val newState = parseStateJson(message) ?: return false
                val prev = _machineState.value
                _machineState.value = newState
                runCatching { JsonParser.parseString(message).asJsonObject }
                    .getOrNull()
                    ?.let { stateJson ->
                        _firmwareRemoteLeaseReadiness.value = parseFirmwareRemoteLeaseReadiness(
                            stateJson,
                            _connectionState.value,
                        )
                    }
                if (prev.state != newState.state) {
                    log(LogLevel.INFO, "STATE", "${prev.state} → ${newState.state}")
                }
                // La machine a quitté le streaming (menu, erreur, long-press physique…):
                // le mapping live n'est plus garanti, on désarme le pad.
                if (_streamingReady.value && !newState.state.contains("streaming", ignoreCase = true)) {
                    _streamingReady.value = false
                    cancelLiveTargets()
                    log(LogLevel.WARNING, "STATE", "Sortie du streaming — live désarmé")
                }
                true
            }
            source.equals(BleConstants.PATTERN_LIST_UUID.toString(), ignoreCase = true) -> {
                val parsed = parsePatternListJson(message)
                if (parsed.isNotEmpty()) {
                    _availablePatterns.value = mergeKnownPatterns(parsed)
                    log(LogLevel.INFO, "PATTERN", "Patterns: ${parsed.joinToString { "${it.id}:${it.name}" }}")
                }
                parsed.isNotEmpty()
            }
            source.equals(BleConstants.COMMAND_CHAR_UUID.toString(), ignoreCase = true) -> {
                when {
                    message.startsWith("ok:") -> log(LogLevel.DEBUG, "ACK", message)
                    message.startsWith("fail:") -> log(LogLevel.WARNING, "ACK", message)
                    else -> log(LogLevel.DEBUG, "NOTIFY", "[CMD] $message")
                }
                true
            }
            else -> {
                log(LogLevel.DEBUG, "NOTIFY", "[$source] $message")
                true
            }
        }
    }

    private var lastStateParseErrorMs: Long = 0L

    private fun parseStateJson(payload: String): MachineState? =
        parseMachineStatePayload(payload).also { parsed ->
            if (parsed == null) {
                // Truncated JSON is normal when MTU < payload size. Throttle log to once / 5s.
                val now = System.currentTimeMillis()
                if (now - lastStateParseErrorMs > 5000) {
                    lastStateParseErrorMs = now
                    val preview = payload.take(64).replace("\n", " ")
                    log(LogLevel.WARNING, "STATE", "JSON tronqué (MTU=$currentMtu): $preview")
                }
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
        // (Auto Random, Naturel, Progressif, Live) existent TOUJOURS, la liste machine ne
        // fait que rafraîchir les noms des patterns stroke-engine. Simple
        // Penetration reste hors du sélecteur (convention établie).
        return KnownAppDrivenPatterns + updatedStrokeEngine
    }

    private fun sanitizePatternKey(raw: String): String {
        return raw
            .trim()
            .replace(" ", "")
            .replace(Regex("[^A-Za-z0-9_-]"), "")
            .ifBlank { "pattern" }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(BleConstants.CCCD_UUID) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
        }
    }

    // Sens du mode Live, réglé depuis les préférences (voir mapping dans sendCommand/Stream).
    @Volatile
    var liveInvert: Boolean = false

    /**
     * Phone-owned remote-session speed ceiling. Null preserves ordinary local
     * behavior (including the existing 100% Live re-arm/setup writes).
     */
    @Volatile
    private var phoneHostSpeedCeilingPercent: Int? = null

    fun setPhoneHostSpeedCeiling(value: Float?) {
        val previous = phoneHostSpeedCeilingPercent
        val ceiling = value?.coerceIn(0f, 1f)?.let { (it * 100f).toInt() }
        phoneHostSpeedCeilingPercent = ceiling
        if (ceiling == null || ceiling >= (previous ?: 100)) return
        if (ceiling == 0) {
            cancelLiveTargets()
            writeRaw("set:speed:0", urgent = true)
            _lastCommand.value = "set:speed:0"
            return
        }
        if (!canEnforcePhoneHostSpeedCeiling()) {
            // Only a Live that is actually armed or in progress is refused (and logged). A ceiling
            // change while no Live is running (ordinary stroke-engine session) is not a refusal:
            // a later Live target is still rejected at [sendCommand] / the GATT target path.
            if (isPhoneHostLiveEngaged()) rejectUnsupportedPhoneHostLive()
            return
        }
        synchronized(liveTargetLock) {
            if (!liveSpeedArmed) return
        }
        // A lowered ceiling must reach an already armed streaming session;
        // otherwise the next target would still run at the previous 100% setup.
        liveSet("speed", ceiling, urgent = true)
    }

    /** True when Live is armed (speed re-armed for targets) or the streaming mode is entered. */
    private fun isPhoneHostLiveEngaged(): Boolean =
        synchronized(liveTargetLock) { liveSpeedArmed } || _streamingReady.value

    private fun clampPhoneHostSpeedPercent(percent: Int): Int =
        percent.coerceIn(0, phoneHostSpeedCeilingPercent ?: 100)

    /**
     * Partial phone ceilings are safe only on the binary Live backend. The
     * legacy `stream:<position>:<time>` fallback has a firmware speed limit
     * independent of `set:speed`, so it cannot enforce a nonzero partial cap.
     * USB Live frames are binary; GATT requires the verified Live characteristic
     * and capability marker. Null/100 preserve ordinary legacy behavior.
     */
    fun canEnforcePhoneHostSpeedCeiling(): Boolean {
        val ceiling = phoneHostSpeedCeilingPercent ?: return true
        if (ceiling == 0 || ceiling >= 100) return true
        if (usbSerialTransport.ready.value) return true
        return canEnforcePhoneHostSpeedCeilingOnGatt()
    }

    private fun canEnforcePhoneHostSpeedCeilingOnGatt(assumeStreamingReady: Boolean = false): Boolean {
        val ceiling = phoneHostSpeedCeilingPercent ?: return true
        if (ceiling == 0 || ceiling >= 100) return true
        if (_connectionState.value !is BleConnectionState.Connected ||
            !(_streamingReady.value || assumeStreamingReady)
        ) return false
        val characteristic = liveSmoothCharacteristic ?: return false
        val writable = characteristic.properties and (
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            ) != 0
        val capabilityVerified = liveSmoothCapabilityPayload?.let {
            ResistanceProtocol.decodeCapability(it).value != null
        } == true
        return writable && capabilityVerified
    }

    private fun rejectUnsupportedPhoneHostLive() {
        val wasArmed = synchronized(liveTargetLock) { liveSpeedArmed }
        cancelLiveTargets()
        if (wasArmed) {
            writeRaw("set:speed:0", urgent = true)
            _lastCommand.value = "set:speed:0"
        }
        log(
            LogLevel.WARNING,
            "LIVE",
            "Live refusé : ce transport texte ne peut pas appliquer le plafond téléphone; " +
                "utilisez le Live binaire ou un plafond téléphone à 100 %",
        )
    }

    /** Record the explicit French rejection used by the remote Store seam. */
    fun rejectRemoteLiveTargetForPhoneLimit() = rejectUnsupportedPhoneHostLive()

    /**
     * 2.3.28: arms the Live speed exactly like the first touch does in [sendCommand] (same
     * phone ceiling, same generation, same scheduler resume), so a producer without a finger
     * (Voix) has an armed speed before its first target. Every refusal is journaled.
     */
    fun armLiveSpeed(reason: String): Boolean {
        if (!_streamingReady.value) {
            log(LogLevel.WARNING, "LIVE", "Vitesse Live non armée ($reason) : entrée streaming non prête")
            return false
        }
        if (!canEnforcePhoneHostSpeedCeiling()) {
            rejectUnsupportedPhoneHostLive()
            return false
        }
        synchronized(liveTargetLock) {
            val generation = liveWriteGeneration.current()
            val liveSpeed = clampPhoneHostSpeedPercent(100)
            if (liveSpeed <= 0) {
                log(LogLevel.WARNING, "LIVE", "Vitesse Live non armée ($reason) : plafond téléphone à 0")
                return false
            }
            if (!liveSpeedArmed) {
                writeRaw("set:speed:$liveSpeed", liveGeneration = generation)
                liveSpeedArmed = true
                _lastCommand.value = "set:speed:$liveSpeed"
                log(LogLevel.INFO, "CMD", "Vitesse Live armée ($reason)")
            }
            liveTargetScheduler.resume()
        }
        return true
    }

    fun sendCommand(command: OssmCommand) {
        if (command is OssmCommand.Stream) {
            // Jamais de position tant que le setup streaming n'est pas vérifié :
            // sans stroke=100/depth=100 confirmés, la bande de course est imprévisible
            // (cause du bug « slider 0-100 ≠ 0-100 du home »).
            if (!_streamingReady.value) {
                log(LogLevel.WARNING, "LIVE", "Cible ignorée : entrée streaming non prête")
                return
            }
            if (!canEnforcePhoneHostSpeedCeiling()) {
                rejectUnsupportedPhoneHostLive()
                return
            }
            synchronized(liveTargetLock) {
                val generation = liveWriteGeneration.current()
                if (clampPhoneHostSpeedPercent(100) <= 0) {
                    log(LogLevel.WARNING, "LIVE", "Cible ignorée : plafond téléphone à 0")
                    return
                }
                if (!_streamingReady.value) {
                    log(LogLevel.WARNING, "LIVE", "Cible ignorée : entrée streaming non prête")
                    return
                }
                if (!liveSpeedArmed) {
                    val liveSpeed = clampPhoneHostSpeedPercent(100)
                    writeRaw("set:speed:$liveSpeed", liveGeneration = generation)
                    liveSpeedArmed = true
                    _lastCommand.value = "set:speed:$liveSpeed"
                    log(LogLevel.INFO, "CMD", "Vitesse Live réarmée après relâchement")
                }
                liveTargetScheduler.resume()
                liveTargetUpdates.trySend(
                    command.copy(receivedAtMs = command.receivedAtMs ?: SystemClock.elapsedRealtime())
                )
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastCommandTime < BleConstants.DEBOUNCE_MIN_MS && command !is OssmCommand.Stop) return
        lastCommandTime = now

        when (command) {
            is OssmCommand.Stop -> {
                cancelLiveTargets()
                writeRaw("set:speed:0", urgent = true)
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
                    PatternControlMode.STROKE_ENGINE, PatternControlMode.PROGRESSIVE,
                    PatternControlMode.NATURAL -> {
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
                val speed = clampPhoneHostSpeedPercent(toPercent(command.command.speed))
                val depthMax = toPercent(command.command.depthMax)
                val stroke = toPercent(command.command.depthMax - command.command.depthMin)
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
                    writeStrokeEngineSensation(command.command)
                }
                val label = "spd=$speed depth=$depthMax stroke=$stroke sens=${toPercent(command.command.sensation)}"
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
    @Volatile
    private var requestedLiveAccelerationPercent = DEFAULT_LIVE_ACCELERATION_PERCENT

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

    fun enterStreaming(accelerationPercent: Int) {
        launchStreamingEntry(accelerationPercent)
    }

    private fun launchStreamingEntry(
        accelerationPercent: Int = requestedLiveAccelerationPercent
    ) {
        requestedLiveAccelerationPercent = normalizeLiveAcceleration(accelerationPercent)
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
    private suspend fun configureSpeedKnobIndependent(): Boolean {
        val char = speedKnobCharacteristic ?: run {
            log(LogLevel.WARNING, "GATT", "Caractéristique speed-knob absente (firmware ancien ?)")
            return false
        }
        val bytes = "false".toByteArray(Charsets.UTF_8)
        val setupWasDone = postSetupDone
        postSetupDone = true
        return try {
            val written = writeGattAndAwait(char, bytes, preferResponse = true)
            if (!written) {
                log(LogLevel.WARNING, "GATT", "Speed knob indépendant non livré")
                false
            } else {
                val confirmed = isSpeedKnobIndependentValue(readGattAndAwait(char))
                log(
                    if (confirmed) LogLevel.INFO else LogLevel.WARNING,
                    "GATT",
                    if (confirmed) {
                        "Speed knob → independent confirmé (BLE speed non plafonné)"
                    } else {
                        "Speed knob indépendant non confirmé par lecture"
                    },
                )
                confirmed
            }
        } finally {
            postSetupDone = setupWasDone
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeLatencyCompensationEnabled() {
        val char = latencyCompensationCharacteristic ?: run {
            log(LogLevel.WARNING, "GATT", "Caractéristique latency-compensation absente")
            return
        }
        val bytes = "true".toByteArray(Charsets.UTF_8)
        enqueueGattWrite(char, bytes)
        log(LogLevel.INFO, "GATT", "Latency compensation → enabled")
    }

    private suspend fun sendStreamingSetup() {
        writeRaw(buildLiveAccelerationCommand(requestedLiveAccelerationPercent))
        delay(60)
        // speed = PLAFOND de vitesse des moves streaming : 100 pour que la machine
        // puisse suivre les gestes rapides (la vitesse réelle vient de pos+time).
        writeRaw("set:speed:${clampPhoneHostSpeedPercent(100)}")
        delay(60)
        writeRaw("set:sensation:50")
        delay(60)
        writeRaw("set:depth:100")
        delay(60)
        writeRaw("set:stroke:100")
        delay(60)
        if (isLatencyCompensationEnabledForLive()) {
            writeLatencyCompensationEnabled()
        }
        delay(60)
    }

    private fun bandVerified(state: MachineState): Boolean =
        state.state.contains("streaming", ignoreCase = true) &&
            (state.stroke ?: 0) >= 99 && (state.depth ?: 0) >= 99

    private suspend fun triggerStreamingEntry() {
        _streamingReady.value = false
        cancelLiveTargets()

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
        configureSpeedKnobIndependent()
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
            synchronized(liveTargetLock) {
                liveSpeedArmed = false
                liveTargetScheduler.resume()
            }
            if (!convergeLiveEntryToHome()) {
                log(
                    LogLevel.WARNING,
                    "LIVE",
                    "Entrée Live interrompue pendant le retour lent au départ — live désarmé",
                )
                return
            }
            _streamingReady.value = true
            _lastCommand.value = "streaming prêt (stroke=100 depth=100)"
            log(
                LogLevel.INFO,
                "CMD",
                "Streaming vérifié : plage live 0-100 = home→fond; vitesse réarmée au premier toucher",
            )
        } else {
            val stt = _machineState.value
            log(
                LogLevel.ERROR, "CMD",
                "Bande jamais confirmée — live désarmé. Machine: état=${stt.state} stroke=${stt.stroke} depth=${stt.depth} speed=${stt.speed}"
            )
        }
    }

    /**
     * 2.3.17 — cause du « saut brusque à l'entrée en Live » : le firmware entre en streaming
     * SANS bouger (le chariot reste où le mode précédent l'a arrêté), l'UI repartait de 0 et la
     * première cible tactile était envoyée seule avec une durée par défaut que le firmware
     * plafonne à ~250 ms (deadline rebasée à réception + 250 ms) : le chariot traversait
     * toute la course vers Home en un quart de seconde.
     *
     * Ici, une fois la bande 100/100 vérifiée et AVANT d'ouvrir la porte `streamingReady`, on
     * rejoint Home lentement : plafond `set:speed` bas (le moteur Live binaire borne sa vitesse
     * par `settings.speed`), cible unique Home avec une durée proportionnelle à la distance
     * (le transport texte honore cette durée), attente bornée, puis `set:speed:0`. Aucun toucher
     * n'est accepté pendant ce temps. Un STOP/annulation (génération Live invalidée) interrompt
     * la convergence et laisse le Live désarmé. Position inconnue (ancien firmware) : rien à
     * faire de sûr, on garde le comportement historique.
     */
    private suspend fun convergeLiveEntryToHome(): Boolean {
        val positionMm = _machineState.value.positionMm ?: return true
        if (!positionMm.isFinite()) return true
        // Physical Home is stepper position 0. With the user inversion, logical Live 0 is the
        // opposite end, whose distance is unknown without the calibrated stroke: assume max.
        if (!liveInvert && abs(positionMm) <= LIVE_ENTRY_HOME_TOLERANCE_MM) return true
        val speedPercent = clampPhoneHostSpeedPercent(LIVE_ENTRY_HOMING_SPEED_PERCENT)
        if (speedPercent <= 0) return true
        val distanceMm = if (liveInvert) LIVE_ENTRY_MAX_TRAVEL_MM else abs(positionMm)
        val travelMs = liveEntryConvergenceDurationMs(distanceMm)
        val generation = synchronized(liveTargetLock) { liveWriteGeneration.current() }
        log(
            LogLevel.INFO,
            "LIVE",
            "Entrée Live : chariot à ${"%.1f".format(positionMm)} mm — retour lent au départ (${travelMs} ms)",
        )
        writeRaw("set:speed:$speedPercent", liveGeneration = generation)
        _lastCommand.value = "set:speed:$speedPercent (retour lent entrée Live)"
        delay(LIVE_ENTRY_SETUP_GAP_MS)
        val scheduled = synchronized(liveTargetLock) {
            if (!liveWriteGeneration.accepts(generation)) return false
            liveTargetScheduler.scheduleDirect(
                positionPercent = mapLiveStreamPositionPercent(0, liveInvert),
                durationMs = travelMs.toInt(),
                nowMs = SystemClock.elapsedRealtime(),
            )
        } ?: return false
        dispatchLiveTarget(scheduled, generation, entryConvergence = true)
        var waitedMs = 0L
        val totalWaitMs = travelMs + LIVE_ENTRY_SETTLE_MS
        while (waitedMs < totalWaitMs) {
            delay(LIVE_ENTRY_POLL_MS)
            waitedMs += LIVE_ENTRY_POLL_MS
            val stillCurrent = synchronized(liveTargetLock) { liveWriteGeneration.accepts(generation) }
            if (!stillCurrent || _connectionState.value !is BleConnectionState.Connected) return false
        }
        // Idle at Home: the first explicit touch re-arms the Live speed ceiling as before.
        writeRaw("set:speed:0", liveGeneration = generation)
        synchronized(liveTargetLock) { liveSpeedArmed = false }
        return synchronized(liveTargetLock) { liveWriteGeneration.accepts(generation) }
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
            applyStrokeEngineParams(command, reason = "Ré-application stroke-engine")
        }
    }

    /**
     * Entrée de mode stroke-engine SÉQUENTIELLE et vérifiée : navigation menu→strokeEngine,
     * sélection du pattern, PUIS ré-application de depth/stroke — le tout dans UNE SEULE
     * coroutine. Android n'a PAS de file d'attente GATT : jusqu'ici la navigation
     * (set:pattern) et l'application des paramètres tournaient dans DEUX coroutines qui se
     * réveillaient au même instant (dès l'état « strokeEngine »), leurs écritures BLE
     * collisionnaient et certaines (set:depth / set:stroke) étaient PERDUES. Résultat : la
     * bande stroke=100/depth=100 héritée du streaming/Live RESTAIT active dans le mode
     * suivant → « le chariot avance ~10 % puis cogne au fond et n'atteint jamais 50 % ».
     * En séquençant tout ici, la plage de l'écran est TOUJOURS réappliquée à l'entrée du
     * mode (aucune fuite d'état possible depuis le streaming).
     */
    fun cancelStrokeEngineActivation() {
        synchronized(strokeEngineActivationLock) {
            strokeEngineActivationController.cancel()
            strokeEngineActivationJob?.cancel()
            strokeEngineActivationJob = null
        }
    }

    fun activatePatternVerified(
        patternId: Int,
        patternName: String,
        command: StrokeEngineCommand,
    ) {
        activatePatternVerified(
            patternId = patternId,
            patternName = patternName,
            command = command,
            onFinished = {},
        )
    }

    fun activatePatternVerified(
        patternId: Int,
        patternName: String,
        command: StrokeEngineCommand,
        onFinished: (Boolean) -> Unit,
    ) {
        cancelStrokeEngineActivation()
        val activationToken: Long
        synchronized(strokeEngineActivationLock) {
            activationToken = strokeEngineActivationController.begin()
            strokeEngineActivationJob = scope.launch {
                var succeeded = false
                try {
                    if (!strokeEngineActivationController.isCurrent(activationToken)) return@launch
                    val ok = navigateToMode("strokeEngine")
                    if (!strokeEngineActivationController.isCurrent(activationToken)) return@launch
                    writeRaw("set:pattern:$patternId")
                    delay(80)
                    if (!strokeEngineActivationController.isCurrent(activationToken)) return@launch
                    _lastCommand.value = "menu → strokeEngine pattern=$patternId ($patternName)"
                    log(
                        LogLevel.INFO, "CMD",
                        "menu → strokeEngine pattern=$patternId ($patternName)" + if (!ok) " [état non confirmé]" else ""
                    )
                    succeeded = applyStrokeEngineParams(
                        command,
                        reason = "Entrée mode '$patternName' (plage ${toPercent(command.depthMin)}-${toPercent(command.depthMax)}%)",
                        activationToken = activationToken,
                    )
                } finally {
                    val shouldNotify = synchronized(strokeEngineActivationLock) {
                        val current = strokeEngineActivationController.finish(activationToken)
                        if (current) strokeEngineActivationJob = null
                        current
                    }
                    if (shouldNotify) onFinished(succeeded)
                }
            }
        }
    }

    /**
     * Envoie les paramètres stroke-engine (depth AVANT stroke — voir UpdateStrokeEngine),
     * puis VÉRIFIE via l'état NOTIFY, jusqu'à 3 essais. Loggue en INFO/CMD la profondeur et
     * la course RÉELLEMENT envoyées, visible dans l'écran Diagnostics : c'est la preuve que
     * la bande streaming 100/100 a bien été écrasée à l'entrée du mode.
     */
    private suspend fun applyStrokeEngineParams(
        command: StrokeEngineCommand,
        reason: String,
        activationToken: Long? = null,
    ): Boolean {
        val speed = clampPhoneHostSpeedPercent(toPercent(command.speed))
        val depthMax = toPercent(command.depthMax)
        val stroke = toPercent(command.depthMax - command.depthMin)
        log(
            LogLevel.INFO, "CMD",
            "$reason → envoi depth=$depthMax stroke=$stroke spd=$speed sens=${toPercent(command.sensation)} (anti-fuite streaming 100/100)"
        )
        var attempt = 0
        while (attempt < 3) {
            if (activationToken != null && !strokeEngineActivationController.isCurrent(activationToken)) {
                return false
            }
            attempt++
            writeRaw("set:speed:$speed")
            delay(40)
            if (activationToken != null && !strokeEngineActivationController.isCurrent(activationToken)) {
                return false
            }
            writeRaw("set:depth:$depthMax")
            delay(40)
            writeRaw("set:stroke:$stroke")
            delay(40)
            writeStrokeEngineSensation(command)
            val verified = awaitMachineState(2_000) {
                isStrokeEngineParamsConfirmed(it, depthMax, stroke)
            }
            if (activationToken != null && !strokeEngineActivationController.isCurrent(activationToken)) {
                return false
            }
            if (verified) {
                _lastCommand.value = "params confirmés: depth=$depthMax stroke=$stroke spd=$speed"
                log(LogLevel.INFO, "CMD", "Paramètres confirmés par la machine : depth=$depthMax stroke=$stroke")
                return true
            }
            val st = _machineState.value
            log(
                LogLevel.WARNING, "CMD",
                "Paramètres non confirmés (essai $attempt/3) — machine: depth=${st.depth} stroke=${st.stroke} état=${st.state}"
            )
        }
        log(LogLevel.ERROR, "CMD", "Paramètres JAMAIS confirmés — la machine n'utilise peut-être pas la plage affichée !")
        return false
    }

    /** Attend (avec timeout) que l'état machine notifié satisfasse le prédicat. */
    private suspend fun awaitMachineState(timeoutMs: Long, predicate: (MachineState) -> Boolean): Boolean {
        if (predicate(_machineState.value)) return true
        return withTimeoutOrNull(timeoutMs) { machineState.first(predicate) } != null
    }

    private suspend fun runGattWriteWorker() {
        while (scope.isActive) {
            val request = urgentGattWrites.tryReceive().getOrNull() ?: normalGattWrites.tryReceive().getOrNull()
            if (request != null) {
                if (request.epoch != connectionEpoch || !liveWriteGeneration.accepts(request.liveGeneration)) {
                    request.completion?.complete(false)
                    continue
                }
                var setupWaitMs = 0L
                while (!postSetupDone &&
                    request.epoch == connectionEpoch &&
                    liveWriteGeneration.accepts(request.liveGeneration) &&
                    setupWaitMs < GATT_SETUP_WAIT_MS
                ) {
                    delay(GATT_SETUP_POLL_MS)
                    setupWaitMs += GATT_SETUP_POLL_MS
                }
                if (request.epoch != connectionEpoch || !liveWriteGeneration.accepts(request.liveGeneration)) {
                    request.completion?.complete(false)
                    continue
                }
                val succeeded = performGattWrite(request)
                request.completion?.complete(succeeded)
                if (!succeeded && request.completion == null) {
                    log(LogLevel.WARNING, "WRITE", "Écriture GATT non livrée")
                }
                continue
            }
            val read: GattOperation = gattReads.tryReceive().getOrNull()
                ?.let(GattOperation::Read)
                ?: select {
                urgentGattWrites.onReceive { GattOperation.Write(it) }
                normalGattWrites.onReceive { GattOperation.Write(it) }
                gattReads.onReceive { GattOperation.Read(it) }
            }
            when (read) {
                is GattOperation.Write -> {
                    if (read.request.epoch != connectionEpoch ||
                        !liveWriteGeneration.accepts(read.request.liveGeneration)
                    ) {
                        read.request.completion?.complete(false)
                        continue
                    }
                    val succeeded = performGattWrite(read.request)
                    read.request.completion?.complete(succeeded)
                    if (!succeeded && read.request.completion == null) {
                        log(LogLevel.WARNING, "WRITE", "Écriture GATT non livrée")
                    }
                }
                is GattOperation.Read -> {
                    if (read.request.epoch != connectionEpoch || !postSetupDone) {
                        read.request.completion.complete(null)
                        continue
                    }
                    read.request.completion.complete(performGattRead(read.request.characteristic))
                }
            }
        }
    }

    private sealed interface GattOperation {
        data class Write(val request: GattWriteRequest) : GattOperation
        data class Read(val request: GattReadRequest) : GattOperation
    }

    @SuppressLint("MissingPermission")
    private suspend fun performGattRead(characteristic: BluetoothGattCharacteristic): ByteArray? {
        val activeGatt = gatt ?: return null
        val callback = CompletableDeferred<ByteArray?>()
        activeReadCompletion = callback
        val accepted = runCatching { activeGatt.readCharacteristic(characteristic) }.getOrDefault(false)
        if (!accepted) {
            activeReadCompletion = null
            return null
        }
        val value = withTimeoutOrNull(GATT_READ_CALLBACK_TIMEOUT_MS) { callback.await() }
        activeReadCompletion = null
        return value
    }

    private suspend fun readGattAndAwait(characteristic: BluetoothGattCharacteristic): ByteArray? {
        val completion = CompletableDeferred<ByteArray?>()
        gattReads.send(GattReadRequest(connectionEpoch, characteristic, completion))
        return completion.await()
    }

    internal suspend fun claimFirmwareRemoteLease(): Boolean =
        executeFirmwareRemoteLeaseCommand("remote:claim", "ok:remote:claimed")

    internal suspend fun heartbeatFirmwareRemoteLease(): Boolean =
        executeFirmwareRemoteLeaseCommand("remote:heartbeat", "ok:remote:heartbeat")

    internal suspend fun releaseFirmwareRemoteLease(): Boolean =
        executeFirmwareRemoteLeaseCommand("remote:release", "ok:remote:released")

    private suspend fun executeFirmwareRemoteLeaseCommand(command: String, expectedAck: String): Boolean {
        if (usbSerialTransport.ready.value || !_firmwareRemoteLeaseReadiness.value.connected) return false
        val characteristic = commandCharacteristic ?: return false
        if (!writeGattAndAwait(characteristic, command.toByteArray(Charsets.UTF_8), preferResponse = true)) {
            return false
        }
        val response = readGattAndAwait(characteristic)
            ?.toString(Charsets.UTF_8)
            ?.trim()
        val diagnostic = firmwareRemoteLeaseDiagnosticFor(command, response)
        if (diagnostic != null) {
            log(LogLevel.WARNING, "REMOTE", diagnostic.consoleMessage)
            _firmwareRemoteLeaseDiagnostics.tryEmit(diagnostic)
        }
        return response == expectedAck
    }

    private suspend fun writeGattAndAwait(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        preferResponse: Boolean = false,
        liveGeneration: Long? = null,
    ): Boolean {
        val completion = CompletableDeferred<Boolean>()
        normalGattWrites.send(
            GattWriteRequest(
                connectionEpoch,
                characteristic,
                value.copyOf(),
                preferResponse = preferResponse,
                completion = completion,
                liveGeneration = liveGeneration,
            )
        )
        return completion.await()
    }

    @SuppressLint("MissingPermission")
    private suspend fun performGattWrite(request: GattWriteRequest): Boolean {
        val activeGatt = gatt ?: return false
        if (request.epoch != connectionEpoch ||
            !liveWriteGeneration.accepts(request.liveGeneration) ||
            !postSetupDone
        ) return false
        val writeType = chooseGattWriteType(
            properties = request.characteristic.properties,
            preferResponse = request.preferResponse,
        )
        val callback = if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
            CompletableDeferred<Int>()
        } else {
            null
        }
        activeWriteCompletion = callback
        gattWriteAttemptObserver?.invoke(request.characteristic, request.value.copyOf(), writeType)
        val accepted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activeGatt.writeCharacteristic(request.characteristic, request.value, writeType) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                request.characteristic.value = request.value
                @Suppress("DEPRECATION")
                request.characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                activeGatt.writeCharacteristic(request.characteristic)
            }
        } catch (_: Exception) {
            false
        }
        if (!accepted) {
            activeWriteCompletion = null
            return false
        }
        val succeeded = if (callback != null) {
            withTimeoutOrNull(GATT_WRITE_CALLBACK_TIMEOUT_MS) { callback.await() } == BluetoothGatt.GATT_SUCCESS
        } else {
            delay(GATT_WRITE_NO_RESPONSE_GAP_MS)
            true
        }
        activeWriteCompletion = null
        return succeeded
    }

    private fun enqueueGattWrite(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        urgent: Boolean = false,
        preferResponse: Boolean = true,
        liveGeneration: Long? = null,
    ) {
        val request = GattWriteRequest(
            connectionEpoch,
            characteristic,
            value.copyOf(),
            preferResponse,
            liveGeneration = liveGeneration,
        )
        if (urgent) urgentGattWrites.trySend(request) else normalGattWrites.trySend(request)
    }

    private fun resetPostSetupReadiness() {
        postSetupDone = false
        _postSetupReady.value = false
    }

    private fun completePostSetupReadiness() {
        postSetupDone = true
        _postSetupReady.value = true
    }


    private suspend fun runLiveTargetWorker() {
        for (command in liveTargetUpdates) {
            offerLiveTarget(command)
            while (scope.isActive) {
                while (true) {
                    val newer = liveTargetUpdates.tryReceive().getOrNull() ?: break
                    offerLiveTarget(newer)
                }
                val now = SystemClock.elapsedRealtime()
                val waitMs = synchronized(liveTargetLock) {
                    liveTargetScheduler.millisecondsUntilReady(now)
                }
                if (waitMs > 0L) {
                    val newer = withTimeoutOrNull(waitMs) { liveTargetUpdates.receive() }
                    if (newer != null) {
                        offerLiveTarget(newer)
                        continue
                    }
                }
                val scheduled = synchronized(liveTargetLock) {
                    liveTargetScheduler.poll(SystemClock.elapsedRealtime())?.let {
                        it to liveWriteGeneration.current()
                    }
                }
                if (scheduled != null) {
                    dispatchLiveTarget(scheduled.first, scheduled.second)
                    continue
                }
                break
            }
        }
    }

    private fun offerLiveTarget(command: OssmCommand.Stream) {
        if (!_streamingReady.value) {
            log(LogLevel.WARNING, "LIVE", "Cible ignorée : streaming non prêt (file)")
            return
        }
        val rawPosition = command.positionPercent.coerceIn(0, 100)
        val actuatorPosition = mapLiveStreamPositionPercent(rawPosition, liveInvert)
        synchronized(liveTargetLock) {
            liveTargetScheduler.offer(
                LiveTargetInput(
                    positionPercent = actuatorPosition,
                    requestedDurationMs = command.timeMs,
                    producer = command.producer,
                    receivedAtMs = command.receivedAtMs ?: SystemClock.elapsedRealtime(),
                )
            )
        }
    }

    private suspend fun dispatchLiveTarget(
        target: ScheduledLiveTarget,
        liveGeneration: Long,
        entryConvergence: Boolean = false,
    ) {
        val usbPayload = LiveSmoothProtocol.encode(
            LiveSmoothPacket(
                sequence = target.sequence,
                deadlineMs = target.deadlineMs,
                positionPermille = target.positionPercent * 10,
                durationMs = target.durationMs,
            )
        )
        if (usbSerialTransport.sendLiveTarget(usbPayload)) {
            _lastCommand.value = "usb-live-v1:${target.positionPercent}:${target.durationMs} seq=${target.sequence}"
            log(
                LogLevel.INFO,
                "LIVE",
                "Cible livrée transport=USB pos=${target.positionPercent} durée=${target.durationMs} seq=${target.sequence}",
            )
            return
        }
        if (!canEnforcePhoneHostSpeedCeilingOnGatt(assumeStreamingReady = entryConvergence)) {
            rejectUnsupportedPhoneHostLive()
            return
        }
        val smoothCharacteristic = liveSmoothCharacteristic
        val writable = smoothCharacteristic != null && smoothCharacteristic.properties and (
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            ) != 0
        val write = LiveSmoothProtocol.createTargetWrite(
            target = target,
            capabilityPayload = liveSmoothCapabilityPayload,
            characteristicWritable = writable,
            connected = _connectionState.value is BleConnectionState.Connected,
            // Entry convergence runs after the band is verified but before the public ready
            // gate opens (touch targets stay refused until the actuator has reached Home).
            streamingReady = _streamingReady.value || entryConvergence,
            binaryTransportVerified = writable,
        ) ?: run {
            log(LogLevel.WARNING, "LIVE", "Cible non livrée seq=${target.sequence} : transport indisponible")
            return
        }
        when (write.transport) {
            LiveTransportSelection.BLOCKED -> {
                log(LogLevel.WARNING, "LIVE", "Cible non livrée seq=${target.sequence} : transport bloqué")
                return
            }
            LiveTransportSelection.BINARY_V1 -> {
                if (!writeGattAndAwait(
                        smoothCharacteristic ?: return,
                        write.payload,
                        liveGeneration = liveGeneration,
                    )
                ) {
                    log(LogLevel.ERROR, "LIVE", "Cible binaire non livrée seq=${target.sequence}")
                    return
                }
                _lastCommand.value = "live-v1:${target.positionPercent}:${target.durationMs} seq=${target.sequence}"
                log(
                    LogLevel.INFO,
                    "LIVE",
                    "Cible livrée transport=binaire pos=${target.positionPercent} durée=${target.durationMs} seq=${target.sequence}",
                )
            }
            LiveTransportSelection.LEGACY_TEXT -> {
                val characteristic = commandCharacteristic ?: return
                if (!writeGattAndAwait(characteristic, write.payload, liveGeneration = liveGeneration)) {
                    log(LogLevel.ERROR, "LIVE", "Cible texte non livrée seq=${target.sequence}")
                    return
                }
                _lastCommand.value = write.payload.toString(Charsets.UTF_8)
                log(
                    LogLevel.INFO,
                    "LIVE",
                    "Cible livrée transport=texte pos=${target.positionPercent} durée=${target.durationMs} seq=${target.sequence}",
                )
            }
        }
    }

    private fun cancelLiveTargets() {
        while (liveTargetUpdates.tryReceive().isSuccess) Unit
        synchronized(liveTargetLock) {
            // Invalidate both queued and not-yet-written Live GATT requests before the urgent STOP.
            // Keep generation invalidation and scheduler.stop() under the same lock used when a
            // target is polled, so cancellation cannot race a fresh generation into dispatch.
            liveWriteGeneration.invalidate()
            liveSpeedArmed = false
            liveTargetScheduler.stop()
        }
    }

    fun resumeStreamingTargets() {
        if (_streamingReady.value) synchronized(liveTargetLock) { liveTargetScheduler.resume() }
    }

    @SuppressLint("MissingPermission")
    private fun writeRaw(text: String, urgent: Boolean = false, liveGeneration: Long? = null) {
        // A direct write supersedes a pending real-time value of the same parameter, and a
        // speed-0 write (STOP, mode change) drops every pending slider value: nothing coalesced
        // may be written after it.
        if (text == "set:speed:0") {
            liveParameterWrites.clear()
        } else {
            liveParameterOf(text)?.let(liveParameterWrites::remove)
        }
        if (urgent && text == "set:speed:0") {
            usbSerialTransport.sendStop()
        } else if (usbSerialTransport.sendCommand(text)) {
            log(LogLevel.INFO, "USB", "Commande livrée par USB : $text")
            return
        }
        val char = commandCharacteristic ?: return
        enqueueGattWrite(
            char,
            text.toByteArray(Charsets.UTF_8),
            urgent = urgent,
            liveGeneration = liveGeneration,
        )
    }

    private fun writeStrokeEngineSensation(command: StrokeEngineCommand) {
        writeRaw(buildSensationCommand(command))
    }

    /**
     * Écriture mono-paramètre légère pour le drag temps réel des sliders. Non urgente, elle
     * passe par la boîte « dernière valeur » [liveParameterWrites] : jamais d'arriéré dans la
     * file GATT (voir [LiveParameterCoalescer]). Urgente (plafond de vitesse abaissé), elle
     * garde le chemin prioritaire historique.
     */
    fun liveSet(param: String, percent: Int, urgent: Boolean = false) {
        val p = if (param == "speed") {
            clampPhoneHostSpeedPercent(percent)
        } else {
            percent.coerceIn(0, 100)
        }
        if (urgent || (param == "speed" && p == 0)) {
            writeRaw("set:$param:$p", urgent = urgent)
        } else {
            liveParameterWrites.offer(param, p)
            liveParameterSignal.trySend(Unit)
        }
        _lastCommand.value = "set:$param:$p"
    }

    /** Write one command and wait for its acknowledgement (no queue backlog). */
    private suspend fun writeRawAndAwait(text: String): Boolean {
        if (usbSerialTransport.sendCommand(text)) return true
        val char = commandCharacteristic ?: return false
        return writeGattAndAwait(char, text.toByteArray(Charsets.UTF_8), preferResponse = true)
    }

    fun setLiveAcceleration(percent: Int) {
        val acceleration = normalizeLiveAcceleration(percent)
        requestedLiveAccelerationPercent = acceleration
        val command = buildLiveAccelerationCommand(acceleration)
        writeRaw(command)
        _lastCommand.value = command
        log(LogLevel.INFO, "CMD", command)
    }

    /**
     * Envoie une plage complète sans lancer deux écritures GATT concurrentes. Le
     * firmware interprète `depth` comme le fond et `stroke` comme la course : l'ordre
     * depth puis stroke évite un dépassement transitoire, et chaque nouveau drag
     * remplace la paire encore en attente par sa valeur la plus récente.
     */
    fun liveSetDepthRange(depthPercent: Int, strokePercent: Int) {
        liveDepthRangeUpdates.trySend(LiveDepthRange(
            depthPercent = depthPercent.coerceIn(0, 100),
            strokePercent = strokePercent.coerceIn(0, 100)
        ))
    }

    /** Change le pattern stroke-engine actif sans repasser par le menu. */
    fun setStrokeEnginePattern(id: Int) {
        writeRaw("set:pattern:$id")
        _lastCommand.value = "set:pattern:$id"
        log(LogLevel.INFO, "CMD", "set:pattern:$id (chaos)")
    }

    fun sendStop() {
        cancelLiveTargets()
        writeRaw("set:speed:0", urgent = true)
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
        _streamingReady.value = false
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

    private fun scheduleKnownDeviceReconnect(delayMs: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (usbSerialTransport.blocksBleFallback(userRequested = false)) {
                log(LogLevel.INFO, "USB", "Reconnexion BLE suspendue : câble USB présent")
                return@launch
            }
            val knownAddress = readKnownDeviceAddress()
            if (knownAddress.isNullOrBlank()) return@launch
            val action = chooseKnownDeviceReconnectAction(
                hasConnectPermission = hasConnectPermission(),
                bluetoothEnabled = bluetoothAdapter?.isEnabled == true,
                hasKnownAddress = true,
                connectionState = _connectionState.value,
            )
            if (action == KnownDeviceReconnectAction.CONNECT_KNOWN_DEVICE) {
                log(LogLevel.INFO, "CONNECT", "Reconnexion au périphérique OSSM connu")
                reconnectJob = null
                connect(knownAddress)
            }
        }
    }

    private fun rememberKnownDevice(address: String) {
        runCatching {
            context.getSharedPreferences(BLE_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(KNOWN_DEVICE_ADDRESS, address)
                .apply()
        }
    }

    private fun readKnownDeviceAddress(): String? = runCatching {
        context.getSharedPreferences(BLE_PREFERENCES, Context.MODE_PRIVATE)
            .getString(KNOWN_DEVICE_ADDRESS, null)
    }.getOrNull()

    private fun hasConnectPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun toPercent(value: Float): Int = (value * 100f).toInt().coerceIn(0, 100)

    /**
     * Entry point for non-BLE app components (e.g. the camera share screen) to write in the same
     * Diagnostics journal. Callers are responsible for never passing a secret or a link.
     */
    fun appendExternalDiagnostic(level: LogLevel, tag: String, message: String) = log(level, tag, message)

    private fun log(level: LogLevel, tag: String, message: String) {
        // Miroir vers logcat (lisible via `adb logcat -s OSSM`) pour diagnostiquer à
        // distance, en plus de l'écran Diagnostics de l'app.
        val line = "[$tag] $message"
        when (level) {
            LogLevel.ERROR -> android.util.Log.e("OSSM", line)
            LogLevel.WARNING -> android.util.Log.w("OSSM", line)
            LogLevel.DEBUG -> android.util.Log.d("OSSM", line)
            else -> android.util.Log.i("OSSM", line)
        }
        scope.launch {
            _logs.emit(DiagnosticsLog(level = level, tag = tag, message = message))
        }
    }

    companion object {
        private const val LIVE_DEPTH_RANGE_WRITE_GAP_MS = 40L
        private const val CAPABILITY_READ_WAIT_MS = 300L
        private const val STATE_BOOTSTRAP_MAX_ATTEMPTS = 5
        private const val STATE_BOOTSTRAP_RETRY_MS = 300L
        private const val STATE_BOOTSTRAP_READ_TIMEOUT_MS = 1_000L
        private const val GATT_SETUP_WAIT_MS = 5_000L
        private const val GATT_SETUP_POLL_MS = 20L
        private const val GATT_WRITE_CALLBACK_TIMEOUT_MS = 1_000L
        private const val GATT_READ_CALLBACK_TIMEOUT_MS = 1_000L
        private const val GATT_WRITE_NO_RESPONSE_GAP_MS = 20L
        private const val RECONNECT_AFTER_RELAUNCH_DELAY_MS = 750L
        private const val RECONNECT_AFTER_DROP_DELAY_MS = 500L
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 2
        private const val SERVICE_DISCOVERY_RETRY_DELAY_MS = 400L
        private const val BLE_PREFERENCES = "ossm_ble_connection"
        private const val KNOWN_DEVICE_ADDRESS = "known_ossm_address"
        // Détection de perte de connexion silencieuse (ping RSSI périodique).
        private const val HEALTH_CHECK_INTERVAL_MS = 3_000L
        private const val HEALTH_CHECK_REPLY_TIMEOUT_MS = 1_500L
        private const val HEALTH_CHECK_MAX_MISSES = 3

        // Quota de scan Android non documenté (~5 startScan / 30 s). On garde une marge.
        private const val SCAN_THROTTLE_WINDOW_MS = 30_000L
        private const val SCAN_THROTTLE_MAX = 4
    }
}

private data class LiveDepthRange(
    val depthPercent: Int,
    val strokePercent: Int
)

internal fun buildSensationCommand(command: StrokeEngineCommand): String {
    val sensation = (command.sensation * 100f).toInt().coerceIn(0, 100)
    return "set:sensation:$sensation"
}

internal fun buildLiveAccelerationCommand(percent: Int): String =
    "set:acceleration:${normalizeLiveAcceleration(percent)}"

private fun normalizeLiveAcceleration(percent: Int): Int = percent.coerceIn(10, 100)

private const val DEFAULT_LIVE_ACCELERATION_PERCENT = 33

/**
 * Live-entry convergence (2.3.17). Firmware max linear speed is 1500 rpm * 20 teeth * 2 mm
 * = 1000 mm/s and the binary Live engine caps motion at `settings.speed` % of it, so
 * `set:speed:3` bounds the entry return to ~30 mm/s (~15 %/s of a 200 mm stroke, gentler
 * than the 20 %/s release return).
 */
internal const val LIVE_ENTRY_HOMING_SPEED_PERCENT = 3
internal const val LIVE_ENTRY_HOMING_SPEED_MM_PER_S = 30f
/** Live 0 maps to wire 98 (2 % of travel from Home); a few mm is already "at Home". */
internal const val LIVE_ENTRY_HOME_TOLERANCE_MM = 6f
/** Unknown distance (user inversion): assume the longest OSSM stroke. */
internal const val LIVE_ENTRY_MAX_TRAVEL_MM = 250f
private const val LIVE_ENTRY_MIN_DURATION_MS = 400L
private const val LIVE_ENTRY_MAX_DURATION_MS = 10_000L
private const val LIVE_ENTRY_SETUP_GAP_MS = 80L
private const val LIVE_ENTRY_SETTLE_MS = 400L
private const val LIVE_ENTRY_POLL_MS = 50L

/** Duration of the slow Live-entry return for [distanceMm] at the bounded entry speed. */
internal fun liveEntryConvergenceDurationMs(distanceMm: Float): Long {
    val distance = if (distanceMm.isFinite()) abs(distanceMm) else LIVE_ENTRY_MAX_TRAVEL_MM
    val ms = (distance / LIVE_ENTRY_HOMING_SPEED_MM_PER_S * 1_000f).toLong()
    return ms.coerceIn(LIVE_ENTRY_MIN_DURATION_MS, LIVE_ENTRY_MAX_DURATION_MS)
}
