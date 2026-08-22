package com.ossm.remote.ble

import com.ossm.remote.model.LiveTargetProducer
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class LiveSmoothPacket(
    val sequence: Int,
    val deadlineMs: Long,
    val positionPermille: Int,
    val durationMs: Int
)

internal data class LiveSmoothSafetyState(
    val homingValid: Boolean,
    val calibratedTravelSteps: Int,
    val shallowEndpointSteps: Int,
    val deepEndpointSteps: Int,
    val currentPositionSteps: Int,
    val repeatedEndpointPressure: Boolean
)

internal enum class LiveSmoothOfferResult {
    ACCEPTED,
    COALESCED,
    DUPLICATE,
    STALE_SEQUENCE,
    EXPIRED,
    SAFETY_INHIBITED
}

internal enum class LiveTransportSelection {
    BLOCKED,
    LEGACY_TEXT,
    BINARY_V1,
}

internal data class LiveTargetInput(
    val positionPercent: Int,
    val requestedDurationMs: Int,
    val producer: LiveTargetProducer,
    val receivedAtMs: Long,
)

internal data class ScheduledLiveTarget(
    val positionPercent: Int,
    val durationMs: Int,
    val producer: LiveTargetProducer,
    val sequence: Int,
    val deadlineMs: Long,
)

internal data class LiveTargetWrite(
    val transport: LiveTransportSelection,
    val payload: ByteArray,
)

internal object LiveSmoothProtocol {
    const val CHARACTERISTIC_UUID = "522b443a-4f53-534d-7000-420badbabe69"
    const val VERSION = 1
    const val PACKET_SIZE = 12
    const val QUEUE_CAPACITY = 4
    const val MINIMUM_INPUT_INTERVAL_MS = 20L
    const val TARGET_EXPIRY_MS = 250L

    fun selectTransport(
        capabilityPayload: ByteArray?,
        characteristicWritable: Boolean,
        streamingReady: Boolean,
    ): LiveTransportSelection {
        if (!streamingReady) return LiveTransportSelection.BLOCKED
        val capabilityVerified = capabilityPayload?.let {
            ResistanceProtocol.decodeCapability(it).value
        } != null
        return if (capabilityVerified && characteristicWritable) {
            LiveTransportSelection.BINARY_V1
        } else {
            LiveTransportSelection.LEGACY_TEXT
        }
    }

    fun createTargetWrite(
        target: ScheduledLiveTarget,
        capabilityPayload: ByteArray?,
        characteristicWritable: Boolean,
        connected: Boolean,
        streamingReady: Boolean,
    ): LiveTargetWrite? {
        if (!connected) return null
        return when (selectTransport(capabilityPayload, characteristicWritable, streamingReady)) {
            LiveTransportSelection.BLOCKED -> null
            LiveTransportSelection.LEGACY_TEXT -> LiveTargetWrite(
                transport = LiveTransportSelection.LEGACY_TEXT,
                payload = "stream:${target.positionPercent}:${target.durationMs}".toByteArray(Charsets.UTF_8),
            )
            LiveTransportSelection.BINARY_V1 -> LiveTargetWrite(
                transport = LiveTransportSelection.BINARY_V1,
                payload = encode(
                    LiveSmoothPacket(
                        sequence = target.sequence,
                        deadlineMs = target.deadlineMs,
                        positionPermille = target.positionPercent * 10,
                        durationMs = target.durationMs,
                    )
                ),
            )
        }
    }

    fun encode(packet: LiveSmoothPacket): ByteArray {
        require(packet.sequence in 0..0xffff)
        require(packet.deadlineMs in 0..0xffff_ffffL)
        require(packet.positionPermille in 0..1000)
        require(packet.durationMs in 0..0xffff)
        return ByteArray(PACKET_SIZE).also { bytes ->
            bytes[0] = VERSION.toByte()
            putU16(bytes, 2, packet.sequence)
            putU32(bytes, 4, packet.deadlineMs)
            putU16(bytes, 8, packet.positionPermille)
            putU16(bytes, 10, packet.durationMs)
        }
    }

    fun decode(bytes: ByteArray): LiveSmoothPacket? {
        if (bytes.size != PACKET_SIZE || bytes[0].toInt() and 0xff != VERSION || bytes[1].toInt() != 0) {
            return null
        }
        val position = readU16(bytes, 8)
        if (position > 1000) {
            return null
        }
        return LiveSmoothPacket(
            sequence = readU16(bytes, 2),
            deadlineMs = readU32(bytes, 4),
            positionPermille = position,
            durationMs = readU16(bytes, 10)
        )
    }

    fun isNewerSequence(candidate: Int, previous: Int): Boolean {
        val difference = (candidate - previous) and 0xffff
        return difference in 1 until 0x8000
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}

internal class LiveTargetScheduler {
    private data class PendingTarget(
        val positionPercent: Int,
        val durationMs: Int,
        val producer: LiveTargetProducer,
    )

