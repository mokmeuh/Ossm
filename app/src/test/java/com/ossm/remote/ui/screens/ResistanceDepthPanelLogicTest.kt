package com.ossm.remote.ui.screens

import com.ossm.remote.ble.DepthByLoadState
import com.ossm.remote.session.DepthByLoadSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResistanceDepthPanelLogicTest {
    private fun model(state: DepthByLoadState, canEnable: Boolean = true) = ResistanceDepthPanelModel(
        state = state,
        settings = DepthByLoadSettings(),
        canEnable = canEnable,
        onEnabledChange = {},
        onSensitivityCommit = {},
        onFloorCommit = {},
    )

    @Test
    fun `panel is hidden until the machine publishes the characteristic`() {
        assertFalse(shouldShowResistanceDepthPanel(DepthByLoadState()))
        assertTrue(shouldShowResistanceDepthPanel(DepthByLoadState(present = true)))
    }

    @Test
    fun `switch turns on only when the machine is ready and the context allows it`() {
        assertTrue(canToggleResistanceDepth(model(DepthByLoadState(present = true))))
        assertFalse(canToggleResistanceDepth(model(DepthByLoadState(present = true), canEnable = false)))
        assertFalse(canToggleResistanceDepth(model(DepthByLoadState(present = true, unavailable = true))))
    }

    @Test
    fun `switch can always be turned off while active`() {
        assertTrue(canToggleResistanceDepth(model(DepthByLoadState(present = true, enabled = true), canEnable = false)))
    }

    @Test
    fun `permille converts to a rounded percent inside 0 to 100`() {
        assertEquals(0, permilleToPercent(0))
        assertEquals(73, permilleToPercent(725))
        assertEquals(100, permilleToPercent(1_000))
        assertEquals(100, permilleToPercent(5_000))
        assertEquals(0, permilleToPercent(-20))
    }
}
