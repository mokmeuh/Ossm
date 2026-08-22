#ifdef RESISTANCE_PROTOCOL_CODEC_CLI

#include <array>
#include <cstdint>
#include <iomanip>
#include <iostream>

#include "resistance_protocol.h"

namespace {

template <std::size_t Size>
void printHex(const char* name, const std::array<uint8_t, Size>& bytes) {
    std::cout << name << '=' << std::hex << std::setfill('0');
    for (const auto byte : bytes) {
        std::cout << std::setw(2) << static_cast<unsigned int>(byte);
    }
    std::cout << '\n';
}

}

int main() {
    printHex(
        "CAPABILITY_HEX",
        resistance_protocol::encodeCapability({
            resistance_protocol::kAllCapabilityBits,
            resistance_protocol::kTelemetryProperties,
            resistance_protocol::kConfigProperties,
        }));
    printHex(
        "TELEMETRY_HEX",
        resistance_protocol::encodeTelemetry({
            resistance_protocol::kFlagValid |
                resistance_protocol::kFlagFresh |
                resistance_protocol::kFlagCalibrationMatch |
                resistance_protocol::kFlagHomingValid |
                resistance_protocol::kFlagPositionInEnvelope,
            0x1234,
            0x01020304,
            0x0abc,
            0x0789,
            0x0700,
            750,
            7,
            3,
            0,
        }));
    printHex(
        "CONFIG_HEX",
        resistance_protocol::encodeConfig({
            resistance_protocol::kConfigArmed,
            10,
            5,
            650,
            450,
        }));
    return 0;
}

#endif
