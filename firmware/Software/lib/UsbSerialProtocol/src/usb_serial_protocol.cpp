#include "usb_serial_protocol.h"

#include <cstring>

namespace usb_serial {

uint16_t crc16Ccitt(const uint8_t* data, std::size_t length,
                    uint16_t initial) {
    uint16_t crc = initial;
    if (data == nullptr) return crc;
    for (std::size_t index = 0; index < length; ++index) {
        crc ^= static_cast<uint16_t>(data[index]) << 8U;
        for (uint8_t bit = 0; bit < 8U; ++bit) {
            crc = (crc & 0x8000U) != 0U
                      ? static_cast<uint16_t>((crc << 1U) ^ 0x1021U)
                      : static_cast<uint16_t>(crc << 1U);
        }
    }
    return crc;
}

std::size_t encodeFrame(MessageType type, const uint8_t* payload,
                        std::size_t payloadLength, uint8_t* output,
                        std::size_t outputCapacity) {
    const std::size_t totalLength = payloadLength + kFrameOverhead;
    if (output == nullptr || payloadLength > kMaxPayloadSize ||
        outputCapacity < totalLength ||
        (payloadLength != 0U && payload == nullptr)) {
        return 0;
    }

    output[0] = kMagic0;
    output[1] = kMagic1;
    output[2] = kVersion;
    output[3] = static_cast<uint8_t>(type);
    output[4] = static_cast<uint8_t>(payloadLength & 0xffU);
    output[5] = static_cast<uint8_t>((payloadLength >> 8U) & 0xffU);
    if (payloadLength != 0U) {
        std::memcpy(output + 6, payload, payloadLength);
    }
    const uint16_t crc = crc16Ccitt(output + 2, payloadLength + 4);
    output[6 + payloadLength] = static_cast<uint8_t>(crc & 0xffU);
    output[7 + payloadLength] = static_cast<uint8_t>((crc >> 8U) & 0xffU);
    return totalLength;
}

DecodeResult StreamDecoder::push(uint8_t byte) {
    switch (state_) {
        case State::Magic0:
            if (byte == kMagic0) state_ = State::Magic1;
            break;
        case State::Magic1:
            if (byte == kMagic1) {
                state_ = State::Version;
            } else if (byte != kMagic0) {
                state_ = State::Magic0;
            }
            break;
        case State::Version:
            if (byte != kVersion) {
                return fail(DecodeResult::UnsupportedVersion);
            }
            crc_ = crc16Ccitt(&byte, 1);
            state_ = State::Type;
            break;
        case State::Type:
            frame_.type = static_cast<MessageType>(byte);
            updateCrc(byte);
            state_ = State::LengthLow;
            break;
        case State::LengthLow:
            frame_.length = byte;
            updateCrc(byte);
            state_ = State::LengthHigh;
            break;
        case State::LengthHigh:
            frame_.length |= static_cast<uint16_t>(byte) << 8U;
            updateCrc(byte);
            if (frame_.length > kMaxPayloadSize) {
                return fail(DecodeResult::PayloadTooLarge);
            }
            payloadIndex_ = 0;
            state_ = frame_.length == 0U ? State::CrcLow : State::Payload;
            break;
        case State::Payload:
            frame_.payload[payloadIndex_++] = byte;
            updateCrc(byte);
            if (payloadIndex_ == frame_.length) state_ = State::CrcLow;
            break;
        case State::CrcLow:
            receivedCrc_ = byte;
            state_ = State::CrcHigh;
            break;
        case State::CrcHigh:
            receivedCrc_ |= static_cast<uint16_t>(byte) << 8U;
            if (receivedCrc_ != crc_) {
                return fail(DecodeResult::InvalidCrc);
            }
            state_ = State::Magic0;
            payloadIndex_ = 0;
            crc_ = 0xffffU;
            receivedCrc_ = 0;
            return DecodeResult::FrameReady;
    }
    return DecodeResult::NeedMore;
}

const Frame& StreamDecoder::frame() const { return frame_; }

void StreamDecoder::reset() {
    state_ = State::Magic0;
    payloadIndex_ = 0;
    crc_ = 0xffffU;
    receivedCrc_ = 0;
}

void StreamDecoder::updateCrc(uint8_t byte) {
    crc_ = crc16Ccitt(&byte, 1, crc_);
}

DecodeResult StreamDecoder::fail(DecodeResult result) {
    reset();
    return result;
}

}
