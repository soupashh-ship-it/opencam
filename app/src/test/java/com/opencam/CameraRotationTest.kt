package com.opencam

import com.opencam.util.CameraRotation
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRotationTest {

    @Test
    fun testNormalizeDegrees() {
        assertEquals(0, CameraRotation.normalize(0))
        assertEquals(90, CameraRotation.normalize(90))
        assertEquals(180, CameraRotation.normalize(180))
        assertEquals(270, CameraRotation.normalize(270))
        assertEquals(0, CameraRotation.normalize(360))
        assertEquals(90, CameraRotation.normalize(450))
        assertEquals(0, CameraRotation.normalize(720))
        assertEquals(270, CameraRotation.normalize(-90))
        assertEquals(180, CameraRotation.normalize(-180))
        assertEquals(90, CameraRotation.normalize(-270))
        assertEquals(0, CameraRotation.normalize(-360))
        assertEquals(270, CameraRotation.normalize(-450))
    }

    @Test
    fun testRoundOrientationBoundaries() {
        // Natural portrait range [315..359, 0..44]
        assertEquals(0, CameraRotation.roundOrientation(0))
        assertEquals(0, CameraRotation.roundOrientation(44))
        assertEquals(0, CameraRotation.roundOrientation(315))
        assertEquals(0, CameraRotation.roundOrientation(359))

        // Clockwise 90 / Landscape Right [45..134]
        assertEquals(90, CameraRotation.roundOrientation(45))
        assertEquals(90, CameraRotation.roundOrientation(90))
        assertEquals(90, CameraRotation.roundOrientation(134))

        // Inverted portrait [135..224]
        assertEquals(180, CameraRotation.roundOrientation(135))
        assertEquals(180, CameraRotation.roundOrientation(180))
        assertEquals(180, CameraRotation.roundOrientation(224))

        // Counter-clockwise 90 / Landscape Left [225..314]
        assertEquals(270, CameraRotation.roundOrientation(225))
        assertEquals(270, CameraRotation.roundOrientation(270))
        assertEquals(270, CameraRotation.roundOrientation(314))
    }

    @Test
    fun testBackCameraStandardSensor90Degrees() {
        val sensor = 90
        val isFront = false

        // 1. Portrait hold: device orientation = 0
        // Image must rotate 90 deg clockwise to align sensor native top (at phone right) to screen top.
        assertEquals(90, CameraRotation.calculateStreamRotation(sensor, 0, isFront))

        // 2. Landscape Right hold (clockwise tilt, left side at top): device orientation = 90
        // Phone right edge is pointing down, so sensor native top is pointing down.
        // Image must rotate 180 deg to be upright.
        assertEquals(180, CameraRotation.calculateStreamRotation(sensor, 90, isFront))

        // 3. Upside-down portrait hold: device orientation = 180
        // Phone right edge is pointing left. Image must rotate 270 deg to be upright.
        assertEquals(270, CameraRotation.calculateStreamRotation(sensor, 180, isFront))

        // 4. Landscape Left hold (counter-clockwise tilt, right side at top): device orientation = 270
        // Phone right edge is pointing up, so sensor native top is pointing up.
        // Image is natively upright: rotation must be 0 deg.
        assertEquals(0, CameraRotation.calculateStreamRotation(sensor, 270, isFront))
    }

    @Test
    fun testFrontCameraStandardSensor270Degrees() {
        val sensor = 270
        val isFront = true

        // 1. Portrait hold: device orientation = 0
        assertEquals(270, CameraRotation.calculateStreamRotation(sensor, 0, isFront))

        // 2. Landscape Right hold (clockwise tilt): device orientation = 90
        // (270 - 90) % 360 = 180 deg
        assertEquals(180, CameraRotation.calculateStreamRotation(sensor, 90, isFront))

        // 3. Upside-down portrait hold: device orientation = 180
        // (270 - 180) % 360 = 90 deg
        assertEquals(90, CameraRotation.calculateStreamRotation(sensor, 180, isFront))

        // 4. Landscape Left hold (counter-clockwise tilt): device orientation = 270
        // (270 - 270) % 360 = 0 deg
        assertEquals(0, CameraRotation.calculateStreamRotation(sensor, 270, isFront))
    }

    @Test
    fun testExternalOrTabletCameraSensor0Degrees() {
        val sensor = 0

        // Back-facing
        assertEquals(0, CameraRotation.calculateStreamRotation(sensor, 0, isFrontFacing = false))
        assertEquals(90, CameraRotation.calculateStreamRotation(sensor, 90, isFrontFacing = false))
        assertEquals(180, CameraRotation.calculateStreamRotation(sensor, 180, isFrontFacing = false))
        assertEquals(270, CameraRotation.calculateStreamRotation(sensor, 270, isFrontFacing = false))

        // Front-facing
        assertEquals(0, CameraRotation.calculateStreamRotation(sensor, 0, isFrontFacing = true))
        assertEquals(270, CameraRotation.calculateStreamRotation(sensor, 90, isFrontFacing = true))
        assertEquals(180, CameraRotation.calculateStreamRotation(sensor, 180, isFrontFacing = true))
        assertEquals(90, CameraRotation.calculateStreamRotation(sensor, 270, isFrontFacing = true))
    }

    @Test
    fun testNonStandardSensorOrientations() {
        // Back camera with 270 degree sensor (e.g. Nexus 5X)
        assertEquals(270, CameraRotation.calculateStreamRotation(270, 0, isFrontFacing = false))
        assertEquals(0, CameraRotation.calculateStreamRotation(270, 90, isFrontFacing = false))
        assertEquals(90, CameraRotation.calculateStreamRotation(270, 180, isFrontFacing = false))
        assertEquals(180, CameraRotation.calculateStreamRotation(270, 270, isFrontFacing = false))

        // Front camera with 90 degree sensor
        assertEquals(90, CameraRotation.calculateStreamRotation(90, 0, isFrontFacing = true))
        assertEquals(0, CameraRotation.calculateStreamRotation(90, 90, isFrontFacing = true))
        assertEquals(270, CameraRotation.calculateStreamRotation(90, 180, isFrontFacing = true))
        assertEquals(180, CameraRotation.calculateStreamRotation(90, 270, isFrontFacing = true))
    }
}
