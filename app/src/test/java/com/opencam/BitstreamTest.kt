package com.opencam

import com.opencam.encode.Bitstream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BitstreamTest {

    @Test
    fun testToAnnexBWithAlreadyAnnexB() {
        // Starts with 00 00 00 01 (Annex-B 4-byte start code)
        val annexBData = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f)
        val result = Bitstream.toAnnexB(annexBData, 4)

        // Should return exact same array reference (fast path, zero allocation)
        assertSame(annexBData, result)
    }

    @Test
    fun testToAnnexBWith3ByteStartCode() {
        // Starts with 00 00 01 (Annex-B 3-byte start code commonly used for P-frames)
        val pFrameData = byteArrayOf(0x00, 0x00, 0x01, 0x41, 0x05, 0x06)
        val result = Bitstream.toAnnexB(pFrameData, 4)

        // Should return exact same array reference (fast path, zero allocation)
        assertSame(pFrameData, result)

        // Exactly 3 bytes minimum start code
        val exactThree = byteArrayOf(0x00, 0x00, 0x01)
        assertSame(exactThree, Bitstream.toAnnexB(exactThree, 4))
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

    @Test
    fun testConcatAnnexBMergesConfigBuffers() {
        // MediaCodec reports SPS and PPS separately; the client needs them in one
        // configuration packet before the first frame.
        val sps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

        assertArrayEquals(sps + pps, Bitstream.concatAnnexB(listOf(sps, pps)))
    }

    @Test
    fun testConcatAnnexBNormalizesLengthPrefixedParts() {
        val avccSps = byteArrayOf(0x00, 0x00, 0x00, 0x04, 0x67, 0x42, 0x00, 0x1F)
        val annexBPps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val expected = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F) + annexBPps

        assertArrayEquals(expected, Bitstream.concatAnnexB(listOf(avccSps, annexBPps)))
    }

    @Test
    fun testConcatAnnexBEdgeCases() {
        assertEquals(0, Bitstream.concatAnnexB(emptyList()).size)
        assertEquals(0, Bitstream.concatAnnexB(listOf(ByteArray(0), ByteArray(0))).size)

        val single = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0x01)
        // A single part is returned as-is: no copy, no re-allocation.
        assertSame(single, Bitstream.concatAnnexB(listOf(single)))
    }
}
