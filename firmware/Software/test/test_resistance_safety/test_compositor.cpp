#include <cstdint>

#include <unity.h>

#include "resistance_safety.h"

using resistance_safety::Compositor;
using resistance_safety::CompositorRequest;
using resistance_safety::Telemetry;

namespace {

CompositorRequest request() {
    return {40, 20, 60, 100, 100, 100, 100, 10, 5};
}

Telemetry sample(uint8_t load, uint32_t at) {
    return {load, at, true, false, false, false};
}

void qualify(Compositor& compositor, const CompositorRequest& limits, uint8_t load) {
    compositor.update(limits, sample(load, 0), 0, true);
    compositor.update(limits, sample(load, 50), 50, true);
}

}

void test_contract_constants() {
    TEST_ASSERT_EQUAL_UINT32(50, resistance_safety::kFilterHorizonMs);
    TEST_ASSERT_EQUAL_UINT32(100, resistance_safety::kFreshnessMs);
    TEST_ASSERT_EQUAL_UINT8(10, resistance_safety::kHardSpeedBonusPoints);
    TEST_ASSERT_EQUAL_UINT8(5, resistance_safety::kHardDepthBonusPoints);
    TEST_ASSERT_EQUAL_UINT32(2000, resistance_safety::kSpeedRiseMilliPointsPerSecond);
    TEST_ASSERT_EQUAL_UINT32(1000, resistance_safety::kDepthRiseMilliPointsPerSecond);
    TEST_ASSERT_GREATER_OR_EQUAL_UINT32(4000, resistance_safety::kSpeedDecayMilliPointsPerSecond);
    TEST_ASSERT_GREATER_OR_EQUAL_UINT32(2000, resistance_safety::kDepthDecayMilliPointsPerSecond);
}

void test_hysteresis_requires_filter_horizon() {
    Compositor compositor;
    const auto limits = request();

    const auto before = compositor.update(limits, sample(100, 0), 0, true);
    const auto stillFiltering = compositor.update(limits, sample(100, 49), 49, true);
    const auto qualified = compositor.update(limits, sample(100, 50), 50, true);

    TEST_ASSERT_EQUAL_UINT32(0, before.speedBonusMilliPoints);
    TEST_ASSERT_EQUAL_UINT32(0, stillFiltering.speedBonusMilliPoints);
    TEST_ASSERT_GREATER_THAN_UINT32(0, qualified.speedBonusMilliPoints);
}

void test_hysteresis_releases_only_at_lower_threshold() {
    Compositor compositor;
    const auto limits = request();
    qualify(compositor, limits, 100);
    const auto aboveRelease = compositor.update(limits, sample(21, 100), 100, true);
    const auto atRelease = compositor.update(limits, sample(20, 150), 150, true);

    TEST_ASSERT_GREATER_THAN_UINT32(0, aboveRelease.speedBonusMilliPoints);
    TEST_ASSERT_EQUAL_UINT32(0, atRelease.speedBonusMilliPoints);
}

void test_moderate_load_ramps_within_positive_rates() {
    Compositor compositor;
    const auto limits = request();
    compositor.update(limits, sample(60, 0), 0, true);
    const auto output = compositor.update(limits, sample(60, 50), 50, true);

    TEST_ASSERT_GREATER_THAN_UINT32(0, output.speedBonusMilliPoints);
    TEST_ASSERT_LESS_OR_EQUAL_UINT32(100, output.speedBonusMilliPoints);
    TEST_ASSERT_GREATER_THAN_UINT32(0, output.depthBonusMilliPoints);
    TEST_ASSERT_LESS_OR_EQUAL_UINT32(50, output.depthBonusMilliPoints);
}

void test_fresh_load_decay_is_at_least_twice_the_rise_rate() {
    Compositor compositor;
    const auto limits = request();
    qualify(compositor, limits, 100);
    const auto raised = compositor.update(limits, sample(100, 550), 550, true);
    const auto decayed = compositor.update(limits, sample(20, 600), 600, true);

    TEST_ASSERT_GREATER_OR_EQUAL_UINT32(200,
        raised.speedBonusMilliPoints - decayed.speedBonusMilliPoints);
    TEST_ASSERT_GREATER_OR_EQUAL_UINT32(100,
        raised.depthBonusMilliPoints - decayed.depthBonusMilliPoints);
}

