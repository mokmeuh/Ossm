package com.ossm.remote.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthByLoadProtocolTest {
    private fun packet(
        version: Int = 1,
        flags: Int = 0,
        sensitivity: Int = 50,
        floor: Int = 40,
        load: Int = 0,
        factor: Int = 1000,
        size: Int = 8,
    ): ByteArray = ByteArray(size).also {
        if (size > 0) it[0] = version.toByte()
        if (size > 1) it[1] = flags.toByte()
        if (size > 2) it[2] = sensitivity.toByte()
        if (size > 3) it[3] = floor.toByte()
        if (size > 5) {
            it[4] = (load and 0xff).toByte()
            it[5] = (load ushr 8).toByte()
        }
        if (size > 7) {
            it[6] = (factor and 0xff).toByte()
            it[7] = (factor ushr 8).toByte()
        }
    }

    @Test
    fun `characteristic uuid is the contract value`() {
        assertEquals("522b443a-4f53-534d-6200-420badbabe69", DepthByLoadProtocol.CHARACTERISTIC_UUID.toString())
    }

    @Test
    fun `write packet carries version flags settings and zeroed read only bytes`() {
        assertArrayEquals(
            byteArrayOf(1, 1, 70, 25, 0, 0, 0, 0),
            DepthByLoadProtocol.encodeWrite(enabled = true, sensitivityPercent = 70, floorPercent = 25),
        )
        assertArrayEquals(
            byteArrayOf(1, 0, 0, 100, 0, 0, 0, 0),
            DepthByLoadProtocol.encodeWrite(enabled = false, sensitivityPercent = 0, floorPercent = 100),
        )
    }

    @Test
    fun `write packet clamps out of range settings into the contract range`() {
        assertArrayEquals(
            byteArrayOf(1, 1, 100, 10, 0, 0, 0, 0),
            DepthByLoadProtocol.encodeWrite(enabled = true, sensitivityPercent = 250, floorPercent = -5),
        )
        assertArrayEquals(
            byteArrayOf(1, 0, 0, 100, 0, 0, 0, 0),
            DepthByLoadProtocol.encodeWrite(enabled = false, sensitivityPercent = -3, floorPercent = 400),
        )
    }

    @Test
    fun `a written packet decodes back to the same settings`() {
        val decoded = DepthByLoadProtocol.decode(
            DepthByLoadProtocol.encodeWrite(enabled = true, sensitivityPercent = 64, floorPercent = 33),
        ).value
        assertNotNull(decoded)
        assertTrue(decoded!!.enabled)
        assertFalse(decoded.unavailable)
        assertEquals(64, decoded.sensitivityPercent)
        assertEquals(33, decoded.floorPercent)
        assertEquals(0, decoded.loadPermille)
        assertEquals(0, decoded.depthFactorPermille)
    }

    @Test
    fun `decodes load and applied depth factor as little endian permille`() {
        val decoded = DepthByLoadProtocol.decode(packet(flags = 1, load = 640, factor = 725)).value!!
        assertEquals(640, decoded.loadPermille)
        assertEquals(725, decoded.depthFactorPermille)
        assertTrue(decoded.enabled)
    }

    @Test
    fun `decodes the unavailable flag`() {
        val decoded = DepthByLoadProtocol.decode(packet(flags = 2)).value!!
        assertTrue(decoded.unavailable)
        assertFalse(decoded.enabled)
    }

    @Test
    fun `accepts the exact range limits`() {
        assertNotNull(DepthByLoadProtocol.decode(packet(sensitivity = 0, floor = 10, load = 0, factor = 0)).value)
        assertNotNull(DepthByLoadProtocol.decode(packet(sensitivity = 100, floor = 100, load = 1000, factor = 1000)).value)
    }

    @Test
    fun `rejects a wrong length`() {
        listOf(0, 7, 9, 20).forEach { size ->
            assertEquals(
                "size $size",
                DepthByLoadProtocol.RejectReason.INVALID_LENGTH,
                DepthByLoadProtocol.decode(packet(size = size)).error,
            )
        }
    }

    @Test
    fun `rejects an unknown version`() {
        assertEquals(DepthByLoadProtocol.RejectReason.UNSUPPORTED_VERSION, DepthByLoadProtocol.decode(packet(version = 0)).error)
        assertEquals(DepthByLoadProtocol.RejectReason.UNSUPPORTED_VERSION, DepthByLoadProtocol.decode(packet(version = 2)).error)
    }

    @Test
    fun `rejects unknown flag bits`() {
        listOf(4, 8, 0x80, 0xff).forEach { flags ->
            assertEquals(
                "flags $flags",
                DepthByLoadProtocol.RejectReason.INVALID_FLAGS,
                DepthByLoadProtocol.decode(packet(flags = flags)).error,
            )
        }
    }

    @Test
    fun `rejects out of range values`() {
        assertEquals(DepthByLoadProtocol.RejectReason.OUT_OF_RANGE, DepthByLoadProtocol.decode(packet(sensitivity = 101)).error)
        assertEquals(DepthByLoadProtocol.RejectReason.OUT_OF_RANGE, DepthByLoadProtocol.decode(packet(floor = 9)).error)
        assertEquals(DepthByLoadProtocol.RejectReason.OUT_OF_RANGE, DepthByLoadProtocol.decode(packet(floor = 101)).error)
        assertEquals(DepthByLoadProtocol.RejectReason.OUT_OF_RANGE, DepthByLoadProtocol.decode(packet(load = 1001)).error)
        assertEquals(DepthByLoadProtocol.RejectReason.OUT_OF_RANGE, DepthByLoadProtocol.decode(packet(factor = 1001)).error)
    }

    @Test
    fun `rejected packets carry no value`() {
        assertNull(DepthByLoadProtocol.decode(packet(version = 9)).value)
    }

    @Test
    fun `state is not usable before a valid read`() {
        assertFalse(DepthByLoadState().present)
        assertFalse(DepthByLoadState().usable)
    }

    @Test
    fun `state from an unavailable packet is present but not usable and never active`() {
        val state = DepthByLoadState.from(DepthByLoadProtocol.decode(packet(flags = 3)).value!!)
        assertTrue(state.present)
        assertTrue(state.unavailable)
        assertFalse(state.usable)
        assertFalse(state.enabled)
    }

    @Test
    fun `state from a normal packet is usable`() {
        val state = DepthByLoadState.from(DepthByLoadProtocol.decode(packet(flags = 1, load = 300, factor = 900)).value!!)
        assertTrue(state.usable)
        assertTrue(state.enabled)
        assertEquals(300, state.loadPermille)
        assertEquals(900, state.depthFactorPermille)
    }
}
