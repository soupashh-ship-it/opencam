package com.opencam.util

/** Shared camera/display rotation math used by both the stream and preview. */
object CameraRotation {
    fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /**
     * Quantizes an orientation angle (0..359 from OrientationEventListener)
     * into one of the four cardinal rotation steps (0, 90, 180, 270).
     */
    fun roundOrientation(orientation: Int): Int = when (normalize(orientation)) {
        in 45..134 -> 90
        in 135..224 -> 180
        in 225..314 -> 270
        else -> 0
    }

    /**
     * Calculates the clockwise rotation angle in degrees required to make the stream upright
     * given the camera sensor orientation, device orientation, and whether the camera is front-facing.
     *
     * In accordance with the Android Camera2 specification (CaptureRequest.JPEG_ORIENTATION):
     * - For back-facing cameras: (sensorOrientation + deviceOrientation) % 360
     * - For front-facing cameras: (sensorOrientation - deviceOrientation) % 360
     */
    fun calculateStreamRotation(
        sensorOrientation: Int,
        deviceOrientationDeg: Int,
        isFrontFacing: Boolean,
    ): Int {
        return if (isFrontFacing) {
            normalize(sensorOrientation - deviceOrientationDeg)
        } else {
            normalize(sensorOrientation + deviceOrientationDeg)
        }
    }
}

