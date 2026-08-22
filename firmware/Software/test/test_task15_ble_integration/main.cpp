#include <iostream>

#include "services/communication/nimble.h"
#include "resistance_safety.h"

int optionalBleContractAndTelemetryBehavior() {
    using namespace optional_ble;

    const auto capability = capabilityPacket();
    if (capability.capabilityBits != 0 || kResistanceSignalGatePassed ||
        kResistanceTelemetryIntervalMs != 50 || telemetryDue(1049, 1000) ||
        !telemetryDue(1050, 1000) || !telemetryDue(35, 0xfffffff0) ||
        !shouldRefreshStateValue(false, true) ||
        !shouldRefreshStateValue(true, false) ||
        shouldRefreshStateValue(false, false)) {
        return 1;
    }

    resistance_protocol::Config current{};
    auto valid = resistance_protocol::encodeConfig({
        resistance_protocol::kConfigArmed, 10, 5, 650, 450});
    if (applyResistanceConfig(valid.data(), valid.size(), current) !=
        ConfigWriteStatus::signal_gate_unavailable) {
        return 2;
    }

    auto aboveCaps = valid;
    aboveCaps[2] = 11;
    if (applyResistanceConfig(aboveCaps.data(), aboveCaps.size(), current) !=
        ConfigWriteStatus::invalid_packet) {
        return 3;
    }

    const uint8_t validFlags =
        resistance_protocol::kFlagValid |
        resistance_protocol::kFlagFresh |
        resistance_protocol::kFlagCalibrationMatch |
        resistance_protocol::kFlagHomingValid |
        resistance_protocol::kFlagPositionInEnvelope;
    const resistance_protocol::Telemetry firstPacket{
        validFlags, 0xfffeU, 0x01020304U, 1200, 1100, 1000, 500, 0, 0, 0};
    const auto firstEncoded = resistance_protocol::encodeTelemetry(firstPacket);
    if (firstEncoded[4] != 0x04 || firstEncoded[5] != 0x03 ||
        firstEncoded[6] != 0x02 || firstEncoded[7] != 0x01) {
        return 4;
    }

    resistance_protocol::Telemetry firstDecoded{};
    if (resistance_protocol::decodeTelemetry(
            firstEncoded.data(), firstEncoded.size(), firstDecoded) !=
            resistance_protocol::DecodeStatus::ok ||
        firstDecoded.sequence != 0xfffeU ||
        firstDecoded.timestampMs != 0x01020304U) {
        return 5;
    }

    const uint16_t nextSequence = nextTelemetrySequence(firstDecoded.sequence);
    const uint16_t rolloverSequence = nextTelemetrySequence(0xffffU);
    if (nextSequence != 0xffffU || rolloverSequence != 0U ||
        !resistance_protocol::isSequenceNewer(rolloverSequence, 0xffffU)) {
        return 6;
    }

    const auto rolloverEncoded = resistance_protocol::encodeTelemetry({
        validFlags, rolloverSequence, 0x01020354U, 1200, 1100, 1000, 500, 0,
        0, 0});
    resistance_protocol::Telemetry rolloverDecoded{};
    const uint16_t previousSequence = 0xffffU;
    if (resistance_protocol::decodeTelemetry(
            rolloverEncoded.data(), rolloverEncoded.size(), rolloverDecoded,
            &previousSequence) != resistance_protocol::DecodeStatus::ok ||
        rolloverDecoded.sequence != 0U ||
        rolloverDecoded.timestampMs != 0x01020354U) {
        return 7;
    }

    if (resistance_protocol::decodeTelemetry(
            firstEncoded.data(), firstEncoded.size(), firstDecoded,
            &firstDecoded.sequence) != resistance_protocol::DecodeStatus::stale_sequence) {
        return 8;
    }

    resistance_safety::Compositor compositor;
    const resistance_safety::CompositorRequest request{
        40, 20, 60, 100, 100, 100, 100, 10, 5};
    const resistance_safety::Telemetry fresh{
        50, 1000, true, false, false, false};
    if (!compositor.update(request, fresh, 1100, true).telemetryUsable ||
        compositor.update(request, fresh, 1101, true).telemetryUsable) {
        return 9;
    }

    return 0;
}

int main(int, char**) {
    const int result = optionalBleContractAndTelemetryBehavior();
    if (result != 0) {
        std::cerr << "TASK15_PROTOCOL_INTEGRATION_FAIL group=" << result << '\n';
        return result;
    }
    std::cout << "TASK15_PROTOCOL_INTEGRATION_PASS gate=FAIL resistance_bits=0 "
                 "telemetry_interval_ms=50 sequence=INCREMENT_ROLLOVER "
                 "timestamp=ENCODED stale_sequence=REJECT freshness=100ms\n";
    return 0;
}
