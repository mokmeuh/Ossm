#ifdef TASK11_TRACE
#include <cstdint>
#include <iostream>

#include "live_smooth_protocol.h"
#include "streaming_logic.h"

using namespace live_smooth;

int main() {
    QueueModel queue({true, 10000, 1000, 9000, 5000, false});
    std::size_t accepted = 0;
    std::size_t coalesced = 0;

    for (uint16_t sequence = 1; sequence <= 20; ++sequence) {
        const uint16_t position = sequence % 2 == 0 ? 900 : 100;
        const auto result =
            queue.offer({sequence, 1000, position, 20}, sequence * 10);
        accepted += result == OfferResult::Accepted ? 1U : 0U;
        coalesced += result == OfferResult::Coalesced ? 1U : 0U;
    }

    const auto first = queue.poll(220);
    if (!first || queue.maxDepthObserved() > kQueueCapacity) return 1;
    const int32_t targetSteps = streaming_logic::scaleStreamPosition(
        first->positionPermille / 10, 8000, 1000);
    const auto profile = streaming_logic::planMotion(
        -5000, targetSteps, first->durationMs / 1000.0f, 50000, 100000, 1);
    if (!profile.valid) return 2;

    const auto expired =
        queue.offer({21, 220, 500, 20}, 220);
    if (expired != OfferResult::Expired) return 3;

    queue.stopOutOfBand();
    if (queue.depth() != 0 || !queue.isStopped()) return 4;

    QueueModel unsafe({false, 10000, 1000, 9000, 5000, false});
    if (unsafe.offer({1, 500, 500, 20}, 0) !=
        OfferResult::SafetyInhibited) {
        return 5;
    }

    QueueModel pressure({true, 10000, 1000, 9000, 5000, true});
    if (pressure.motionAllowed() || pressure.depth() != 0) return 6;

    std::cout << "TRACE"
              << " accepted=" << accepted
              << " coalesced=" << coalesced
              << " max_depth=" << queue.maxDepthObserved()
              << " planner_valid=" << profile.valid
              << " deadline_remaining="
              << streaming_logic::remainingDurationMs(1040, 1020, 80)
              << " depth_after_stop=" << queue.depth()
              << " unsafe_blocked=1"
              << " pressure_blocked=1"
              << '\n';
    return 0;
}
#endif
