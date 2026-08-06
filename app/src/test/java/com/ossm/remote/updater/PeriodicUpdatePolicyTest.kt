package com.ossm.remote.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodicUpdatePolicyTest {

    private val manifest = UpdateManifest(
        versionCode = 116,
        versionName = "1.28.24",
        apkUrl = "http://192.168.1.2:8080/OSSM-Remote-v1.28.24-debug.apk",
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    )

    @Test
    fun `periodic check runs only when enabled`() {
        assertTrue(PeriodicUpdatePolicy.shouldRun(true))
        assertFalse(PeriodicUpdatePolicy.shouldRun(false))
    }

    @Test
    fun `notification is sent only when an update is available`() {
        assertTrue(PeriodicUpdatePolicy.shouldNotify(UpdateCheckResult.Available(manifest)))
        assertFalse(PeriodicUpdatePolicy.shouldNotify(UpdateCheckResult.NoUpdate))
        assertFalse(PeriodicUpdatePolicy.shouldNotify(UpdateCheckResult.InvalidManifest("bad")))
        assertFalse(PeriodicUpdatePolicy.shouldNotify(UpdateCheckResult.ServerUnavailable))
    }

    @Test
    fun `cadence is twelve hours with a fixed unique work name`() {
        assertEquals(12L, PeriodicUpdatePolicy.INTERVAL_HOURS)
        assertEquals("periodic-update-check", PeriodicUpdatePolicy.UNIQUE_WORK_NAME)
    }
}
