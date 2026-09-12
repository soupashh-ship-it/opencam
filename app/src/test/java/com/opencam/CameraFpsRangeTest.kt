package com.opencam

import com.opencam.camera.Camera2Controller
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFpsRangeTest {

    @Test
    fun testSelectFpsRangePrefers60Fixed() {
        val ranges = listOf(
            15 to 30,
            30 to 30,
            15 to 60,
            30 to 60,
            60 to 60,
        )
        val selected = Camera2Controller.selectFpsRange(ranges, 60)
        assertEquals(60 to 60, selected)
    }

    @Test
    fun testSelectFpsRangeFallsBackTo30to60When60FixedAbsent() {
        val ranges = listOf(
            15 to 30,
            30 to 30,
            15 to 60,
            30 to 60,
        )
        val selected = Camera2Controller.selectFpsRange(ranges, 60)
        assertEquals(30 to 60, selected)
    }

    @Test
    fun testSelectFpsRangeFallsBackTo15to60When30to60Absent() {
        val ranges = listOf(
            15 to 30,
            30 to 30,
            15 to 60,
        )
        val selected = Camera2Controller.selectFpsRange(ranges, 60)
        assertEquals(15 to 60, selected)
    }

    @Test
    fun testSelectFpsRangeFallsBackTo30WhenHardwareOnlySupports30() {
        val ranges = listOf(
            15 to 30,
            30 to 30,
        )
        val selected = Camera2Controller.selectFpsRange(ranges, 60)
        assertEquals(30 to 30, selected)
    }

    @Test
    fun testSelectFpsRangeSelects30WhenRequested() {
        val ranges = listOf(
            15 to 30,
            30 to 30,
            30 to 60,
            60 to 60,
        )
        val selected = Camera2Controller.selectFpsRange(ranges, 30)
        assertEquals(30 to 30, selected)
    }

    @Test
    fun testSelectFpsRangeHandlesEmpty() {
        assertEquals(30 to 30, Camera2Controller.selectFpsRange(emptyList(), 60))
    }
}
