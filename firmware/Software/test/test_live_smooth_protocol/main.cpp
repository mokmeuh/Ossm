#include <unity.h>

#include <array>
#include <cstdio>
#include <cstdint>

#include "live_smooth_protocol.h"

using namespace live_smooth;

namespace {

Packet packet(uint16_t sequence, uint32_t deadlineMs, uint16_t positionPermille = 500) {
    return Packet{sequence, deadlineMs, positionPermille, 20};
}

SafetyState safeState() {
    return SafetyState{true, 10000, 1000, 9000, 5000, false};
}

void test_golden_vector_is_little_endian_and_round_trips() {
    const Packet target{0x1234, 0x89abcdef, 750, 40};
    const std::array<uint8_t, kPacketSize> golden{
        0x01, 0x00, 0x34, 0x12, 0xef, 0xcd, 0xab, 0x89, 0xee, 0x02, 0x28, 0x00};

    TEST_ASSERT_EQUAL_STRING("522b443a-4f53-534d-7000-420badbabe69", kCharacteristicUuid);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(golden.data(), encode(target).data(), golden.size());
    Packet decoded{};
    TEST_ASSERT_EQUAL(DecodeStatus::Ok, decode(golden.data(), golden.size(), decoded));
    TEST_ASSERT_TRUE(target == decoded);
    TEST_ASSERT_LESS_OR_EQUAL(20, golden.size());
}

void test_malformed_future_and_stop_like_packets_are_rejected() {
    auto bytes = encode(packet(1, 100));
    Packet decoded{};
    TEST_ASSERT_EQUAL(DecodeStatus::InvalidLength, decode(bytes.data(), bytes.size() - 1, decoded));
    bytes[0] = 2;
    TEST_ASSERT_EQUAL(DecodeStatus::UnsupportedVersion, decode(bytes.data(), bytes.size(), decoded));
    bytes = encode(packet(1, 100));
    bytes[1] = 1;
    TEST_ASSERT_EQUAL(DecodeStatus::InvalidFlags, decode(bytes.data(), bytes.size(), decoded));
    bytes = encode(packet(1, 100, 1001));
    TEST_ASSERT_EQUAL(DecodeStatus::InvalidPosition, decode(bytes.data(), bytes.size(), decoded));
}

void test_sequence_duplicate_and_wrap_rules() {
    TEST_ASSERT_FALSE(isNewerSequence(7, 7));
    TEST_ASSERT_FALSE(isNewerSequence(6, 7));
    TEST_ASSERT_TRUE(isNewerSequence(0, 0xffff));
    TEST_ASSERT_FALSE(isNewerSequence(0x8000, 0));
}

void test_first_target_and_zero_distance_are_safe() {
    QueueModel queue(safeState());
    TEST_ASSERT_EQUAL(OfferResult::Accepted, queue.offer(packet(1, 100, 500), 0));
    const auto first = queue.poll(0);
    TEST_ASSERT_TRUE(first.has_value());
    TEST_ASSERT_EQUAL_UINT16(500, first->positionPermille);
    TEST_ASSERT_EQUAL(OfferResult::Accepted, queue.offer(packet(2, 100, 500), 20));
    TEST_ASSERT_EQUAL_INT32(0, distancePermille(500, *queue.poll(20)));
}

void test_100_hz_flood_coalesces_and_stays_bounded() {
    QueueModel queue(safeState());
    for (uint16_t index = 0; index < 10; ++index) {
        const auto expected = index % 2 == 0 && index < 8
                                  ? OfferResult::Accepted
                                  : OfferResult::Coalesced;
        TEST_ASSERT_EQUAL(expected, queue.offer(packet(index, 500, index * 100), index * 10));
        TEST_ASSERT_LESS_OR_EQUAL(kQueueCapacity, queue.depth());
    }
    TEST_ASSERT_LESS_OR_EQUAL(kQueueCapacity, queue.maxDepthObserved());
    TEST_ASSERT_EQUAL_UINT16(900, queue.pending(queue.depth() - 1).positionPermille);
}

void test_full_reversal_burst_replaces_only_newest() {
    QueueModel queue(safeState());
    const uint16_t positions[] = {100, 900, 100, 900};
    for (uint16_t index = 0; index < 4; ++index) {
        TEST_ASSERT_EQUAL(OfferResult::Accepted, queue.offer(packet(index, 500, positions[index]), index * 20));
    }
    TEST_ASSERT_EQUAL(OfferResult::Coalesced, queue.offer(packet(4, 500, 100), 80));
    TEST_ASSERT_EQUAL_UINT16(100, queue.pending(0).positionPermille);
    TEST_ASSERT_EQUAL_UINT16(900, queue.pending(1).positionPermille);
    TEST_ASSERT_EQUAL_UINT16(100, queue.pending(2).positionPermille);
    TEST_ASSERT_EQUAL_UINT16(100, queue.pending(3).positionPermille);
    TEST_ASSERT_EQUAL_UINT8(4, queue.depth());
}

void test_duplicate_stale_expired_and_stop_are_bounded() {
    QueueModel queue(safeState());
    TEST_ASSERT_EQUAL(OfferResult::Accepted, queue.offer(packet(0xffff, 300), 0));
    TEST_ASSERT_EQUAL(OfferResult::Duplicate, queue.offer(packet(0xffff, 300), 20));
    TEST_ASSERT_EQUAL(OfferResult::StaleSequence, queue.offer(packet(0xfffe, 300), 40));
    TEST_ASSERT_EQUAL(OfferResult::Accepted, queue.offer(packet(0, 300), 60));
    TEST_ASSERT_EQUAL(OfferResult::Expired,
                      queue.offer(Packet{1, 99, 500, 0}, 100));
    TEST_ASSERT_EQUAL_UINT16(0, queue.poll(251)->sequence);
    TEST_ASSERT_FALSE(queue.poll(311).has_value());
    TEST_ASSERT_EQUAL_UINT8(0, queue.depth());

    for (uint16_t index = 1; index <= kQueueCapacity; ++index) {
        queue.offer(packet(index, 500), 100 + index * 20);
    }
    queue.stopOutOfBand();
    TEST_ASSERT_EQUAL_UINT8(0, queue.depth());
    TEST_ASSERT_TRUE(queue.isStopped());
    TEST_ASSERT_FALSE(queue.poll(250).has_value());
}

void test_invalid_homing_envelope_and_endpoint_pressure_inhibit_motion() {
    QueueModel queue(safeState());
    TEST_ASSERT_EQUAL(OfferResult::Accepted, queue.offer(packet(1, 500), 0));
    auto unsafe = safeState();
    unsafe.homingValid = false;
    queue.updateSafety(unsafe);
    TEST_ASSERT_EQUAL_UINT8(0, queue.depth());
    TEST_ASSERT_TRUE(queue.isStopped());
    TEST_ASSERT_FALSE(queue.motionAllowed());
    TEST_ASSERT_EQUAL(OfferResult::SafetyInhibited, queue.offer(packet(2, 500), 20));

    unsafe = safeState();
    unsafe.repeatedEndpointPressure = true;
    QueueModel endpointPressureQueue(unsafe);
    TEST_ASSERT_EQUAL(OfferResult::SafetyInhibited, endpointPressureQueue.offer(packet(1, 500), 0));
    TEST_ASSERT_EQUAL_UINT8(0, endpointPressureQueue.depth());
}

void test_deadline_is_rebased_from_sender_to_receiver_clock_domain() {
    QueueModel queue(safeState());
    const uint32_t receiverNowMs = 100000;

    TEST_ASSERT_EQUAL(
        OfferResult::Accepted,
        queue.offer(packet(1, 100, 500), receiverNowMs));
    const auto target = queue.poll(receiverNowMs);
    TEST_ASSERT_TRUE(target.has_value());
    TEST_ASSERT_EQUAL_UINT32(receiverNowMs + kTargetExpiryMs,
                             target->deadlineMs);
}

void test_mode_can_arm_after_setup_makes_the_envelope_safe() {
    auto startup = safeState();
    startup.shallowEndpointSteps = -7500;
    startup.deepEndpointSteps = -2500;
    startup.currentPositionSteps = 0;
    QueueModel queue(startup);

    TEST_ASSERT_FALSE(queue.resume());
    TEST_ASSERT_FALSE(queue.motionAllowed());

    auto configured = startup;
    configured.shallowEndpointSteps = -10000;
    configured.deepEndpointSteps = 0;
    queue.updateSafety(configured);

    TEST_ASSERT_TRUE(queue.resume());
    TEST_ASSERT_TRUE(queue.motionAllowed());
    TEST_ASSERT_EQUAL(OfferResult::Accepted,
                      queue.offer(packet(1, 500, 950), 100));
}

void test_deterministic_trace_reports_queue_outcomes() {
    QueueModel queue(safeState());
    std::size_t accepted = 0;
    std::size_t coalesced = 0;
    std::size_t expiredTargets = 0;
    const Packet trace[] = {
        packet(1, 500, 100), packet(2, 500, 900), packet(3, 500, 100),
        packet(4, 500, 900), packet(5, 500, 100), packet(6, 500, 900),
        Packet{7, 99, 500, 0}};
    const uint32_t arrivals[] = {0, 10, 20, 40, 60, 80, 100};

    for (std::size_t index = 0; index < std::size(trace); ++index) {
        const auto result = queue.offer(trace[index], arrivals[index]);
        accepted += result == OfferResult::Accepted ? 1U : 0U;
        coalesced += result == OfferResult::Coalesced ? 1U : 0U;
        expiredTargets += result == OfferResult::Expired ? 1U : 0U;
    }
    const std::size_t maxDepth = queue.maxDepthObserved();
    queue.stopOutOfBand();

    char traceOutput[128];
    std::snprintf(
        traceOutput,
        sizeof(traceOutput),
        "TRACE accepted=%zu coalesced=%zu expired=%zu max_depth=%zu depth_after_stop=%zu\n",
        accepted,
        coalesced,
        expiredTargets,
        maxDepth,
        queue.depth());
    TEST_MESSAGE(traceOutput);
    TEST_ASSERT_EQUAL_UINT8(4, accepted);
    TEST_ASSERT_EQUAL_UINT8(2, coalesced);
    TEST_ASSERT_EQUAL_UINT8(1, expiredTargets);
    TEST_ASSERT_EQUAL_UINT8(4, maxDepth);
    TEST_ASSERT_EQUAL_UINT8(0, queue.depth());
}

}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_golden_vector_is_little_endian_and_round_trips);
    RUN_TEST(test_malformed_future_and_stop_like_packets_are_rejected);
    RUN_TEST(test_sequence_duplicate_and_wrap_rules);
    RUN_TEST(test_first_target_and_zero_distance_are_safe);
    RUN_TEST(test_100_hz_flood_coalesces_and_stays_bounded);
    RUN_TEST(test_full_reversal_burst_replaces_only_newest);
    RUN_TEST(test_duplicate_stale_expired_and_stop_are_bounded);
    RUN_TEST(test_invalid_homing_envelope_and_endpoint_pressure_inhibit_motion);
    RUN_TEST(test_deadline_is_rebased_from_sender_to_receiver_clock_domain);
    RUN_TEST(test_mode_can_arm_after_setup_makes_the_envelope_safe);
    RUN_TEST(test_deterministic_trace_reports_queue_outcomes);
    return UNITY_END();
}
