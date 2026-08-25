package com.ossm.remote.ble

import com.ossm.remote.model.LiveTargetProducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSmoothProtocolTest {
    @Test
    fun `legacy live mapping remains inverted and bounded`() {
        assertEquals(98, mapLiveStreamPositionPercent(0, liveInvert = false))
        assertEquals(50, mapLiveStreamPositionPercent(50, liveInvert = false))
        assertEquals(2, mapLiveStreamPositionPercent(100, liveInvert = false))
        assertEquals(2, mapLiveStreamPositionPercent(-1, liveInvert = true))
        assertEquals(98, mapLiveStreamPositionPercent(101, liveInvert = true))
    }

    @Test
    fun `binary transport requires dedicated verification in addition to capability and writable characteristic`() {
        val capability = ResistanceProtocol.encodeCapability(
            ResistanceProtocol.Capability(
                capabilityBits = 0,
                telemetryProperties = ResistanceProtocol.TELEMETRY_PROPERTIES,
                configProperties = ResistanceProtocol.CONFIG_PROPERTIES,
            )
        )

        assertEquals(
            LiveTransportSelection.BLOCKED,
            LiveSmoothProtocol.selectTransport(capability, characteristicWritable = true, streamingReady = false),
        )
        assertEquals(
            LiveTransportSelection.LEGACY_TEXT,
            LiveSmoothProtocol.selectTransport(null, characteristicWritable = true, streamingReady = true),
        )
        assertEquals(
            LiveTransportSelection.LEGACY_TEXT,
            LiveSmoothProtocol.selectTransport(capability, characteristicWritable = false, streamingReady = true),
        )
        assertEquals(
            LiveTransportSelection.LEGACY_TEXT,
            LiveSmoothProtocol.selectTransport(capability, characteristicWritable = true, streamingReady = true),
        )
        assertEquals(
            LiveTransportSelection.BINARY_V1,
            LiveSmoothProtocol.selectTransport(
                capability,
                characteristicWritable = true,
                streamingReady = true,
                binaryTransportVerified = true,
            ),
        )
    }

    @Test
    fun `touch target emits serialized payload only for connected ready session`() {
        val scheduler = LiveTargetScheduler().apply { resume() }
        scheduler.offer(touchTarget(positionPercent = mapLiveStreamPositionPercent(0, false), receivedAtMs = 0))
        val target = scheduler.poll(0)!!

        assertNull(
            LiveSmoothProtocol.createTargetWrite(
                target = target,
                capabilityPayload = null,
                characteristicWritable = false,
                connected = false,
                streamingReady = true,
            )
        )
        assertNull(
            LiveSmoothProtocol.createTargetWrite(
                target = target,
                capabilityPayload = null,
                characteristicWritable = false,
                connected = true,
                streamingReady = false,
            )
        )

        val legacy = LiveSmoothProtocol.createTargetWrite(
            target = target,
            capabilityPayload = null,
            characteristicWritable = false,
            connected = true,
            streamingReady = true,
        )!!
        assertEquals(LiveTransportSelection.LEGACY_TEXT, legacy.transport)
        assertEquals("stream:98:300", legacy.payload.toString(Charsets.UTF_8))
    }

    @Test
    fun `direct touch duration follows measured velocity and full displacement`() {
        val fast = LiveTargetScheduler().apply { resume() }
        assertTrue(fast.offer(touchTarget(positionPercent = 20, receivedAtMs = 0)))
        assertNotNull(fast.poll(0))
        assertTrue(fast.offer(touchTarget(positionPercent = 60, receivedAtMs = 5)))
        val fastMove = fast.poll(LiveSmoothProtocol.MINIMUM_INPUT_INTERVAL_MS)!!

        val slow = LiveTargetScheduler().apply { resume() }
        assertTrue(
            slow.offer(
                touchTarget(
                    positionPercent = 20,
                    receivedAtMs = 0,
                    producer = LiveTargetProducer.RANGE_WIZARD,
                )
            )
        )
        assertNotNull(slow.poll(0))
        assertTrue(
            slow.offer(
                touchTarget(
                    positionPercent = 60,
                    receivedAtMs = 5_000,
                    producer = LiveTargetProducer.RANGE_WIZARD,
                )
            )
        )
        val slowMove = slow.poll(5_000)!!

        assertEquals(60, fastMove.positionPercent)
        assertEquals(60, slowMove.positionPercent)
        assertEquals(267, fastMove.durationMs)
        assertEquals(LiveTargetScheduler.MAX_TOUCH_DURATION_MS, slowMove.durationMs)
        assertTrue(fastMove.durationMs < slowMove.durationMs)
    }

    @Test
    fun `new touch input replaces the unsent endpoint`() {
        val scheduler = LiveTargetScheduler().apply { resume() }
        scheduler.offer(touchTarget(positionPercent = 10, receivedAtMs = 0))
        val first = scheduler.poll(0)!!

        scheduler.offer(touchTarget(positionPercent = 90, receivedAtMs = 5))
        scheduler.offer(touchTarget(positionPercent = 25, receivedAtMs = 10))

        assertNull(scheduler.poll(19))
        val latest = scheduler.poll(20)!!
        assertEquals(25, latest.positionPercent)
        assertEquals(first.sequence + 1, latest.sequence)
        assertTrue(latest.deadlineMs > first.deadlineMs)
        assertNull(scheduler.poll(40))
    }

    @Test
    fun `abrupt touch request dispatches one bounded direct target`() {
        val scheduler = LiveTargetScheduler().apply { resume() }
        scheduler.offer(touchTarget(positionPercent = 20, receivedAtMs = 0))
        val first = scheduler.poll(0)!!
        scheduler.offer(touchTarget(positionPercent = 80, receivedAtMs = 5))

        assertNull(scheduler.poll(19))
        val direct = scheduler.poll(20)!!

        assertEquals(80, direct.positionPercent)
        assertEquals(400, direct.durationMs)
        assertNull(scheduler.poll(40))
    }

    @Test
    fun `slow direct touch duration is bounded without intermediate steps`() {
        val scheduler = LiveTargetScheduler().apply { resume() }
        scheduler.offer(touchTarget(positionPercent = 20, receivedAtMs = 0))
        scheduler.poll(0)
        scheduler.offer(touchTarget(positionPercent = 33, receivedAtMs = 1_300))

        val first = scheduler.poll(1_300)!!
        assertEquals(33, first.positionPercent)
        assertEquals(LiveTargetScheduler.MAX_TOUCH_DURATION_MS, first.durationMs)
        assertNull(scheduler.poll(1_320))
    }

    @Test
    fun `direct touch targets use only the twenty millisecond cadence`() {
        val scheduler = LiveTargetScheduler().apply { resume() }
        scheduler.offer(touchTarget(positionPercent = 10, receivedAtMs = 0))
        val initial = scheduler.poll(0)!!
        scheduler.offer(touchTarget(positionPercent = 30, receivedAtMs = 5))

        assertEquals(LiveSmoothProtocol.MINIMUM_INPUT_INTERVAL_MS, scheduler.millisecondsUntilReady(0))
        assertNull(scheduler.poll(19))
        val direct = scheduler.poll(20)!!
        assertEquals(30, direct.positionPercent)
        assertEquals(133, direct.durationMs)
    }

    @Test
    fun `stop immediately discards a pending direct target`() {
        val scheduler = LiveTargetScheduler().apply { resume() }
        scheduler.offer(touchTarget(positionPercent = 10, receivedAtMs = 0))
        scheduler.poll(0)
        scheduler.offer(touchTarget(positionPercent = 90, receivedAtMs = 5))

        scheduler.stop()

        assertEquals(0L, scheduler.millisecondsUntilReady(10_000))
        assertNull(scheduler.poll(10_000))
    }

    @Test
    fun `duplicates are suppressed and reversal receives conservative timing`() {
        val scheduler = LiveTargetScheduler().apply { resume() }
        scheduler.offer(touchTarget(positionPercent = 20, receivedAtMs = 0))
        scheduler.poll(0)

        assertFalse(scheduler.offer(touchTarget(positionPercent = 20, receivedAtMs = 40)))
        assertNull(scheduler.poll(40))

        scheduler.offer(touchTarget(positionPercent = 80, receivedAtMs = 80))
        scheduler.poll(120)
        scheduler.offer(touchTarget(positionPercent = 0, receivedAtMs = 140))
        val reversal = scheduler.poll(180)!!

        assertTrue(reversal.durationMs >= LiveTargetScheduler.REVERSAL_MIN_DURATION_MS)
    }

    @Test
    fun `stop clears pending target and blocks late producer callbacks until resume`() {
        val scheduler = LiveTargetScheduler().apply { resume() }
        scheduler.offer(touchTarget(positionPercent = 10, receivedAtMs = 0))
        scheduler.poll(0)
        scheduler.offer(touchTarget(positionPercent = 80, receivedAtMs = 5))

        scheduler.stop()

        assertNull(scheduler.poll(20))
        assertFalse(scheduler.offer(touchTarget(positionPercent = 90, receivedAtMs = 30)))
        scheduler.resume()
        assertTrue(scheduler.offer(touchTarget(positionPercent = 90, receivedAtMs = 40)))
        assertEquals(90, scheduler.poll(40)?.positionPercent)
    }

    @Test
    fun `timeline producers preserve requested duration through shared scheduler`() {
        val scheduler = LiveTargetScheduler().apply { resume() }
        scheduler.offer(
            LiveTargetInput(
                positionPercent = 75,
                requestedDurationMs = 420,
                producer = LiveTargetProducer.FUNSCRIPT,
                receivedAtMs = 1_000,
            )
        )

        val target = scheduler.poll(1_000)!!
        assertEquals(420, target.durationMs)
        assertEquals(LiveTargetProducer.FUNSCRIPT, target.producer)

        scheduler.offer(
            LiveTargetInput(
                positionPercent = 25,
                requestedDurationMs = 21,
                producer = LiveTargetProducer.VIDEO_SYNC,
                receivedAtMs = 1_001,
            )
        )
        assertNull(scheduler.poll(1_019))
        assertEquals(21, scheduler.poll(1_020)?.durationMs)
    }

    @Test
    fun `v1 golden vector is exactly little endian and round trips`() {
        val packet = LiveSmoothPacket(
            sequence = 0x1234,
            deadlineMs = 0x89abcdefL,
            positionPermille = 750,
            durationMs = 40
        )
        val golden = byteArrayOf(
            0x01, 0x00, 0x34, 0x12, 0xef.toByte(), 0xcd.toByte(),
            0xab.toByte(), 0x89.toByte(), 0xee.toByte(), 0x02, 0x28, 0x00
        )

        assertEquals("522b443a-4f53-534d-7000-420badbabe69", LiveSmoothProtocol.CHARACTERISTIC_UUID)
        assertTrue(golden.contentEquals(LiveSmoothProtocol.encode(packet)))
        assertEquals(packet, LiveSmoothProtocol.decode(golden))
        assertTrue(golden.size <= 20)
    }

    @Test
    fun `malformed future and stop-like packets are rejected`() {
        val valid = LiveSmoothProtocol.encode(packet(sequence = 1, deadlineMs = 100))

        assertNull(LiveSmoothProtocol.decode(valid.copyOf(11)))
        assertNull(LiveSmoothProtocol.decode(valid + 0))
        assertNull(LiveSmoothProtocol.decode(valid.copyOf().also { it[0] = 2 }))
        assertNull(LiveSmoothProtocol.decode(valid.copyOf().also { it[1] = 1 }))
        assertNull(LiveSmoothProtocol.decode(valid.copyOf().also { it[8] = 0xe9.toByte(); it[9] = 0x03 }))
    }

    @Test
    fun `sequence comparison rejects duplicates and accepts wrap`() {
        assertFalse(LiveSmoothProtocol.isNewerSequence(7, 7))
        assertFalse(LiveSmoothProtocol.isNewerSequence(6, 7))
        assertTrue(LiveSmoothProtocol.isNewerSequence(0, 0xffff))
        assertFalse(LiveSmoothProtocol.isNewerSequence(0x8000, 0))
    }

    @Test
    fun `queue initializes first target and accepts zero distance`() {
        val queue = LiveSmoothQueueModel(safeState())

        assertEquals(LiveSmoothOfferResult.ACCEPTED, queue.offer(packet(1, 100, 500), 0))
        assertEquals(500, queue.poll(0)?.positionPermille)
        assertEquals(LiveSmoothOfferResult.ACCEPTED, queue.offer(packet(2, 100, 500), 20))
        assertEquals(0, queue.distancePermille(currentPositionPermille = 500, queue.poll(20)!!))
    }

    @Test
    fun `100 Hz flood coalesces newest target and remains bounded`() {
        val queue = LiveSmoothQueueModel(safeState())

        for (index in 0 until 10) {
            val result = queue.offer(packet(index, 500, index * 100), index * 10L)
            val expected = if (index % 2 == 0 && index < 8) {
                LiveSmoothOfferResult.ACCEPTED
            } else {
                LiveSmoothOfferResult.COALESCED
            }
            assertEquals(expected, result)
            assertTrue(queue.depth <= LiveSmoothProtocol.QUEUE_CAPACITY)
        }

        assertTrue(queue.maxDepthObserved <= LiveSmoothProtocol.QUEUE_CAPACITY)
        assertEquals(900, queue.pendingTargets().last().positionPermille)
    }

    @Test
    fun `full queue reversal burst replaces only newest target`() {
        val queue = LiveSmoothQueueModel(safeState())
        listOf(100, 900, 100, 900).forEachIndexed { index, position ->
            assertEquals(LiveSmoothOfferResult.ACCEPTED, queue.offer(packet(index, 500, position), index * 20L))
        }

        assertEquals(LiveSmoothOfferResult.COALESCED, queue.offer(packet(4, 500, 100), 80))
        assertEquals(listOf(100, 900, 100, 100), queue.pendingTargets().map { it.positionPermille })
        assertEquals(4, queue.depth)
    }

    @Test
    fun `duplicate stale and expired targets never enter queue`() {
        val queue = LiveSmoothQueueModel(safeState())

        assertEquals(LiveSmoothOfferResult.ACCEPTED, queue.offer(packet(0xffff, 300), 0))
        assertEquals(LiveSmoothOfferResult.DUPLICATE, queue.offer(packet(0xffff, 300), 20))
        assertEquals(LiveSmoothOfferResult.STALE_SEQUENCE, queue.offer(packet(0xfffe, 300), 40))
        assertEquals(LiveSmoothOfferResult.ACCEPTED, queue.offer(packet(0, 300), 60))
        assertEquals(LiveSmoothOfferResult.EXPIRED, queue.offer(packet(1, 99), 100))
        assertEquals(0, queue.poll(251)?.sequence)
        assertNull(queue.poll(311))
        assertEquals(0, queue.depth)
    }

    @Test
    fun `deadline and target expiry boundaries are exact and deterministic`() {
        val deadlineQueue = LiveSmoothQueueModel(safeState())
        assertEquals(LiveSmoothOfferResult.EXPIRED, deadlineQueue.offer(packet(1, 100), 100))

        val ageQueue = LiveSmoothQueueModel(safeState())
        assertEquals(LiveSmoothOfferResult.ACCEPTED, ageQueue.offer(packet(1, 1_000), 0))
        assertNull(ageQueue.poll(LiveSmoothProtocol.TARGET_EXPIRY_MS))
        assertEquals(0, ageQueue.depth)
    }

    @Test
    fun `stop is out of band and independent of queue depth`() {
        val queue = LiveSmoothQueueModel(safeState())
        repeat(LiveSmoothProtocol.QUEUE_CAPACITY) { index ->
            queue.offer(packet(index, 500), index * 20L)
        }

        queue.stopOutOfBand()

        assertEquals(0, queue.depth)
        assertTrue(queue.isStopped)
        assertNull(queue.poll(100))
    }

    @Test
    fun `invalid homing envelope or repeated endpoint pressure inhibits motion immediately`() {
        val queue = LiveSmoothQueueModel(safeState())
        assertEquals(LiveSmoothOfferResult.ACCEPTED, queue.offer(packet(1, 500), 0))

        queue.updateSafety(safeState().copy(homingValid = false))

        assertEquals(0, queue.depth)
        assertTrue(queue.isStopped)
        assertFalse(queue.motionAllowed)
        assertEquals(LiveSmoothOfferResult.SAFETY_INHIBITED, queue.offer(packet(2, 500), 20))

        val endpointPressureQueue = LiveSmoothQueueModel(safeState().copy(repeatedEndpointPressure = true))
        assertEquals(LiveSmoothOfferResult.SAFETY_INHIBITED, endpointPressureQueue.offer(packet(1, 500), 0))
        assertEquals(0, endpointPressureQueue.depth)
    }

    private fun packet(
        sequence: Int,
        deadlineMs: Long,
        positionPermille: Int = 500
    ) = LiveSmoothPacket(
        sequence = sequence,
        deadlineMs = deadlineMs,
        positionPermille = positionPermille,
        durationMs = 20
    )

    private fun safeState() = LiveSmoothSafetyState(
        homingValid = true,
        calibratedTravelSteps = 10_000,
        shallowEndpointSteps = 1_000,
        deepEndpointSteps = 9_000,
        currentPositionSteps = 5_000,
        repeatedEndpointPressure = false
    )

    private fun touchTarget(
        positionPercent: Int,
        receivedAtMs: Long,
        producer: LiveTargetProducer = LiveTargetProducer.TOUCH,
    ) = LiveTargetInput(
        positionPercent = positionPercent,
        requestedDurationMs = 60,
        producer = producer,
        receivedAtMs = receivedAtMs,
    )
}
