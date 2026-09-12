package com.opencam

import com.opencam.encode.Bitstream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BitstreamTest {

    @Test
    fun testToAnnexBWithAlreadyAnnexB() {
        // Starts with 00 00 00 01 (Annex-B 4-byte start code)
        val annexBData = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f)
        val result = Bitstream.toAnnexB(annexBData, 4)

        // Should return exact same array reference (fast path, zero allocation)
        assertEquals(annexBData, result)
    }

    @Test
    fun testToAnnexBWithLengthPrefixedAvcc() {
        // AVCC 4-byte length prefix for NALU of length 4: [0x00, 0x00, 0x00, 0x04, 0x65, 0x01, 0x02, 0x03]
        val avccData = byteArrayOf(0x00, 0x00, 0x00, 0x04, 0x65, 0x01, 0x02, 0x03)
        val expected = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x01, 0x02, 0x03)

        val result = Bitstream.toAnnexB(avccData, 4)
        assertArrayEquals(expected, result)
    }

    @Test
    fun testToAnnexBEmptyOrInvalid() {
        val empty = byteArrayOf()
        assertEquals(empty, Bitstream.toAnnexB(empty, 4))

        val small = byteArrayOf(0x01, 0x02)
        // Corrupt length or too small: returns original data as fallback
        assertArrayEquals(small, Bitstream.toAnnexB(small, 4))
    }
}
