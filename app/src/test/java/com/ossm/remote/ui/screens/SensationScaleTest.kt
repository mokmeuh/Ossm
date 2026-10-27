package com.ossm.remote.ui.screens

import com.ossm.remote.R
import com.ossm.remote.remote.RemoteSessionView
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 2.3.16: centred sensation scale (−100…0…+100) and session banner text. */
class SensationScaleTest {

    @Test
    fun `signed labels are centred on neutral`() {
        assertEquals("0", signedSensationLabel(0.5f))
        assertEquals("+100", signedSensationLabel(1f))
        assertEquals("−100", signedSensationLabel(0f))
        assertEquals("+40", signedSensationLabel(0.7f))
        assertEquals("−40", signedSensationLabel(0.3f))
        assertEquals(-60, signedSensationPercent(0.2f))
    }

    @Test
    fun `banner shows intensity and range on the signed scale`() {
        val view = RemoteSessionView(active = true, speedMax = 0.5f, sensationMin = 0.3f, sensationMax = 0.7f)
        assertArrayEquals(arrayOf("±40", "−40", "+40"), remoteSensationBannerArgs(view))
        assertEquals("Sensation ±40 (−40–+40)", "Sensation %1\$s (%2\$s–%3\$s)".format(*remoteSensationBannerArgs(view)))

        val neutral = RemoteSessionView(active = true, sensationMin = 0.5f, sensationMax = 0.5f)
        assertArrayEquals(arrayOf("±0", "0", "0"), remoteSensationBannerArgs(neutral))
    }

    @Test
    fun `session draft keeps the side the user dragged to`() {
        // User dragged left to 0.2 -> intensity 0.3: the draft stays at 0.2 (no jump to 0.8).
        assertEquals(0.2f, sessionSensationDraft(0.2f, 0.3f), 0.0001f)
        // Intensity changed elsewhere: keep the current side.
        assertEquals(0.4f, sessionSensationDraft(0.3f, 0.1f), 0.0001f)
        assertEquals(0.9f, sessionSensationDraft(0.6f, 0.4f), 0.0001f)
    }

    @Test
    fun `legend only for patterns whose firmware sensation is known`() {
        assertEquals(R.string.ctl_sensation_legend_out_in, sensationLegendRes("teasingPounding"))
        assertEquals(R.string.ctl_sensation_legend_out_in, sensationLegendRes("halfNHalf"))
        listOf("simpleStroke", "roboStroke", "deeper", "stopNGo", "insist", null).forEach {
            assertNull(it, sensationLegendRes(it))
        }
    }
}
