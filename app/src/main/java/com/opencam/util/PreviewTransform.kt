package com.opencam.util

import android.graphics.Matrix
import android.view.TextureView
import kotlin.math.max

object PreviewTransform {
/**
 * Center-crops (and optionally mirrors) the camera buffer so the TextureView
 * preview is upright and undistorted. The app is locked to portrait, so no
 * display rotation needs to be applied here.
 *
 * @param mirror mirrors the preview so it matches the mirrored stream.
 * Front cameras arrive mirrored from the HAL; [frontFacing] cancels that by
 * default so the preview matches the unmirrored stream, and the toggle
 * mirrors both together.
 */
fun apply(
    textureView: TextureView,
    bufferWidth: Int,
    bufferHeight: Int,
    frontFacing: Boolean,
    mirror: Boolean = false,
) {
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return

        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        val bufferLandscape = bufferWidth > bufferHeight
        val viewLandscape = viewWidth > viewHeight

        val scaleX: Float
        val scaleY: Float

        if (bufferLandscape != viewLandscape) {
            val rotatedW = bufferHeight.toFloat()
            val rotatedH = bufferWidth.toFloat()
            val scale = max(viewWidth.toFloat() / rotatedW, viewHeight.toFloat() / rotatedH)
            scaleX = rotatedW * scale / viewWidth.toFloat()
            scaleY = rotatedH * scale / viewHeight.toFloat()
        } else {
            val scale = max(viewWidth.toFloat() / bufferWidth.toFloat(), viewHeight.toFloat() / bufferHeight.toFloat())
            scaleX = bufferWidth.toFloat() * scale / viewWidth.toFloat()
            scaleY = bufferHeight.toFloat() * scale / viewHeight.toFloat()
        }

        // A front camera + mirror enabled cancel out (both flip) so the preview
        // stays identical to what the mirror toggle produces on the stream.
        val flipX = if (frontFacing != mirror) -1f else 1f
        val matrix = Matrix().apply {
            reset()
            postScale(scaleX, scaleY, centerX, centerY)
            // The mirror flip is applied in content space (before any rotation):
            // post* concatenates rightward, so this postScale runs on the content
            // first. A view-space flip would invert the up direction whenever the
            // content is rotated 90/270 degrees (upside-down mirrored preview).
            if (flipX != 1f) {
                postScale(flipX, 1f, centerX, centerY)
            }
        }
        textureView.setTransform(matrix)
    }
}

