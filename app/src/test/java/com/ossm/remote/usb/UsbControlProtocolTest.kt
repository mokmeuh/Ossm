package com.ossm.remote.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbControlProtocolTest {
    @Test
    fun `ready frame is found after arbitrary serial log bytes`() {
        val bytes = "boot log\r\n".toByteArray() +
            UsbControlProtocol.encode(UsbControlProtocol.TYPE_READY, byteArrayOf(1))

        assertTrue(UsbControlProtocol.findReadyFrame(bytes))
    }

    @Test
    fun `corrupted ready frame is rejected`() {
        val bytes = UsbControlProtocol.encode(UsbControlProtocol.TYPE_READY).also {
            it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte()
        }

        assertFalse(UsbControlProtocol.findReadyFrame(bytes))
    }

    @Test
    fun `state frame is decoded and consumed from a split stream`() {
        val frame = UsbControlProtocol.encode(
            UsbControlProtocol.TYPE_STATE,
            "{\"state\":\"menu.idle\"}".toByteArray(),
        )
        val first = UsbControlProtocol.decodeFrames(frame.copyOfRange(0, 5))
        assertTrue(first.first.isEmpty())
        val second = UsbControlProtocol.decodeFrames(first.second + frame.copyOfRange(5, frame.size))
        assertTrue(second.first.single().type == UsbControlProtocol.TYPE_STATE)
        assertTrue(second.second.isEmpty())
    }
}
