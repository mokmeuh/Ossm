#ifndef OSSM_COMMUNICATION_QUEUE_H
#define OSSM_COMMUNICATION_QUEUE_H

#include <Arduino.h>
#include <chrono>
#include <queue>

#include "live_smooth_protocol.h"

struct PositionTime {
    uint8_t position; // 0 - 100
    uint16_t inTime;     // in ms
    std::chrono::steady_clock::time_point setTime; //received timestamp
    int direction; //0:uncalculated, 1:out, -1:in
};

extern std::queue<String> messageQueue;
extern std::queue<PositionTime> targetQueue;

struct LiveSmoothTarget {
    live_smooth::Packet packet{};
    uint32_t acceptedAtMs = 0;
};

void initializeLiveSmoothQueue();
void updateLiveSmoothSafety(const live_smooth::SafetyState& safety);
void latchLiveSmoothEndpointPressure();
live_smooth::OfferResult offerLiveSmoothTarget(
    const live_smooth::Packet& packet, uint32_t nowMs);
bool pollLiveSmoothTarget(uint32_t nowMs, LiveSmoothTarget& target);
void clearLiveSmoothTargets();
void stopLiveSmoothOutOfBand();
bool resumeLiveSmooth();
std::size_t liveSmoothQueueDepth();
bool liveSmoothMotionAllowed();

#endif  // OSSM_COMMUNICATION_QUEUE_H
