#include "resistance_protocol.h"

namespace resistance_protocol {
namespace {

uint16_t readU16(const uint8_t* data) {
    return static_cast<uint16_t>(data[0]) |
           static_cast<uint16_t>(static_cast<uint16_t>(data[1]) << 8U);
}

uint32_t readU32(const uint8_t* data) {
    return static_cast<uint32_t>(data[0]) |
           (static_cast<uint32_t>(data[1]) << 8U) |
           (static_cast<uint32_t>(data[2]) << 16U) |
           (static_cast<uint32_t>(data[3]) << 24U);
}

void writeU16(uint8_t* data, uint16_t value) {
    data[0] = static_cast<uint8_t>(value & 0xffU);
    data[1] = static_cast<uint8_t>((value >> 8U) & 0xffU);
}

void writeU32(uint8_t* data, uint32_t value) {
    data[0] = static_cast<uint8_t>(value & 0xffU);
    data[1] = static_cast<uint8_t>((value >> 8U) & 0xffU);
    data[2] = static_cast<uint8_t>((value >> 16U) & 0xffU);
    data[3] = static_cast<uint8_t>((value >> 24U) & 0xffU);
}

bool telemetryFlagsAreConsistent(const Telemetry& packet) {
    const bool valid = (packet.flags & kFlagValid) != 0;
    const bool fresh = (packet.flags & kFlagFresh) != 0;
    const bool saturated = (packet.flags & kFlagSaturated) != 0;
    const bool reversing = (packet.flags & kFlagReversing) != 0;
    const bool faultLatched = (packet.flags & kFlagFaultLatched) != 0;
    const bool homingValid = (packet.flags & kFlagHomingValid) != 0;
    const bool insideEnvelope =
        (packet.flags & kFlagPositionInEnvelope) != 0;

    if ((fresh && !valid) || (insideEnvelope && !homingValid)) return false;
    if (valid && (saturated || reversing || faultLatched)) return false;
    if (faultLatched != (packet.faultCode != 0)) return false;
    if ((packet.appliedSpeedBonus != 0 || packet.appliedDepthBonus != 0) &&
        !allowsAdaptiveBonus(packet)) {
        return false;
    }
    return true;
}

}

std::array<uint8_t, kCapabilityPacketSize> encodeCapability(
    const Capability& packet) {
    std::array<uint8_t, kCapabilityPacketSize> encoded{};
    encoded[0] = kVersion;
    writeU16(encoded.data() + 1, packet.capabilityBits);
    encoded[3] = packet.telemetryProperties;
    encoded[4] = packet.configProperties;
    encoded[5] = static_cast<uint8_t>(kTelemetryPacketSize);
    encoded[6] = static_cast<uint8_t>(kConfigPacketSize);
    return encoded;
}

std::array<uint8_t, kTelemetryPacketSize> encodeTelemetry(
    const Telemetry& packet) {
    std::array<uint8_t, kTelemetryPacketSize> encoded{};
    encoded[0] = kVersion;
    encoded[1] = packet.flags;
    writeU16(encoded.data() + 2, packet.sequence);
    writeU32(encoded.data() + 4, packet.timestampMs);
    writeU16(encoded.data() + 8, packet.raw);
    writeU16(encoded.data() + 10, packet.filtered);
    writeU16(encoded.data() + 12, packet.baseline);
    writeU16(encoded.data() + 14, packet.normalizedPermille);
    encoded[16] = packet.appliedSpeedBonus;
    encoded[17] = packet.appliedDepthBonus;
    encoded[18] = packet.faultCode;
    return encoded;
}

std::array<uint8_t, kConfigPacketSize> encodeConfig(const Config& packet) {
    std::array<uint8_t, kConfigPacketSize> encoded{};
    encoded[0] = kVersion;
    encoded[1] = packet.flags;
    encoded[2] = packet.maxSpeedBonus;
    encoded[3] = packet.maxDepthBonus;
    writeU16(encoded.data() + 4, packet.enterThresholdPermille);
    writeU16(encoded.data() + 6, packet.exitThresholdPermille);
    return encoded;
}

DecodeStatus decodeCapability(const uint8_t* data, std::size_t length,
                              Capability& packet) {
    if (data == nullptr || length != kCapabilityPacketSize) {
        return DecodeStatus::invalid_length;
    }
    if (data[0] != kVersion) return DecodeStatus::unsupported_version;
    if (data[7] != 0) return DecodeStatus::invalid_flags;

    Capability decoded{readU16(data + 1), data[3], data[4]};
    if ((decoded.capabilityBits & ~kAllCapabilityBits) != 0 ||
        decoded.telemetryProperties != kTelemetryProperties ||
        decoded.configProperties != kConfigProperties ||
        data[5] != kTelemetryPacketSize || data[6] != kConfigPacketSize) {
        return DecodeStatus::invalid_flags;
    }
    packet = decoded;
    return DecodeStatus::ok;
}

DecodeStatus decodeTelemetry(const uint8_t* data, std::size_t length,
                             Telemetry& packet,
                             const uint16_t* lastSequence) {
    if (data == nullptr || length != kTelemetryPacketSize) {
        return DecodeStatus::invalid_length;
    }
    if (data[0] != kVersion) return DecodeStatus::unsupported_version;
    if (data[19] != 0) return DecodeStatus::invalid_flags;

    Telemetry decoded{
        data[1],          readU16(data + 2),  readU32(data + 4),
        readU16(data + 8),  readU16(data + 10), readU16(data + 12),
        readU16(data + 14), data[16],           data[17],
        data[18],
    };
    if (decoded.raw > kMaxAdcValue || decoded.filtered > kMaxAdcValue ||
        decoded.baseline > kMaxAdcValue ||
        decoded.normalizedPermille > kMaxPermille ||
        decoded.appliedSpeedBonus > kMaxSpeedBonus ||
        decoded.appliedDepthBonus > kMaxDepthBonus) {
        return DecodeStatus::out_of_range;
    }
    if (!telemetryFlagsAreConsistent(decoded)) {
        return DecodeStatus::invalid_flags;
    }
    if (lastSequence != nullptr &&
        !isSequenceNewer(decoded.sequence, *lastSequence)) {
        return DecodeStatus::stale_sequence;
    }
    packet = decoded;
    return DecodeStatus::ok;
}

DecodeStatus decodeConfig(const uint8_t* data, std::size_t length,
                          Config& packet) {
    if (data == nullptr || length != kConfigPacketSize) {
        return DecodeStatus::invalid_length;
    }
    if (data[0] != kVersion) return DecodeStatus::unsupported_version;

    Config decoded{
        data[1], data[2], data[3], readU16(data + 4), readU16(data + 6),
    };
    if ((decoded.flags & ~kAllConfigFlags) != 0 ||
        decoded.flags == kAllConfigFlags) {
        return DecodeStatus::invalid_flags;
    }
    if (decoded.maxSpeedBonus > kMaxSpeedBonus ||
        decoded.maxDepthBonus > kMaxDepthBonus ||
        decoded.enterThresholdPermille > kMaxPermille ||
        decoded.exitThresholdPermille > kMaxPermille ||
        decoded.exitThresholdPermille > decoded.enterThresholdPermille) {
        return DecodeStatus::out_of_range;
    }
    packet = decoded;
    return DecodeStatus::ok;
}

bool isSequenceNewer(uint16_t candidate, uint16_t previous) {
    const uint16_t delta = static_cast<uint16_t>(candidate - previous);
    return delta != 0 && delta < 0x8000U;
}

bool allowsAdaptiveBonus(const Telemetry& packet) {
    const uint8_t required = kFlagValid | kFlagFresh | kFlagCalibrationMatch |
                             kFlagHomingValid | kFlagPositionInEnvelope;
    const uint8_t blocking =
        kFlagSaturated | kFlagReversing | kFlagFaultLatched;
    return (packet.flags & required) == required &&
           (packet.flags & blocking) == 0;
}

}
