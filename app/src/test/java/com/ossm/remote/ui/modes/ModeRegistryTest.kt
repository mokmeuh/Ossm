package com.ossm.remote.ui.modes

import com.ossm.remote.model.KnownAutoRandomPattern
import com.ossm.remote.model.KnownFallbackPatterns
import com.ossm.remote.model.KnownNaturalPattern
import com.ossm.remote.model.KnownProgressivePattern
import com.ossm.remote.model.KnownSimplePenetrationPattern
import com.ossm.remote.model.KnownStreamingPattern
import com.ossm.remote.model.KnownStrokeEnginePatterns
import com.ossm.remote.model.KnownVoicePattern
import com.ossm.remote.ui.screens.shouldShowSensationSlider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeRegistryTest {
    @Test
    fun everyKnownModeHasADescriptor() {
        (KnownFallbackPatterns + KnownSimplePenetrationPattern).forEach {
            assertNotNull("pas de descripteur pour ${it.key}", ModeRegistry.of(it))
        }
    }

    @Test
    fun noModeMeansNoDescriptor() {
        assertNull(ModeRegistry.of(null))
    }

    @Test
    fun sensationChannelAgreesWithTheSensationSliderRule() {
        KnownStrokeEnginePatterns.forEach {
            assertEquals(
                "canal sensation de ${it.key}",
                shouldShowSensationSlider(it),
                ModeRegistry.of(it)!!.exposes(ModeChannel.SENSATION)
            )
        }
    }

    @Test
    fun liveAndVoiceNeedTheStreamingGateAndExposeNoSlider() {
        listOf(KnownStreamingPattern, KnownVoicePattern).forEach {
            val d = ModeRegistry.of(it)!!
            assertTrue(d.needsStreaming)
            assertTrue(d.channels.isEmpty())
        }
    }

    @Test
    fun machineDrivenModesAreNotAppDriven() {
        KnownStrokeEnginePatterns.forEach { assertFalse(ModeRegistry.of(it)!!.appDriven) }
        assertTrue(ModeRegistry.of(KnownNaturalPattern)!!.appDriven)
        assertTrue(ModeRegistry.of(KnownAutoRandomPattern)!!.appDriven)
        assertTrue(ModeRegistry.of(KnownProgressivePattern)!!.appDriven)
    }

    @Test
    fun unknownModeContextHidesNothing() {
        assertTrue(ModeContext.Idle.exposes(ModeChannel.SENSATION))
    }
}
