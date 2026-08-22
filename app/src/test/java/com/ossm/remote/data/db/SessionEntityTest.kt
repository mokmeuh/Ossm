package com.ossm.remote.data.db

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionEntityTest {
    @Test
    fun `epoch day preserves the local calendar date`() {
        val millis = Instant.parse("2026-08-11T02:30:00Z").toEpochMilli()
        val zone = ZoneId.systemDefault()

        assertEquals(
            Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay(),
            epochDayOf(millis)
        )
    }
}
