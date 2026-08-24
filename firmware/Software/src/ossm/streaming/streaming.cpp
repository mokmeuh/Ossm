#include "streaming.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <optional>
#include <queue>

#include "live_trajectory.h"
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
    bool modeArmed = false;
    bool liveSession = false;

    targetQueue = {};
    initializeLiveSmoothQueue();

    const uint16_t maxSpeed =
        Config::Driver::maxSpeedMmPerSecond * (1_mm);
    const uint32_t maxAccel =
        Config::Driver::maxAcceleration * (1_mm);
    stepper->setSpeedInHz(maxSpeed);
    stepper->setAcceleration(maxAccel);

    constexpr double kMaximumLiveDtSeconds = 0.010;
    constexpr double kAccelerationRampSeconds = 0.050;
    const uint32_t liveAccelLimit =
        streaming_logic::liveAccelerationLimit(maxAccel);
    live_trajectory::Engine liveEngine({
        static_cast<double>(maxSpeed),
        static_cast<double>(liveAccelLimit),
        static_cast<double>(liveAccelLimit) / kAccelerationRampSeconds,
        0,
        0,
        kMaximumLiveDtSeconds,
    });
    liveEngine.initialize(stepper->getCurrentPosition(), millis());
    auto previousTickTime = std::chrono::steady_clock::now();
    TickType_t lastWakeTime = xTaskGetTickCount();

    while (isInCorrectState()) {
        vTaskDelayUntil(&lastWakeTime, pdMS_TO_TICKS(1));
        const auto tickTime = std::chrono::steady_clock::now();
        const double measuredDt =
            std::chrono::duration<double>(tickTime - previousTickTime).count();
        previousTickTime = tickTime;
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
            liveEngine.cancel();
            targetQueue = {};
            stepper->forceStop();
            stepper->disableOutputs();
            continue;
        }

        const auto now = tickTime;
        const uint16_t currentBuffer =
            std::chrono::duration_cast<std::chrono::milliseconds>(now - best)
                .count();
        const uint32_t nowMs = millis();
        const live_trajectory::Limits liveLimits{
            maxSpeed * (settings.speed / 100.0f),
            static_cast<double>(liveAccelLimit),
            static_cast<double>(liveAccelLimit) / kAccelerationRampSeconds,
            -allowedStroke - allowedDepth,
            -allowedDepth,
            kMaximumLiveDtSeconds,
        };
        liveEngine.setLimits(liveLimits);
        if (settings.speed == 0) {
            clearLiveSmoothTargets();
            if (liveSession) {
                stepper->forceStop();
                liveEngine.initialize(stepper->getCurrentPosition(), nowMs);
                liveSession = false;
                continue;
            }
        }
        LiveSmoothTarget liveTarget{};
        if (pollLiveSmoothTarget(nowMs, liveTarget)) {
            const uint16_t durationMs = streaming_logic::remainingDurationMs(
                liveTarget.packet.deadlineMs,
                nowMs,
                liveTarget.packet.durationMs);
            const int32_t targetPosition = streaming_logic::scaleStreamPosition(
                liveTarget.packet.positionPermille / 10U,
                allowedStroke,
                allowedDepth);
            if (durationMs > 0U &&
                liveEngine.setTarget(targetPosition, durationMs, nowMs)) {
                liveSession = true;
            }
        }
        if (liveSession) {
            const auto output = liveEngine.tick(measuredDt, nowMs);
            if (output.commandChanged) {
                stepper->setAcceleration(static_cast<uint32_t>(
                    std::max(1.0, std::ceil(std::abs(output.acceleration)))));
                stepper->setSpeedInHz(static_cast<uint32_t>(
                    std::max(1.0, std::ceil(std::abs(output.velocity)))));
                stepper->moveTo(output.commandPosition, false);
            }
            if (!liveEngine.hasTarget()) liveSession = false;
            continue;
        }

        const bool isLiveSmooth = false;
        PositionTime targetPositionTime{};
        if (!targetQueue.empty()) {
            targetPositionTime = targetQueue.front();
        } else {
            continue;
        }

        const int16_t streamDistance =
            targetPositionTime.position - lastPositionTime.position;
        targetPositionTime.direction =
            streaming_logic::streamDirection(streamDistance);
        if (!streaming_logic::shouldScheduleStreamPosition(
                hasLastPosition, streamDistance)) {
            targetQueue.pop();
            continue;
        }
        const bool sameDirection =
            !hasLastPosition ||
            lastPositionTime.direction == targetPositionTime.direction;
        if (!sameDirection && stepper->isRunning()) {
            continue;
        }

        targetQueue.pop();

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
        const uint32_t accelLimit =
            isLiveSmooth
                ? streaming_logic::liveAccelerationLimit(maxAccel)
                : maxAccel * (settings.sensation / 100.0);
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
            continue;
        }

        const bool stepperIsRunning = stepper->isRunning();
        const uint32_t acceleration =
            streaming_logic::selectStreamAcceleration(
                isLiveSmooth,
                stepperIsRunning,
                stepperIsRunning ? stepper->getAcceleration() : 0U,
                profile.acceleration);
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
    }

    stopLiveSmoothOutOfBand();
    liveEngine.cancel();
    stepper->forceStop();
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