    private data class TouchSample(val positionPercent: Int, val receivedAtMs: Long)

    private var pending: PendingTarget? = null
    private var stopped = true
    private var nextSequence = 0
    private var lastDispatchAtMs: Long? = null
    private var lastDispatchedPosition: Int? = null
    private var lastDirection = 0
    private var lastTouchSample: TouchSample? = null
    private var lastDeadlineAbsoluteMs = -1L

    fun offer(input: LiveTargetInput): Boolean {
        if (stopped) return false
        val position = input.positionPercent.coerceIn(0, 100)
        val previousSample = lastTouchSample
        val isTouchProducer = input.producer == LiveTargetProducer.TOUCH ||
            input.producer == LiveTargetProducer.RANGE_WIZARD
        if (isTouchProducer && previousSample?.positionPercent == position) return false
        if (!isTouchProducer && (pending?.positionPercent ?: lastDispatchedPosition) == position) return false

        val duration = if (isTouchProducer) {
            calculateTouchDuration(position, input.receivedAtMs, previousSample)
        } else {
            input.requestedDurationMs.coerceIn(MIN_TIMELINE_DURATION_MS, MAX_TIMELINE_DURATION_MS)
        }
        if (isTouchProducer) {
            lastTouchSample = TouchSample(position, input.receivedAtMs)
        } else {
            lastTouchSample = null
        }
        pending = PendingTarget(position, duration, input.producer)
        return true
    }

    fun poll(nowMs: Long): ScheduledLiveTarget? {
        if (stopped) return null
        val candidate = pending ?: return null
        val previousDispatchAt = lastDispatchAtMs
        if (previousDispatchAt != null && nowMs - previousDispatchAt < LiveSmoothProtocol.MINIMUM_INPUT_INTERVAL_MS) {
            return null
        }
        val previousPosition = lastDispatchedPosition
        if (previousPosition == candidate.positionPercent) {
            pending = null
            return null
        }

        val isTouchProducer = candidate.producer == LiveTargetProducer.TOUCH ||
            candidate.producer == LiveTargetProducer.RANGE_WIZARD
        val dispatchPosition = if (previousPosition != null && isTouchProducer) {
            val delta = candidate.positionPercent - previousPosition
            previousPosition + delta.coerceIn(-MAX_TOUCH_STEP_PERCENT, MAX_TOUCH_STEP_PERCENT)
        } else {
            candidate.positionPercent
        }
        if (dispatchPosition == candidate.positionPercent) {
            pending = null
        }

        val direction = previousPosition?.let { dispatchPosition.compareTo(it) } ?: 0
        val reversal = direction != 0 && lastDirection != 0 && direction != lastDirection
        val duration = if (reversal) {
            candidate.durationMs.coerceAtLeast(REVERSAL_MIN_DURATION_MS)
        } else {
            candidate.durationMs
        }
        if (direction != 0) lastDirection = direction

        val deadlineLeadMs = duration.coerceIn(MIN_DEADLINE_LEAD_MS, MAX_DEADLINE_LEAD_MS)
        val deadlineAbsoluteMs = maxOf(nowMs + deadlineLeadMs, lastDeadlineAbsoluteMs + 1L)
        lastDeadlineAbsoluteMs = deadlineAbsoluteMs
        val sequence = nextSequence
        nextSequence = (nextSequence + 1) and 0xffff
        lastDispatchAtMs = nowMs
        lastDispatchedPosition = dispatchPosition
        return ScheduledLiveTarget(
            positionPercent = dispatchPosition,
            durationMs = duration,
            producer = candidate.producer,
            sequence = sequence,
            deadlineMs = deadlineAbsoluteMs and 0xffff_ffffL,
        )
    }

    fun millisecondsUntilReady(nowMs: Long): Long {
        if (stopped || pending == null) return 0L
        val previousDispatchAt = lastDispatchAtMs ?: return 0L
        return (LiveSmoothProtocol.MINIMUM_INPUT_INTERVAL_MS - (nowMs - previousDispatchAt)).coerceAtLeast(0L)
    }

    fun stop() {
        stopped = true
        pending = null
        lastDispatchAtMs = null
        lastDispatchedPosition = null
        lastDirection = 0
        lastTouchSample = null
    }

    fun resume() {
        stopped = false
        pending = null
        lastDispatchAtMs = null
        lastDispatchedPosition = null
        lastDirection = 0
        lastTouchSample = null
    }

    private fun calculateTouchDuration(
        positionPercent: Int,
        receivedAtMs: Long,
        previous: TouchSample?,
    ): Int {
        if (previous == null) return DEFAULT_TOUCH_DURATION_MS
        val elapsedMs = (receivedAtMs - previous.receivedAtMs).coerceAtLeast(1L)
        val displacement = abs(positionPercent - previous.positionPercent)
        if (displacement == 0) return DEFAULT_TOUCH_DURATION_MS
        val velocityPercentPerSecond = displacement * 1_000f / elapsedMs
        val movementDurationMs = (displacement * 1_000f / velocityPercentPerSecond).roundToInt()
        return movementDurationMs.coerceIn(MIN_TOUCH_DURATION_MS, MAX_TOUCH_DURATION_MS)
    }

