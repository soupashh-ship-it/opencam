package com.opencam.encode

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/** Microphone capture with AAC-LC encoding for the DroidCam OBS wire protocol. */
class AudioEncoder(
    private val sampleRate: Int = 48_000,
    private val channelCount: Int = 1,
    private val onPacket: (data: ByteArray, ptsUs: Long, isConfig: Boolean) -> Unit,
) {
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var captureThread: Thread? = null
    private var drainThread: Thread? = null
    private val running = AtomicBoolean(false)
    private var configSent = false
    private var totalSamplesRead = 0L

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (running.get() || audioRecord != null || mediaCodec != null) return false
        return try {
            val channelMask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return false

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 8192),
            )
            audioRecord = record // retain before any subsequent operation can fail
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                releaseInternal()
                return false
            }

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
            }
            val mc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec = mc // retain before configure/start
            mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mc.start()
            record.startRecording()

            configSent = false
            totalSamplesRead = 0L
            running.set(true)
            captureThread = Thread({ captureLoop(record, mc) }, "opencam-audio-capture").apply { start() }
            drainThread = Thread({ drainLoop(mc) }, "opencam-audio-encode").apply { start() }
            true
        } catch (_: Throwable) {
            running.set(false)
            releaseInternal()
            false
        }
    }

    private fun captureLoop(record: AudioRecord, mc: MediaCodec) {
        val pcm = ByteArray(4096)
        val bytesPerSampleFrame = 2 * channelCount
        try {
            while (running.get()) {
                val read = record.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                val ptsUs = totalSamplesRead * 1_000_000L / sampleRate
                totalSamplesRead += read / bytesPerSampleFrame
                val index = mc.dequeueInputBuffer(20_000)
                if (index < 0) continue
                val inBuf = mc.getInputBuffer(index)
                if (inBuf == null) {
                    mc.queueInputBuffer(index, 0, 0, ptsUs, 0)
                    continue
                }
                inBuf.clear()
                inBuf.put(pcm, 0, read)
                mc.queueInputBuffer(index, 0, read, ptsUs, 0)
            }
        } catch (_: Exception) {
            // Stopped.
        }
    }

    private fun drainLoop(mc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                when (val index = mc.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> ensureConfigFromFormat(mc.outputFormat)
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        try {
                            val buffer = mc.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                val payload = Bitstream.toByteArray(buffer, info.offset, info.size)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    configSent = true
                                    onPacket(payload, info.presentationTimeUs, true)
                                } else {
                                    onPacket(stripAdts(payload), info.presentationTimeUs, false)
                                }
                            }
                        } finally {
                            mc.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Stopped.
        }
    }

    private fun stripAdts(data: ByteArray): ByteArray {
        if (data.size < 7) return data
        if ((data[0].toInt() and 0xFF) != 0xFF || (data[1].toInt() and 0xF0) != 0xF0) return data
        val protectionAbsent = (data[1].toInt() and 0x01) != 0
        val headerLen = if (protectionAbsent) 7 else 9
        return if (data.size > headerLen) data.copyOfRange(headerLen, data.size) else data
    }

    private fun ensureConfigFromFormat(format: MediaFormat) {
        if (configSent) return
        val csd = format.getByteBuffer("csd-0") ?: return
        if (!csd.hasRemaining()) return
        configSent = true
        onPacket(Bitstream.toByteArray(csd), 0L, true)
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}
        captureThread?.interrupt()
        drainThread?.interrupt()
        try { captureThread?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { drainThread?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        captureThread = null
        drainThread = null
        releaseInternal()
    }

    private fun releaseInternal() {
        val record = audioRecord
        audioRecord = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}

        val codec = mediaCodec
        mediaCodec = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
    }
}
