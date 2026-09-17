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

        // Fast path: already Annex-B with 3-byte (00 00 01) or 4-byte (00 00 00 01) start code - skip processing/allocation entirely.
        if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            (data[2] == 1.toByte() || (data.size >= 4 && data[2] == 0.toByte() && data[3] == 1.toByte()))
        ) {
            return data
        }

        // Try a complete length-prefixed parse first.
        convertLengthPrefixed(data, lengthSize)?.let { return it }
        return data
    }

    /**
     * Concatenates codec configuration buffers (SPS/PPS/VPS) into a single
     * Annex-B blob, converting any length-prefixed parts first. MediaCodec
     * delivers these either as separate `csd-0`/`csd-1` buffers (Annex-B) or as
     * AVCC/HVCC length-prefixed data, so each part is normalized before merging.
     */
    fun concatAnnexB(parts: List<ByteArray>, lengthSize: Int = 4): ByteArray {
        val normalized = parts.filter { it.isNotEmpty() }.map { toAnnexB(it, lengthSize) }
        if (normalized.isEmpty()) return ByteArray(0)
        if (normalized.size == 1) return normalized[0]
        val total = normalized.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (part in normalized) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
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
