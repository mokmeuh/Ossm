#ifndef OSSM_NIMBLE_H
#define OSSM_NIMBLE_H

#include <cstddef>
#include <cstdint>

#include "live_smooth_protocol.h"
#include "resistance_protocol.h"

class NimBLEServer;

#ifndef RESISTANCE_SIGNAL_GATE_PASS
#define RESISTANCE_SIGNAL_GATE_PASS 0
#endif

namespace optional_ble {

inline constexpr bool kResistanceSignalGatePassed =
    RESISTANCE_SIGNAL_GATE_PASS == 1;
inline constexpr uint32_t kResistanceTelemetryIntervalMs = 50;

enum class ConfigWriteStatus : uint8_t {
    accepted,
    invalid_packet,
    signal_gate_unavailable,
};

inline resistance_protocol::Capability capabilityPacket() {
    return {
        kResistanceSignalGatePassed ? resistance_protocol::kAllCapabilityBits
                                    : 0U,
        resistance_protocol::kTelemetryProperties,
        resistance_protocol::kConfigProperties,
    };
}

inline bool telemetryDue(uint32_t nowMs, uint32_t lastTelemetryMs) {
    return static_cast<uint32_t>(nowMs - lastTelemetryMs) >=
           kResistanceTelemetryIntervalMs;
}

inline bool shouldRefreshStateValue(bool stateChanged, bool timeElapsed) {
    return stateChanged || timeElapsed;
}

inline uint16_t nextTelemetrySequence(uint16_t sequence) {
    return sequence == 0xffffU ? 0U
                               : static_cast<uint16_t>(sequence + 1U);
}

inline ConfigWriteStatus applyResistanceConfig(
    const uint8_t* data, std::size_t length,
    resistance_protocol::Config& current) {
    resistance_protocol::Config candidate{};
    if (resistance_protocol::decodeConfig(data, length, candidate) !=
        resistance_protocol::DecodeStatus::ok) {
        return ConfigWriteStatus::invalid_packet;
    }
    if (!kResistanceSignalGatePassed) {
        return ConfigWriteStatus::signal_gate_unavailable;
    }
    current = candidate;
    return ConfigWriteStatus::accepted;
}

}

#define SERVICE_UUID "522b443a-4f53-534d-0001-420badbabe69"

// **********************************************************
// SYSTEM CHARACTERISTICS
// - Range: 0002-0FFF
// **********************************************************
// Pairing characteristic: read device info, write WiFi credentials.
// Used by the dashboard BLE one-click pairing flow.
#define CHARACTERISTIC_PAIRING_UUID "522b443a-4f53-534d-0010-420badbabe69"

// **********************************************************
// Command and Configuration Characteristics
// - Range: 1000-1FFF
// - Description: These are writable and change the config of the device.
// **********************************************************
#define CHARACTERISTIC_UUID "522b443a-4f53-534d-1000-420badbabe69"
#define CHARACTERISTIC_SPEED_KNOB_CONFIG_UUID \
    "522b443a-4f53-534d-1010-420badbabe69"
#define CHARACTERISTIC_WIFI_CONFIG_UUID \
    "522b443a-4f53-534d-1020-420badbabe69"
#define CHARACTERISTIC_LATENCY_COMPENSATION_CONFIG_UUID \
    "522b443a-4f53-534d-1030-420badbabe69"
#define CHARACTERISTIC_RENAME_CONFIG_UUID \
    "522b443a-4f53-534d-1040-420badbabe69"
// **********************************************************
// State Characteristics
// - Range: 2000-2FFF
// - Description: Read and subscribe to the current state of the
// device.
// **********************************************************

// Clients should read the current state from this char.
#define CHARACTERISTIC_STATE_UUID "522b443a-4f53-534d-2000-420badbabe69"

// ************************************************
// Pattern Characteristics
// - Range: 3000-3FFF
// - Description: Read and request information about patterns on this device.
// ************************************************
#define CHARACTERISTIC_PATTERNS_UUID "522b443a-4f53-534d-3000-420badbabe69"
#define CHARACTERISTIC_GET_PATTERN_DATA_UUID \
    "522b443a-4f53-534d-3010-420badbabe69"

// ************************************************
// GPIO Characteristics
// - Range: 4000-4FFF
// - Description: Read and request information about patterns on this device.
// ************************************************
#define CHARACTERISTIC_GPIO_UUID "522b443a-4f53-534d-4000-420badbabe69"

// ************************************************
// ****************** ETC *************************
// ************************************************
#define MANUFACTURER_NAME_UUID "2A29"    // Standard UUID for manufacturer name
#define SYSTEM_ID_UUID "2A23"            // Standard UUID for system ID
#define DEVICE_INFO_SERVICE_UUID "180A"  // Device Information Service

extern NimBLEServer* pServer;

void nimbleLoop(void* pvParameters);
void initNimble();

#endif  // OSSM_NIMBLE_H
