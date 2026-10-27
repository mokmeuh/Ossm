package com.ossm.remote.usb

import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Serial bootloader protocol of the ESP32 ROM: SLIP-framed requests, responses made of an
 * 8-byte header followed by data whose trailing bytes carry the status.
 */
internal object Esp32BootloaderProtocol {
    const val BLOCK_SIZE = 0x400
    const val OP_FLASH_BEGIN = 0x02
    const val OP_FLASH_DATA = 0x03
    const val OP_FLASH_END = 0x04
    const val OP_SYNC = 0x08
    const val OP_SPI_ATTACH = 0x0D
    const val OP_CHANGE_BAUD = 0x0F

    private const val CHECKSUM_SEED = 0xEF
    private const val SLIP_END = 0xC0
    private const val SLIP_ESC = 0xDB
    private const val SLIP_ESC_END = 0xDC
    private const val SLIP_ESC_ESC = 0xDD
    private const val HEADER_SIZE = 8
    private const val IMAGE_MAGIC = 0xE9
    private const val IMAGE_MAX_SEGMENTS = 16
    private const val IMAGE_CHIP_ID_OFFSET = 12
    private const val IMAGE_MIN_SIZE = 0x8000

    fun syncPacket(): ByteArray =
        request(OP_SYNC, byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 })

    /** Connects the ROM to the SPI flash on its default pins; required before any flash command. */
    fun spiAttach(): ByteArray = request(OP_SPI_ATTACH, le32(0) + le32(0))

    fun blockCount(size: Int): Int = (size + BLOCK_SIZE - 1) / BLOCK_SIZE

    fun flashBegin(size: Int, offset: Int): ByteArray =
        request(OP_FLASH_BEGIN, le32(size) + le32(blockCount(size)) + le32(BLOCK_SIZE) + le32(offset))

    fun flashData(sequence: Int, image: ByteArray, start: Int): ByteArray {
        val block = ByteArray(BLOCK_SIZE) { 0xFF.toByte() }
        val count = min(BLOCK_SIZE, image.size - start)
        image.copyInto(block, destinationOffset = 0, startIndex = start, endIndex = start + count)
        return request(
            OP_FLASH_DATA,
            le32(BLOCK_SIZE) + le32(sequence) + le32(0) + le32(0) + block,
            checksum(block)
        )
    }

    fun flashEnd(reboot: Boolean): ByteArray = request(OP_FLASH_END, le32(if (reboot) 0 else 1))

    /** The ROM answers at the old speed, then both sides switch. */
    fun changeBaud(newBaud: Int): ByteArray = request(OP_CHANGE_BAUD, le32(newBaud) + le32(0))

    fun isResponse(frame: ByteArray, operation: Int): Boolean =
        frame.size >= HEADER_SIZE && frame[0].toInt() == 1 && (frame[1].toInt() and 0xFF) == operation

    /** The ROM puts a 4-byte status (failed flag, error code) at the end of the response data. */
    fun succeeded(frame: ByteArray): Boolean {
        if (frame.size < HEADER_SIZE) return false
        val declared = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        val available = min(declared, frame.size - HEADER_SIZE)
        if (available < 2) return false
        val flagIndex = HEADER_SIZE + if (available >= 4) available - 4 else available - 2
        return frame[flagIndex].toInt() == 0
    }

    /** Header check for a plain ESP32 application image that fits [maxBytes]. */
    fun isEsp32AppImage(image: ByteArray, maxBytes: Int): Boolean {
        if (image.size !in IMAGE_MIN_SIZE..maxBytes) return false
        if ((image[0].toInt() and 0xFF) != IMAGE_MAGIC) return false
        if ((image[1].toInt() and 0xFF) !in 1..IMAGE_MAX_SEGMENTS) return false
        // Chip id 0x0000 is the original ESP32; other chips use another value.
        return image[IMAGE_CHIP_ID_OFFSET].toInt() == 0 && image[IMAGE_CHIP_ID_OFFSET + 1].toInt() == 0
    }

    private fun request(operation: Int, data: ByteArray, checksum: Int = 0): ByteArray =
        slip(byteArrayOf(0, operation.toByte()) + le16(data.size) + le32(checksum) + data)

    private fun checksum(bytes: ByteArray): Int =
        bytes.fold(CHECKSUM_SEED) { value, byte -> value xor (byte.toInt() and 0xFF) }

    private fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private fun le32(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte()
    )

    private fun slip(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size + 8)
        out.write(SLIP_END)
        for (byte in input) {
            when (byte.toInt() and 0xFF) {
                SLIP_END -> { out.write(SLIP_ESC); out.write(SLIP_ESC_END) }
                SLIP_ESC -> { out.write(SLIP_ESC); out.write(SLIP_ESC_ESC) }
                else -> out.write(byte.toInt())
            }
        }
        out.write(SLIP_END)
        return out.toByteArray()
    }

    /** Incremental SLIP decoder: feed raw serial chunks, get complete frames back. */
    class SlipDecoder {
        private val current = ByteArrayOutputStream()
        private var escaped = false

        fun push(chunk: ByteArray): List<ByteArray> {
            val frames = ArrayList<ByteArray>(1)
            for (byte in chunk) {
                val value = byte.toInt() and 0xFF
                when {
                    value == SLIP_END -> {
                        if (current.size() > 0) frames += current.toByteArray()
                        current.reset()
                        escaped = false
                    }
                    escaped -> {
                        current.write(
                            when (value) {
                                SLIP_ESC_END -> SLIP_END
                                SLIP_ESC_ESC -> SLIP_ESC
                                else -> value
                            }
                        )
                        escaped = false
                    }
                    value == SLIP_ESC -> escaped = true
                    else -> current.write(value)
                }
            }
            return frames
        }
    }
}
