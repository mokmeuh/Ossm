package com.ossm.remote.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedKnobConfigTest {
    @Test
    fun `only an explicit false readback confirms independent speed`() {
        assertTrue(isSpeedKnobIndependentValue("false".toByteArray()))
        assertTrue(isSpeedKnobIndependentValue(" FALSE\n".toByteArray()))
        assertFalse(isSpeedKnobIndependentValue("true".toByteArray()))
        assertFalse(isSpeedKnobIndependentValue(null))
    }
}
