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
     * This is the standard Camera2 JPEG-orientation formula (the app is locked to
     * portrait, so the display rotation term is always 0):
     * - Back camera:  rotation = (sensorOrientation + deviceOrientationDeg) % 360
     * - Front camera: rotation = (sensorOrientation - deviceOrientationDeg) % 360
     *
     * The front formula is negated because OpenCam's stream pipeline horizontally
     * mirrors front frames to cancel the HAL selfie mirror. A horizontal
     * reflection conjugates 2D rotation (M * R(θ) = R(−θ) * M), so the sign of
     * the device-orientation term flips. The deviceOrientationDeg values follow
     * OrientationEventListener: 90 = left side up (clockwise tilt), 270 = right
     * side up (counter-clockwise tilt).
     *
     * For example, on standard devices:
     * - Back camera (sensor 90°):
     *   - Portrait (0°): 90°
     *   - Landscape right / clockwise tilt (90°): 180°
     *   - Inverted portrait (180°): 270°
     *   - Landscape left / counter-clockwise tilt (270°): 0°
     * - Front camera (sensor 270°):
     *   - Portrait (0°): 270°
     *   - Landscape right / clockwise tilt (90°): 180°
     *   - Inverted portrait (180°): 90°
     *   - Landscape left / counter-clockwise tilt (270°): 0°
     */
    fun calculateStreamRotation(
        sensorOrientation: Int,
        deviceOrientationDeg: Int,
        isFrontFacing: Boolean = false,
    ): Int {
        val device = normalize(deviceOrientationDeg)
        return if (isFrontFacing) {
            normalize(sensorOrientation - device)
        } else {
            normalize(sensorOrientation + device)
        }
    }

    /**
     * True when rotating by [degrees] exchanges the frame's width and height.
     *
     * A 90/270 rotation of a landscape sensor buffer produces upright portrait
     * content, so the encoded frame has to be portrait as well. Rotating the
     * content while keeping the landscape frame dimensions scales the two axes
     * by different factors, which stretches the image (16:9 -> 9:16).
     */
    fun swapsDimensions(degrees: Int): Boolean {
        val rotation = normalize(degrees)
        return rotation == 90 || rotation == 270
    }

    /**
     * Frame size produced by rotating a [width]x[height] buffer by [degrees].
     * Returns the swapped size for 90/270 and the original size for 0/180.
     */
    fun orientedSize(width: Int, height: Int, degrees: Int): Pair<Int, Int> =
        if (swapsDimensions(degrees)) height to width else width to height
}

