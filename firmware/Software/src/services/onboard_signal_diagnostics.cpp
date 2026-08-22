#include "onboard_signal_diagnostics.h"

#include "Arduino.h"
#include "constants/Pins.h"
#include "ossm/state/calibration.h"
#include "services/stepper.h"
#include "signal_diagnostics.h"

namespace {
constexpr TickType_t kSamplePeriod = pdMS_TO_TICKS(10);

void acquireSignalDiagnostics(void *) {
    SignalDiagnosticAcquisition acquisition;
    TickType_t lastWakeTime = xTaskGetTickCount();

    Serial.println(
        "timestamp_ms,raw_adc,offset_adc,direction,command_speed_millihz,"
        "command_acceleration,command_position,saturated,timestamp_monotonic");

    while (true) {
        const uint32_t rawAdc = analogRead(Pins::Driver::currentSensorPin);
        const uint16_t offsetAdc = static_cast<uint16_t>(constrain(
            lroundf(calibration.currentSensorOffset * 4096.0f / 100.0f), 0L,
            4095L));
        const int32_t currentPosition = stepper->getCurrentPosition();
        const int32_t commandedPosition =
            stepper->getPositionAfterCommandsCompleted();
        const int8_t direction = commandedPosition > currentPosition
                                     ? 1
                                     : (commandedPosition < currentPosition ? -1 : 0);
        const SignalDiagnosticSample sample = acquisition.capture(
            millis(), rawAdc, offsetAdc, direction,
            static_cast<int32_t>(stepper->getSpeedInMilliHz()),
            stepper->getAcceleration(), commandedPosition);

        Serial.printf("%lu,%u,%u,%d,%ld,%lu,%ld,%u,%u\n",
                      static_cast<unsigned long>(sample.timestampMs),
                      sample.rawAdc, sample.offsetAdc, sample.direction,
                      static_cast<long>(sample.commandedSpeedMilliHz),
                      static_cast<unsigned long>(sample.commandedAcceleration),
                      static_cast<long>(sample.commandedPosition),
                      sample.saturated ? 1U : 0U,
                      sample.timestampMonotonic ? 1U : 0U);
        vTaskDelayUntil(&lastWakeTime, kSamplePeriod);
    }
}
}

void startSignalDiagnostics() {
    pinMode(Pins::Driver::currentSensorPin, INPUT);
    adcAttachPin(Pins::Driver::currentSensorPin);
    xTaskCreatePinnedToCore(acquireSignalDiagnostics, "signalDiagnostics", 4096,
                            nullptr, 1, nullptr, 0);
}
