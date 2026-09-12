package com.opencam

import com.opencam.server.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun testFrameHeaderSerialization() {
        val ptsUs = 123456789L
        val len = 1024
        val header = Protocol.frameHeader(ptsUs, len)

        assertEquals(Protocol.HEADER_SIZE, header.size)

        // Verify PTS (first 8 bytes, big-endian)
        var parsedPts = 0L
        for (i in 0..7) {
            parsedPts = (parsedPts shl 8) or (header[i].toLong() and 0xFF)
        }
        assertEquals(ptsUs, parsedPts)

        // Verify Length (last 4 bytes, big-endian)
        var parsedLen = 0
        for (i in 8..11) {
            parsedLen = (parsedLen shl 8) or (header[i].toInt() and 0xFF)
        }
        assertEquals(len, parsedLen)
    }

    @Test
    fun testFramePacketPackaging() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val ptsUs = 987654321L
        val packet = Protocol.framePacket(payload, ptsUs)

        assertEquals(Protocol.HEADER_SIZE + payload.size, packet.size)
        val extractedPayload = packet.copyOfRange(Protocol.HEADER_SIZE, packet.size)
        assertArrayEquals(payload, extractedPayload)
    }

    @Test
    fun testParseVideoRequest() {
        val path = "/v5/video/jpg/1920x1080/port/4747/os/win/obs/30.0/client/studio/hdr/0/nonce/12345/"
        val request = Protocol.parseRequest("GET", path)

        assertTrue(request is Protocol.Request.Video)
        val video = request as Protocol.Request.Video
        assertEquals(Codec.MJPEG, video.codec)
        assertEquals(1920, video.width)
        assertEquals(1080, video.height)
        assertEquals(4747, video.port)
        assertEquals(false, video.hdr)
    }

    @Test
    fun testParseStatusRequest() {
        val request = Protocol.parseRequest("GET", "/v1/status")
        assertEquals(Protocol.Request.Status, request)
    }

    @Test
    fun testParseBatteryAndPingRequest() {
        assertEquals(Protocol.Request.Battery, Protocol.parseRequest("GET", "/battery"))
        assertEquals(Protocol.Request.Ping, Protocol.parseRequest("GET", "/ping"))
        assertEquals(Protocol.Request.Audio, Protocol.parseRequest("GET", "/v2/audio"))
    }

    @Test
    fun testParseSettingsRequest() {
        val path = "/v1/settings?fps=60&bitrate=12&zoom=2.5&torch=1"
        val request = Protocol.parseRequest("PUT", path)

        assertTrue(request is Protocol.Request.Settings)
        val settings = (request as Protocol.Request.Settings).params
        assertEquals("60", settings["fps"])
        assertEquals("12", settings["bitrate"])
        assertEquals("2.5", settings["zoom"])
        assertEquals("1", settings["torch"])
    }

    @Test
    fun testParseTallyRequest() {
        val req1 = Protocol.parseRequest("PUT", "/v1/tally/program")
        assertTrue(req1 is Protocol.Request.Tally)
        assertEquals("program", (req1 as Protocol.Request.Tally).state)

        val req2 = Protocol.parseRequest("PUT", "/v1/tally/invalid")
        assertEquals(Protocol.Request.Unknown, req2)
    }

    @Test
    fun testParseUnknownRequest() {
        assertEquals(Protocol.Request.Unknown, Protocol.parseRequest("GET", "/nonexistent"))
        assertEquals(Protocol.Request.Unknown, Protocol.parseRequest("POST", "/v1/status"))
    }
}
