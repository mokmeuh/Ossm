#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace usb_serial {

inline constexpr uint8_t kMagic0 = 0xa5;
inline constexpr uint8_t kMagic1 = 0x5a;
inline constexpr uint8_t kVersion = 1;
inline constexpr std::size_t kMaxPayloadSize = 512;
inline constexpr std::size_t kFrameOverhead = 8;
inline constexpr std::size_t kMaxEncodedFrameSize =
    kMaxPayloadSize + kFrameOverhead;

enum class MessageType : uint8_t {
    Command = 0x01,
    LiveTarget = 0x02,
    Stop = 0x03,
    Heartbeat = 0x04,
    Hello = 0x05,
    Ready = 0x81,
    State = 0x82,
    Error = 0x83,
};

enum class DecodeResult {
    NeedMore,
    FrameReady,
    UnsupportedVersion,
    PayloadTooLarge,
    InvalidCrc,
};

struct Frame {
    MessageType type = MessageType::Error;
    uint16_t length = 0;
    std::array<uint8_t, kMaxPayloadSize> payload{};
};

uint16_t crc16Ccitt(const uint8_t* data, std::size_t length,
                    uint16_t initial = 0xffffU);

std::size_t encodeFrame(MessageType type, const uint8_t* payload,
                        std::size_t payloadLength, uint8_t* output,
                        std::size_t outputCapacity);

class StreamDecoder {
   public:
    DecodeResult push(uint8_t byte);
    const Frame& frame() const;
    void reset();

   private:
    enum class State {
        Magic0,
        Magic1,
        Version,
        Type,
        LengthLow,
        LengthHigh,
        Payload,
        CrcLow,
        CrcHigh,
    };

    void updateCrc(uint8_t byte);
    DecodeResult fail(DecodeResult result);

    State state_ = State::Magic0;
    Frame frame_{};
    std::size_t payloadIndex_ = 0;
    uint16_t crc_ = 0xffffU;
    uint16_t receivedCrc_ = 0;
};

}
