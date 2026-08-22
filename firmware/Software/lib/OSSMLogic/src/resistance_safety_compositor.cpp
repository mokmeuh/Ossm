#include "resistance_safety.h"

#include <algorithm>

namespace resistance_safety {
namespace {

constexpr uint8_t kLoadEngagePercent = 30;
constexpr uint8_t kLoadReleasePercent = 20;
constexpr uint32_t kPointScale = 1000;

uint8_t clampPoint(uint8_t point) {
    return std::min<uint8_t>(point, 100);
}

uint32_t ramp(uint32_t current, uint32_t target, uint32_t elapsedMs,
              uint32_t risePerSecond, uint32_t decayPerSecond) {
    if (target >= current) {
        const uint64_t step = static_cast<uint64_t>(risePerSecond) * elapsedMs / 1000;
        return static_cast<uint32_t>(std::min<uint64_t>(target, current + step));
    }
    const uint64_t step = static_cast<uint64_t>(decayPerSecond) * elapsedMs / 1000;
    return step >= current - target ? target : current - static_cast<uint32_t>(step);
}

uint32_t bonusTarget(uint8_t loadPercent, uint8_t requestedCap, uint8_t hardCap,
                     uint8_t base, uint8_t absoluteCeiling) {
    if (base >= absoluteCeiling) {
        return 0;
    }
    const uint8_t available = absoluteCeiling - base;
    const uint8_t cap = std::min({requestedCap, hardCap, available});
    return static_cast<uint32_t>(cap) * kPointScale * loadPercent / 100;
}

}

CompositorOutput Compositor::update(const CompositorRequest& request,
                                    const Telemetry& telemetry, uint32_t nowMs,
                                    bool adaptiveAllowed) {
    const uint32_t elapsedMs = updateStarted_ ? nowMs - lastUpdateMs_ : 0;
    updateStarted_ = true;
    lastUpdateMs_ = nowMs;

    const bool usable = adaptiveAllowed && telemetry.valid && !telemetry.saturated &&
                        !telemetry.reversing && !telemetry.overload &&
                        telemetry.normalizedLoadPercent <= 100 &&
                        nowMs - telemetry.sampledAtMs <= kFreshnessMs;
    uint8_t loadPercent = 0;
    if (usable) {
        loadPercent = effectiveLoad(
            updateFilteredLoad(telemetry.normalizedLoadPercent, nowMs), nowMs);
    } else {
        resetLoadFilter();
        speedBonusMilliPoints_ = 0;
        depthBonusMilliPoints_ = 0;
    }

    const uint8_t baseSpeed = clampPoint(request.baseSpeedPoints);
    const uint8_t speedCeiling = std::min(clampPoint(request.userSpeedCeilingPoints),
                                          clampPoint(request.firmwareSpeedLimitPoints));
    const uint8_t outputSpeedBase = std::min(baseSpeed, speedCeiling);
    const uint32_t speedTarget = bonusTarget(loadPercent, request.speedBonusCeilingPoints,
                                             kHardSpeedBonusPoints, baseSpeed, speedCeiling);

    const uint8_t requestedShallow = clampPoint(request.shallowEndpointPoints);
    const uint8_t requestedDeep = clampPoint(request.baseDeepEndpointPoints);
    const uint8_t deepCeiling = std::min(clampPoint(request.userDeepCeilingPoints),
                                         clampPoint(request.firmwareDeepLimitPoints));
    const uint8_t outputDeepBase = std::min(requestedDeep, deepCeiling);
    const bool validRange = requestedShallow <= outputDeepBase;
    const uint8_t outputShallow = validRange ? requestedShallow : outputDeepBase;
    const uint32_t depthTarget = validRange
        ? bonusTarget(loadPercent, request.depthBonusCeilingPoints,
                      kHardDepthBonusPoints, requestedDeep, deepCeiling)
        : 0;

    if (usable) {
        speedBonusMilliPoints_ = ramp(speedBonusMilliPoints_, speedTarget, elapsedMs,
                                      kSpeedRiseMilliPointsPerSecond,
                                      kSpeedDecayMilliPointsPerSecond);
        depthBonusMilliPoints_ = ramp(depthBonusMilliPoints_, depthTarget, elapsedMs,
                                      kDepthRiseMilliPointsPerSecond,
                                      kDepthDecayMilliPointsPerSecond);
        speedBonusMilliPoints_ = std::min(speedBonusMilliPoints_, speedTarget);
        depthBonusMilliPoints_ = std::min(depthBonusMilliPoints_, depthTarget);
    }

    return {
        static_cast<uint32_t>(outputSpeedBase) * kPointScale + speedBonusMilliPoints_,
        static_cast<uint32_t>(outputShallow) * kPointScale,
        static_cast<uint32_t>(outputDeepBase) * kPointScale + depthBonusMilliPoints_,
        speedBonusMilliPoints_, depthBonusMilliPoints_, usable};
}

uint8_t Compositor::updateFilteredLoad(uint8_t loadPercent, uint32_t nowMs) {
    if (!filterStarted_) {
        filterStarted_ = true;
        lastFilterMs_ = nowMs;
        filteredLoadPercent_ = loadPercent;
        return filteredLoadPercent_;
    }
    const uint32_t elapsedMs = std::min(nowMs - lastFilterMs_, kFilterHorizonMs);
    lastFilterMs_ = nowMs;
    const uint32_t retained = filteredLoadPercent_ * (kFilterHorizonMs - elapsedMs);
    const uint32_t incoming = loadPercent * elapsedMs;
    filteredLoadPercent_ = static_cast<uint8_t>((retained + incoming) / kFilterHorizonMs);
    return filteredLoadPercent_;
}

uint8_t Compositor::effectiveLoad(uint8_t filteredLoad, uint32_t nowMs) {
    if (loadQualified_) {
        if (filteredLoad <= kLoadReleasePercent) {
            loadQualified_ = false;
            qualifying_ = false;
        }
    } else if (filteredLoad >= kLoadEngagePercent) {
        if (!qualifying_) {
            qualifying_ = true;
            qualifyingSinceMs_ = nowMs;
        } else if (nowMs - qualifyingSinceMs_ >= kFilterHorizonMs) {
            loadQualified_ = true;
        }
    } else {
        qualifying_ = false;
    }
    if (!loadQualified_) {
        return 0;
    }
    return static_cast<uint8_t>((filteredLoad - kLoadReleasePercent) * 100 /
                                (100 - kLoadReleasePercent));
}

void Compositor::resetLoadFilter() {
    filterStarted_ = false;
    qualifying_ = false;
    loadQualified_ = false;
    filteredLoadPercent_ = 0;
}

uint32_t remapStreamingPosition(uint8_t positionPercent, uint32_t shallowMilliPoints,
                                uint32_t deepMilliPoints) {
    const uint32_t shallow = std::min<uint32_t>(shallowMilliPoints, 100000);
    const uint32_t deep = std::min<uint32_t>(deepMilliPoints, 100000);
    if (deep <= shallow) {
        return deep;
    }
    const uint32_t position = std::min<uint8_t>(positionPercent, 100);
    return shallow + static_cast<uint32_t>(
        static_cast<uint64_t>(deep - shallow) * position / 100);
}

}
