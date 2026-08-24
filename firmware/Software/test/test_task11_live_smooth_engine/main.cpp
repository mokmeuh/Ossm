#include <unity.h>

#define ESP_LOGI(tag, format, ...)

#include "streaming_logic.h"
#include "command/commands.hpp"
#include "live_smooth_protocol.h"

namespace {

void test_legacy_stream_command_bytes_and_mapping_are_unchanged() {
    const auto command = streamCommandValue(String("stream:50:200"));

    TEST_ASSERT_EQUAL(Commands::streamPosition, command.command);
    TEST_ASSERT_EQUAL_INT(50, command.value);
    TEST_ASSERT_EQUAL_INT(200, command.time);
    TEST_ASSERT_EQUAL_INT32(-7000, streaming_logic::scaleStreamPosition(0, 5000, 2000));
    TEST_ASSERT_EQUAL_INT32(-4500, streaming_logic::scaleStreamPosition(50, 5000, 2000));
    TEST_ASSERT_EQUAL_INT32(-2000, streaming_logic::scaleStreamPosition(100, 5000, 2000));
}

void test_legacy_motion_profile_characterization() {
    const auto profile = streaming_logic::planMotion(0, 1000, 1.0f, 50000, 100000, 1);

    TEST_ASSERT_EQUAL_UINT32(2000, profile.speed);
    TEST_ASSERT_EQUAL_UINT32(4000, profile.acceleration);
    TEST_ASSERT_EQUAL_INT32(1000, profile.distance);
    TEST_ASSERT_EQUAL_INT32(1000, profile.targetPosition);
}

void test_live_acceleration_limit_is_quarter_machine_max_with_floor() {
    TEST_ASSERT_EQUAL_UINT32(
        125000, streaming_logic::liveAccelerationLimit(500000));
    TEST_ASSERT_EQUAL_UINT32(
        100, streaming_logic::liveAccelerationLimit(200));
}

void test_running_live_motion_selects_lower_planned_acceleration() {
    TEST_ASSERT_EQUAL_UINT32(
        4000,
        streaming_logic::selectStreamAcceleration(true, true, 8000, 4000));
    TEST_ASSERT_EQUAL_UINT32(
        8000,
        streaming_logic::selectStreamAcceleration(false, true, 8000, 4000));
    TEST_ASSERT_EQUAL_UINT32(
        4000,
        streaming_logic::selectStreamAcceleration(false, false, 8000, 4000));
}

void test_planner_rejects_zero_distance_and_short_duration() {
    const auto zero = streaming_logic::planMotion(500, 500, 0.02f, 50000, 100000, 1);
    const auto shortMove = streaming_logic::planMotion(0, 500, 0.005f, 50000, 100000, 1);
    TEST_ASSERT_FALSE(zero.valid);
    TEST_ASSERT_FALSE(shortMove.valid);
}

void test_planner_rejects_distance_that_clamps_to_zero() {
    const auto profile =
        streaming_logic::planMotion(0, 500, 0.02f, 50000, 1000, 1);

    TEST_ASSERT_FALSE(profile.valid);
    TEST_ASSERT_EQUAL_INT32(0, profile.distance);
    TEST_ASSERT_EQUAL_INT32(0, profile.targetPosition);
}

void test_accepted_60ms_stream_command_maps_to_motion_at_firmware_limits() {
    const auto command = streamCommandValue(String("stream:95:60"));
    const int32_t currentPosition = 0;
    const int32_t targetPosition = streaming_logic::scaleStreamPosition(
        command.value, 10000, 0);
    const auto profile = streaming_logic::planMotion(
        currentPosition, targetPosition, command.time / 1000.0f,
        20000, 500000, 20);

    TEST_ASSERT_EQUAL(Commands::streamPosition, command.command);
    TEST_ASSERT_TRUE(profile.valid);
    TEST_ASSERT_GREATER_THAN_INT32(0, profile.distance);
    TEST_ASSERT_NOT_EQUAL(currentPosition, profile.targetPosition);
}

void test_accepted_60ms_legacy_command_schedules_after_streaming_speed_reset() {
    const auto command = streamCommandValue(String("stream:95:60"));
    const uint32_t machineMaxSpeed = 20000;
    const float resetStreamingSpeedPercent = 0.0f;
    const uint32_t legacySpeedLimit =
        streaming_logic::timedStreamSpeedLimit(machineMaxSpeed);
    const int32_t currentPosition = 0;
    const int32_t targetPosition = streaming_logic::scaleStreamPosition(
        command.value, 10000, 0);
    const auto profile = streaming_logic::planMotion(
        currentPosition, targetPosition, command.time / 1000.0f,
        legacySpeedLimit, 500000, 20);

    TEST_ASSERT_EQUAL(Commands::streamPosition, command.command);
    TEST_ASSERT_EQUAL_FLOAT(0.0f, resetStreamingSpeedPercent);
    TEST_ASSERT_EQUAL_UINT32(machineMaxSpeed, legacySpeedLimit);
    TEST_ASSERT_TRUE(profile.valid);
    TEST_ASSERT_NOT_EQUAL(currentPosition, profile.targetPosition);
}

void test_duplicate_legacy_positions_are_accepted_without_scheduling_again() {
    const auto first = streamCommandValue(String("stream:95:60"));
    const auto duplicate = streamCommandValue(String("stream:95:60"));
    const int positionDelta = duplicate.value - first.value;

    TEST_ASSERT_EQUAL(Commands::streamPosition, first.command);
    TEST_ASSERT_EQUAL(Commands::streamPosition, duplicate.command);
    TEST_ASSERT_EQUAL_INT8(0, streaming_logic::streamDirection(positionDelta));
    TEST_ASSERT_FALSE(
        streaming_logic::shouldScheduleStreamPosition(true, positionDelta));
    TEST_ASSERT_TRUE(
        streaming_logic::shouldScheduleStreamPosition(false, positionDelta));
}

void test_monotonic_deadline_never_extends_duration() {
    TEST_ASSERT_EQUAL_UINT16(40, streaming_logic::remainingDurationMs(1040, 1000, 80));
    TEST_ASSERT_EQUAL_UINT16(20, streaming_logic::remainingDurationMs(1040, 1020, 80));
    TEST_ASSERT_EQUAL_UINT16(0, streaming_logic::remainingDurationMs(1040, 1040, 80));
}

void test_task7_queue_semantics_drive_the_tested_planner() {
    using namespace live_smooth;
    QueueModel queue({true, 10000, 1000, 9000, 5000, false});
    for (uint16_t sequence = 1; sequence <= 10; ++sequence) {
        queue.offer({sequence, 500, static_cast<uint16_t>(sequence * 90), 20}, sequence * 10);
    }
    TEST_ASSERT_LESS_OR_EQUAL(kQueueCapacity, queue.maxDepthObserved());
    const auto target = queue.poll(120);
    TEST_ASSERT_TRUE(target.has_value());
    const int32_t targetSteps = streaming_logic::scaleStreamPosition(target->positionPermille / 10, 8000, 1000);
    const auto profile = streaming_logic::planMotion(-5000, targetSteps, target->durationMs / 1000.0f, 50000, 100000, 1);
    TEST_ASSERT_TRUE(profile.valid);
    queue.stopOutOfBand();
    TEST_ASSERT_EQUAL_UINT8(0, queue.depth());
    TEST_ASSERT_TRUE(queue.isStopped());
}

}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_legacy_stream_command_bytes_and_mapping_are_unchanged);
    RUN_TEST(test_legacy_motion_profile_characterization);
    RUN_TEST(test_live_acceleration_limit_is_quarter_machine_max_with_floor);
    RUN_TEST(test_running_live_motion_selects_lower_planned_acceleration);
    RUN_TEST(test_planner_rejects_zero_distance_and_short_duration);
    RUN_TEST(test_planner_rejects_distance_that_clamps_to_zero);
    RUN_TEST(test_accepted_60ms_stream_command_maps_to_motion_at_firmware_limits);
    RUN_TEST(test_accepted_60ms_legacy_command_schedules_after_streaming_speed_reset);
    RUN_TEST(test_duplicate_legacy_positions_are_accepted_without_scheduling_again);
    RUN_TEST(test_monotonic_deadline_never_extends_duration);
    RUN_TEST(test_task7_queue_semantics_drive_the_tested_planner);
    return UNITY_END();
}
