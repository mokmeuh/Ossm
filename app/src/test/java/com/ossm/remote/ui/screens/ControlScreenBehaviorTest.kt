package com.ossm.remote.ui.screens

import com.ossm.remote.R
import com.ossm.remote.model.KnownStrokeEnginePatterns
import com.ossm.remote.model.KnownStreamingPattern
import com.ossm.remote.ui.components.liveControlMinimumHeight
import com.ossm.remote.ui.components.liveControlMinimumWidth
import com.ossm.remote.ui.components.liveEndpointLabels
import com.ossm.remote.ui.components.liveSliderThumbRadius
import com.ossm.remote.ui.components.liveSliderTrackThickness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlScreenBehaviorTest {

    @Test
    fun `simple stroke does not show sensation slider`() {
        val simpleStroke = KnownStrokeEnginePatterns.first { it.key == "simpleStroke" }

        assertFalse(shouldShowSensationSlider(simpleStroke))
    }

    @Test
    fun `other stroke engine patterns keep sensation slider`() {
        val roboStroke = KnownStrokeEnginePatterns.first { it.key == "roboStroke" }

        assertTrue(shouldShowSensationSlider(roboStroke))
    }

    @Test
    fun `Live hides stop pause and save controls`() {
        val visibility = controlActionVisibility(KnownStreamingPattern)

        assertFalse(visibility.showStop)
        assertFalse(visibility.showPause)
        assertFalse(visibility.showSave)
    }

    @Test
    fun `invert direction setting is available only for Live`() {
        val simpleStroke = KnownStrokeEnginePatterns.first { it.key == "simpleStroke" }
        val roboStroke = KnownStrokeEnginePatterns.first { it.key == "roboStroke" }

        assertTrue(shouldShowLiveInvertSetting(KnownStreamingPattern))
        assertFalse(shouldShowLiveInvertSetting(simpleStroke))
        assertFalse(shouldShowLiveInvertSetting(roboStroke))
        assertFalse(shouldShowLiveInvertSetting(null))
    }

    @Test
    fun `precision defaults off for Live and on for stroke engine patterns`() {
        val simpleStroke = KnownStrokeEnginePatterns.first { it.key == "simpleStroke" }

        assertFalse(isPrecisionEnabled(KnownStreamingPattern, emptyMap()))
        assertTrue(isPrecisionEnabled(simpleStroke, emptyMap()))
    }

    @Test
    fun `precision overrides remain isolated per pattern`() {
        val simpleStroke = KnownStrokeEnginePatterns.first { it.key == "simpleStroke" }
        val overrides = mutableMapOf<String, Boolean>()

        overrides[KnownStreamingPattern.key] = true
        assertTrue(isPrecisionEnabled(KnownStreamingPattern, overrides))
        assertTrue(isPrecisionEnabled(simpleStroke, overrides))

        overrides[simpleStroke.key] = false
        assertTrue(isPrecisionEnabled(KnownStreamingPattern, overrides))
        assertFalse(isPrecisionEnabled(simpleStroke, overrides))
    }

    @Test
    fun `Live surface has useful minimum dimensions in portrait and landscape`() {
        assertTrue(liveControlMinimumHeight(compact = false).value >= 420f)
        assertTrue(liveControlMinimumHeight(compact = true).value >= 180f)
        assertTrue(liveControlMinimumWidth(compact = false).value >= 280f)
        assertTrue(liveControlMinimumWidth(compact = true).value >= 280f)
        assertTrue(liveSliderTrackThickness(compact = false).value >= 18f)
        assertTrue(liveSliderTrackThickness(compact = true).value >= 16f)
        assertTrue(liveSliderThumbRadius(compact = false).value >= 28f)
        assertTrue(liveSliderThumbRadius(compact = true).value >= 22f)
    }

    @Test
    fun `Live endpoint labels place Fond above Home`() {
        val labels = liveEndpointLabels()

        assertEquals(R.string.ctl_live_deep, labels.top)
        assertEquals(R.string.ctl_live_home, labels.bottom)
    }

    @Test
    fun `control header renders only the active mode name`() {
        assertEquals(KnownStreamingPattern.name, controlHeaderTitle(KnownStreamingPattern))
        assertEquals("", controlHeaderTitle(null))
    }
}
