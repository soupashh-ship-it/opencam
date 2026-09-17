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
    private var configSent = false

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
        while (running.get()) {
            val index = try {
                mc.dequeueOutputBuffer(info, 10_000)
            } catch (_: Exception) {
                // Codec stopped/released underneath us.
                return
            }
            when (index) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> emitConfigFromFormat(mc)
                else -> if (index >= 0) {
                    // A single malformed buffer must not kill the whole drain loop
                    // (that used to stop video permanently until a full rebuild).
                    try {
                        val buffer = mc.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val payload = Bitstream.toByteArray(buffer, info.offset, info.size)
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            val annexB = Bitstream.toAnnexB(payload)
                            // SPS/PPS may arrive either as a codec-config buffer or only
                            // through INFO_OUTPUT_FORMAT_CHANGED; send whichever comes
                            // first exactly once, then never drop it for new clients.
                            if (isConfig) configSent = true
                            onPacket(annexB, info.presentationTimeUs, isConfig)
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { mc.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    /**
     * Emits SPS/PPS/VPS as a single configuration packet when the codec reports
     * them only via the output format (no BUFFER_FLAG_CODEC_CONFIG buffer).
     * Without this the client receives frames it cannot decode on such devices.
     */
    private fun emitConfigFromFormat(mc: MediaCodec) {
        if (configSent) return
        val format = try { mc.outputFormat } catch (_: Exception) { null } ?: return
        val parts = listOf("csd-0", "csd-1", "csd-2").mapNotNull { key ->
            val bytes = try {
                format.getByteBuffer(key)?.let { Bitstream.toByteArray(it) }
            } catch (_: Exception) {
                null
            }
            bytes?.takeIf { it.isNotEmpty() }
        }
        if (parts.isEmpty()) return
        configSent = true
        onPacket(Bitstream.concatAnnexB(parts), 0L, true)
    }

    @Synchronized
    fun stop() {
        running.set(false)
        drainThread?.interrupt()
        try { drainThread?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        drainThread = null
        configSent = false
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
