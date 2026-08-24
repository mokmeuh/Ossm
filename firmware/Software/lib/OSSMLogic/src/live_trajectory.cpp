#include "live_trajectory.h"

#include <algorithm>
#include <cmath>

namespace live_trajectory {
namespace {

constexpr double kSettledPositionToleranceSteps = 0.5;
constexpr double kSettledVelocityToleranceStepsPerSecond = 1.0;

double approach(double current, double target, double maximumChange) {
    if (current < target) return std::min(current + maximumChange, target);
    return std::max(current - maximumChange, target);
}

double direction(double value) {
    if (value > 0.0) return 1.0;
    if (value < 0.0) return -1.0;
    return 0.0;
}

}  // namespace

Engine::Engine(const Limits& limits) : limits_(limits) {}

void Engine::initialize(int32_t position, uint32_t nowMs) {
    position_ = std::clamp(position, limits_.minPosition, limits_.maxPosition);
    velocity_ = 0.0;
    acceleration_ = 0.0;
    requestedSpeed_ = 0.0;
    commandedPosition_ = static_cast<int32_t>(std::llround(position_));
    targetPosition_ = commandedPosition_;
    lastTargetAtMs_ = nowMs;
    hasTarget_ = false;
    armed_ = true;
}

void Engine::setLimits(const Limits& limits) {
    limits_ = limits;
    position_ = std::clamp(
        position_, static_cast<double>(limits_.minPosition),
        static_cast<double>(limits_.maxPosition));
    commandedPosition_ = static_cast<int32_t>(std::llround(position_));
}

bool Engine::setTarget(int32_t position, uint16_t durationMs, uint32_t nowMs) {
    if (!armed_ || durationMs == 0U || position < limits_.minPosition ||
        position > limits_.maxPosition) {
        return false;
    }

    targetPosition_ = position;
    lastTargetAtMs_ = nowMs;
    hasTarget_ = true;
    const double durationSeconds = static_cast<double>(durationMs) / 1000.0;
    requestedSpeed_ = std::min(
        limits_.maxSpeed,
        std::abs(static_cast<double>(targetPosition_) - position_) /
            durationSeconds);
    return true;
}

TickOutput Engine::tick(double measuredDtSeconds, uint32_t nowMs) {
    (void)nowMs;
    TickOutput output{false, commandedPosition_, velocity_, acceleration_};
    if (!armed_ || !hasTarget_ || measuredDtSeconds <= 0.0) return output;

    const double dt = std::min(measuredDtSeconds, limits_.maxDtSeconds);
    const double error = static_cast<double>(targetPosition_) - position_;
    if (std::abs(error) <= kSettledPositionToleranceSteps &&
        std::abs(velocity_) <= kSettledVelocityToleranceStepsPerSecond) {
        position_ = targetPosition_;
        velocity_ = 0.0;
        acceleration_ = 0.0;
        requestedSpeed_ = 0.0;
        hasTarget_ = false;
        if (commandedPosition_ != targetPosition_) {
            commandedPosition_ = targetPosition_;
            output.commandChanged = true;
            output.commandPosition = commandedPosition_;
        }
        output.velocity = 0.0;
        output.acceleration = 0.0;
        return output;
    }
    const double stoppingSpeed =
        std::sqrt(std::max(0.0, 2.0 * limits_.maxAcceleration * std::abs(error)));
    const double desiredVelocity =
        direction(error) * std::min(requestedSpeed_, stoppingSpeed);
    const double velocityResponseSeconds = std::max(
        dt, limits_.maxAcceleration / limits_.maxJerk);
    const double desiredAcceleration = std::clamp(
        (desiredVelocity - velocity_) / velocityResponseSeconds,
        -limits_.maxAcceleration,
        limits_.maxAcceleration);

    const double previousVelocity = velocity_;
    acceleration_ = approach(
        acceleration_, desiredAcceleration, limits_.maxJerk * dt);
    velocity_ = std::clamp(
        velocity_ + acceleration_ * dt, -limits_.maxSpeed, limits_.maxSpeed);
    if ((previousVelocity > 0.0 && velocity_ < 0.0) ||
        (previousVelocity < 0.0 && velocity_ > 0.0)) {
        velocity_ = 0.0;
    }

    position_ += (previousVelocity + velocity_) * 0.5 * dt;
    if (position_ <= static_cast<double>(limits_.minPosition)) {
        position_ = limits_.minPosition;
        velocity_ = 0.0;
        acceleration_ = 0.0;
    } else if (position_ >= static_cast<double>(limits_.maxPosition)) {
        position_ = limits_.maxPosition;
        velocity_ = 0.0;
        acceleration_ = 0.0;
    }

    const int32_t nextCommand = static_cast<int32_t>(std::llround(position_));
    if (std::abs(nextCommand - commandedPosition_) >= 1) {
        commandedPosition_ = nextCommand;
        output.commandChanged = true;
        output.commandPosition = commandedPosition_;
    }
    output.velocity = velocity_;
    output.acceleration = acceleration_;
    return output;
}

void Engine::cancel() {
    velocity_ = 0.0;
    acceleration_ = 0.0;
    requestedSpeed_ = 0.0;
    targetPosition_ = commandedPosition_;
    hasTarget_ = false;
    armed_ = false;
}

void Engine::arm() {
    armed_ = true;
}

double Engine::fractionalPosition() const {
    return position_ - static_cast<double>(commandedPosition_);
}

bool Engine::targetFresh(uint32_t nowMs, uint32_t maxAgeMs) const {
    return hasTarget_ && static_cast<uint32_t>(nowMs - lastTargetAtMs_) <= maxAgeMs;
}

}  // namespace live_trajectory
