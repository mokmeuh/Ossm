package com.ossm.remote.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamingBehaviorTest {

    @Test
    fun `live pad at 100 percent drives deep end (stream 2), 0 percent drives home (stream 98)`() {
        // Calibration appareil : pad 100 % (doigt en haut) -> FOND (stream bas) ;
        // pad 0 % -> HOME (stream haut). Mapping inverse par defaut.
        assertEquals(98, mapLiveStreamPositionPercent(0, liveInvert = false))
        assertEquals(50, mapLiveStreamPositionPercent(50, liveInvert = false))
        assertEquals(2, mapLiveStreamPositionPercent(100, liveInvert = false))
    }

    @Test
    fun `live mode disables latency compensation (irregular finger timing per OSSM docs)`() {
        // Doc officielle : la compensation de latence ne doit PAS être active quand
        // l'intervalle entre commandes ne correspond pas au champ `time`. En Live, le
        // timing suit le doigt (irrégulier), donc elle est désactivée.
        assertFalse(isLatencyCompensationEnabledForLive())
    }
}
