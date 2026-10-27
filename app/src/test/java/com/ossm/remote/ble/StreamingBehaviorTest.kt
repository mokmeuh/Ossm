package com.ossm.remote.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamingBehaviorTest {

    @Test
    fun `live pad restores inverse wire mapping and invert opts into direct mapping`() {
        assertEquals(98, mapLiveStreamPositionPercent(0, liveInvert = false))
        assertEquals(50, mapLiveStreamPositionPercent(50, liveInvert = false))
        assertEquals(2, mapLiveStreamPositionPercent(100, liveInvert = false))
        assertEquals(2, mapLiveStreamPositionPercent(0, liveInvert = true))
        assertEquals(98, mapLiveStreamPositionPercent(100, liveInvert = true))
    }

    @Test
    fun `first one percent movement from Home remains at shallow wire endpoint`() {
        assertEquals(98, mapLiveStreamPositionPercent(1, liveInvert = false))
        assertEquals(97, mapLiveStreamPositionPercent(3, liveInvert = false))
        assertEquals(98, mapLiveStreamPositionPercent(-20, liveInvert = false))
        assertEquals(2, mapLiveStreamPositionPercent(120, liveInvert = false))
    }

    @Test
    fun `live mode disables latency compensation (irregular finger timing per OSSM docs)`() {
        // Doc officielle : la compensation de latence ne doit PAS être active quand
        // l'intervalle entre commandes ne correspond pas au champ `time`. En Live, le
        // timing suit le doigt (irrégulier), donc elle est désactivée.
        assertFalse(isLatencyCompensationEnabledForLive())
    }
}
