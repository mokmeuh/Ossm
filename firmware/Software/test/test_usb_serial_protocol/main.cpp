#include <unity.h>

#include <array>
#include <cstdint>
#include <cstring>

#include "usb_serial_protocol.h"

using namespace usb_serial;

namespace {

void test_crc16_ccitt_matches_standard_vector() {
    constexpr char input[] = "123456789";
    TEST_ASSERT_EQUAL_HEX16(
        0x29b1,
        crc16Ccitt(reinterpret_cast<const uint8_t*>(input), sizeof(input) - 1));
}

void test_frame_round_trips_when_delivered_one_byte_at_a_time() {
    constexpr std::array<uint8_t, 5> payload{'H', 'E', 'L', 'L', 'O'};
    std::array<uint8_t, kMaxEncodedFrameSize> encoded{};
    const std::size_t size = encodeFrame(
        MessageType::Hello, payload.data(), payload.size(), encoded.data(),
        encoded.size());

    TEST_ASSERT_EQUAL_UINT8(kMagic0, encoded[0]);
    TEST_ASSERT_EQUAL_UINT8(kMagic1, encoded[1]);
    TEST_ASSERT_EQUAL_UINT8(kVersion, encoded[2]);
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(MessageType::Hello), encoded[3]);
    TEST_ASSERT_EQUAL_UINT16(payload.size(), size - kFrameOverhead);

    StreamDecoder decoder;
    DecodeResult result = DecodeResult::NeedMore;
    for (std::size_t index = 0; index < size; ++index) {
        result = decoder.push(encoded[index]);
    }

    TEST_ASSERT_EQUAL(DecodeResult::FrameReady, result);
    TEST_ASSERT_EQUAL(MessageType::Hello, decoder.frame().type);
    TEST_ASSERT_EQUAL_UINT16(payload.size(), decoder.frame().length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(
        payload.data(), decoder.frame().payload.data(), payload.size());
}

void test_bad_crc_is_rejected_and_next_frame_resynchronizes() {
    const std::array<uint8_t, 1> firstPayload{0x55};
    const std::array<uint8_t, 1> secondPayload{0xaa};
    std::array<uint8_t, kMaxEncodedFrameSize> first{};
    std::array<uint8_t, kMaxEncodedFrameSize> second{};
    const std::size_t firstSize = encodeFrame(
        MessageType::Heartbeat, firstPayload.data(), firstPayload.size(),
        first.data(), first.size());
    const std::size_t secondSize = encodeFrame(
        MessageType::Stop, secondPayload.data(), secondPayload.size(),
        second.data(), second.size());
    first[firstSize - 1] ^= 0x80;

    StreamDecoder decoder;
    DecodeResult result = DecodeResult::NeedMore;
    for (std::size_t index = 0; index < firstSize; ++index) {
        result = decoder.push(first[index]);
    }
    TEST_ASSERT_EQUAL(DecodeResult::InvalidCrc, result);

    for (std::size_t index = 0; index < secondSize; ++index) {
        result = decoder.push(second[index]);
    }
    TEST_ASSERT_EQUAL(DecodeResult::FrameReady, result);
    TEST_ASSERT_EQUAL(MessageType::Stop, decoder.frame().type);
    TEST_ASSERT_EQUAL_UINT8(0xaa, decoder.frame().payload[0]);
}

void test_oversized_length_is_rejected_without_buffering_payload() {
    StreamDecoder decoder;
    const uint16_t oversized = static_cast<uint16_t>(kMaxPayloadSize + 1);
    const std::array<uint8_t, 6> header{
        kMagic0, kMagic1, kVersion,
        static_cast<uint8_t>(MessageType::Command),
        static_cast<uint8_t>(oversized & 0xffU),
        static_cast<uint8_t>((oversized >> 8U) & 0xffU)};

    DecodeResult result = DecodeResult::NeedMore;
    for (const uint8_t byte : header) result = decoder.push(byte);

    TEST_ASSERT_EQUAL(DecodeResult::PayloadTooLarge, result);
}

void test_live_target_payload_is_preserved_exactly() {
    constexpr std::array<uint8_t, 12> livePacket{
        0x01, 0x00, 0x34, 0x12, 0xef, 0xcd,
        0xab, 0x89, 0xee, 0x02, 0x28, 0x00};
    std::array<uint8_t, kMaxEncodedFrameSize> encoded{};
    const std::size_t size = encodeFrame(
        MessageType::LiveTarget, livePacket.data(), livePacket.size(),
        encoded.data(), encoded.size());

    StreamDecoder decoder;
    DecodeResult result = DecodeResult::NeedMore;
    for (std::size_t index = 0; index < size; ++index) {
        result = decoder.push(encoded[index]);
    }

    TEST_ASSERT_EQUAL(DecodeResult::FrameReady, result);
    TEST_ASSERT_EQUAL_UINT16(livePacket.size(), decoder.frame().length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(
        livePacket.data(), decoder.frame().payload.data(), livePacket.size());
}

}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_crc16_ccitt_matches_standard_vector);
    RUN_TEST(test_frame_round_trips_when_delivered_one_byte_at_a_time);
    RUN_TEST(test_bad_crc_is_rejected_and_next_frame_resynchronizes);
    RUN_TEST(test_oversized_length_is_rejected_without_buffering_payload);
    RUN_TEST(test_live_target_payload_is_preserved_exactly);
    return UNITY_END();
}
