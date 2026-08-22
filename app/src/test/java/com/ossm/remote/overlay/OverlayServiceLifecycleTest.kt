package com.ossm.remote.overlay

import android.view.WindowManager
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.ComposeView
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OverlayServiceLifecycleTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `persisted bubble position is applied when a recreated window is set up`() = runTest {
        val service = service()
        service.overlayPreferencesRepository = mockk(relaxed = true) {
            every { bubbleX } returns flowOf(141)
            every { bubbleY } returns flowOf(287)
            every { transparencyPercent } returns flowOf(100)
        }

        invokePrivate(service, "observePreferences")
        advanceUntilIdle()
        invokePrivate(service, "setupOverlay")
        advanceUntilIdle()

        val params = getField<WindowManager.LayoutParams>(service, "params")
        assertEquals(141, params.x)
        assertEquals(287, params.y)
    }

    @Test
    fun `hiding the overlay resets expanded state before it can be shown again`() {
        val service = service()
        getField<MutableState<Boolean>>(service, "expanded\$delegate").value = true
        setField(service, "viewAttached", true)
        setField(service, "windowManager", mockk<WindowManager>(relaxed = true))
        setField(service, "composeView", mockk<ComposeView>())

        invokePrivate(service, "hideOverlay")

        assertEquals(false, getField<MutableState<Boolean>>(service, "expanded\$delegate").value)
    }

    @Test
    fun `closing the overlay stops the service immediately`() {
        val service = service()
        service.overlayPreferencesRepository = mockk(relaxed = true)

        invokePrivate(service, "closeOverlay")

        assertEquals(true, shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `snapInterpolate returns the start position when progress is zero`() {
        assertEquals(100, snapInterpolate(100, 300, 0f))
    }

    @Test
    fun `snapInterpolate returns the midpoint when progress is one half`() {
        assertEquals(200, snapInterpolate(100, 300, 0.5f))
    }

    @Test
    fun `snapInterpolate returns three quarters between start and target when progress is 75 percent`() {
        assertEquals(250, snapInterpolate(100, 300, 0.75f))
    }

    @Test
    fun `snapInterpolate returns the target position when progress is one`() {
        assertEquals(300, snapInterpolate(100, 300, 1f))
    }

    @Test
    fun `snapInterpolate clamps progress below zero to the start position`() {
        assertEquals(100, snapInterpolate(100, 300, -0.5f))
    }

    @Test
    fun `snapInterpolate clamps progress above one to the target position`() {
        assertEquals(300, snapInterpolate(100, 300, 1.5f))
    }

    private fun service(): OverlayService {
        return Robolectric.buildService(OverlayService::class.java).get()
    }

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private inline fun <reified T> getField(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T

    private fun invokePrivate(target: Any, name: String) {
        target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
    }
}
