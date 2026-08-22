#include "live_smooth_protocol.h"

#include <algorithm>
#include <cstdlib>

namespace live_smooth {
namespace {

uint16_t readU16(const uint8_t* bytes) {
    return static_cast<uint16_t>(bytes[0]) |
           static_cast<uint16_t>(static_cast<uint16_t>(bytes[1]) << 8U);
}

uint32_t readU32(const uint8_t* bytes) {
    return static_cast<uint32_t>(bytes[0]) |
           (static_cast<uint32_t>(bytes[1]) << 8U) |
           (static_cast<uint32_t>(bytes[2]) << 16U) |
           (static_cast<uint32_t>(bytes[3]) << 24U);
}

void writeU16(uint8_t* bytes, uint16_t value) {
    bytes[0] = static_cast<uint8_t>(value & 0xffU);
    bytes[1] = static_cast<uint8_t>((value >> 8U) & 0xffU);
}

void writeU32(uint8_t* bytes, uint32_t value) {
    bytes[0] = static_cast<uint8_t>(value & 0xffU);
    bytes[1] = static_cast<uint8_t>((value >> 8U) & 0xffU);
    bytes[2] = static_cast<uint8_t>((value >> 16U) & 0xffU);
    bytes[3] = static_cast<uint8_t>((value >> 24U) & 0xffU);
}

bool hasReached(uint32_t candidate, uint32_t reference) {
    const uint32_t difference = candidate - reference;
    return difference < 0x80000000U;
}

bool expired(const Packet& packet, uint32_t acceptedAtMs, uint32_t nowMs) {
    return hasReached(nowMs, packet.deadlineMs) ||
           hasReached(nowMs, acceptedAtMs + kTargetExpiryMs);
}

}

bool Packet::operator==(const Packet& other) const {
    return sequence == other.sequence && deadlineMs == other.deadlineMs &&
           positionPermille == other.positionPermille && durationMs == other.durationMs;
}

std::array<uint8_t, kPacketSize> encode(const Packet& packet) {
    std::array<uint8_t, kPacketSize> bytes{};
    bytes[0] = kVersion;
    bytes[1] = 0;
    writeU16(bytes.data() + 2, packet.sequence);
    writeU32(bytes.data() + 4, packet.deadlineMs);
    writeU16(bytes.data() + 8, packet.positionPermille);
    writeU16(bytes.data() + 10, packet.durationMs);
    return bytes;
}

DecodeStatus decode(const uint8_t* bytes, std::size_t length, Packet& packet) {
    if (bytes == nullptr || length != kPacketSize) {
        return DecodeStatus::InvalidLength;
    }
    if (bytes[0] != kVersion) {
        return DecodeStatus::UnsupportedVersion;
    }
    if (bytes[1] != 0U) {
        return DecodeStatus::InvalidFlags;
    }
    const uint16_t positionPermille = readU16(bytes + 8);
    if (positionPermille > 1000U) {
        return DecodeStatus::InvalidPosition;
    }
    packet = Packet{readU16(bytes + 2), readU32(bytes + 4), positionPermille, readU16(bytes + 10)};
    return DecodeStatus::Ok;
}

bool isNewerSequence(uint16_t candidate, uint16_t previous) {
    const uint16_t difference = static_cast<uint16_t>(candidate - previous);
    return difference != 0U && difference < 0x8000U;
}

int32_t distancePermille(uint16_t currentPositionPermille, const Packet& target) {
    return std::abs(static_cast<int32_t>(target.positionPermille) -
                    static_cast<int32_t>(currentPositionPermille));
}

Packet rebaseDeadline(const Packet& packet, uint32_t receivedAtMs) {
    Packet rebased = packet;
    rebased.deadlineMs = receivedAtMs + kTargetExpiryMs;
    return rebased;
}

QueueModel::QueueModel(const SafetyState& safety)
    : safety_(safety), stopped_(!safetyAllowsMotion(safety)) {}

OfferResult QueueModel::offer(const Packet& packet, uint32_t receivedAtMs) {
    updateSafety(safety_);
    if (stopped_ || !safetyAllowsMotion(safety_)) {
        return OfferResult::SafetyInhibited;
    }
    purgeExpired(receivedAtMs);
    if (packet.durationMs == 0U) {
        return OfferResult::Expired;
    }
    if (hasSequence_) {
        if (packet.sequence == lastSequence_) {
            return OfferResult::Duplicate;
        }
        if (!isNewerSequence(packet.sequence, lastSequence_)) {
            return OfferResult::StaleSequence;
        }
    }

    const Packet localPacket = rebaseDeadline(packet, receivedAtMs);
    lastSequence_ = packet.sequence;
    hasSequence_ = true;
    const bool tooSoon = hasQueuedAt_ &&
                         static_cast<uint32_t>(receivedAtMs - lastQueuedAtMs_) < kMinimumInputIntervalMs;
    if ((tooSoon || depth_ == kQueueCapacity) && depth_ != 0U) {
        entries_[depth_ - 1] = Entry{localPacket, receivedAtMs};
        return OfferResult::Coalesced;
    }
    if (tooSoon) {
        return OfferResult::Coalesced;
    }

    entries_[depth_] = Entry{localPacket, receivedAtMs};
    ++depth_;
    maxDepthObserved_ = std::max(maxDepthObserved_, depth_);
    lastQueuedAtMs_ = receivedAtMs;
    hasQueuedAt_ = true;
    return OfferResult::Accepted;
}

std::optional<Packet> QueueModel::poll(uint32_t nowMs) {
    if (stopped_ || !safetyAllowsMotion(safety_)) {
        stopOutOfBand();
        return std::nullopt;
    }
    purgeExpired(nowMs);
    if (depth_ == 0U) {
        return std::nullopt;
    }
    const Packet packet = entries_[0].packet;
    for (std::size_t index = 1; index < depth_; ++index) {
        entries_[index - 1] = entries_[index];
    }
    --depth_;
    return packet;
}

void QueueModel::updateSafety(const SafetyState& safety) {
    safety_ = safety;
    if (!safetyAllowsMotion(safety_)) {
        stopOutOfBand();
    }
}

void QueueModel::stopOutOfBand() {
    depth_ = 0;
    stopped_ = true;
}

bool QueueModel::resume() {
    if (!safetyAllowsMotion(safety_)) return false;
    stopped_ = false;
    return true;
}

std::size_t QueueModel::depth() const {
    return depth_;
}

std::size_t QueueModel::maxDepthObserved() const {
    return maxDepthObserved_;
}

const Packet& QueueModel::pending(std::size_t index) const {
    return entries_[index].packet;
}

bool QueueModel::isStopped() const {
    return stopped_;
}

bool QueueModel::motionAllowed() const {
    return !stopped_ && safetyAllowsMotion(safety_);
}

void QueueModel::purgeExpired(uint32_t nowMs) {
    std::size_t output = 0;
    for (std::size_t index = 0; index < depth_; ++index) {
        if (!expired(entries_[index].packet, entries_[index].acceptedAtMs, nowMs)) {
            entries_[output++] = entries_[index];
        }
    }
    depth_ = output;
}

bool QueueModel::safetyAllowsMotion(const SafetyState& safety) {
    if (!safety.homingValid || safety.repeatedEndpointPressure || safety.calibratedTravelSteps <= 0) {
        return false;
    }
    if (safety.shallowEndpointSteps >= safety.deepEndpointSteps) {
        return false;
    }
    const int32_t usableTravel = safety.deepEndpointSteps - safety.shallowEndpointSteps;
    return usableTravel <= safety.calibratedTravelSteps &&
           safety.currentPositionSteps >= safety.shallowEndpointSteps &&
           safety.currentPositionSteps <= safety.deepEndpointSteps;
}

}
