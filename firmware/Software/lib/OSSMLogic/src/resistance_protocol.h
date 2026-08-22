#ifndef OSSM_LOGIC_RESISTANCE_PROTOCOL_H
#define OSSM_LOGIC_RESISTANCE_PROTOCOL_H

#include <array>
#include <cstddef>
#include <cstdint>

namespace resistance_protocol {

inline constexpr char kServiceUuid[] =
    "522b443a-4f53-534d-0001-420badbabe69";
inline constexpr char kCapabilityUuid[] =
    "522b443a-4f53-534d-5000-420badbabe69";
inline constexpr char kTelemetryUuid[] =
    "522b443a-4f53-534d-6000-420badbabe69";
inline constexpr char kConfigUuid[] =
    "522b443a-4f53-534d-6100-420badbabe69";

inline constexpr uint8_t kVersion = 1;
inline constexpr uint32_t kNegotiationTimeoutMs = 1000;
inline constexpr std::size_t kCapabilityPacketSize = 8;
inline constexpr std::size_t kTelemetryPacketSize = 20;
inline constexpr std::size_t kConfigPacketSize = 8;

inline constexpr uint16_t kCapabilityResistanceTelemetry = 1U << 0U;
inline constexpr uint16_t kCapabilityResistanceConfig = 1U << 1U;
inline constexpr uint16_t kCapabilityResistanceAvailable = 1U << 2U;
inline constexpr uint16_t kAllCapabilityBits =
    kCapabilityResistanceTelemetry | kCapabilityResistanceConfig |
    kCapabilityResistanceAvailable;

inline constexpr uint8_t kPropertyRead = 1U << 0U;
inline constexpr uint8_t kPropertyNotify = 1U << 1U;
inline constexpr uint8_t kPropertyWrite = 1U << 2U;
inline constexpr uint8_t kCapabilityProperties = kPropertyRead;
inline constexpr uint8_t kTelemetryProperties =
    kPropertyRead | kPropertyNotify;
inline constexpr uint8_t kConfigProperties = kPropertyRead | kPropertyWrite;

inline constexpr uint8_t kFlagValid = 1U << 0U;
inline constexpr uint8_t kFlagFresh = 1U << 1U;
inline constexpr uint8_t kFlagSaturated = 1U << 2U;
inline constexpr uint8_t kFlagReversing = 1U << 3U;
inline constexpr uint8_t kFlagFaultLatched = 1U << 4U;
inline constexpr uint8_t kFlagCalibrationMatch = 1U << 5U;
inline constexpr uint8_t kFlagHomingValid = 1U << 6U;
inline constexpr uint8_t kFlagPositionInEnvelope = 1U << 7U;

inline constexpr uint8_t kConfigArmed = 1U << 0U;
inline constexpr uint8_t kConfigAcknowledgeFault = 1U << 1U;
inline constexpr uint8_t kAllConfigFlags =
    kConfigArmed | kConfigAcknowledgeFault;

inline constexpr uint8_t kFaultRepeatedEndpointPressure = 1;
inline constexpr uint8_t kMaxSpeedBonus = 10;
inline constexpr uint8_t kMaxDepthBonus = 5;
inline constexpr uint16_t kMaxPermille = 1000;
inline constexpr uint16_t kMaxAdcValue = 4095;

enum class DecodeStatus : uint8_t {
    ok,
    invalid_length,
    unsupported_version,
    invalid_flags,
    out_of_range,
    stale_sequence,
};

struct Capability {
    uint16_t capabilityBits;
    uint8_t telemetryProperties;
    uint8_t configProperties;
};

struct Telemetry {
    uint8_t flags;
    uint16_t sequence;
    uint32_t timestampMs;
    uint16_t raw;
    uint16_t filtered;
    uint16_t baseline;
    uint16_t normalizedPermille;
    uint8_t appliedSpeedBonus;
    uint8_t appliedDepthBonus;
    uint8_t faultCode;
};

struct Config {
    uint8_t flags;
    uint8_t maxSpeedBonus;
    uint8_t maxDepthBonus;
    uint16_t enterThresholdPermille;
    uint16_t exitThresholdPermille;
};

std::array<uint8_t, kCapabilityPacketSize> encodeCapability(
    const Capability& packet);
std::array<uint8_t, kTelemetryPacketSize> encodeTelemetry(
    const Telemetry& packet);
std::array<uint8_t, kConfigPacketSize> encodeConfig(const Config& packet);

DecodeStatus decodeCapability(const uint8_t* data, std::size_t length,
                              Capability& packet);
DecodeStatus decodeTelemetry(const uint8_t* data, std::size_t length,
                             Telemetry& packet,
                             const uint16_t* lastSequence = nullptr);
DecodeStatus decodeConfig(const uint8_t* data, std::size_t length,
                          Config& packet);

bool isSequenceNewer(uint16_t candidate, uint16_t previous);
bool allowsAdaptiveBonus(const Telemetry& packet);

}

#endif
