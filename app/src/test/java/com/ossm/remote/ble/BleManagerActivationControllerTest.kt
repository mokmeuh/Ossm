package com.ossm.remote.ble

import com.ossm.remote.model.MachineState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleManagerActivationControllerTest {
    @Test
    fun `stale activation token cannot finish after a newer activation begins`() {
        val controller = StrokeEngineActivationController()

        val firstToken = controller.begin()
        val secondToken = controller.begin()

        assertFalse(controller.finish(firstToken))
        assertTrue(controller.isCurrent(secondToken))
        assertTrue(controller.finish(secondToken))
    }

    @Test
    fun `stroke parameters require a matching ready stroke engine notification`() {
        assertFalse(
            isStrokeEngineParamsConfirmed(
                MachineState(state = "menu", depth = 80, stroke = 60),
                depthPercent = 80,
                strokePercent = 60,
            )
        )
        assertFalse(
            isStrokeEngineParamsConfirmed(
                MachineState(state = "strokeEngine.preflight", depth = 80, stroke = 60),
                depthPercent = 80,
                strokePercent = 60,
            )
        )
        assertTrue(
            isStrokeEngineParamsConfirmed(
                MachineState(state = "strokeEngine.idle", depth = 80, stroke = 60),
                depthPercent = 80,
                strokePercent = 60,
            )
        )
    }
}
