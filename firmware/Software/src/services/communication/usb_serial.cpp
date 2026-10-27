#include "usb_serial.h"

#include <Arduino.h>

#include <array>
#include <atomic>
#include <cstring>
#include <string>

#include "command/commands.hpp"
#include "constants/Version.h"
#include "live_smooth_protocol.h"
#include "ossm/OSSM.h"
#include "ossm/state/actions.h"
#include "services/communication/queue.h"
#include "services/board.h"
#include "services/stepper.h"
#include "services/tasks.h"
#include "usb_serial_protocol.h"

namespace {

constexpr uint32_t kLinkWatchdogMs = 250;
constexpr uint32_t kStatePublishIntervalMs = 100;
std::atomic<bool> claimed{false};

void sendFrame(usb_serial::MessageType type, const uint8_t* payload,
               std::size_t payloadLength) {
    std::array<uint8_t, usb_serial::kMaxEncodedFrameSize> encoded{};
    const std::size_t length = usb_serial::encodeFrame(
        type, payload, payloadLength, encoded.data(), encoded.size());
    if (length != 0U) Serial.write(encoded.data(), length);
}

void sendText(usb_serial::MessageType type, const char* text) {
    const std::size_t length = text == nullptr ? 0U : std::strlen(text);
    sendFrame(type, reinterpret_cast<const uint8_t*>(text), length);
}

void normalStop() {
    stopLiveSmoothOutOfBand();
    if (ossm != nullptr) ossm->ble_click("set:speed:0");
    if (stepper != nullptr) stepper->forceStop();
}

void watchdogStop() {
    claimed.store(false);
    USE_SPEED_KNOB_AS_LIMIT = true;
    normalStop();
    ossmEmergencyStop();
    if (ossm != nullptr) ossm->ble_click("go:menu");
    ossmSetNotHomed();
}

bool handleCommand(const usb_serial::Frame& frame) {
    const std::string command(
        reinterpret_cast<const char*>(frame.payload.data()), frame.length);
    if (!isValidControlCommand(command) || command.rfind("stream:", 0) == 0) {
        sendText(usb_serial::MessageType::Error, "INVALID_COMMAND");
        return false;
    }
    if (ossm == nullptr) {
        sendText(usb_serial::MessageType::Error, "NOT_READY");
        return false;
    }
    ossm->ble_click(String(command.c_str()));
    return true;
}

bool handleLiveTarget(const usb_serial::Frame& frame, uint32_t nowMs) {
    live_smooth::Packet packet{};
    if (live_smooth::decode(frame.payload.data(), frame.length, packet) !=
        live_smooth::DecodeStatus::Ok) {
        sendText(usb_serial::MessageType::Error, "INVALID_LIVE_TARGET");
        return false;
    }
    const auto result = offerLiveSmoothTarget(packet, nowMs);
    if (result == live_smooth::OfferResult::SafetyInhibited) {
        sendText(usb_serial::MessageType::Error, "MOTION_INHIBITED");
        return false;
    }
    return result == live_smooth::OfferResult::Accepted ||
           result == live_smooth::OfferResult::Coalesced;
}

bool handleFrame(const usb_serial::Frame& frame, uint32_t nowMs) {
    using usb_serial::MessageType;
    if (frame.type == MessageType::Hello) {
        claimed.store(true);
        USE_SPEED_KNOB_AS_LIMIT = false;
        const String ready = String("OSSM;") + VERSION + ";USB1";
        sendFrame(MessageType::Ready,
                  reinterpret_cast<const uint8_t*>(ready.c_str()),
                  ready.length());
        return true;
    }
    if (frame.type == MessageType::Stop) {
        normalStop();
        return claimed.load();
    }
    if (!claimed.load()) {
        sendText(MessageType::Error, "HELLO_REQUIRED");
        return false;
    }
    switch (frame.type) {
        case MessageType::Command:
            handleCommand(frame);
            return false;
        case MessageType::LiveTarget:
            return handleLiveTarget(frame, nowMs);
        case MessageType::Heartbeat:
            return true;
        default:
            sendText(MessageType::Error, "UNSUPPORTED_TYPE");
            return false;
    }
}

void usbSerialTask(void*) {
    usb_serial::StreamDecoder decoder;
    uint32_t lastLinkActivityMs = 0;
    uint32_t lastStatePublishMs = 0;
    while (true) {
        while (Serial.available() > 0) {
            const int input = Serial.read();
            if (input < 0) break;
            const auto result = decoder.push(static_cast<uint8_t>(input));
            if (result == usb_serial::DecodeResult::FrameReady) {
                const uint32_t nowMs = millis();
                if (handleFrame(decoder.frame(), nowMs)) {
                    lastLinkActivityMs = nowMs;
                }
            }
        }

        const uint32_t nowMs = millis();
        if (claimed.load() &&
            static_cast<uint32_t>(nowMs - lastLinkActivityMs) >=
                kLinkWatchdogMs) {
            sendText(usb_serial::MessageType::Error, "WATCHDOG_TIMEOUT");
            watchdogStop();
        }
        if (claimed.load() && ossm != nullptr &&
            static_cast<uint32_t>(nowMs - lastStatePublishMs) >=
                kStatePublishIntervalMs) {
            const String state = ossm->getCurrentState();
            sendFrame(usb_serial::MessageType::State,
                      reinterpret_cast<const uint8_t*>(state.c_str()),
                      state.length());
            lastStatePublishMs = nowMs;
        }
        vTaskDelay(pdMS_TO_TICKS(1));
    }
}

}

void initUsbSerial() {
    xTaskCreatePinnedToCore(usbSerialTask, "usbSerialTask",
                            6 * configMINIMAL_STACK_SIZE, nullptr,
                            configMAX_PRIORITIES - 2, nullptr,
                            Tasks::stepperCore);
}

bool usbSerialClaimed() { return claimed.load(); }
