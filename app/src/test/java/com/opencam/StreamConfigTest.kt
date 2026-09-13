package com.opencam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConfigTest {

    @Test
    fun testQualityPresetsContain4k() {
        val preset4k = QUALITY_PRESETS.find { it.first == "4K" }
        assertNotNull("4K quality preset should exist", preset4k)
        assertEquals(3840 to 2160, preset4k?.second)
        assertEquals(20, preset4k?.third)
    }

    @Test
    fun testCodecFromWire() {
        assertEquals(Codec.AVC, Codec.fromWire("avc"))
        assertEquals(Codec.AVC, Codec.fromWire("h264"))
        assertEquals(Codec.HEVC, Codec.fromWire("hevc"))
        assertEquals(Codec.HEVC, Codec.fromWire("h265"))
        assertEquals(Codec.MJPEG, Codec.fromWire("jpg"))
        assertEquals(Codec.MJPEG, Codec.fromWire("mjpeg"))
        assertEquals(null, Codec.fromWire("unknown"))
    }

    @Test
    fun testDefaultStreamConfig() {
        val config = StreamConfig()
        assertEquals(1920, config.width)
        assertEquals(1080, config.height)
        assertEquals(Codec.MJPEG, config.codec)
        assertEquals(4747, config.port)
        assertTrue(config.bitrateMbps in 1..50)
    }

    @Test
    fun testSanitizedConfigClampsValues() {
        val extremeConfig = StreamConfig(
            width = 99999,
            height = 0,
            fps = 500,
            bitrateMbps = 200,
            jpegQuality = -10,
            port = 80,
        ).sanitized()

        assertEquals(7680, extremeConfig.width)
        assertEquals(2, extremeConfig.height)
        assertEquals(120, extremeConfig.fps)
        assertEquals(100, extremeConfig.bitrateMbps)
        assertEquals(1, extremeConfig.jpegQuality)
        assertEquals(1024, extremeConfig.port)
    }

    @Test
    fun testResolutionAndFpsPresets() {
        assertTrue(RESOLUTION_PRESETS.contains(1920 to 1080))
        assertTrue(RESOLUTION_PRESETS.contains(3840 to 2160))
        assertTrue(FPS_PRESETS.contains(30))
        assertTrue(FPS_PRESETS.contains(60))
    }
}
