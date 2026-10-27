package com.ossm.remote.usb

internal object UsbControlProtocol {
    const val TYPE_COMMAND = 0x01
    const val TYPE_LIVE_TARGET = 0x02
    const val TYPE_STOP = 0x03
    const val TYPE_HEARTBEAT = 0x04
    const val TYPE_HELLO = 0x05
    const val TYPE_READY = 0x81
    const val TYPE_STATE = 0x82
    const val VERSION = 1
    const val MAX_PAYLOAD_SIZE = 512

    private const val MAGIC_0 = 0xA5
    private const val MAGIC_1 = 0x5A

    fun encode(type: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(payload.size <= MAX_PAYLOAD_SIZE)
        val frame = ByteArray(8 + payload.size)
        frame[0] = MAGIC_0.toByte()
        frame[1] = MAGIC_1.toByte()
        frame[2] = VERSION.toByte()
        frame[3] = type.toByte()
        putU16(frame, 4, payload.size)
        payload.copyInto(frame, destinationOffset = 6)
        putU16(frame, frame.lastIndex - 1, crc16(frame, 2, 4 + payload.size))
        return frame
    }

    fun findReadyFrame(bytes: ByteArray): Boolean {
        var offset = 0
        while (offset + 8 <= bytes.size) {
            if ((bytes[offset].toInt() and 0xff) != MAGIC_0 ||
                (bytes[offset + 1].toInt() and 0xff) != MAGIC_1
            ) {
                offset++
                continue
            }
            val payloadSize = readU16(bytes, offset + 4)
            val frameSize = 8 + payloadSize
            if (payloadSize > MAX_PAYLOAD_SIZE || offset + frameSize > bytes.size) return false
            val expected = readU16(bytes, offset + frameSize - 2)
            val actual = crc16(bytes, offset + 2, 4 + payloadSize)
            if (expected == actual &&
                (bytes[offset + 2].toInt() and 0xff) == VERSION &&
                (bytes[offset + 3].toInt() and 0xff) == TYPE_READY
            ) {
                return true
            }
            offset += frameSize
        }
        return false
    }

    fun decodeFrames(bytes: ByteArray): Pair<List<UsbControlFrame>, ByteArray> {
        val frames = mutableListOf<UsbControlFrame>()
        var offset = 0
        while (offset + 1 < bytes.size) {
            if ((bytes[offset].toInt() and 0xff) != MAGIC_0 ||
                (bytes[offset + 1].toInt() and 0xff) != MAGIC_1
            ) {
                offset++
                continue
            }
            if (offset + 8 > bytes.size) break
            val payloadSize = readU16(bytes, offset + 4)
            if (payloadSize > MAX_PAYLOAD_SIZE) {
                offset++
                continue
            }
            val frameSize = 8 + payloadSize
            if (offset + frameSize > bytes.size) break
            val expected = readU16(bytes, offset + frameSize - 2)
            val actual = crc16(bytes, offset + 2, 4 + payloadSize)
            if (expected == actual && (bytes[offset + 2].toInt() and 0xff) == VERSION) {
                frames += UsbControlFrame(
                    type = bytes[offset + 3].toInt() and 0xff,
                    payload = bytes.copyOfRange(offset + 6, offset + 6 + payloadSize),
                )
                offset += frameSize
            } else {
                offset++
            }
        }
        return frames to bytes.copyOfRange(offset, bytes.size)
    }

    private fun crc16(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xffff
        repeat(length) { index ->
            crc = crc xor ((bytes[offset + index].toInt() and 0xff) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xffff
            }
        }
        return crc
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}

internal data class UsbControlFrame(val type: Int, val payload: ByteArray)
