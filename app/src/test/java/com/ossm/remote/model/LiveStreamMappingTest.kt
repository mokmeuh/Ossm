package com.ossm.remote.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveStreamMappingTest {

    @Test
    fun `deepest slider position maps to lowest stream position`() {
        assertEquals(98, mapLiveSliderPercentToStreamPosition(100))
    }

    @Test
    fun `home slider position maps to highest stream position`() {
        assertEquals(2, mapLiveSliderPercentToStreamPosition(0))
    }

    @Test
    fun `mid slider position stays centered`() {
        assertEquals(50, mapLiveSliderPercentToStreamPosition(50))
    }

    @Test
    fun `mapping clamps out of range values`() {
        assertEquals(2, mapLiveSliderPercentToStreamPosition(-25))
        assertEquals(98, mapLiveSliderPercentToStreamPosition(250))
    }

    @Test
    fun `display percent uses home to fond semantics`() {
        assertEquals(100, mapLiveDisplayPercentToRawSliderPercent(0))
        assertEquals(50, mapLiveDisplayPercentToRawSliderPercent(50))
        assertEquals(0, mapLiveDisplayPercentToRawSliderPercent(100))
    }
}
