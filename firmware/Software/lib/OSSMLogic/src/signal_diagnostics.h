#ifndef OSSM_LOGIC_SIGNAL_DIAGNOSTICS_H
#define OSSM_LOGIC_SIGNAL_DIAGNOSTICS_H

#include <algorithm>
#include <cstdint>

struct SignalDiagnosticSample {
    uint32_t timestampMs;
    uint16_t rawAdc;
    uint16_t offsetAdc;
    int8_t direction;
    int32_t commandedSpeedMilliHz;
    uint32_t commandedAcceleration;
    int32_t commandedPosition;
    bool saturated;
    bool timestampMonotonic;
};

class SignalDiagnosticAcquisition {
   public:
    SignalDiagnosticSample capture(uint32_t observedTimestampMs, uint32_t rawAdc,
                                   uint16_t offsetAdc, int8_t direction,
                                   int32_t commandedSpeedMilliHz,
                                   uint32_t commandedAcceleration,
                                   int32_t commandedPosition) {
        const uint16_t boundedRaw =
            static_cast<uint16_t>(std::min<uint32_t>(rawAdc, kAdcMaximum));
        const uint32_t emittedTimestamp =
            hasPrevious_ ? previousTimestampMs_ + kSamplePeriodMs
                         : observedTimestampMs;
        const bool monotonic =
            !hasPrevious_ || emittedTimestamp > previousTimestampMs_;
        previousTimestampMs_ = emittedTimestamp;
        hasPrevious_ = true;

        return {emittedTimestamp,
                boundedRaw,
                offsetAdc,
                direction,
                commandedSpeedMilliHz,
                commandedAcceleration,
                commandedPosition,
                boundedRaw == 0 || boundedRaw == kAdcMaximum,
                monotonic};
    }

   private:
    static constexpr uint16_t kAdcMaximum = 4095;
    static constexpr uint32_t kSamplePeriodMs = 10;
    uint32_t previousTimestampMs_ = 0;
    bool hasPrevious_ = false;
};

#endif
