#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>

namespace live_smooth {

inline constexpr char kCharacteristicUuid[] = "522b443a-4f53-534d-7000-420badbabe69";
inline constexpr uint8_t kVersion = 1;
inline constexpr std::size_t kPacketSize = 12;
inline constexpr std::size_t kQueueCapacity = 4;
inline constexpr uint32_t kMinimumInputIntervalMs = 20;
inline constexpr uint32_t kTargetExpiryMs = 250;

struct Packet {
    uint16_t sequence;
    uint32_t deadlineMs;
    uint16_t positionPermille;
    uint16_t durationMs;

    bool operator==(const Packet& other) const;
};

enum class DecodeStatus {
    Ok,
    InvalidLength,
    UnsupportedVersion,
    InvalidFlags,
    InvalidPosition,
};

enum class OfferResult {
    Accepted,
    Coalesced,
    Duplicate,
    StaleSequence,
    Expired,
    SafetyInhibited,
};

struct SafetyState {
    bool homingValid;
    int32_t calibratedTravelSteps;
    int32_t shallowEndpointSteps;
    int32_t deepEndpointSteps;
    int32_t currentPositionSteps;
    bool repeatedEndpointPressure;
};

std::array<uint8_t, kPacketSize> encode(const Packet& packet);
DecodeStatus decode(const uint8_t* bytes, std::size_t length, Packet& packet);
bool isNewerSequence(uint16_t candidate, uint16_t previous);
int32_t distancePermille(uint16_t currentPositionPermille, const Packet& target);
Packet rebaseDeadline(const Packet& packet, uint32_t receivedAtMs);

class QueueModel {
public:
    explicit QueueModel(const SafetyState& safety);

    OfferResult offer(const Packet& packet, uint32_t receivedAtMs);
    std::optional<Packet> poll(uint32_t nowMs);
    void updateSafety(const SafetyState& safety);
    void stopOutOfBand();
    bool resume();

    std::size_t depth() const;
    std::size_t maxDepthObserved() const;
    const Packet& pending(std::size_t index) const;
    bool isStopped() const;
    bool motionAllowed() const;

private:
    struct Entry {
        Packet packet;
        uint32_t acceptedAtMs;
    };

    void purgeExpired(uint32_t nowMs);
    static bool safetyAllowsMotion(const SafetyState& safety);

    std::array<Entry, kQueueCapacity> entries_{};
    std::size_t depth_ = 0;
    std::size_t maxDepthObserved_ = 0;
    SafetyState safety_;
    bool stopped_;
    bool hasSequence_ = false;
    uint16_t lastSequence_ = 0;
    bool hasQueuedAt_ = false;
    uint32_t lastQueuedAtMs_ = 0;
};

}
