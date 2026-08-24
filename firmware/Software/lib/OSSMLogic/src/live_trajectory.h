#pragma once

#include <cstdint>

namespace live_trajectory {

struct Limits {
    double maxSpeed;
    double maxAcceleration;
    double maxJerk;
    int32_t minPosition;
    int32_t maxPosition;
    double maxDtSeconds;
};

struct TickOutput {
    bool commandChanged;
    int32_t commandPosition;
    double velocity;
    double acceleration;
};

class Engine {
public:
    explicit Engine(const Limits& limits);

    void initialize(int32_t position, uint32_t nowMs);
    void setLimits(const Limits& limits);
    bool setTarget(int32_t position, uint16_t durationMs, uint32_t nowMs);
    TickOutput tick(double measuredDtSeconds, uint32_t nowMs);
    void cancel();
    void arm();

    double position() const { return position_; }
    double fractionalPosition() const;
    double velocity() const { return velocity_; }
    double acceleration() const { return acceleration_; }
    double requestedSpeed() const { return requestedSpeed_; }
    int32_t commandedPosition() const { return commandedPosition_; }
    int32_t targetPosition() const { return targetPosition_; }
    uint32_t lastTargetAtMs() const { return lastTargetAtMs_; }
    bool hasTarget() const { return hasTarget_; }
    bool isArmed() const { return armed_; }
    bool targetFresh(uint32_t nowMs, uint32_t maxAgeMs) const;

private:
    Limits limits_;
    double position_ = 0.0;
    double velocity_ = 0.0;
    double acceleration_ = 0.0;
    double requestedSpeed_ = 0.0;
    int32_t commandedPosition_ = 0;
    int32_t targetPosition_ = 0;
    uint32_t lastTargetAtMs_ = 0;
    bool hasTarget_ = false;
    bool armed_ = false;
};

}  // namespace live_trajectory
