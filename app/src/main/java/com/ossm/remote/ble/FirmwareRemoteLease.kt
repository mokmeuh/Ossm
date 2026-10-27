package com.ossm.remote.ble

import com.google.gson.JsonObject
import com.ossm.remote.model.BleConnectionState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class FirmwareRemoteLeaseReadiness(
    val connected: Boolean = false,
    val homed: Boolean = false,
    val remoteControlSafe: Boolean = false,
    val watchdogMs: Long? = null,
    val transport: String? = null,
) {
    val canClaim: Boolean
        get() = connected && homed && remoteControlSafe && watchdogMs != null && transport == "ble-local"

    val ownsLease: Boolean
        get() = connected && homed && remoteControlSafe && watchdogMs != null && transport == "ble-lease"
}

enum class FirmwareRemoteLeaseDiagnostic(
    val wireCode: String,
    val consoleMessage: String,
) {
    RemoteAuthRequired(
        wireCode = "firmware_remote_auth_required",
        consoleMessage = "ACK fail:remote-auth-required",
    ),
}

internal fun firmwareRemoteLeaseDiagnosticFor(
    command: String,
    acknowledgement: String?,
): FirmwareRemoteLeaseDiagnostic? = when {
    command == "remote:claim" && acknowledgement == "fail:remote-auth-required" ->
        FirmwareRemoteLeaseDiagnostic.RemoteAuthRequired
    else -> null
}

internal fun parseFirmwareRemoteLeaseReadiness(
    state: JsonObject,
    connectionState: BleConnectionState,
): FirmwareRemoteLeaseReadiness = FirmwareRemoteLeaseReadiness(
    connected = connectionState is BleConnectionState.Connected &&
        connectionState.address != "USB-C",
    homed = state.get("homed")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
    remoteControlSafe = state.get("remoteControlSafe")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
    watchdogMs = state.get("watchdogMs")?.takeIf { !it.isJsonNull }?.asLong,
    transport = state.get("transport")?.takeIf { !it.isJsonNull }?.asString,
)

internal fun firmwareLeaseHeartbeatIntervalMillis(watchdogMs: Long): Long =
    (watchdogMs / 2).coerceIn(MIN_HEARTBEAT_INTERVAL_MS, MAX_HEARTBEAT_INTERVAL_MS)

interface FirmwareRemoteLeaseTransport {
    val readiness: StateFlow<FirmwareRemoteLeaseReadiness>
    val diagnostics: SharedFlow<FirmwareRemoteLeaseDiagnostic>

    suspend fun claim(): Boolean
    suspend fun heartbeat(): Boolean
    suspend fun release(): Boolean
}

@Singleton
class BleFirmwareRemoteLeaseTransport @Inject constructor(
    private val bleManager: BleManager,
) : FirmwareRemoteLeaseTransport {
    override val readiness: StateFlow<FirmwareRemoteLeaseReadiness> = bleManager.firmwareRemoteLeaseReadiness
    override val diagnostics: SharedFlow<FirmwareRemoteLeaseDiagnostic> = bleManager.firmwareRemoteLeaseDiagnostics

    override suspend fun claim(): Boolean = bleManager.claimFirmwareRemoteLease()

    override suspend fun heartbeat(): Boolean = bleManager.heartbeatFirmwareRemoteLease()

    override suspend fun release(): Boolean = bleManager.releaseFirmwareRemoteLease()
}

private const val MIN_HEARTBEAT_INTERVAL_MS = 50L
private const val MAX_HEARTBEAT_INTERVAL_MS = 100L
