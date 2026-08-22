package com.ossm.remote.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionEntityApiCompatibilityTest {
    @Test
    fun epochDayConversionWorksOnDeviceRuntime() {
        val millis = Instant.parse("2026-08-11T02:30:00Z").toEpochMilli()
        val zone = ZoneId.systemDefault()

        assertEquals(
            Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay(),
            epochDayOf(millis)
        )
    }
}