void test_invalid_telemetry_decays_at_least_twice_as_fast() {
    Compositor compositor;
    const auto limits = request();
    qualify(compositor, limits, 100);
    const auto raised = compositor.update(limits, sample(100, 550), 550, true);
    Telemetry stale = sample(100, 550);
    const auto decayed = compositor.update(limits, stale, 700, true);

    TEST_ASSERT_GREATER_THAN_UINT32(0, raised.speedBonusMilliPoints);
    TEST_ASSERT_EQUAL_UINT32(0, decayed.speedBonusMilliPoints);
    TEST_ASSERT_EQUAL_UINT32(0, decayed.depthBonusMilliPoints);
    TEST_ASSERT_FALSE(decayed.telemetryUsable);
}

void test_caps_and_user_ceilings_dominate() {
    Compositor compositor;
    auto limits = request();
    limits.baseSpeedPoints = 95;
    limits.userSpeedCeilingPoints = 97;
    limits.speedBonusCeilingPoints = 100;
    limits.baseDeepEndpointPoints = 98;
    limits.userDeepCeilingPoints = 99;
    limits.depthBonusCeilingPoints = 100;
    qualify(compositor, limits, 100);
    const auto output = compositor.update(limits, sample(100, 10000), 10000, true);

    TEST_ASSERT_LESS_OR_EQUAL_UINT32(2000, output.speedBonusMilliPoints);
    TEST_ASSERT_LESS_OR_EQUAL_UINT32(1000, output.depthBonusMilliPoints);
    TEST_ASSERT_LESS_OR_EQUAL_UINT32(97000, output.speedMilliPoints);
    TEST_ASSERT_LESS_OR_EQUAL_UINT32(99000, output.deepEndpointMilliPoints);
}

void test_user_ceiling_below_base_prevents_expansion() {
    Compositor compositor;
    auto limits = request();
    limits.baseSpeedPoints = 80;
    limits.userSpeedCeilingPoints = 60;
    limits.baseDeepEndpointPoints = 80;
    limits.userDeepCeilingPoints = 60;
    qualify(compositor, limits, 100);
    const auto output = compositor.update(limits, sample(100, 10000), 10000, true);

    TEST_ASSERT_EQUAL_UINT32(60000, output.speedMilliPoints);
    TEST_ASSERT_EQUAL_UINT32(0, output.speedBonusMilliPoints);
    TEST_ASSERT_EQUAL_UINT32(60000, output.deepEndpointMilliPoints);
    TEST_ASSERT_EQUAL_UINT32(0, output.depthBonusMilliPoints);
}

void test_firmware_limits_below_base_prevent_expansion() {
    Compositor compositor;
    auto limits = request();
    limits.baseSpeedPoints = 80;
    limits.firmwareSpeedLimitPoints = 50;
    limits.baseDeepEndpointPoints = 80;
    limits.firmwareDeepLimitPoints = 55;
    qualify(compositor, limits, 100);
    const auto output = compositor.update(limits, sample(100, 10000), 10000, true);

    TEST_ASSERT_EQUAL_UINT32(50000, output.speedMilliPoints);
    TEST_ASSERT_EQUAL_UINT32(0, output.speedBonusMilliPoints);
    TEST_ASSERT_EQUAL_UINT32(55000, output.deepEndpointMilliPoints);
    TEST_ASSERT_EQUAL_UINT32(0, output.depthBonusMilliPoints);
}

void test_fixed_shallow_endpoint_and_deep_expansion() {
    Compositor compositor;
    const auto limits = request();
    qualify(compositor, limits, 100);
    const auto output = compositor.update(limits, sample(100, 10000), 10000, true);

    TEST_ASSERT_EQUAL_UINT32(20000, output.shallowEndpointMilliPoints);
    TEST_ASSERT_GREATER_THAN_UINT32(60000, output.deepEndpointMilliPoints);
}

