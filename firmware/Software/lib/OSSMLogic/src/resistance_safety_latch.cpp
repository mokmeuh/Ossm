#include "resistance_safety.h"

namespace resistance_safety {

LatchedFaultSafety::LatchedFaultSafety(PersistentFault persisted)
    : faultCode_(persisted.code) {}

bool LatchedFaultSafety::requestArm(bool telemetryUsable, bool homed) {
    resistanceArmed_ = telemetryUsable && homed && outputEnabled();
    return resistanceArmed_;
}

void LatchedFaultSafety::observeTelemetry(bool usable) {
    if (!usable) {
        resistanceArmed_ = false;
    }
}

void LatchedFaultSafety::observeMotionEnvelope(const MotionEnvelope& envelope) {
    const bool ordered = envelope.keepoutMinSteps <= envelope.shallowEndpointSteps &&
                         envelope.shallowEndpointSteps < envelope.deepEndpointSteps &&
                         envelope.deepEndpointSteps <= envelope.keepoutMaxSteps;
    const bool positionInside = envelope.positionSteps >= envelope.shallowEndpointSteps &&
                                envelope.positionSteps <= envelope.deepEndpointSteps;
    envelopeSafe_ = envelope.homingValid && envelope.travelCalibrated && ordered && positionInside;
    if (!envelopeSafe_) {
        resistanceArmed_ = false;
        endpointPressureSeen_ = false;
        return;
    }

    shallowEndpointSteps_ = envelope.shallowEndpointSteps;
    deepEndpointSteps_ = envelope.deepEndpointSteps;
    if (envelope.endpointPressure) {
        if (endpointPressureSeen_) {
            latch(FaultCode::EndpointPressure);
        } else {
            endpointPressureSeen_ = true;
        }
    } else {
        endpointPressureSeen_ = false;
    }
}

void LatchedFaultSafety::observeOverload(bool overload, uint32_t nowMs) {
    if (faultCode_ != FaultCode::None) {
        return;
    }
    if (!overload) {
        overloadTracking_ = false;
        return;
    }
    if (!overloadTracking_) {
        overloadTracking_ = true;
        overloadSinceMs_ = nowMs;
        return;
    }
    if (nowMs - overloadSinceMs_ >= kOverloadConfirmationMs) {
        latch(FaultCode::Overload);
    }
}

void LatchedFaultSafety::onLifecycle(LifecycleEvent event) {
    resistanceArmed_ = false;
    if (event == LifecycleEvent::Fault || event == LifecycleEvent::Reboot) {
        envelopeSafe_ = false;
    }
    if (event == LifecycleEvent::Reboot) {
        overloadTracking_ = false;
        endpointPressureSeen_ = false;
    }
}

void LatchedFaultSafety::acknowledgeFault() {
    acknowledged_ = faultCode_ != FaultCode::None;
}

void LatchedFaultSafety::reportHomeResult(bool successful, const MotionEnvelope& envelope) {
    resistanceArmed_ = false;
    if (!successful) {
        envelopeSafe_ = false;
        return;
    }
    observeMotionEnvelope(envelope);
    if (acknowledged_ && envelopeSafe_) {
        faultCode_ = FaultCode::None;
        acknowledged_ = false;
        endpointPressureSeen_ = false;
    }
}

bool LatchedFaultSafety::outputEnabled() const {
    return faultCode_ == FaultCode::None && envelopeSafe_;
}

bool LatchedFaultSafety::resistanceArmed() const {
    return resistanceArmed_;
}

bool LatchedFaultSafety::acceptsMotion(MotionCommand command) const {
    static_cast<void>(command);
    return outputEnabled();
}

bool LatchedFaultSafety::acceptsTarget(int32_t targetSteps) const {
    return outputEnabled() && targetSteps >= shallowEndpointSteps_ &&
           targetSteps <= deepEndpointSteps_;
}

FaultCode LatchedFaultSafety::faultCode() const {
    return faultCode_;
}

PersistentFault LatchedFaultSafety::persistentFault() const {
    return {faultCode_};
}

void LatchedFaultSafety::latch(FaultCode code) {
    faultCode_ = code;
    acknowledged_ = false;
    resistanceArmed_ = false;
}

}
