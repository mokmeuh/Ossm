package com.ossm.remote.ble

import com.ossm.remote.model.StrokeEngineCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class BleManagerSensationTest {

    @Test
    fun `sensation command converts scalar to legacy percent`() {
        val command = buildSensationCommand(StrokeEngineCommand(sensation = 0.439f))

        assertEquals("set:sensation:43", command)
    }

    @Test
    fun `sensation command clamps scalar to protocol bounds`() {
        val command = buildSensationCommand(StrokeEngineCommand(sensation = 1.5f))

        assertEquals("set:sensation:100", command)
    }

    @Test
    fun `Live acceleration command formats and clamps protocol percentage`() {
        assertEquals("set:acceleration:33", buildLiveAccelerationCommand(33))
        assertEquals("set:acceleration:10", buildLiveAccelerationCommand(-1))
        assertEquals("set:acceleration:100", buildLiveAccelerationCommand(101))
    }
}
