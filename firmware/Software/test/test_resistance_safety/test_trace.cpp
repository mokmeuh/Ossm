#include <cstdio>

#include <unity.h>

#include "resistance_safety.h"

void test_deterministic_bounded_trace() {
    resistance_safety::LatchedFaultSafety safety;
    const resistance_safety::MotionEnvelope envelope = {
        true, true, 5000, 1000, 9000, 500, 9500, false};
    safety.observeMotionEnvelope(envelope);
    TEST_ASSERT_TRUE(safety.requestArm(true, true));

    resistance_safety::Compositor compositor;
    const resistance_safety::CompositorRequest request = {
        40, 20, 60, 100, 100, 100, 100, 10, 5};
    const resistance_safety::Telemetry first = {60, 0, true, false, false, false};
    const resistance_safety::Telemetry qualified = {60, 50, true, false, false, false};
    const auto atZero = compositor.update(request, first, 0, safety.resistanceArmed());
    const auto atFifty = compositor.update(request, qualified, 50, safety.resistanceArmed());

    char trace[128];
    std::snprintf(trace, sizeof(trace),
                  "TRACE t=0 speed=%u depth=%u enabled=%u fault=%u",
                  atZero.speedMilliPoints, atZero.deepEndpointMilliPoints,
                  safety.outputEnabled(), static_cast<unsigned>(safety.faultCode()));
    TEST_MESSAGE(trace);
    std::snprintf(trace, sizeof(trace),
                  "TRACE t=50 speed=%u depth=%u enabled=%u fault=%u",
                  atFifty.speedMilliPoints, atFifty.deepEndpointMilliPoints,
                  safety.outputEnabled(), static_cast<unsigned>(safety.faultCode()));
    TEST_MESSAGE(trace);

    safety.observeOverload(true, 50);
    safety.observeOverload(true, 150);
    const resistance_safety::Telemetry overloaded = {100, 150, true, false, false, true};
    const auto latched = compositor.update(request, overloaded, 150, safety.resistanceArmed());
    std::snprintf(trace, sizeof(trace),
                  "TRACE t=150 speed=%u depth=%u enabled=%u fault=%u",
                  latched.speedMilliPoints, latched.deepEndpointMilliPoints,
                  safety.outputEnabled(), static_cast<unsigned>(safety.faultCode()));
    TEST_MESSAGE(trace);

    TEST_ASSERT_LESS_OR_EQUAL_UINT32(50000, atFifty.speedMilliPoints);
    TEST_ASSERT_LESS_OR_EQUAL_UINT32(65000, atFifty.deepEndpointMilliPoints);
    TEST_ASSERT_EQUAL_UINT32(0, latched.speedBonusMilliPoints);
    TEST_ASSERT_EQUAL_UINT32(0, latched.depthBonusMilliPoints);
    TEST_ASSERT_FALSE(safety.outputEnabled());
}
