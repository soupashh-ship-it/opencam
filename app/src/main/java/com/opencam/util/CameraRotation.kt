package com.opencam.util

/** Shared camera/display rotation math used by both the stream and preview. */
object CameraRotation {
    fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /**
     * Quantizes an orientation angle (0..359 from OrientationEventListener)
     * into one of the four cardinal rotation steps (0, 90, 180, 270).
     */
    fun roundOrientation(orientation: Int): Int {
        if (orientation < 0) return 0
        return when (normalize(orientation)) {
            in 45..134 -> 90
            in 135..224 -> 180
            in 225..314 -> 270
            else -> 0
        }
    }

    /**
     * Calculates the clockwise rotation angle in degrees required to make the stream upright
     * given the camera sensor orientation and physical device orientation.
     *
     * In OpenCam's streaming pipeline, front-camera frames are horizontally mirrored
     * to cancel the HAL selfie mirror (or apply user mirroring). Because horizontal reflection
     * conjugates 2D rotation (M * R(theta) = R(-theta) * M), the vertical alignment of the
     * image is preserved identically for both back and front cameras:
     *   rotation = (sensorOrientation + deviceOrientationDeg) % 360
     *
     * For example, on standard devices:
     * - Back camera (sensor 90°):
     *   - Portrait (0°): 90°
     *   - Landscape right / clockwise tilt (90°): 180°
     *   - Inverted portrait (180°): 270°
     *   - Landscape left / counter-clockwise tilt (270°): 0°
     * - Front camera (sensor 270°):
     *   - Portrait (0°): 270°
     *   - Landscape right / clockwise tilt (90°): 0°
     *   - Inverted portrait (180°): 90°
     *   - Landscape left / counter-clockwise tilt (270°): 180°
     */
    fun calculateStreamRotation(
        sensorOrientation: Int,
        deviceOrientationDeg: Int,
        isFrontFacing: Boolean = false,
    ): Int {
        return normalize(sensorOrientation + deviceOrientationDeg)
    }
}

