#include "queue.h"

#include <array>

#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>

std::queue<String> messageQueue = {};
std::queue<PositionTime> targetQueue = {};

namespace {

QueueHandle_t liveQueue = nullptr;
SemaphoreHandle_t liveMutex = nullptr;
live_smooth::SafetyState liveSafety{};
bool liveStopped = true;
bool endpointPressureLatched = false;
bool hasSequence = false;
uint16_t lastSequence = 0;
bool hasAcceptedAt = false;
uint32_t lastAcceptedAt = 0;

class QueueLock {
public:
    QueueLock() : locked_(liveMutex != nullptr &&
                          xSemaphoreTake(liveMutex, portMAX_DELAY) == pdTRUE) {}
    ~QueueLock() {
        if (locked_) xSemaphoreGive(liveMutex);
    }
    explicit operator bool() const { return locked_; }

private:
    bool locked_;
};

bool reached(uint32_t candidate, uint32_t reference) {
    return static_cast<uint32_t>(candidate - reference) < 0x80000000U;
}

bool safetyAllowsMotion() {
    if (!liveSafety.homingValid || endpointPressureLatched ||
        liveSafety.repeatedEndpointPressure ||
        liveSafety.calibratedTravelSteps <= 0 ||
        liveSafety.shallowEndpointSteps >= liveSafety.deepEndpointSteps) {
        return false;
    }
    const int32_t usableTravel =
        liveSafety.deepEndpointSteps - liveSafety.shallowEndpointSteps;
    return usableTravel <= liveSafety.calibratedTravelSteps &&
           liveSafety.currentPositionSteps >= liveSafety.shallowEndpointSteps &&
           liveSafety.currentPositionSteps <= liveSafety.deepEndpointSteps;
}

void replaceNewest(const LiveSmoothTarget& target) {
    std::array<LiveSmoothTarget, live_smooth::kQueueCapacity> pending{};
    std::size_t count = 0;
    while (count < pending.size() &&
           xQueueReceive(liveQueue, &pending[count], 0) == pdTRUE) {
        ++count;
    }
    if (count == 0) {
        xQueueSend(liveQueue, &target, 0);
        return;
    }
    pending[count - 1] = target;
    for (std::size_t index = 0; index < count; ++index) {
        xQueueSend(liveQueue, &pending[index], 0);
    }
}

}  // namespace

void initializeLiveSmoothQueue() {
    if (liveMutex == nullptr) liveMutex = xSemaphoreCreateMutex();
    QueueLock lock;
    if (!lock) return;

    if (liveQueue == nullptr) {
        liveQueue = xQueueCreate(live_smooth::kQueueCapacity,
                                 sizeof(LiveSmoothTarget));
    } else {
        xQueueReset(liveQueue);
    }
    liveStopped = true;
    hasSequence = false;
    hasAcceptedAt = false;
}

void updateLiveSmoothSafety(const live_smooth::SafetyState& safety) {
    QueueLock lock;
    if (!lock) return;

    liveSafety = safety;
    if (!safetyAllowsMotion()) {
        liveStopped = true;
        if (liveQueue != nullptr) xQueueReset(liveQueue);
    }
}

void latchLiveSmoothEndpointPressure() {
    QueueLock lock;
    if (!lock) return;

    endpointPressureLatched = true;
    liveStopped = true;
    if (liveQueue != nullptr) xQueueReset(liveQueue);
}

live_smooth::OfferResult offerLiveSmoothTarget(
    const live_smooth::Packet& packet, uint32_t nowMs) {
    using namespace live_smooth;
    QueueLock lock;
    if (!lock || liveStopped || liveQueue == nullptr || !safetyAllowsMotion()) {
        return OfferResult::SafetyInhibited;
    }
    if (packet.durationMs == 0U) return OfferResult::Expired;
    if (hasSequence) {
        if (packet.sequence == lastSequence) return OfferResult::Duplicate;
        if (!isNewerSequence(packet.sequence, lastSequence)) {
            return OfferResult::StaleSequence;
        }
    }

    lastSequence = packet.sequence;
    hasSequence = true;
    const bool tooSoon =
        hasAcceptedAt &&
        static_cast<uint32_t>(nowMs - lastAcceptedAt) < kMinimumInputIntervalMs;
    const bool full = uxQueueMessagesWaiting(liveQueue) >= kQueueCapacity;
    const LiveSmoothTarget target{rebaseDeadline(packet, nowMs), nowMs};
    if (tooSoon || full) {
        replaceNewest(target);
        return OfferResult::Coalesced;
    }
    if (xQueueSend(liveQueue, &target, 0) != pdTRUE) {
        replaceNewest(target);
        return OfferResult::Coalesced;
    }

    lastAcceptedAt = nowMs;
    hasAcceptedAt = true;
    return OfferResult::Accepted;
}

bool pollLiveSmoothTarget(uint32_t nowMs, LiveSmoothTarget& target) {
    QueueLock lock;
    if (!lock || liveStopped || liveQueue == nullptr || !safetyAllowsMotion()) {
        return false;
    }

    while (xQueueReceive(liveQueue, &target, 0) == pdTRUE) {
        if (!reached(nowMs, target.packet.deadlineMs) &&
            !reached(nowMs,
                     target.acceptedAtMs + live_smooth::kTargetExpiryMs)) {
            return true;
        }
    }
    return false;
}

void stopLiveSmoothOutOfBand() {
    QueueLock lock;
    if (!lock) return;

    liveStopped = true;
    if (liveQueue != nullptr) xQueueReset(liveQueue);
}

bool resumeLiveSmooth() {
    QueueLock lock;
    if (!lock || !safetyAllowsMotion()) return false;
    liveStopped = false;
    return true;
}

std::size_t liveSmoothQueueDepth() {
    QueueLock lock;
    if (!lock || liveQueue == nullptr) return 0;
    return uxQueueMessagesWaiting(liveQueue);
}
bool liveSmoothMotionAllowed() {
    QueueLock lock;
    if (!lock) return false;
    return !liveStopped && safetyAllowsMotion();
}
