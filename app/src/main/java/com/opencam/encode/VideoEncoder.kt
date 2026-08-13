package com.opencam.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.opencam.Codec
import java.util.concurrent.atomic.AtomicBoolean

/** Hardware H.264/HEVC encoder backed by a Surface input. */
class VideoEncoder(
    private val codec: Codec,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onPacket: (data: ByteArray, ptsUs: Long, isConfig: Boolean) -> Unit,
) {
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null
    private val running = AtomicBoolean(false)

    val surface: Surface? get() = inputSurface

    @Synchronized
    fun start(): Boolean {
        if (running.get() || mediaCodec != null) return false
        return try {
            require(width > 0 && height > 0 && fps > 0 && bitrate > 0)
            val format = MediaFormat.createVideoFormat(codec.mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            val mc = MediaCodec.createEncoderByType(codec.mime)
            mediaCodec = mc // retain immediately so every failure path can release the native handle
            mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mc.createInputSurface()
            mc.start()
            running.set(true)
            drainThread = Thread({ drainLoop(mc) }, "opencam-video-encode").apply { start() }
            true
        } catch (_: Throwable) {
            running.set(false)
            releaseInternal()
            false
        }
    }

    private fun drainLoop(mc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                when (val index = mc.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        try {
                            val buffer = mc.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                val payload = Bitstream.toByteArray(buffer, info.offset, info.size)
                                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                onPacket(Bitstream.toAnnexB(payload), info.presentationTimeUs, isConfig)
                            }
                        } finally {
                            mc.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Codec was stopped or the output surface disappeared.
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        drainThread?.interrupt()
        try { drainThread?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        drainThread = null
        releaseInternal()
    }

    private fun releaseInternal() {
        val surface = inputSurface
        inputSurface = null
        try { surface?.release() } catch (_: Exception) {}

        val codec = mediaCodec
        mediaCodec = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
    }
}
