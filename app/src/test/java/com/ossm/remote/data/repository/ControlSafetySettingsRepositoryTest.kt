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

    @Test
    fun `Live invert preserves stored choice when repository is recreated`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        ControlSafetySettingsRepository(context).setLiveInvertEnabled(true)

        val recreatedRepository = ControlSafetySettingsRepository(context)
        assertEquals(true, recreatedRepository.liveInvertEnabled.first())

        recreatedRepository.setLiveInvertEnabled(false)
        assertEquals(false, ControlSafetySettingsRepository(context).liveInvertEnabled.first())
    }

    @Test
    fun `precision false survives repository recreation and excludes unrelated keys`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val repository = ControlSafetySettingsRepository(context)
        val before = repository.precisionOverrides.first()
        repository.setPrecisionEnabled("simpleStroke", false)
        repository.setLiveInvertEnabled(true)
        assertEquals(
            before + ("simpleStroke" to false),
            ControlSafetySettingsRepository(context).precisionOverrides.first()
        )
    }

    @Test
    fun `precision keys remain independent across updates and recreation`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val repository = ControlSafetySettingsRepository(context)
        repository.setPrecisionEnabled("streaming", true)
        repository.setPrecisionEnabled("simpleStroke", false)
        assertEquals(
            mapOf("streaming" to true, "simpleStroke" to false),
            ControlSafetySettingsRepository(context).precisionOverrides.first()
        )
        repository.setPrecisionEnabled("simpleStroke", true)
        repository.setPrecisionEnabled("streaming", false)
        assertEquals(
            mapOf("streaming" to false, "simpleStroke" to true),
            ControlSafetySettingsRepository(context).precisionOverrides.first()
        )
    }
}
