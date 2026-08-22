package com.ossm.remote.ble

import java.util.UUID

object ResistanceProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("522b443a-4f53-534d-0001-420badbabe69")
    val CAPABILITY_UUID: UUID = UUID.fromString("522b443a-4f53-534d-5000-420badbabe69")
    val TELEMETRY_UUID: UUID = UUID.fromString("522b443a-4f53-534d-6000-420badbabe69")
    val CONFIG_UUID: UUID = UUID.fromString("522b443a-4f53-534d-6100-420badbabe69")

    const val VERSION = 1
    const val NEGOTIATION_TIMEOUT_MS = 1_000L
    const val CAPABILITY_PACKET_SIZE = 8
    const val TELEMETRY_PACKET_SIZE = 20
    const val CONFIG_PACKET_SIZE = 8

    const val CAPABILITY_RESISTANCE_TELEMETRY = 1 shl 0
    const val CAPABILITY_RESISTANCE_CONFIG = 1 shl 1
    const val CAPABILITY_RESISTANCE_AVAILABLE = 1 shl 2
    const val ALL_CAPABILITY_BITS =
        CAPABILITY_RESISTANCE_TELEMETRY or CAPABILITY_RESISTANCE_CONFIG or CAPABILITY_RESISTANCE_AVAILABLE

    const val PROPERTY_READ = 1 shl 0
    const val PROPERTY_NOTIFY = 1 shl 1
    const val PROPERTY_WRITE = 1 shl 2
    const val CAPABILITY_PROPERTIES = PROPERTY_READ
    const val TELEMETRY_PROPERTIES = PROPERTY_READ or PROPERTY_NOTIFY
    const val CONFIG_PROPERTIES = PROPERTY_READ or PROPERTY_WRITE

    const val FLAG_VALID = 1 shl 0
    const val FLAG_FRESH = 1 shl 1
    const val FLAG_SATURATED = 1 shl 2
    const val FLAG_REVERSING = 1 shl 3
    const val FLAG_FAULT_LATCHED = 1 shl 4
    const val FLAG_CALIBRATION_MATCH = 1 shl 5
    const val FLAG_HOMING_VALID = 1 shl 6
    const val FLAG_POSITION_IN_ENVELOPE = 1 shl 7

    const val CONFIG_ARMED = 1 shl 0
    const val CONFIG_ACKNOWLEDGE_FAULT = 1 shl 1
    const val ALL_CONFIG_FLAGS = CONFIG_ARMED or CONFIG_ACKNOWLEDGE_FAULT

    const val FAULT_REPEATED_ENDPOINT_PRESSURE = 1
    const val MAX_SPEED_BONUS = 10
    const val MAX_DEPTH_BONUS = 5
    const val MAX_PERMILLE = 1_000
    const val MAX_ADC_VALUE = 4_095

    enum class RejectReason {
        INVALID_LENGTH,
        UNSUPPORTED_VERSION,
        INVALID_FLAGS,
        OUT_OF_RANGE,
        STALE_SEQUENCE,
    }

    data class DecodeResult<T>(
        val value: T? = null,
        val error: RejectReason? = null,
    )

    data class Capability(
        val capabilityBits: Int,
        val telemetryProperties: Int,
        val configProperties: Int,
    )

    data class Telemetry(
        val flags: Int,
        val sequence: Int,
        val timestampMs: Long,
        val raw: Int,
        val filtered: Int,
        val baseline: Int,
        val normalizedPermille: Int,
        val appliedSpeedBonus: Int,
        val appliedDepthBonus: Int,
        val faultCode: Int,
    )

    data class Config(
        val flags: Int,
        val maxSpeedBonus: Int,
        val maxDepthBonus: Int,
        val enterThresholdPermille: Int,
        val exitThresholdPermille: Int,
    )

    fun encodeCapability(packet: Capability): ByteArray = ByteArray(CAPABILITY_PACKET_SIZE).also {
        it[0] = VERSION.toByte()
        it.writeU16(1, packet.capabilityBits)
        it[3] = packet.telemetryProperties.toByte()
        it[4] = packet.configProperties.toByte()
        it[5] = TELEMETRY_PACKET_SIZE.toByte()
        it[6] = CONFIG_PACKET_SIZE.toByte()
    }

    fun encodeTelemetry(packet: Telemetry): ByteArray = ByteArray(TELEMETRY_PACKET_SIZE).also {
        it[0] = VERSION.toByte()
        it[1] = packet.flags.toByte()
        it.writeU16(2, packet.sequence)
        it.writeU32(4, packet.timestampMs)
        it.writeU16(8, packet.raw)
        it.writeU16(10, packet.filtered)
        it.writeU16(12, packet.baseline)
        it.writeU16(14, packet.normalizedPermille)
        it[16] = packet.appliedSpeedBonus.toByte()
        it[17] = packet.appliedDepthBonus.toByte()
        it[18] = packet.faultCode.toByte()
    }

    fun encodeConfig(packet: Config): ByteArray = ByteArray(CONFIG_PACKET_SIZE).also {
        it[0] = VERSION.toByte()
        it[1] = packet.flags.toByte()
        it[2] = packet.maxSpeedBonus.toByte()
        it[3] = packet.maxDepthBonus.toByte()
        it.writeU16(4, packet.enterThresholdPermille)
        it.writeU16(6, packet.exitThresholdPermille)
    }

    fun decodeCapability(bytes: ByteArray): DecodeResult<Capability> {
        validateHeader(bytes, CAPABILITY_PACKET_SIZE)?.let { return DecodeResult(error = it) }
        if (bytes.u8(7) != 0) return DecodeResult(error = RejectReason.INVALID_FLAGS)
        val packet = Capability(bytes.u16(1), bytes.u8(3), bytes.u8(4))
        if (
            packet.capabilityBits and ALL_CAPABILITY_BITS.inv() != 0 ||
            packet.telemetryProperties != TELEMETRY_PROPERTIES ||
            packet.configProperties != CONFIG_PROPERTIES ||
            bytes.u8(5) != TELEMETRY_PACKET_SIZE ||
            bytes.u8(6) != CONFIG_PACKET_SIZE
        ) {
            return DecodeResult(error = RejectReason.INVALID_FLAGS)
        }
        return DecodeResult(value = packet)
    }

    fun decodeTelemetry(bytes: ByteArray, lastSequence: Int? = null): DecodeResult<Telemetry> {
        validateHeader(bytes, TELEMETRY_PACKET_SIZE)?.let { return DecodeResult(error = it) }
        if (bytes.u8(19) != 0) return DecodeResult(error = RejectReason.INVALID_FLAGS)
        val packet = Telemetry(
            flags = bytes.u8(1),
            sequence = bytes.u16(2),
            timestampMs = bytes.u32(4),
            raw = bytes.u16(8),
            filtered = bytes.u16(10),
            baseline = bytes.u16(12),
            normalizedPermille = bytes.u16(14),
            appliedSpeedBonus = bytes.u8(16),
            appliedDepthBonus = bytes.u8(17),
            faultCode = bytes.u8(18),
        )
        if (
            packet.raw > MAX_ADC_VALUE || packet.filtered > MAX_ADC_VALUE ||
            packet.baseline > MAX_ADC_VALUE || packet.normalizedPermille > MAX_PERMILLE ||
            packet.appliedSpeedBonus > MAX_SPEED_BONUS || packet.appliedDepthBonus > MAX_DEPTH_BONUS
        ) {
            return DecodeResult(error = RejectReason.OUT_OF_RANGE)
        }
        if (!telemetryFlagsAreConsistent(packet)) {
            return DecodeResult(error = RejectReason.INVALID_FLAGS)
        }
        if (lastSequence != null && !isSequenceNewer(packet.sequence, lastSequence)) {
            return DecodeResult(error = RejectReason.STALE_SEQUENCE)
        }
        return DecodeResult(value = packet)
    }

    fun decodeConfig(bytes: ByteArray): DecodeResult<Config> {
        validateHeader(bytes, CONFIG_PACKET_SIZE)?.let { return DecodeResult(error = it) }
        val packet = Config(
            flags = bytes.u8(1),
            maxSpeedBonus = bytes.u8(2),
            maxDepthBonus = bytes.u8(3),
            enterThresholdPermille = bytes.u16(4),
            exitThresholdPermille = bytes.u16(6),
        )
        if (packet.flags and ALL_CONFIG_FLAGS.inv() != 0 || packet.flags == ALL_CONFIG_FLAGS) {
            return DecodeResult(error = RejectReason.INVALID_FLAGS)
        }
        if (
            packet.maxSpeedBonus > MAX_SPEED_BONUS || packet.maxDepthBonus > MAX_DEPTH_BONUS ||
            packet.enterThresholdPermille > MAX_PERMILLE || packet.exitThresholdPermille > MAX_PERMILLE ||
            packet.exitThresholdPermille > packet.enterThresholdPermille
        ) {
            return DecodeResult(error = RejectReason.OUT_OF_RANGE)
        }
        return DecodeResult(value = packet)
    }

    fun isSequenceNewer(candidate: Int, previous: Int): Boolean {
        val delta = (candidate - previous) and 0xffff
        return delta != 0 && delta < 0x8000
    }

    fun allowsAdaptiveBonus(packet: Telemetry): Boolean {
        val required = FLAG_VALID or FLAG_FRESH or FLAG_CALIBRATION_MATCH or
            FLAG_HOMING_VALID or FLAG_POSITION_IN_ENVELOPE
        val blocking = FLAG_SATURATED or FLAG_REVERSING or FLAG_FAULT_LATCHED
        return packet.flags and required == required && packet.flags and blocking == 0
    }

    private fun validateHeader(bytes: ByteArray, expectedSize: Int): RejectReason? = when {
        bytes.size != expectedSize -> RejectReason.INVALID_LENGTH
        bytes.u8(0) != VERSION -> RejectReason.UNSUPPORTED_VERSION
        else -> null
    }

    private fun telemetryFlagsAreConsistent(packet: Telemetry): Boolean {
        val valid = packet.flags and FLAG_VALID != 0
        val fresh = packet.flags and FLAG_FRESH != 0
        val saturated = packet.flags and FLAG_SATURATED != 0
        val reversing = packet.flags and FLAG_REVERSING != 0
        val faultLatched = packet.flags and FLAG_FAULT_LATCHED != 0
        val homingValid = packet.flags and FLAG_HOMING_VALID != 0
        val insideEnvelope = packet.flags and FLAG_POSITION_IN_ENVELOPE != 0
        if (fresh && !valid || insideEnvelope && !homingValid) return false
        if (valid && (saturated || reversing || faultLatched)) return false
        if (faultLatched != (packet.faultCode != 0)) return false
        if (
            (packet.appliedSpeedBonus != 0 || packet.appliedDepthBonus != 0) &&
            !allowsAdaptiveBonus(packet)
        ) {
            return false
        }
        return true
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

    private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

    private fun ByteArray.u32(offset: Int): Long =
        u8(offset).toLong() or
            (u8(offset + 1).toLong() shl 8) or
            (u8(offset + 2).toLong() shl 16) or
            (u8(offset + 3).toLong() shl 24)

    private fun ByteArray.writeU16(offset: Int, value: Int) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = (value ushr 8 and 0xff).toByte()
    }

    private fun ByteArray.writeU32(offset: Int, value: Long) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = (value ushr 8 and 0xff).toByte()
        this[offset + 2] = (value ushr 16 and 0xff).toByte()
        this[offset + 3] = (value ushr 24 and 0xff).toByte()
    }
}
