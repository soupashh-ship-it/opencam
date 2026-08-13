package com.opencam.encode

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object Bitstream {

    fun toByteArray(buffer: ByteBuffer, offset: Int, size: Int): ByteArray {
        require(offset >= 0 && size >= 0 && offset.toLong() + size <= buffer.capacity()) {
            "Invalid ByteBuffer range: offset=$offset size=$size capacity=${buffer.capacity()}"
        }
        val duplicate = buffer.duplicate()
        duplicate.limit(offset + size)
        duplicate.position(offset)
        return ByteArray(size).also { duplicate.get(it) }
    }

    fun toByteArray(buffer: ByteBuffer): ByteArray {
        val duplicate = buffer.duplicate()
        return ByteArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    /** Convert AVCC/HVCC length-prefixed NAL units to Annex-B start-code form. */
    fun toAnnexB(data: ByteArray, lengthSize: Int = 4): ByteArray {
        if (data.isEmpty() || lengthSize !in 1..4) return data

        // Try a complete length-prefixed parse first. This correctly handles the
        // ambiguous AVCC case where the first NAL length itself is 00 00 00 01.
        convertLengthPrefixed(data, lengthSize)?.let { return it }
        return data
    }

    private fun convertLengthPrefixed(data: ByteArray, lengthSize: Int): ByteArray? {
        val output = ByteArrayOutputStream(data.size + 64)
        var offset = 0
        var naluCount = 0
        while (offset < data.size) {
            if (offset + lengthSize > data.size) return null
            var naluLength = 0L
            repeat(lengthSize) { index ->
                naluLength = (naluLength shl 8) or (data[offset + index].toLong() and 0xFFL)
            }
            if (naluLength <= 0L || naluLength > Int.MAX_VALUE) return null
            val payloadStart = offset + lengthSize
            val payloadEnd = payloadStart.toLong() + naluLength
            if (payloadEnd > data.size) return null

            output.write(0)
            output.write(0)
            output.write(0)
            output.write(1)
            output.write(data, payloadStart, naluLength.toInt())
            offset = payloadEnd.toInt()
            naluCount++
        }
        return if (naluCount > 0 && offset == data.size) output.toByteArray() else null
    }
}
