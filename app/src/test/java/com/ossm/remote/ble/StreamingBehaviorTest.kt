package com.ossm.remote.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingBehaviorTest {

    @Test
    fun `live stream mapping keeps home at zero and deep end near hundred by default`() {
        assertEquals(2, mapLiveStreamPositionPercent(0, liveInvert = false))
        assertEquals(50, mapLiveStreamPositionPercent(50, liveInvert = false))
        assertEquals(98, mapLiveStreamPositionPercent(100, liveInvert = false))
    }

    @Test
    fun `live mode enables latency compensation`() {
        assertTrue(isLatencyCompensationEnabledForLive())
    }
}
