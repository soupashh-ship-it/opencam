package com.opencam.util

/** Allocation-free clockwise rotation for even-sized NV21 frames. */
object Nv21Rotation {
    fun rotate(
        source: ByteArray,
        destination: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ) {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        val frameSize = width * height * 3 / 2
        require(source.size >= frameSize && destination.size >= frameSize)
        val rotation = CameraRotation.normalize(rotationDegrees)
        require(rotation == 90 || rotation == 180 || rotation == 270)
        val outputWidth = if (rotation == 90 || rotation == 270) height else width
        val outputHeight = if (rotation == 90 || rotation == 270) width else height

        fun rotatePlane(
            sourceOffset: Int,
            destinationOffset: Int,
            planeWidth: Int,
            planeHeight: Int,
            bytesPerPixel: Int,
        ) {
            val rotatedWidth = if (rotation == 90 || rotation == 270) planeHeight else planeWidth
            for (y in 0 until planeHeight) {
                for (x in 0 until planeWidth) {
                    val destinationPixel = when (rotation) {
                        90 -> x * rotatedWidth + (planeHeight - 1 - y)
                        180 -> (planeHeight - 1 - y) * rotatedWidth + (planeWidth - 1 - x)
                        else -> (planeWidth - 1 - x) * rotatedWidth + y
                    }
                    val sourceIndex = sourceOffset + (y * planeWidth + x) * bytesPerPixel
                    val destinationIndex = destinationOffset + destinationPixel * bytesPerPixel
                    for (byteIndex in 0 until bytesPerPixel) {
                        destination[destinationIndex + byteIndex] = source[sourceIndex + byteIndex]
                    }
                }
            }
        }

        rotatePlane(0, 0, width, height, 1)
        rotatePlane(
            sourceOffset = width * height,
            destinationOffset = outputWidth * outputHeight,
            planeWidth = width / 2,
            planeHeight = height / 2,
            bytesPerPixel = 2,
        )
    }

    /**
     * Flips an NV21 frame left/right (mirror effect). The Y plane rows are
     * reversed element-wise; the interleaved VU chroma rows are reversed as
     * 2-byte pairs so the color channels stay paired.
     */
    fun mirrorHorizontally(
        source: ByteArray,
        destination: ByteArray,
        width: Int,
        height: Int,
    ) {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        val frameSize = width * height * 3 / 2
        require(source.size >= frameSize && destination.size >= frameSize)

        val ySize = width * height
        for (row in 0 until height) {
            val base = row * width
            for (col in 0 until width) {
                destination[base + col] = source[base + (width - 1 - col)]
            }
        }

        val chromaWidth = width / 2
        val chromaStart = ySize
        for (row in 0 until height / 2) {
            val base = chromaStart + row * chromaWidth * 2
            for (col in 0 until chromaWidth) {
                val src = base + (chromaWidth - 1 - col) * 2
                val dst = base + col * 2
                destination[dst] = source[src]
                destination[dst + 1] = source[src + 1]
            }
        }
    }
}
