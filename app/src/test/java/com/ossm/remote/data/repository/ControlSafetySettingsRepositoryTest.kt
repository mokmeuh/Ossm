package com.ossm.remote.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ControlSafetySettingsRepositoryTest {

    @Test
    fun `Live acceleration defaults persists and clamps to UI range`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val repository = ControlSafetySettingsRepository(context)

        assertEquals(33, repository.liveAccelerationPercent.first())

        repository.setLiveAccelerationPercent(4)
        assertEquals(10, ControlSafetySettingsRepository(context).liveAccelerationPercent.first())

        repository.setLiveAccelerationPercent(140)
        assertEquals(100, repository.liveAccelerationPercent.first())
    }
}
