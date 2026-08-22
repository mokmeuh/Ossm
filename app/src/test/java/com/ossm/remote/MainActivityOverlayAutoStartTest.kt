package com.ossm.remote

import com.ossm.remote.data.repository.OverlayPreferencesRepository
import com.ossm.remote.overlay.OverlayService
import com.ossm.remote.ui.screens.onOverlayToggle
import com.ossm.remote.viewmodel.OverlaySettingsViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSettings
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MainActivityOverlayAutoStartTest {

    @Before
    fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        OverlayService.isRunning = true
        OverlayService.manuallyClosed = false
        setForeground(false)
    }

    @After
    fun tearDown() {
        OverlayService.isRunning = false
        OverlayService.manuallyClosed = false
        setForeground(false)
    }

    @Test
    fun `disabled setting does not request show while service is still running`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val repository = mockk<OverlayPreferencesRepository>()
        every { repository.overlayEnabled } returns flowOf(false)
        setField(activity, "overlayPreferencesRepository", repository)

        invokePrivate(activity, "maybeAutoStartOverlay")

        assertNull(shadowOf(activity.application).nextStartedService)
    }

    @Test
    fun `enabling overlay while app is foreground does not start the service`() {
        val context = RuntimeEnvironment.getApplication()
        val viewModel = mockk<OverlaySettingsViewModel>(relaxed = true)
        setForeground(true)

        onOverlayToggle(context, viewModel, true)

        assertNull(shadowOf(context).nextStartedService)
    }

    private fun setForeground(value: Boolean) {
        val field = AppForegroundTracker::class.java.getDeclaredField("_isAppInForeground")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(AppForegroundTracker) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = value
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun invokePrivate(target: Any, name: String) {
        target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
    }
}