    companion object {
        const val MAX_TOUCH_STEP_PERCENT = 10
        const val MIN_TOUCH_DURATION_MS = 40
        const val MAX_TOUCH_DURATION_MS = 600
        const val REVERSAL_MIN_DURATION_MS = 100
        private const val DEFAULT_TOUCH_DURATION_MS = 120
        private const val MIN_TIMELINE_DURATION_MS = 20
        private const val MAX_TIMELINE_DURATION_MS = 2_000
        private const val MIN_DEADLINE_LEAD_MS = 40
        private const val MAX_DEADLINE_LEAD_MS = 200
    }
}

internal class LiveSmoothQueueModel(initialSafety: LiveSmoothSafetyState) {
    private data class Entry(val packet: LiveSmoothPacket, val acceptedAtMs: Long)

    private val entries = mutableListOf<Entry>()
    private var safety = initialSafety
    private var lastSequence: Int? = null
    private var lastQueuedAtMs: Long? = null

    var maxDepthObserved: Int = 0
        private set
    var isStopped: Boolean = !safetyAllowsMotion(initialSafety)
        private set

    val depth: Int
        get() = entries.size
    val motionAllowed: Boolean
        get() = !isStopped && safetyAllowsMotion(safety)

    fun offer(packet: LiveSmoothPacket, receivedAtMs: Long): LiveSmoothOfferResult {
        updateSafety(safety)
        if (!motionAllowed) {
            return LiveSmoothOfferResult.SAFETY_INHIBITED
        }
        purgeExpired(receivedAtMs)
        if (hasReached32(receivedAtMs, packet.deadlineMs)) {
            return LiveSmoothOfferResult.EXPIRED
        }
        lastSequence?.let { previous ->
            if (packet.sequence == previous) {
                return LiveSmoothOfferResult.DUPLICATE
            }
            if (!LiveSmoothProtocol.isNewerSequence(packet.sequence, previous)) {
                return LiveSmoothOfferResult.STALE_SEQUENCE
            }
        }

        lastSequence = packet.sequence
        val tooSoon = lastQueuedAtMs?.let { elapsed32(receivedAtMs, it) < LiveSmoothProtocol.MINIMUM_INPUT_INTERVAL_MS } == true
        if ((tooSoon || entries.size == LiveSmoothProtocol.QUEUE_CAPACITY) && entries.isNotEmpty()) {
            entries[entries.lastIndex] = Entry(packet, receivedAtMs)
            return LiveSmoothOfferResult.COALESCED
        }
        if (tooSoon) {
            return LiveSmoothOfferResult.COALESCED
        }

        entries += Entry(packet, receivedAtMs)
        lastQueuedAtMs = receivedAtMs
        maxDepthObserved = maxOf(maxDepthObserved, entries.size)
        return LiveSmoothOfferResult.ACCEPTED
    }

    fun poll(nowMs: Long): LiveSmoothPacket? {
        if (!motionAllowed) {
            stopOutOfBand()
            return null
        }
        purgeExpired(nowMs)
        return entries.removeFirstOrNull()?.packet
    }

    fun pendingTargets(): List<LiveSmoothPacket> = entries.map { it.packet }

    fun distancePermille(currentPositionPermille: Int, target: LiveSmoothPacket): Int =
        abs(target.positionPermille - currentPositionPermille)

    fun updateSafety(updated: LiveSmoothSafetyState) {
        safety = updated
        if (!safetyAllowsMotion(updated)) {
            stopOutOfBand()
        }
    }

    fun stopOutOfBand() {
        entries.clear()
        isStopped = true
    }

    private fun purgeExpired(nowMs: Long) {
        entries.removeAll { entry ->
            hasReached32(nowMs, entry.packet.deadlineMs) ||
                elapsed32(nowMs, entry.acceptedAtMs) >= LiveSmoothProtocol.TARGET_EXPIRY_MS
        }
    }

    private fun safetyAllowsMotion(state: LiveSmoothSafetyState): Boolean {
        if (!state.homingValid || state.repeatedEndpointPressure || state.calibratedTravelSteps <= 0) {
            return false
        }
        if (state.shallowEndpointSteps >= state.deepEndpointSteps) {
            return false
        }
        val usableTravel = state.deepEndpointSteps - state.shallowEndpointSteps
        return usableTravel <= state.calibratedTravelSteps &&
            state.currentPositionSteps in state.shallowEndpointSteps..state.deepEndpointSteps
    }

    private fun hasReached32(candidate: Long, reference: Long): Boolean {
        val difference = elapsed32(candidate, reference)
        return difference <= 0x7fff_ffffL
    }

    private fun elapsed32(candidate: Long, reference: Long): Long =
        (candidate - reference) and 0xffff_ffffL
}
