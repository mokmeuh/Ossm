package com.ossm.remote.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservedPositionRangeTest {

    @Test
    fun `no sample keeps marker hidden`() {
        assertNull(ObservedPositionRange.empty().markerFraction)
    }

    @Test
    fun `single sample keeps marker hidden`() {
        assertNull(rangeOf(20f).markerFraction)
    }

    @Test
    fun `less than five millimetres of travel keeps marker hidden`() {
        assertNull(rangeOf(20f, 24.99f).markerFraction)
    }

    @Test
    fun `exactly five millimetres of travel shows marker`() {
        assertEquals(1f, rangeOf(20f, 25f).markerFraction!!, 0f)
    }

    @Test
    fun `last marker is normalized within observed range`() {
        assertEquals(0.5f, rangeOf(20f, 30f, 25f).markerFraction!!, 0f)
    }

    @Test
    fun `positions above one hundred use observed range without fabricated maximum`() {
        assertEquals(0.5f, rangeOf(120f, 130f, 125f).markerFraction!!, 0f)
    }

    @Test
    fun `non finite positions are ignored`() {
        val range = rangeOf(Float.NaN, Float.POSITIVE_INFINITY, 20f, 30f, 25f)

        assertEquals(0.5f, range.markerFraction!!, 0f)
    }

    private fun rangeOf(vararg positionsMm: Float): ObservedPositionRange =
        positionsMm.fold(ObservedPositionRange.empty()) { range, positionMm ->
            range.observe(positionMm)
        }
}
