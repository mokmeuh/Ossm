package com.ossm.remote.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BleStatePayloadParserTest {
    @Test
    fun `recovers live state and controls from an MTU truncated payload`() {
        val payload =
            "{\"timestamp\":221547582,\"state\":\"streaming.idle\",\"speed\":100," +
                "\"stroke\":100,\"sensation\":50,\"depth\":100,\"buffer\":0"

        val state = parseMachineStatePayload(payload)

        assertNotNull(state)
        assertEquals("streaming.idle", state?.state)
        assertEquals(100, state?.speed)
        assertEquals(100, state?.stroke)
        assertEquals(50, state?.sensation)
        assertEquals(100, state?.depth)
    }

    @Test
    fun `rejects a payload without a complete state field`() {
        assertNull(parseMachineStatePayload("{\"timestamp\":1,\"state\":\"streaming"))
    }
}
