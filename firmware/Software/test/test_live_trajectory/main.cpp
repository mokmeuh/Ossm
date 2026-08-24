#include <cmath>
#include <cstdint>

#include <unity.h>

#include "live_trajectory.h"
#include "streaming_logic.h"

namespace {

using live_trajectory::Engine;
using live_trajectory::Limits;

constexpr Limits kLimits{
    2000.0,
    1000.0,
    4000.0,
    -1000,
    1000,
    0.010,
};

void assertStateEqual(const Engine& left, const Engine& right) {
    TEST_ASSERT_DOUBLE_WITHIN(1e-12, left.position(), right.position());
    TEST_ASSERT_DOUBLE_WITHIN(1e-12, left.velocity(), right.velocity());
    TEST_ASSERT_DOUBLE_WITHIN(1e-12, left.acceleration(), right.acceleration());
    TEST_ASSERT_EQUAL_INT32(left.commandedPosition(), right.commandedPosition());
    TEST_ASSERT_EQUAL(left.hasTarget(), right.hasTarget());
}

void test_initialization_persists_exact_stationary_position() {
    Engine engine(kLimits);
    engine.initialize(125, 10);

    TEST_ASSERT_DOUBLE_WITHIN(1e-12, 125.0, engine.position());
    TEST_ASSERT_DOUBLE_WITHIN(1e-12, 0.0, engine.velocity());
    TEST_ASSERT_DOUBLE_WITHIN(1e-12, 0.0, engine.acceleration());
    TEST_ASSERT_EQUAL_INT32(125, engine.commandedPosition());
    TEST_ASSERT_FALSE(engine.hasTarget());
    TEST_ASSERT_TRUE(engine.isArmed());
}

void test_one_millisecond_ticks_obey_speed_acceleration_and_jerk_bounds() {
    Engine engine(kLimits);
    engine.initialize(0, 0);
    TEST_ASSERT_TRUE(engine.setTarget(900, 100, 0));

    double previousAcceleration = engine.acceleration();
    for (uint32_t tick = 1; tick <= 1200; ++tick) {
        engine.tick(0.001, tick);
        TEST_ASSERT_LESS_OR_EQUAL_DOUBLE(kLimits.maxSpeed, std::abs(engine.velocity()));
        TEST_ASSERT_LESS_OR_EQUAL_DOUBLE(kLimits.maxAcceleration,
                                         std::abs(engine.acceleration()));
        TEST_ASSERT_LESS_OR_EQUAL_DOUBLE(
            kLimits.maxJerk * 0.001 + 1e-9,
            std::abs(engine.acceleration() - previousAcceleration));
        previousAcceleration = engine.acceleration();
    }
}

void test_duration_is_speed_intent_bounded_by_machine_speed() {
    Engine engine(kLimits);
    engine.initialize(0, 0);

    TEST_ASSERT_TRUE(engine.setTarget(100, 200, 1));
    TEST_ASSERT_DOUBLE_WITHIN(1e-12, 500.0, engine.requestedSpeed());
    TEST_ASSERT_TRUE(engine.setTarget(1000, 10, 2));
    TEST_ASSERT_DOUBLE_WITHIN(1e-12, kLimits.maxSpeed, engine.requestedSpeed());
}

void test_retarget_replaces_only_endpoint_and_preserves_kinematics() {
    Engine engine(kLimits);
    engine.initialize(0, 0);
    TEST_ASSERT_TRUE(engine.setTarget(800, 500, 0));
    for (uint32_t tick = 1; tick <= 100; ++tick) engine.tick(0.001, tick);

    const double position = engine.position();
    const double velocity = engine.velocity();
    const double acceleration = engine.acceleration();
    TEST_ASSERT_TRUE(engine.setTarget(300, 250, 101));

    TEST_ASSERT_DOUBLE_WITHIN(1e-12, position, engine.position());
    TEST_ASSERT_DOUBLE_WITHIN(1e-12, velocity, engine.velocity());
    TEST_ASSERT_DOUBLE_WITHIN(1e-12, acceleration, engine.acceleration());
    TEST_ASSERT_EQUAL_INT32(300, engine.targetPosition());
}

void test_reversal_passes_through_zero_velocity() {
    Engine engine(kLimits);
    engine.initialize(0, 0);
    TEST_ASSERT_TRUE(engine.setTarget(900, 300, 0));
    for (uint32_t tick = 1; tick <= 500; ++tick) engine.tick(0.001, tick);
    TEST_ASSERT_GREATER_THAN_DOUBLE(0.0, engine.velocity());

    TEST_ASSERT_TRUE(engine.setTarget(-900, 300, 501));
    bool sawZero = false;
    bool sawNegative = false;
    for (uint32_t tick = 502; tick <= 2500; ++tick) {
        engine.tick(0.001, tick);
        if (engine.velocity() == 0.0) sawZero = true;
        if (engine.velocity() < 0.0) {
            sawNegative = true;
            break;
        }
    }
    TEST_ASSERT_TRUE(sawZero);
    TEST_ASSERT_TRUE(sawNegative);
}

void test_envelope_rejects_target_without_changing_active_target() {
    Engine engine(kLimits);
    engine.initialize(0, 0);
    TEST_ASSERT_TRUE(engine.setTarget(500, 500, 1));

    TEST_ASSERT_FALSE(engine.setTarget(1001, 500, 2));
    TEST_ASSERT_EQUAL_INT32(500, engine.targetPosition());
    TEST_ASSERT_EQUAL_UINT32(1, engine.lastTargetAtMs());
}

void test_sparse_target_remains_active_and_reports_freshness() {
    Engine engine(kLimits);
    engine.initialize(0, 0);
    TEST_ASSERT_TRUE(engine.setTarget(600, 1000, 100));
    for (uint32_t tick = 101; tick <= 600; ++tick) engine.tick(0.001, tick);

    TEST_ASSERT_TRUE(engine.hasTarget());
    TEST_ASSERT_TRUE(engine.targetFresh(600, 500));
    TEST_ASSERT_FALSE(engine.targetFresh(601, 500));
}

void test_cancel_clears_target_and_kinematics_immediately() {
    Engine engine(kLimits);
    engine.initialize(0, 0);
    TEST_ASSERT_TRUE(engine.setTarget(800, 100, 0));
    for (uint32_t tick = 1; tick <= 100; ++tick) engine.tick(0.001, tick);

    engine.cancel();
    TEST_ASSERT_FALSE(engine.hasTarget());
    TEST_ASSERT_FALSE(engine.isArmed());
    TEST_ASSERT_DOUBLE_WITHIN(1e-12, 0.0, engine.velocity());
    TEST_ASSERT_DOUBLE_WITHIN(1e-12, 0.0, engine.acceleration());
}

void test_cancelled_target_cannot_resurrect_without_explicit_rearm() {
    Engine engine(kLimits);
    engine.initialize(0, 0);
    TEST_ASSERT_TRUE(engine.setTarget(800, 100, 1));
    engine.cancel();

    TEST_ASSERT_FALSE(engine.setTarget(400, 100, 2));
    engine.tick(0.010, 12);
    TEST_ASSERT_FALSE(engine.hasTarget());
    TEST_ASSERT_EQUAL_INT32(0, engine.commandedPosition());
    engine.arm();
    TEST_ASSERT_TRUE(engine.setTarget(400, 100, 13));
}

void test_normal_stop_restarts_from_current_stepper_position_and_resumes() {
    Engine engine(kLimits);
    engine.initialize(0, 0);
    TEST_ASSERT_TRUE(engine.setTarget(800, 100, 1));
    for (uint32_t tick = 2; tick <= 100; ++tick) engine.tick(0.001, tick);

    engine.initialize(-321, 101);

    TEST_ASSERT_TRUE(engine.isArmed());
    TEST_ASSERT_FALSE(engine.hasTarget());
    TEST_ASSERT_EQUAL_INT32(-321, engine.commandedPosition());
    TEST_ASSERT_DOUBLE_WITHIN(1e-12, -321.0, engine.position());
    TEST_ASSERT_TRUE(engine.setTarget(400, 200, 102));
}

void test_target_settles_exactly_without_endpoint_micro_oscillation() {
    Engine engine(kLimits);
    engine.initialize(0, 0);
    TEST_ASSERT_TRUE(engine.setTarget(37, 200, 1));

    bool settled = false;
    for (uint32_t tick = 2; tick <= 20000; ++tick) {
        engine.tick(0.001, tick);
        if (engine.hasTarget()) continue;

        settled = true;
        TEST_ASSERT_EQUAL_INT32(37, engine.commandedPosition());
        TEST_ASSERT_DOUBLE_WITHIN(1e-12, 37.0, engine.position());
        TEST_ASSERT_DOUBLE_WITHIN(1e-12, 0.0, engine.velocity());
        TEST_ASSERT_DOUBLE_WITHIN(1e-12, 0.0, engine.acceleration());
        for (uint32_t quietTick = tick + 1; quietTick <= tick + 500; ++quietTick) {
            TEST_ASSERT_FALSE(engine.tick(0.001, quietTick).commandChanged);
        }
        break;
    }

    TEST_ASSERT_TRUE_MESSAGE(
        settled,
        "trajectory kept an active target instead of settling exactly");
}

void test_identical_inputs_are_deterministic() {
    Engine first(kLimits);
    Engine second(kLimits);
    first.initialize(-100, 0);
    second.initialize(-100, 0);
    TEST_ASSERT_TRUE(first.setTarget(700, 350, 1));
    TEST_ASSERT_TRUE(second.setTarget(700, 350, 1));
    for (uint32_t tick = 2; tick <= 500; ++tick) {
        first.tick(0.001, tick);
        second.tick(0.001, tick);
        assertStateEqual(first, second);
    }
}

void test_stalled_tick_is_bounded_and_emits_only_whole_step_changes() {
    Engine engine(kLimits);
    engine.initialize(0, 0);
    TEST_ASSERT_TRUE(engine.setTarget(1000, 100, 0));

    const auto first = engine.tick(1.0, 1000);
    TEST_ASSERT_LESS_OR_EQUAL_DOUBLE(kLimits.maxJerk * kLimits.maxDtSeconds,
                                     std::abs(engine.acceleration()));
    TEST_ASSERT_FALSE(first.commandChanged);

    bool emitted = false;
    int32_t previous = engine.commandedPosition();
    for (uint32_t tick = 1001; tick <= 2000; ++tick) {
        const auto output = engine.tick(0.001, tick);
        if (!output.commandChanged) continue;
        TEST_ASSERT_GREATER_OR_EQUAL_INT32(1, std::abs(output.commandPosition - previous));
        previous = output.commandPosition;
        emitted = true;
    }
    TEST_ASSERT_TRUE(emitted);
}

void test_legacy_planner_characterization_remains_unchanged() {
    const auto profile = streaming_logic::planMotion(
        0, 1000, 1.0f, 50000, 100000, 1);
    TEST_ASSERT_TRUE(profile.valid);
    TEST_ASSERT_EQUAL_UINT32(2000, profile.speed);
    TEST_ASSERT_EQUAL_UINT32(4000, profile.acceleration);
    TEST_ASSERT_EQUAL_INT32(1000, profile.targetPosition);
    TEST_ASSERT_EQUAL_UINT32(
        8000,
        streaming_logic::selectStreamAcceleration(false, true, 8000, 4000));
}

}  // namespace

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_initialization_persists_exact_stationary_position);
    RUN_TEST(test_one_millisecond_ticks_obey_speed_acceleration_and_jerk_bounds);
    RUN_TEST(test_duration_is_speed_intent_bounded_by_machine_speed);
    RUN_TEST(test_retarget_replaces_only_endpoint_and_preserves_kinematics);
    RUN_TEST(test_reversal_passes_through_zero_velocity);
    RUN_TEST(test_envelope_rejects_target_without_changing_active_target);
    RUN_TEST(test_sparse_target_remains_active_and_reports_freshness);
    RUN_TEST(test_cancel_clears_target_and_kinematics_immediately);
    RUN_TEST(test_cancelled_target_cannot_resurrect_without_explicit_rearm);
    RUN_TEST(test_normal_stop_restarts_from_current_stepper_position_and_resumes);
    RUN_TEST(test_target_settles_exactly_without_endpoint_micro_oscillation);
    RUN_TEST(test_identical_inputs_are_deterministic);
    RUN_TEST(test_stalled_tick_is_bounded_and_emits_only_whole_step_changes);
    RUN_TEST(test_legacy_planner_characterization_remains_unchanged);
    return UNITY_END();
}
