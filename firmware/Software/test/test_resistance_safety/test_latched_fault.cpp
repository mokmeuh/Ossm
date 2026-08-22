#include <array>

#include <unity.h>

#include "resistance_safety.h"

using resistance_safety::FaultCode;
using resistance_safety::LatchedFaultSafety;
using resistance_safety::LifecycleEvent;
using resistance_safety::MotionCommand;
using resistance_safety::MotionEnvelope;
using resistance_safety::PersistentFault;

namespace {

MotionEnvelope safeEnvelope(bool endpointPressure = false) {
    return {true, true, 5000, 1000, 9000, 500, 9500, endpointPressure};
}

}

void test_overload_confirmation_disables_by_100ms() {
    LatchedFaultSafety safety;
    safety.observeMotionEnvelope(safeEnvelope());
    safety.observeOverload(true, 1000);
    safety.observeOverload(true, 1099);
    TEST_ASSERT_TRUE(safety.outputEnabled());
    safety.observeOverload(true, 1100);

    TEST_ASSERT_FALSE(safety.outputEnabled());
    TEST_ASSERT_EQUAL(FaultCode::Overload, safety.faultCode());
}

void test_spike_does_not_latch() {
    LatchedFaultSafety safety;
    safety.observeMotionEnvelope(safeEnvelope());
    safety.observeOverload(true, 0);
    safety.observeOverload(false, 50);
    safety.observeOverload(true, 1000);
    safety.observeOverload(true, 1099);

    TEST_ASSERT_TRUE(safety.outputEnabled());
    TEST_ASSERT_EQUAL(FaultCode::None, safety.faultCode());
}

void test_latch_persists_through_reboot() {
    LatchedFaultSafety safety;
    safety.observeOverload(true, 0);
    safety.observeOverload(true, 100);
    const PersistentFault persisted = safety.persistentFault();
    LatchedFaultSafety rebooted(persisted);
    rebooted.onLifecycle(LifecycleEvent::Reboot);

    TEST_ASSERT_FALSE(rebooted.outputEnabled());
    TEST_ASSERT_FALSE(rebooted.resistanceArmed());
    TEST_ASSERT_EQUAL(FaultCode::Overload, rebooted.faultCode());
}

void test_recovery_requires_ack_then_successful_home() {
    LatchedFaultSafety safety;
    safety.observeMotionEnvelope(safeEnvelope());
    safety.observeOverload(true, 0);
    safety.observeOverload(true, 100);
    safety.reportHomeResult(true, safeEnvelope());
    TEST_ASSERT_FALSE(safety.outputEnabled());
    safety.acknowledgeFault();
    safety.reportHomeResult(false, safeEnvelope());
    TEST_ASSERT_FALSE(safety.outputEnabled());
    TEST_ASSERT_FALSE(safety.requestArm(true, true));
    safety.reportHomeResult(true, safeEnvelope());

    TEST_ASSERT_TRUE(safety.outputEnabled());
    TEST_ASSERT_FALSE(safety.resistanceArmed());
    TEST_ASSERT_EQUAL(FaultCode::None, safety.faultCode());
}

void test_latched_fault_rejects_every_motion_command() {
    LatchedFaultSafety safety;
    safety.observeMotionEnvelope(safeEnvelope());
    safety.observeOverload(true, 0);
    safety.observeOverload(true, 100);
    const std::array<MotionCommand, 6> commands = {
        MotionCommand::StrokeEngine, MotionCommand::SimplePenetration,
        MotionCommand::Streaming, MotionCommand::Funscript,
        MotionCommand::VideoSync, MotionCommand::Adaptive};

    for (const auto command : commands) {
        TEST_ASSERT_FALSE(safety.acceptsMotion(command));
    }
}

void test_all_lifecycle_events_clear_arming() {
    const std::array<LifecycleEvent, 8> events = {
        LifecycleEvent::Stop, LifecycleEvent::ModeChange, LifecycleEvent::MenuEntry,
        LifecycleEvent::Disconnect, LifecycleEvent::SessionChange, LifecycleEvent::Fault,
        LifecycleEvent::Reboot, LifecycleEvent::StaleTelemetry};

    for (const auto event : events) {
        LatchedFaultSafety safety;
        safety.observeMotionEnvelope(safeEnvelope());
        TEST_ASSERT_TRUE(safety.requestArm(true, true));
        safety.onLifecycle(event);
        TEST_ASSERT_FALSE(safety.resistanceArmed());
        if (event == LifecycleEvent::Fault || event == LifecycleEvent::Reboot) {
            TEST_ASSERT_FALSE(safety.outputEnabled());
        }
    }
}

void test_stale_reversal_saturation_and_disconnect_never_arm() {
    LatchedFaultSafety safety;
    safety.observeMotionEnvelope(safeEnvelope());
    TEST_ASSERT_FALSE(safety.requestArm(false, true));
    TEST_ASSERT_FALSE(safety.requestArm(true, false));
    TEST_ASSERT_TRUE(safety.requestArm(true, true));
    safety.observeTelemetry(false);
    TEST_ASSERT_FALSE(safety.resistanceArmed());
    safety.onLifecycle(LifecycleEvent::Disconnect);
    TEST_ASSERT_FALSE(safety.resistanceArmed());
}

void test_homing_and_calibrated_envelope_are_non_bypassable() {
    LatchedFaultSafety safety;
    auto envelope = safeEnvelope();
    envelope.homingValid = false;
    safety.observeMotionEnvelope(envelope);
    TEST_ASSERT_FALSE(safety.outputEnabled());
    TEST_ASSERT_FALSE(safety.requestArm(true, true));

    envelope = safeEnvelope();
    envelope.travelCalibrated = false;
    safety.observeMotionEnvelope(envelope);
    TEST_ASSERT_FALSE(safety.outputEnabled());

    envelope = safeEnvelope();
    envelope.positionSteps = 10000;
    safety.observeMotionEnvelope(envelope);
    TEST_ASSERT_FALSE(safety.outputEnabled());

    envelope = safeEnvelope();
    envelope.shallowEndpointSteps = 100;
    safety.observeMotionEnvelope(envelope);
    TEST_ASSERT_FALSE(safety.outputEnabled());
}

void test_targets_must_stay_inside_both_endpoints() {
    LatchedFaultSafety safety;
    safety.observeMotionEnvelope(safeEnvelope());

    TEST_ASSERT_FALSE(safety.acceptsTarget(999));
    TEST_ASSERT_TRUE(safety.acceptsTarget(1000));
    TEST_ASSERT_TRUE(safety.acceptsTarget(9000));
    TEST_ASSERT_FALSE(safety.acceptsTarget(9001));
}

void test_repeated_endpoint_pressure_stops_and_latches() {
    LatchedFaultSafety safety;
    safety.observeMotionEnvelope(safeEnvelope(true));
    TEST_ASSERT_TRUE(safety.outputEnabled());
    safety.observeMotionEnvelope(safeEnvelope(true));

    TEST_ASSERT_FALSE(safety.outputEnabled());
    TEST_ASSERT_FALSE(safety.resistanceArmed());
    TEST_ASSERT_EQUAL(FaultCode::EndpointPressure, safety.faultCode());
}
