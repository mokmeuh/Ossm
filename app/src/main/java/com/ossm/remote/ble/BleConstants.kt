package com.ossm.remote.ble

import java.util.UUID

object BleConstants {
    // OSSM primary service (ESP32 Arduino default)
    val OSSM_SERVICE_UUID: UUID = UUID.fromString("19b10000-e8f2-537e-4f6c-d104768a1214")
    val POSITION_CHAR_UUID: UUID = UUID.fromString("19b10001-e8f2-537e-4f6c-d104768a1214")
    val SPEED_CHAR_UUID: UUID    = UUID.fromString("19b10002-e8f2-537e-4f6c-d104768a1214")
    val STATUS_CHAR_UUID: UUID   = UUID.fromString("19b10003-e8f2-537e-4f6c-d104768a1214")

    // Nordic UART Service (alternative firmware)
    val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // write
    val NUS_RX_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // notify

    // Standard CCCD
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Device name filters
    val OSSM_NAME_PREFIXES = listOf("OSSM", "OFFM", "ossm", "offm", "Ossm")

    // Timing
    const val SCAN_TIMEOUT_MS = 15_000L
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val COMMAND_TIMEOUT_MS = 2_000L
    const val DEBOUNCE_MIN_MS = 50L    // minimum ms between commands (instant response)
    const val GATT_RETRY_DELAY_MS = 600L

    // Safety
    const val MAX_SPEED = 100
    const val WATCHDOG_TIMEOUT_MS = 5_000L // auto-stop if no ack in 5s
}
