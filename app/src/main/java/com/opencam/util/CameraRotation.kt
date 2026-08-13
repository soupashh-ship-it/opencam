package com.opencam.util

/** Shared camera/display rotation math used by both the stream and preview. */
object CameraRotation {
    fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
}

