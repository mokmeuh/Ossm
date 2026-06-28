package com.ossm.remote.ble

import java.util.UUID

object BleConstants {
    // OSSM firmware service ("OSSM" encoded in UUID: 4f53 534d)
    val OSSM_SERVICE_UUID: UUID = UUID.fromString("522b443a-4f53-534d-0001-420badbabe69")
    val COMMAND_CHAR_UUID: UUID = UUID.fromString("522b443a-4f53-534d-1000-420badbabe69")
    val SPEED_KNOB_UUID:   UUID = UUID.fromString("522b443a-4f53-534d-1010-420badbabe69")
    val WIFI_CHAR_UUID:    UUID = UUID.fromString("522b443a-4f53-534d-1020-420badbabe69")
    val LATENCY_UUID:      UUID = UUID.fromString("522b443a-4f53-534d-1030-420badbabe69")
    val STATE_CHAR_UUID:   UUID = UUID.fromString("522b443a-4f53-534d-2000-420badbabe69")
    val PATTERN_LIST_UUID: UUID = UUID.fromString("522b443a-4f53-534d-3000-420badbabe69")
    val PATTERN_DESC_UUID: UUID = UUID.fromString("522b443a-4f53-534d-3010-420badbabe69")

    // Nordic UART Service (legacy/alternative firmware)
    val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_RX_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    // Standard CCCD
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Device name filters
    val OSSM_NAME_PREFIXES = listOf("OSSM", "OFFM", "ossm", "offm", "Ossm")

    // Timing
    const val SCAN_TIMEOUT_MS     = 15_000L
    const val CONNECT_TIMEOUT_MS  = 10_000L
    const val DEBOUNCE_MIN_MS     = 50L
    const val WATCHDOG_TIMEOUT_MS = 5_000L
}