void test_streaming_envelope_remap() {
    TEST_ASSERT_EQUAL_UINT32(20000, resistance_safety::remapStreamingPosition(0, 20000, 65000));
    TEST_ASSERT_EQUAL_UINT32(42500, resistance_safety::remapStreamingPosition(50, 20000, 65000));
    TEST_ASSERT_EQUAL_UINT32(65000, resistance_safety::remapStreamingPosition(100, 20000, 65000));
}

void test_exhaustive_percent_inputs_are_bounded() {
    for (uint16_t base = 0; base <= 100; ++base) {
        for (uint16_t load = 0; load <= 100; ++load) {
            Compositor compositor;
            auto limits = request();
            limits.baseSpeedPoints = static_cast<uint8_t>(base);
            limits.baseDeepEndpointPoints = static_cast<uint8_t>(base);
            limits.shallowEndpointPoints = static_cast<uint8_t>(base / 2);
            qualify(compositor, limits, static_cast<uint8_t>(load));
            const auto output = compositor.update(limits, sample(static_cast<uint8_t>(load), 10000), 10000, true);

            TEST_ASSERT_LESS_OR_EQUAL_UINT32(100000, output.speedMilliPoints);
            TEST_ASSERT_LESS_OR_EQUAL_UINT32(100000, output.shallowEndpointMilliPoints);
            TEST_ASSERT_LESS_OR_EQUAL_UINT32(100000, output.deepEndpointMilliPoints);
            TEST_ASSERT_LESS_OR_EQUAL_UINT32(10000, output.speedBonusMilliPoints);
            TEST_ASSERT_LESS_OR_EQUAL_UINT32(5000, output.depthBonusMilliPoints);
            for (uint16_t position = 0; position <= 100; ++position) {
                const auto remapped = resistance_safety::remapStreamingPosition(
                    static_cast<uint8_t>(position), output.shallowEndpointMilliPoints,
                    output.deepEndpointMilliPoints);
                TEST_ASSERT_LESS_OR_EQUAL_UINT32(100000, remapped);
            }
        }
    }
}

void test_invalid_range_never_expands() {
    Compositor compositor;
    auto limits = request();
    limits.shallowEndpointPoints = 80;
    limits.baseDeepEndpointPoints = 40;
    limits.userDeepCeilingPoints = 50;
    qualify(compositor, limits, 100);
    const auto output = compositor.update(limits, sample(100, 10000), 10000, true);

    TEST_ASSERT_EQUAL_UINT32(0, output.depthBonusMilliPoints);
    TEST_ASSERT_LESS_OR_EQUAL_UINT32(50000, output.shallowEndpointMilliPoints);
    TEST_ASSERT_LESS_OR_EQUAL_UINT32(50000, output.deepEndpointMilliPoints);
}

void test_unsafe_motion_envelope_forces_zero_bonus() {
    Compositor compositor;
    const auto limits = request();
    qualify(compositor, limits, 100);
    const auto output = compositor.update(limits, sample(100, 10000), 10000, false);

    TEST_ASSERT_EQUAL_UINT32(0, output.speedBonusMilliPoints);
    TEST_ASSERT_EQUAL_UINT32(0, output.depthBonusMilliPoints);
}

void test_each_invalid_signal_state_forces_zero_bonus() {
    const auto limits = request();
    for (uint8_t invalidCase = 0; invalidCase < 4; ++invalidCase) {
        Compositor compositor;
        qualify(compositor, limits, 100);
        Telemetry invalid = sample(100, 10000);
        if (invalidCase == 0) {
            invalid.reversing = true;
        } else if (invalidCase == 1) {
            invalid.saturated = true;
        } else if (invalidCase == 2) {
            invalid.sampledAtMs = 9800;
        } else {
            invalid.overload = true;
        }
        const auto output = compositor.update(limits, invalid, 10000, true);
        TEST_ASSERT_EQUAL_UINT32(0, output.speedBonusMilliPoints);
        TEST_ASSERT_EQUAL_UINT32(0, output.depthBonusMilliPoints);
        TEST_ASSERT_FALSE(output.telemetryUsable);
    }
}
