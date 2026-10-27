#include <unity.h>

#include <array>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>

#include "resistance_protocol.h"

namespace {

std::string readProjectFile(const std::filesystem::path& relativePath) {
    const auto projectRoot =
        std::filesystem::path(__FILE__).parent_path().parent_path().parent_path();
    std::ifstream input(projectRoot / relativePath);
    std::ostringstream contents;
    contents << input.rdbuf();
    return contents.str();
}

void assertContains(const std::string& value, const char* expected) {
    TEST_ASSERT_NOT_EQUAL_MESSAGE(
        std::string::npos, value.find(expected), expected);
}

std::size_t countOccurrences(const std::string& value, const char* token) {
    std::size_t count = 0;
    std::size_t position = 0;
    while ((position = value.find(token, position)) != std::string::npos) {
        ++count;
        position += std::char_traits<char>::length(token);
    }
    return count;
}

void test_characterizes_existing_ble_uuids_and_state_characteristic() {
    const auto nimble =
        readProjectFile("src/services/communication/nimble.h");
    assertContains(nimble, "522b443a-4f53-534d-0001-420badbabe69");
    assertContains(nimble, "522b443a-4f53-534d-1000-420badbabe69");
    assertContains(nimble, "522b443a-4f53-534d-2000-420badbabe69");
    assertContains(nimble, "522b443a-4f53-534d-3000-420badbabe69");
    assertContains(nimble, "522b443a-4f53-534d-4000-420badbabe69");

    const auto state =
        readProjectFile("src/services/communication/state.hpp");
    assertContains(state, "NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY");
    assertContains(state, "\"ok:boot\"");
}

void test_characterizes_existing_state_json_cardinality() {
    const auto ossm = readProjectFile("src/ossm/OSSM.cpp");
    const auto functionStart = ossm.find("String OSSM::getCurrentState()");
    const auto finalKey = ossm.find(",\\\"sessionId\\\":\\\"", functionStart);
    const auto functionEnd = ossm.find("\n}", finalKey);
    TEST_ASSERT_NOT_EQUAL(std::string::npos, functionStart);
    TEST_ASSERT_NOT_EQUAL(std::string::npos, finalKey);
    TEST_ASSERT_NOT_EQUAL(std::string::npos, functionEnd);
    const auto functionBody =
        ossm.substr(functionStart, functionEnd - functionStart);
    const char* fields[] = {
        "{\\\"timestamp\\\":", ",\\\"state\\\":\\\"", ",\\\"speed\\\":",
        ",\\\"stroke\\\":",   ",\\\"sensation\\\":", ",\\\"depth\\\":",
        ",\\\"buffer\\\":",   ",\\\"pattern\\\":",   ",\\\"position\\\":",
        ",\\\"remoteControlSafe\\\":true", ",\\\"watchdogMs\\\":",
        ",\\\"homed\\\":", ",\\\"transport\\\":\\\"",
        ",\\\"sessionId\\\":\\\"",
    };

    for (const auto* field : fields) {
        assertContains(functionBody, field);
    }
    TEST_ASSERT_EQUAL_UINT32(
        sizeof(fields) / sizeof(fields[0]), countOccurrences(functionBody, "\\\":"));
}

void test_capability_golden_vector() {
    resistance_protocol::Capability packet{
        resistance_protocol::kAllCapabilityBits,
        resistance_protocol::kPropertyRead | resistance_protocol::kPropertyNotify,
        resistance_protocol::kPropertyRead | resistance_protocol::kPropertyWrite,
    };
    const std::array<uint8_t, resistance_protocol::kCapabilityPacketSize> expected{
        0x01, 0x07, 0x00, 0x03, 0x05, 0x14, 0x08, 0x00,
    };

    const auto encoded = resistance_protocol::encodeCapability(packet);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(expected.data(), encoded.data(), expected.size());

    resistance_protocol::Capability decoded{};
    TEST_ASSERT_EQUAL(
        resistance_protocol::DecodeStatus::ok,
        resistance_protocol::decodeCapability(encoded.data(), encoded.size(), decoded));
    TEST_ASSERT_EQUAL_UINT16(0x0007, decoded.capabilityBits);
    TEST_ASSERT_EQUAL_UINT8(0x03, decoded.telemetryProperties);
    TEST_ASSERT_EQUAL_UINT8(0x05, decoded.configProperties);
}

void test_telemetry_golden_vector_and_little_endian_fields() {
    resistance_protocol::Telemetry packet{
        resistance_protocol::kFlagValid | resistance_protocol::kFlagFresh |
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
    };
    const std::array<uint8_t, resistance_protocol::kTelemetryPacketSize> expected{
        0x01, 0xe3, 0x34, 0x12, 0x04, 0x03, 0x02, 0x01, 0xbc, 0x0a,
        0x89, 0x07, 0x00, 0x07, 0xee, 0x02, 0x07, 0x03, 0x00, 0x00,
    };

    const auto encoded = resistance_protocol::encodeTelemetry(packet);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(expected.data(), encoded.data(), expected.size());

    resistance_protocol::Telemetry decoded{};
    TEST_ASSERT_EQUAL(
        resistance_protocol::DecodeStatus::ok,
        resistance_protocol::decodeTelemetry(encoded.data(), encoded.size(), decoded));
    TEST_ASSERT_EQUAL_UINT16(0x1234, decoded.sequence);
    TEST_ASSERT_EQUAL_UINT32(0x01020304, decoded.timestampMs);
    TEST_ASSERT_EQUAL_UINT16(0x0abc, decoded.raw);
    TEST_ASSERT_EQUAL_UINT16(0x0789, decoded.filtered);
    TEST_ASSERT_EQUAL_UINT16(0x0700, decoded.baseline);
    TEST_ASSERT_EQUAL_UINT16(750, decoded.normalizedPermille);
    TEST_ASSERT_EQUAL_UINT8(7, decoded.appliedSpeedBonus);
    TEST_ASSERT_EQUAL_UINT8(3, decoded.appliedDepthBonus);
}

void test_config_golden_vector_and_bounds() {
    resistance_protocol::Config packet{
        resistance_protocol::kConfigArmed,
        10,
        5,
        650,
        450,
    };
    const std::array<uint8_t, resistance_protocol::kConfigPacketSize> expected{
        0x01, 0x01, 0x0a, 0x05, 0x8a, 0x02, 0xc2, 0x01,
    };

    const auto encoded = resistance_protocol::encodeConfig(packet);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(expected.data(), encoded.data(), expected.size());

    resistance_protocol::Config decoded{};
    TEST_ASSERT_EQUAL(
        resistance_protocol::DecodeStatus::ok,
        resistance_protocol::decodeConfig(encoded.data(), encoded.size(), decoded));
    TEST_ASSERT_EQUAL_UINT8(10, decoded.maxSpeedBonus);
    TEST_ASSERT_EQUAL_UINT8(5, decoded.maxDepthBonus);
    TEST_ASSERT_EQUAL_UINT16(650, decoded.enterThresholdPermille);
    TEST_ASSERT_EQUAL_UINT16(450, decoded.exitThresholdPermille);
}

void test_rejects_malformed_future_stale_and_invalid_packets() {
    const auto valid = resistance_protocol::encodeTelemetry({
        resistance_protocol::kFlagValid | resistance_protocol::kFlagFresh |
            resistance_protocol::kFlagCalibrationMatch |
            resistance_protocol::kFlagHomingValid |
            resistance_protocol::kFlagPositionInEnvelope,
        0x1234,
        1000,
        1200,
        1100,
        1000,
        500,
        4,
        2,
        0,
    });
    resistance_protocol::Telemetry decoded{};

    TEST_ASSERT_EQUAL(
        resistance_protocol::DecodeStatus::invalid_length,
        resistance_protocol::decodeTelemetry(valid.data(), valid.size() - 1, decoded));

    auto future = valid;
    future[0] = 2;
    TEST_ASSERT_EQUAL(
        resistance_protocol::DecodeStatus::unsupported_version,
        resistance_protocol::decodeTelemetry(future.data(), future.size(), decoded));

    auto invalidFlags = valid;
    invalidFlags[1] = 0x80;
    TEST_ASSERT_EQUAL(
        resistance_protocol::DecodeStatus::invalid_flags,
        resistance_protocol::decodeTelemetry(
            invalidFlags.data(), invalidFlags.size(), decoded));

    const uint16_t newerSequence = 0x1235;
    TEST_ASSERT_EQUAL(
        resistance_protocol::DecodeStatus::stale_sequence,
        resistance_protocol::decodeTelemetry(
            valid.data(), valid.size(), decoded, &newerSequence));
    TEST_ASSERT_TRUE(resistance_protocol::isSequenceNewer(0, 0xffff));
    TEST_ASSERT_FALSE(resistance_protocol::isSequenceNewer(0xffff, 0));

    auto invalidConfig = resistance_protocol::encodeConfig({
        resistance_protocol::kConfigArmed,
        10,
        5,
        450,
        650,
    });
    resistance_protocol::Config config{};
    TEST_ASSERT_EQUAL(
        resistance_protocol::DecodeStatus::out_of_range,
        resistance_protocol::decodeConfig(
            invalidConfig.data(), invalidConfig.size(), config));

    auto capability = resistance_protocol::encodeCapability({
        resistance_protocol::kAllCapabilityBits,
        resistance_protocol::kTelemetryProperties,
        resistance_protocol::kConfigProperties,
    });
    resistance_protocol::Capability decodedCapability{};
    TEST_ASSERT_EQUAL(
        resistance_protocol::DecodeStatus::invalid_length,
        resistance_protocol::decodeCapability(
            capability.data(), capability.size() - 1, decodedCapability));
    capability[0] = 2;
    TEST_ASSERT_EQUAL(
        resistance_protocol::DecodeStatus::unsupported_version,
        resistance_protocol::decodeCapability(
            capability.data(), capability.size(), decodedCapability));

    auto configBytes = resistance_protocol::encodeConfig({
        resistance_protocol::kConfigArmed,
        10,
        5,
        650,
        450,
    });
    TEST_ASSERT_EQUAL(
        resistance_protocol::DecodeStatus::invalid_length,
        resistance_protocol::decodeConfig(
            configBytes.data(), configBytes.size() - 1, config));
    configBytes[0] = 2;
    TEST_ASSERT_EQUAL(
        resistance_protocol::DecodeStatus::unsupported_version,
        resistance_protocol::decodeConfig(
            configBytes.data(), configBytes.size(), config));
}

void test_adaptive_bonus_requires_homing_and_calibrated_envelope() {
    resistance_protocol::Telemetry safe{
        resistance_protocol::kFlagValid | resistance_protocol::kFlagFresh |
            resistance_protocol::kFlagCalibrationMatch |
            resistance_protocol::kFlagHomingValid |
            resistance_protocol::kFlagPositionInEnvelope,
        1,
        1000,
        1200,
        1100,
        1000,
        500,
        4,
        2,
        0,
    };
    TEST_ASSERT_TRUE(resistance_protocol::allowsAdaptiveBonus(safe));

    auto homingLost = safe;
    homingLost.flags &= ~resistance_protocol::kFlagHomingValid;
    TEST_ASSERT_FALSE(resistance_protocol::allowsAdaptiveBonus(homingLost));
    const auto homingLostBytes = resistance_protocol::encodeTelemetry(homingLost);
    resistance_protocol::Telemetry decoded{};
    TEST_ASSERT_EQUAL(
        resistance_protocol::DecodeStatus::invalid_flags,
        resistance_protocol::decodeTelemetry(
            homingLostBytes.data(), homingLostBytes.size(), decoded));

    auto outsideEnvelope = safe;
    outsideEnvelope.flags &= ~resistance_protocol::kFlagPositionInEnvelope;
    TEST_ASSERT_FALSE(resistance_protocol::allowsAdaptiveBonus(outsideEnvelope));

    auto endpointPressure = safe;
    endpointPressure.flags |= resistance_protocol::kFlagFaultLatched;
    endpointPressure.faultCode = resistance_protocol::kFaultRepeatedEndpointPressure;
    TEST_ASSERT_FALSE(resistance_protocol::allowsAdaptiveBonus(endpointPressure));
}

void test_contract_constants_fit_mtu23_and_freeze_uuid_properties() {
    TEST_ASSERT_LESS_OR_EQUAL_UINT8(20, resistance_protocol::kCapabilityPacketSize);
    TEST_ASSERT_LESS_OR_EQUAL_UINT8(20, resistance_protocol::kTelemetryPacketSize);
    TEST_ASSERT_LESS_OR_EQUAL_UINT8(20, resistance_protocol::kConfigPacketSize);
    TEST_ASSERT_EQUAL_UINT32(1000, resistance_protocol::kNegotiationTimeoutMs);
    TEST_ASSERT_EQUAL_STRING(
        "522b443a-4f53-534d-0001-420badbabe69",
        resistance_protocol::kServiceUuid);
    TEST_ASSERT_EQUAL_STRING(
        "522b443a-4f53-534d-5000-420badbabe69",
        resistance_protocol::kCapabilityUuid);
    TEST_ASSERT_EQUAL_STRING(
        "522b443a-4f53-534d-6000-420badbabe69",
        resistance_protocol::kTelemetryUuid);
    TEST_ASSERT_EQUAL_STRING(
        "522b443a-4f53-534d-6100-420badbabe69",
        resistance_protocol::kConfigUuid);
    TEST_ASSERT_EQUAL_UINT8(
        resistance_protocol::kPropertyRead,
        resistance_protocol::kCapabilityProperties);
    TEST_ASSERT_EQUAL_UINT8(
        resistance_protocol::kPropertyRead | resistance_protocol::kPropertyNotify,
        resistance_protocol::kTelemetryProperties);
    TEST_ASSERT_EQUAL_UINT8(
        resistance_protocol::kPropertyRead | resistance_protocol::kPropertyWrite,
        resistance_protocol::kConfigProperties);
}

}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_characterizes_existing_ble_uuids_and_state_characteristic);
    RUN_TEST(test_characterizes_existing_state_json_cardinality);
    RUN_TEST(test_capability_golden_vector);
    RUN_TEST(test_telemetry_golden_vector_and_little_endian_fields);
    RUN_TEST(test_config_golden_vector_and_bounds);
    RUN_TEST(test_rejects_malformed_future_stale_and_invalid_packets);
    RUN_TEST(test_adaptive_bonus_requires_homing_and_calibrated_envelope);
    RUN_TEST(test_contract_constants_fit_mtu23_and_freeze_uuid_properties);
    return UNITY_END();
}
