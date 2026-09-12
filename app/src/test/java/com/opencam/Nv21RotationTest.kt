package com.opencam

import com.opencam.util.Nv21Rotation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Nv21RotationTest {

    @Test
    fun testZeroDegreesRotationDoesNotCrash() {
        val width = 4
        val height = 2
        val frameSize = width * height * 3 / 2 // 12 bytes
        val source = ByteArray(frameSize) { it.toByte() }
        val destination = ByteArray(frameSize)

        Nv21Rotation.rotate(source, destination, width, height, 0)
        assertArrayEquals(source, destination)

        // Also test 360 which normalizes to 0
        val destination360 = ByteArray(frameSize)
        Nv21Rotation.rotate(source, destination360, width, height, 360)
        assertArrayEquals(source, destination360)
    }

    @Test
    fun test180DegreesRotation() {
        val width = 2
        val height = 2
        val frameSize = width * height * 3 / 2 // 6 bytes
        // Y plane (4 bytes) + VU plane (2 bytes)
        val source = byteArrayOf(1, 2, 3, 4, 10, 20)
        val destination = ByteArray(frameSize)

        Nv21Rotation.rotate(source, destination, width, height, 180)

        // For 180 deg, Y plane [1, 2, 3, 4] flipped horizontally and vertically becomes [4, 3, 2, 1]
        assertEquals(4.toByte(), destination[0])
        assertEquals(3.toByte(), destination[1])
        assertEquals(2.toByte(), destination[2])
        assertEquals(1.toByte(), destination[3])
    }

    @Test
    fun test90DegreesRotation() {
        val width = 4
        val height = 2
        val frameSize = width * height * 3 / 2 // 12 bytes
        // Y plane (8 bytes: 2 rows of 4):
        // [1, 2, 3, 4]
        // [5, 6, 7, 8]
        // Chroma VU plane (4 bytes: 1 row of 2 pairs):
        // [10, 11, 20, 21]
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 20, 21)
        val destination = ByteArray(frameSize)

        Nv21Rotation.rotate(source, destination, width, height, 90)

        // Rotated 90 deg clockwise (output 2x4):
        // Row 0: [5, 1]
        // Row 1: [6, 2]
        // Row 2: [7, 3]
        // Row 3: [8, 4]
        val expectedY = byteArrayOf(5, 1, 6, 2, 7, 3, 8, 4)
        for (i in expectedY.indices) {
            assertEquals("Y plane mismatch at index $i", expectedY[i], destination[i])
        }
    }

    @Test
    fun test270DegreesRotation() {
        val width = 4
        val height = 2
        val frameSize = width * height * 3 / 2 // 12 bytes
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 20, 21)
        val destination = ByteArray(frameSize)

        Nv21Rotation.rotate(source, destination, width, height, 270)

        // Rotated 270 deg clockwise (output 2x4):
        // Row 0: [4, 8]
        // Row 1: [3, 7]
        // Row 2: [2, 6]
        // Row 3: [1, 5]
        val expectedY = byteArrayOf(4, 8, 3, 7, 2, 6, 1, 5)
        for (i in expectedY.indices) {
            assertEquals("Y plane mismatch at index $i", expectedY[i], destination[i])
        }
    }

    @Test
    fun testMirrorHorizontally() {
        val width = 4
        val height = 2
        val frameSize = width * height * 3 / 2 // 12 bytes
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 20, 21)
        val destination = ByteArray(frameSize)

        Nv21Rotation.mirrorHorizontally(source, destination, width, height)

        // Row 0 mirrored: [4, 3, 2, 1]
        // Row 1 mirrored: [8, 7, 6, 5]
        // Chroma VU pairs reversed: [20, 21, 10, 11]
        val expected = byteArrayOf(4, 3, 2, 1, 8, 7, 6, 5, 20, 21, 10, 11)
        assertArrayEquals(expected, destination)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidDimensionsThrows() {
        val source = ByteArray(10)
        val destination = ByteArray(10)
        // Odd width should throw
        Nv21Rotation.rotate(source, destination, 3, 2, 90)
    }
}
