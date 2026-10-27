package com.ossm.remote.usb

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Esp32BootloaderProtocolTest {
    private fun decode(packet: ByteArray): ByteArray =
        Esp32BootloaderProtocol.SlipDecoder().push(packet).single()

    private fun le32(frame: ByteArray, at: Int): Int =
        (frame[at].toInt() and 0xFF) or
            ((frame[at + 1].toInt() and 0xFF) shl 8) or
            ((frame[at + 2].toInt() and 0xFF) shl 16) or
            ((frame[at + 3].toInt() and 0xFF) shl 24)

    private fun bundledImage(): ByteArray {
        val file = listOf(
            File("src/main/assets/firmware/ossm-1.2.0.bin"),
            File("app/src/main/assets/firmware/ossm-1.2.0.bin")
        ).first(File::isFile)
        return file.readBytes()
    }

    @Test
    fun `spi attach is an eight byte request with default pins`() {
        val frame = decode(Esp32BootloaderProtocol.spiAttach())
        assertEquals(0, frame[0].toInt())
        assertEquals(Esp32BootloaderProtocol.OP_SPI_ATTACH, frame[1].toInt())
        assertEquals(8, frame[2].toInt())
        assertEquals(16, frame.size)
        assertTrue(frame.copyOfRange(8, 16).all { it.toInt() == 0 })
    }

    @Test
    fun `flash end is a four byte request with the reboot flag`() {
        val frame = decode(Esp32BootloaderProtocol.flashEnd(reboot = true))
        assertEquals(0, frame[0].toInt())
        assertEquals(Esp32BootloaderProtocol.OP_FLASH_END, frame[1].toInt())
        assertEquals(4, frame[2].toInt())
        assertEquals(0, le32(frame, 8))
        assertEquals(1, le32(decode(Esp32BootloaderProtocol.flashEnd(reboot = false)), 8))
    }

    @Test
    fun `flash begin carries size, block count, block size and offset`() {
        val frame = decode(Esp32BootloaderProtocol.flashBegin(size = 2500, offset = 0x10000))
        assertEquals(Esp32BootloaderProtocol.OP_FLASH_BEGIN, frame[1].toInt())
        assertEquals(2500, le32(frame, 8))
        assertEquals(3, le32(frame, 12))
        assertEquals(Esp32BootloaderProtocol.BLOCK_SIZE, le32(frame, 16))
        assertEquals(0x10000, le32(frame, 20))
    }

    @Test
    fun `flash data pads the last block with 0xFF and checksums the padded block`() {
        val image = ByteArray(10) { 0xAB.toByte() }
        val frame = decode(Esp32BootloaderProtocol.flashData(sequence = 7, image = image, start = 0))
        val block = frame.copyOfRange(8 + 16, frame.size)
        assertEquals(Esp32BootloaderProtocol.BLOCK_SIZE, block.size)
        assertTrue(block.take(10).all { it == 0xAB.toByte() })
        assertTrue(block.drop(10).all { it == 0xFF.toByte() })
        assertEquals(7, le32(frame, 12))
        val expected = block.fold(0xEF) { value, byte -> value xor (byte.toInt() and 0xFF) }
        assertEquals(expected, le32(frame, 4))
    }

    @Test
    fun `bytes that look like SLIP delimiters survive the round trip`() {
        val image = byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0xDC.toByte(), 0xDD.toByte(), 0x01)
        val frame = decode(Esp32BootloaderProtocol.flashData(sequence = 0, image = image, start = 0))
        assertArrayEquals(image, frame.copyOfRange(8 + 16, 8 + 16 + image.size))
    }

    @Test
    fun `decoder rebuilds a frame split across chunks`() {
        val packet = Esp32BootloaderProtocol.syncPacket()
        val decoder = Esp32BootloaderProtocol.SlipDecoder()
        assertTrue(decoder.push(packet.copyOfRange(0, 20)).isEmpty())
        val frames = decoder.push(packet.copyOfRange(20, packet.size))
        assertEquals(1, frames.size)
        assertEquals(Esp32BootloaderProtocol.OP_SYNC, frames[0][1].toInt())
    }

    @Test
    fun `decoder ignores empty frames between delimiters`() {
        val decoder = Esp32BootloaderProtocol.SlipDecoder()
        assertTrue(decoder.push(ByteArray(3) { 0xC0.toByte() }).isEmpty())
    }

    @Test
    fun `response status is read from the trailing status bytes`() {
        fun response(operation: Int, failedFlag: Int) = byteArrayOf(
            1, operation.toByte(), 4, 0, 0, 0, 0, 0,
            failedFlag.toByte(), 0, 0, 0
        )
        assertTrue(Esp32BootloaderProtocol.isResponse(response(0x03, 0), 0x03))
        assertFalse(Esp32BootloaderProtocol.isResponse(response(0x03, 0), 0x04))
        assertTrue(Esp32BootloaderProtocol.succeeded(response(0x03, 0)))
        assertFalse(Esp32BootloaderProtocol.succeeded(response(0x03, 1)))
        assertFalse(Esp32BootloaderProtocol.succeeded(byteArrayOf(1, 3, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `bundled firmware is a valid ESP32 image for the first OTA slot`() {
        assertTrue(Esp32BootloaderProtocol.isEsp32AppImage(bundledImage(), 0x1E0000))
    }

    @Test
    fun `images for another chip, too small or too large are refused`() {
        val image = bundledImage()
        val otherChip = image.copyOf().also { it[12] = 9 }
        assertFalse(Esp32BootloaderProtocol.isEsp32AppImage(otherChip, 0x1E0000))
        val wrongMagic = image.copyOf().also { it[0] = 0 }
        assertFalse(Esp32BootloaderProtocol.isEsp32AppImage(wrongMagic, 0x1E0000))
        assertFalse(Esp32BootloaderProtocol.isEsp32AppImage(image.copyOf(100), 0x1E0000))
        assertFalse(Esp32BootloaderProtocol.isEsp32AppImage(image, image.size - 1))
    }
}
