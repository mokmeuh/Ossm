#ifndef OSSM_RESISTANCE_SAFETY_H
#define OSSM_RESISTANCE_SAFETY_H

#include <cstdint>

namespace resistance_safety {

constexpr uint32_t kFilterHorizonMs = 50;
constexpr uint32_t kFreshnessMs = 100;
constexpr uint32_t kOverloadConfirmationMs = 100;
constexpr uint8_t kHardSpeedBonusPoints = 10;
constexpr uint8_t kHardDepthBonusPoints = 5;
constexpr uint32_t kSpeedRiseMilliPointsPerSecond = 2000;
constexpr uint32_t kDepthRiseMilliPointsPerSecond = 1000;
constexpr uint32_t kSpeedDecayMilliPointsPerSecond = 4000;
constexpr uint32_t kDepthDecayMilliPointsPerSecond = 2000;

struct Telemetry {
    uint8_t normalizedLoadPercent;
    uint32_t sampledAtMs;
    bool valid;
    bool saturated;
    bool reversing;
    bool overload;
};

struct CompositorRequest {
    uint8_t baseSpeedPoints;
    uint8_t shallowEndpointPoints;
    uint8_t baseDeepEndpointPoints;
    uint8_t userSpeedCeilingPoints;
    uint8_t userDeepCeilingPoints;
    uint8_t firmwareSpeedLimitPoints;
    uint8_t firmwareDeepLimitPoints;
    uint8_t speedBonusCeilingPoints;
    uint8_t depthBonusCeilingPoints;
};

struct CompositorOutput {
    uint32_t speedMilliPoints;
    uint32_t shallowEndpointMilliPoints;
    uint32_t deepEndpointMilliPoints;
    uint32_t speedBonusMilliPoints;
    uint32_t depthBonusMilliPoints;
    bool telemetryUsable;
};

class Compositor {
public:
    CompositorOutput update(const CompositorRequest& request, const Telemetry& telemetry,
                            uint32_t nowMs, bool adaptiveAllowed);

private:
    uint8_t updateFilteredLoad(uint8_t loadPercent, uint32_t nowMs);
    uint8_t effectiveLoad(uint8_t filteredLoad, uint32_t nowMs);
    void resetLoadFilter();

    uint32_t speedBonusMilliPoints_ = 0;
    uint32_t depthBonusMilliPoints_ = 0;
    uint32_t lastUpdateMs_ = 0;
    uint32_t lastFilterMs_ = 0;
    uint32_t qualifyingSinceMs_ = 0;
    uint8_t filteredLoadPercent_ = 0;
    bool updateStarted_ = false;
    bool filterStarted_ = false;
    bool qualifying_ = false;
    bool loadQualified_ = false;
};

uint32_t remapStreamingPosition(uint8_t positionPercent, uint32_t shallowMilliPoints,
                                uint32_t deepMilliPoints);

enum class FaultCode : uint8_t {
    None = 0,
    Overload = 1,
    EndpointPressure = 2
};

enum class LifecycleEvent : uint8_t {
    Stop,
    ModeChange,
    MenuEntry,
    Disconnect,
    SessionChange,
    Fault,
    Reboot,
    StaleTelemetry
};

enum class MotionCommand : uint8_t {
    StrokeEngine,
    SimplePenetration,
    Streaming,
    Funscript,
    VideoSync,
    Adaptive
};

struct MotionEnvelope {
    bool homingValid;
    bool travelCalibrated;
    int32_t positionSteps;
    int32_t shallowEndpointSteps;
    int32_t deepEndpointSteps;
    int32_t keepoutMinSteps;
    int32_t keepoutMaxSteps;
    bool endpointPressure;
};

struct PersistentFault {
    FaultCode code = FaultCode::None;
};

class LatchedFaultSafety {
public:
    LatchedFaultSafety() = default;
    explicit LatchedFaultSafety(PersistentFault persisted);

    bool requestArm(bool telemetryUsable, bool homed);
    void observeTelemetry(bool usable);
    void observeMotionEnvelope(const MotionEnvelope& envelope);
    void observeOverload(bool overload, uint32_t nowMs);
    void onLifecycle(LifecycleEvent event);
    void acknowledgeFault();
    void reportHomeResult(bool successful, const MotionEnvelope& envelope);

    bool outputEnabled() const;
    bool resistanceArmed() const;
    bool acceptsMotion(MotionCommand command) const;
    bool acceptsTarget(int32_t targetSteps) const;
    FaultCode faultCode() const;
    PersistentFault persistentFault() const;

private:
    void latch(FaultCode code);

    FaultCode faultCode_ = FaultCode::None;
    int32_t shallowEndpointSteps_ = 0;
    int32_t deepEndpointSteps_ = 0;
    uint32_t overloadSinceMs_ = 0;
    bool acknowledged_ = false;
    bool resistanceArmed_ = false;
    bool envelopeSafe_ = false;
    bool overloadTracking_ = false;
    bool endpointPressureSeen_ = false;
};

}

#endif
