package com.ossm.remote.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResistanceProtocolTest {
    @Test
    fun `characterizes existing OSSM UUID contract`() {
        assertEquals("522b443a-4f53-534d-0001-420badbabe69", BleConstants.OSSM_SERVICE_UUID.toString())
        assertEquals("522b443a-4f53-534d-1000-420badbabe69", BleConstants.COMMAND_CHAR_UUID.toString())
        assertEquals("522b443a-4f53-534d-2000-420badbabe69", BleConstants.STATE_CHAR_UUID.toString())
        assertEquals("522b443a-4f53-534d-3000-420badbabe69", BleConstants.PATTERN_LIST_UUID.toString())
    }

    @Test
    fun `capability golden vector freezes UUID bits properties and timeout`() {
        val packet = ResistanceProtocol.Capability(
            capabilityBits = ResistanceProtocol.ALL_CAPABILITY_BITS,
            telemetryProperties = ResistanceProtocol.PROPERTY_READ or ResistanceProtocol.PROPERTY_NOTIFY,
            configProperties = ResistanceProtocol.PROPERTY_READ or ResistanceProtocol.PROPERTY_WRITE,
        )

        val encoded = ResistanceProtocol.encodeCapability(packet)
        assertEquals("0107000305140800", encoded.toHex())
        assertEquals(packet, ResistanceProtocol.decodeCapability(encoded).value)
        assertEquals(BleConstants.OSSM_SERVICE_UUID, ResistanceProtocol.SERVICE_UUID)
        assertEquals("522b443a-4f53-534d-5000-420badbabe69", ResistanceProtocol.CAPABILITY_UUID.toString())
        assertEquals("522b443a-4f53-534d-6000-420badbabe69", ResistanceProtocol.TELEMETRY_UUID.toString())
        assertEquals("522b443a-4f53-534d-6100-420badbabe69", ResistanceProtocol.CONFIG_UUID.toString())
        assertEquals(1_000L, ResistanceProtocol.NEGOTIATION_TIMEOUT_MS)
        assertEquals(ResistanceProtocol.PROPERTY_READ, ResistanceProtocol.CAPABILITY_PROPERTIES)
        assertEquals(
            ResistanceProtocol.PROPERTY_READ or ResistanceProtocol.PROPERTY_NOTIFY,
            ResistanceProtocol.TELEMETRY_PROPERTIES,
        )
        assertEquals(
            ResistanceProtocol.PROPERTY_READ or ResistanceProtocol.PROPERTY_WRITE,
            ResistanceProtocol.CONFIG_PROPERTIES,
        )
        assertTrue(
            listOf(
                ResistanceProtocol.CAPABILITY_PACKET_SIZE,
                ResistanceProtocol.TELEMETRY_PACKET_SIZE,
                ResistanceProtocol.CONFIG_PACKET_SIZE,
            ).all { it <= 20 },
        )
    }

    @Test
    fun `telemetry golden vector round trips exact little endian semantics`() {
        val packet = ResistanceProtocol.Telemetry(
            flags = ResistanceProtocol.FLAG_VALID or ResistanceProtocol.FLAG_FRESH or
                ResistanceProtocol.FLAG_CALIBRATION_MATCH or ResistanceProtocol.FLAG_HOMING_VALID or
                ResistanceProtocol.FLAG_POSITION_IN_ENVELOPE,
            sequence = 0x1234,
            timestampMs = 0x01020304L,
            raw = 0x0abc,
            filtered = 0x0789,
            baseline = 0x0700,
            normalizedPermille = 750,
            appliedSpeedBonus = 7,
            appliedDepthBonus = 3,
            faultCode = 0,
        )

        val encoded = ResistanceProtocol.encodeTelemetry(packet)
        assertEquals("01e3341204030201bc0a89070007ee0207030000", encoded.toHex())
        assertEquals(20, encoded.size)
        assertEquals(packet, ResistanceProtocol.decodeTelemetry(encoded).value)
    }

    @Test
    fun `config golden vector round trips bounded fields`() {
        val packet = ResistanceProtocol.Config(
            flags = ResistanceProtocol.CONFIG_ARMED,
            maxSpeedBonus = 10,
            maxDepthBonus = 5,
            enterThresholdPermille = 650,
            exitThresholdPermille = 450,
        )

        val encoded = ResistanceProtocol.encodeConfig(packet)
        assertEquals("01010a058a02c201", encoded.toHex())
        assertEquals(packet, ResistanceProtocol.decodeConfig(encoded).value)
    }

    @Test
    fun `malformed future invalid and stale telemetry are rejected safely`() {
        val valid = ResistanceProtocol.encodeTelemetry(
            ResistanceProtocol.Telemetry(
                flags = ResistanceProtocol.FLAG_VALID or ResistanceProtocol.FLAG_FRESH or
                    ResistanceProtocol.FLAG_CALIBRATION_MATCH or ResistanceProtocol.FLAG_HOMING_VALID or
                    ResistanceProtocol.FLAG_POSITION_IN_ENVELOPE,
                sequence = 0x1234,
                timestampMs = 1_000,
                raw = 1_200,
                filtered = 1_100,
                baseline = 1_000,
                normalizedPermille = 500,
                appliedSpeedBonus = 4,
                appliedDepthBonus = 2,
                faultCode = 0,
            ),
        )

        assertEquals(ResistanceProtocol.RejectReason.INVALID_LENGTH, ResistanceProtocol.decodeTelemetry(valid.copyOf(19)).error)
        assertEquals(ResistanceProtocol.RejectReason.UNSUPPORTED_VERSION, ResistanceProtocol.decodeTelemetry(valid.copyOf().also { it[0] = 2 }).error)
        assertEquals(ResistanceProtocol.RejectReason.INVALID_FLAGS, ResistanceProtocol.decodeTelemetry(valid.copyOf().also { it[1] = 0x80.toByte() }).error)
        assertEquals(ResistanceProtocol.RejectReason.STALE_SEQUENCE, ResistanceProtocol.decodeTelemetry(valid, lastSequence = 0x1235).error)
        assertTrue(ResistanceProtocol.isSequenceNewer(0, 0xffff))
        assertFalse(ResistanceProtocol.isSequenceNewer(0xffff, 0))

        val capability = ResistanceProtocol.encodeCapability(
            ResistanceProtocol.Capability(
                ResistanceProtocol.ALL_CAPABILITY_BITS,
                ResistanceProtocol.TELEMETRY_PROPERTIES,
                ResistanceProtocol.CONFIG_PROPERTIES,
            ),
        )
        assertEquals(ResistanceProtocol.RejectReason.INVALID_LENGTH, ResistanceProtocol.decodeCapability(capability.copyOf(7)).error)
        assertEquals(
            ResistanceProtocol.RejectReason.UNSUPPORTED_VERSION,
            ResistanceProtocol.decodeCapability(capability.copyOf().also { it[0] = 2 }).error,
        )

        val config = ResistanceProtocol.encodeConfig(ResistanceProtocol.Config(1, 10, 5, 650, 450))
        assertEquals(ResistanceProtocol.RejectReason.INVALID_LENGTH, ResistanceProtocol.decodeConfig(config.copyOf(7)).error)
        assertEquals(
            ResistanceProtocol.RejectReason.UNSUPPORTED_VERSION,
            ResistanceProtocol.decodeConfig(config.copyOf().also { it[0] = 2 }).error,
        )
    }

    @Test
    fun `out of range config and inconsistent telemetry are rejected`() {
        val invalidConfig = byteArrayOf(1, 1, 10, 5, 0xc2.toByte(), 1, 0x8a.toByte(), 2)
        assertEquals(ResistanceProtocol.RejectReason.OUT_OF_RANGE, ResistanceProtocol.decodeConfig(invalidConfig).error)

        val invalidFault = byteArrayOf(
            1, ResistanceProtocol.FLAG_FAULT_LATCHED.toByte(), 1, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        assertEquals(ResistanceProtocol.RejectReason.INVALID_FLAGS, ResistanceProtocol.decodeTelemetry(invalidFault).error)
    }

    @Test
    fun `adaptive bonus requires valid homing and calibrated envelope`() {
        val safe = ResistanceProtocol.Telemetry(
            flags = ResistanceProtocol.FLAG_VALID or ResistanceProtocol.FLAG_FRESH or
                ResistanceProtocol.FLAG_CALIBRATION_MATCH or ResistanceProtocol.FLAG_HOMING_VALID or
                ResistanceProtocol.FLAG_POSITION_IN_ENVELOPE,
            sequence = 1,
            timestampMs = 1_000,
            raw = 1_200,
            filtered = 1_100,
            baseline = 1_000,
            normalizedPermille = 500,
            appliedSpeedBonus = 4,
            appliedDepthBonus = 2,
            faultCode = 0,
        )
        assertTrue(ResistanceProtocol.allowsAdaptiveBonus(safe))

        val homingLost = safe.copy(flags = safe.flags and ResistanceProtocol.FLAG_HOMING_VALID.inv())
        assertFalse(ResistanceProtocol.allowsAdaptiveBonus(homingLost))
        assertEquals(
            ResistanceProtocol.RejectReason.INVALID_FLAGS,
            ResistanceProtocol.decodeTelemetry(ResistanceProtocol.encodeTelemetry(homingLost)).error,
        )

        val outsideEnvelope = safe.copy(flags = safe.flags and ResistanceProtocol.FLAG_POSITION_IN_ENVELOPE.inv())
        assertFalse(ResistanceProtocol.allowsAdaptiveBonus(outsideEnvelope))

        val endpointPressure = safe.copy(
            flags = safe.flags or ResistanceProtocol.FLAG_FAULT_LATCHED,
            faultCode = ResistanceProtocol.FAULT_REPEATED_ENDPOINT_PRESSURE,
        )
        assertFalse(ResistanceProtocol.allowsAdaptiveBonus(endpointPressure))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
