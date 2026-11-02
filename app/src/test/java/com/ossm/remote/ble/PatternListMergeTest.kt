package com.ossm.remote.ble

import com.ossm.remote.model.KnownAppDrivenPatterns
import com.ossm.remote.model.KnownStrokeEnginePatterns
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.PatternControlMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternListMergeTest {
    private fun device(id: Int, name: String) =
        OssmPattern("${name.replace(" ", "")}_$id", name, PatternControlMode.STROKE_ENGINE, id)

    private val sevenFromMachine = KnownStrokeEnginePatterns.map { device(it.id!!, it.name) }

    private fun merge(devicePatterns: List<OssmPattern>) =
        mergeDevicePatterns(devicePatterns, KnownStrokeEnginePatterns, KnownAppDrivenPatterns)

    @Test
    fun `a firmware that reports the seven known patterns changes nothing`() {
        val merged = merge(sevenFromMachine)

        assertEquals(KnownAppDrivenPatterns + KnownStrokeEnginePatterns, merged)
    }

    @Test
    fun `a pattern announced by a newer firmware is appended after the known ones`() {
        val merged = merge(sevenFromMachine + device(7, "Wave"))

        val last = merged.last()
        assertEquals("Wave", last.name)
        assertEquals(7, last.id)
        assertEquals(PatternControlMode.STROKE_ENGINE, last.mode)
        assertEquals(KnownAppDrivenPatterns + KnownStrokeEnginePatterns, merged.dropLast(1))
    }

    @Test
    fun `announced patterns keep the machine order whatever the report order`() {
        val merged = merge(sevenFromMachine + device(8, "Next") + device(7, "Wave"))

        assertEquals(listOf("Wave", "Next"), merged.takeLast(2).map { it.name })
    }

    @Test
    fun `known patterns keep their key and take the name reported by the machine`() {
        val renamed = sevenFromMachine.map { if (it.id == 3) it.copy(name = "Half'n'Half") else it }

        val merged = merge(renamed)

        val halfNHalf = merged.first { it.id == 3 }
        assertEquals("halfNHalf", halfNHalf.key)
        assertEquals("Half'n'Half", halfNHalf.name)
    }

    @Test
    fun `the app never offers a pattern the machine did not announce`() {
        val merged = merge(sevenFromMachine)

        assertFalse(merged.any { it.name == "Wave" })
        assertTrue(merged.none { (it.id ?: 0) >= 7 })
    }

    @Test
    fun `app driven modes are always present even when the machine reports nothing known`() {
        val merged = merge(listOf(device(7, "Wave")))

        assertTrue(merged.containsAll(KnownAppDrivenPatterns))
    }
}
