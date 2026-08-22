#include "streaming.h"

#include <chrono>
#include <optional>
#include <queue>

#include "streaming_logic.h"
#include "constants/Config.h"
#include "ossm/state/calibration.h"
#include "ossm/state/session.h"
#include "ossm/state/settings.h"
#include "ossm/state/state.h"
#include "services/board.h"
#include "services/communication/nimble.h"
#include "services/communication/queue.h"
#include "services/stepper.h"
#include "services/tasks.h"

namespace sml = boost::sml;
using namespace sml;

namespace streaming {

static void startStreamingTask(void *pvParameters) {
    stepperTranslateFrame(StepperFrame::Native);
    stepper->setDirectionPin(Pins::Driver::motorDirectionPin, false);
    stepper->enableOutputs();

    auto isInCorrectState = []() {
        return stateMachine->is("streaming"_s) ||
               stateMachine->is("streaming.preflight"_s) ||
               stateMachine->is("streaming.idle"_s);
    };

    auto best = std::chrono::steady_clock::now();
    PositionTime lastPositionTime{};
    bool hasLastPosition = false;
    std::optional<LiveSmoothTarget> pendingLive;
    bool modeArmed = false;

    targetQueue = {};
    initializeLiveSmoothQueue();

    const uint16_t maxSpeed =
        Config::Driver::maxSpeedMmPerSecond * (1_mm);
    const uint32_t maxAccel =
        Config::Driver::maxAcceleration * (1_mm);
    stepper->setSpeedInHz(maxSpeed);
    stepper->setAcceleration(maxAccel);

    while (isInCorrectState()) {
        const int32_t currentPosition = stepper->getCurrentPosition();
        const int32_t measuredTravel =
            static_cast<int32_t>(calibration.measuredStrokeSteps);
        const int32_t allowedStroke = streaming_logic::calculateMaxStroke(
            settings.stroke, settings.depth, calibration.measuredStrokeSteps);
        const int32_t allowedDepth = streaming_logic::calculateDepthOffset(
            calibration.measuredStrokeSteps, allowedStroke, settings.depth);
        updateLiveSmoothSafety({
            calibration.isHomed,
            measuredTravel,
            -allowedStroke - allowedDepth,
            -allowedDepth,
            currentPosition,
            false,
        });
        if (!modeArmed) {
            modeArmed = resumeLiveSmooth();
        }
        if (!liveSmoothMotionAllowed()) {
            pendingLive.reset();
            targetQueue = {};
            stepper->forceStop();
            stepper->disableOutputs();
            vTaskDelay(1);
            continue;
        }

        const auto now = std::chrono::steady_clock::now();
        const uint16_t currentBuffer =
            std::chrono::duration_cast<std::chrono::milliseconds>(now - best)
                .count();
        if (!pendingLive.has_value()) {
            LiveSmoothTarget target{};
            if (pollLiveSmoothTarget(millis(), target)) pendingLive = target;
        }

        const bool isLiveSmooth = pendingLive.has_value();
        PositionTime targetPositionTime{};
        if (isLiveSmooth) {
            const uint32_t nowMs = millis();
            targetPositionTime.position =
                pendingLive->packet.positionPermille / 10U;
            targetPositionTime.inTime =
                streaming_logic::remainingDurationMs(
                    pendingLive->packet.deadlineMs,
                    nowMs,
                    pendingLive->packet.durationMs);
            targetPositionTime.setTime = now;
        } else if (!targetQueue.empty()) {
            targetPositionTime = targetQueue.front();
        } else {
            vTaskDelay(1);
            continue;
        }

        const int16_t streamDistance =
            targetPositionTime.position - lastPositionTime.position;
        targetPositionTime.direction =
            streaming_logic::streamDirection(streamDistance);
        if (!streaming_logic::shouldScheduleStreamPosition(
                hasLastPosition, streamDistance)) {
            if (isLiveSmooth) {
                pendingLive.reset();
            } else {
                targetQueue.pop();
            }
            vTaskDelay(1);
            continue;
        }
        const bool sameDirection =
            !hasLastPosition ||
            lastPositionTime.direction == targetPositionTime.direction;
        if (!sameDirection && stepper->isRunning()) {
            vTaskDelay(1);
            continue;
        }

        if (isLiveSmooth) {
            pendingLive.reset();
        } else {
            targetQueue.pop();
        }

        float timeSeconds = targetPositionTime.inTime / 1000.0f;
        if (!isLiveSmooth && USE_LATENCY_COMPENSATION) {
            const int16_t mincomp =
                min(int(settings.buffer * 2), int(lastPositionTime.inTime));
            int16_t offset = mincomp - currentBuffer;
            int16_t lag =
                std::chrono::duration_cast<std::chrono::milliseconds>(
                    targetPositionTime.setTime - best)
                    .count();
            if (lag < 0 || lag > mincomp * 10) {
                best = targetPositionTime.setTime;
                lag = 0;
                offset = 0;
            }
            best += std::chrono::milliseconds(targetPositionTime.inTime);
            if (offset < 0) {
                offset = max(int16_t(targetPositionTime.inTime / -4), offset);
            }
            timeSeconds += offset / 1000.0f;
        } else {
            best = now;
        }

        lastPositionTime = targetPositionTime;
        hasLastPosition = true;

        const int32_t maxStroke = streaming_logic::calculateMaxStroke(
            settings.stroke, settings.depth, calibration.measuredStrokeSteps);
        const int32_t depth = streaming_logic::calculateDepthOffset(
            calibration.measuredStrokeSteps, maxStroke, settings.depth);
        const uint32_t speedLimit =
            isLiveSmooth
                ? maxSpeed * (settings.speed / 100.0f)
                : streaming_logic::timedStreamSpeedLimit(maxSpeed);
        const uint32_t accelLimit = maxAccel * (settings.sensation / 100.0);
        const int32_t targetPosition =
            streaming_logic::scaleStreamPosition(
                targetPositionTime.position, maxStroke, depth);
        const auto profile = streaming_logic::planMotion(
            currentPosition,
            targetPosition,
            timeSeconds,
            speedLimit,
            accelLimit,
            1_mm);
        if (!profile.valid) {
            vTaskDelay(1);
            continue;
        }

        uint32_t acceleration = profile.acceleration;
        if (stepper->isRunning()) {
            acceleration = max(stepper->getAcceleration(), acceleration);
        }
        stepper->setAcceleration(acceleration);
        stepper->setSpeedInHz(profile.speed);
        stepper->moveTo(profile.targetPosition, false);

        ESP_LOGI(
            "Streaming",
            "P(%d): %d -> %d = %d, T: %.3f, S: %d, A: %d, Q: %d",
            targetPositionTime.position,
            currentPosition,
            profile.targetPosition,
            profile.distance,
            timeSeconds,
            profile.speed,
            acceleration,
            isLiveSmooth ? liveSmoothQueueDepth() : targetQueue.size());
        vTaskDelay(1);
    }

    stopLiveSmoothOutOfBand();
    vTaskDelete(nullptr);
}

void startStreaming() {
    const int stackSize = 10 * configMINIMAL_STACK_SIZE;
    xTaskCreatePinnedToCore(startStreamingTask,
                            "startStreamingTask",
                            stackSize,
                            nullptr,
                            configMAX_PRIORITIES - 1,
                            nullptr,
                            Tasks::operationTaskCore);
}

}  // namespace streaming
