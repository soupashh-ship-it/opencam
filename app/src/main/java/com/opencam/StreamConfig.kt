package com.opencam

import android.hardware.camera2.CameraMetadata
import android.media.MediaFormat

/**
 * Video codecs. [wireName] is the identifier used in the droidcam-obs-plugin
 * v5 protocol (`/v5/video/<name>/...`).
 */
enum class Codec(val wireName: String, val mime: String, val displayName: String) {
    AVC("avc", MediaFormat.MIMETYPE_VIDEO_AVC, "H.264 (AVC)"),
    HEVC("hevc", MediaFormat.MIMETYPE_VIDEO_HEVC, "H.265 (HEVC)"),
    MJPEG("jpg", "image/jpeg", "MJPEG");

    companion object {
        fun fromWire(name: String?): Codec? {
            if (name == null) return null
            return when (name.lowercase()) {
                "avc", "h264" -> AVC
                "hevc", "h265" -> HEVC
                "jpg", "mjpeg" -> MJPEG
                else -> null
            }
        }
    }
}

enum class CameraLens(val displayName: String) {
    BACK("Back"),
    FRONT("Front"),
    BACK_WIDE("Back (wide)"),
    BACK_TELE("Back (tele)"),
}

/** White-balance presets, mapped to Camera2 CONTROL_AWB_MODE values. */
enum class WhiteBalance(val displayName: String, val mode: Int) {
    AUTO("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    CLOUDY("Cloudy", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    DAYLIGHT("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    FLUORESCENT("Fluorescent", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
    INCANDESCENT("Incandescent", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
}

/** Live streaming configuration. Every change is applied by the StreamManager. */
data class StreamConfig(
    val codec: Codec = Codec.AVC,
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateMbps: Int = 8,
    val jpegQuality: Int = 85,
    val audioEnabled: Boolean = true,
    val port: Int = 4747,
    val lens: CameraLens = CameraLens.BACK,
    val keepScreenOn: Boolean = true,
    val torch: Boolean = false,
    val exposureEv: Int = 0,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val eisEnabled: Boolean = false,
    /** Horizontally flips the stream (and preview) like DroidCam's mirror effect. */
    val mirror: Boolean = false,
)

val RESOLUTION_PRESETS: List<Pair<Int, Int>> = listOf(
    640 to 480,
    1280 to 720,
    1920 to 1080,
    2560 to 1440,
    3840 to 2160,
)

val FPS_PRESETS = listOf(15, 24, 30, 60)

/** Quality presets: (label, resolution, bitrate in Mbps). */
val QUALITY_PRESETS: List<Triple<String, Pair<Int, Int>, Int>> = listOf(
    Triple("Low", 640 to 480, 2),
    Triple("Normal", 1280 to 720, 5),
    Triple("High", 1920 to 1080, 8),
    Triple("Ultra", 2560 to 1440, 12),
)
