package io.opencam.webcam.video;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import io.opencam.webcam.net.FrameSink;
import io.opencam.webcam.util.Logs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * H.264/AVC or H.265/HEVC encoder using {@link MediaCodec} with surface input. The camera
 * renders directly into the encoder's input surface; encoded access units are pushed to the
 * currently attached {@link FrameSink}.
 *
 * <p>The codec-config buffer (SPS/PPS) is cached and re-sent to every new client so a client
 * connecting mid-stream can still decode.
 */
public class VideoEncoderPipeline {

    public static final String AVC = "video/avc";
    public static final String HEVC = "video/hevc";

    private final String mime;
    private MediaCodec codec;
    private Surface inputSurface;
    private Thread pump;
    private volatile boolean running;

    /** The client currently receiving encoded frames; null when nobody is attached. */
    public volatile FrameSink sink;

    /** Wall-clock time (ms) of the last successful write to the sink (watchdog uses
     *  it to reclaim clients whose socket died silently — see MjpegProducer). */
    public volatile long lastWriteMs;

    /** Last codec-config buffer (SPS/PPS), replayed to each new client. */
    private volatile byte[] lastConfig;

    /** Reused per-frame output buffer — the sink writes synchronously. */
    private byte[] outBuf;

    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    public VideoEncoderPipeline(String mime) {
        this.mime = mime;
    }

    /** Start the encoder. Returns the surface the camera should render into. */
    public synchronized Surface start(int width, int height, int fps, int bitrateKbps) throws IOException {
        if (codec != null) {
            return inputSurface;
        }
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, mime);
        format.setInteger(MediaFormat.KEY_WIDTH, width);
        format.setInteger(MediaFormat.KEY_HEIGHT, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // Low-latency streaming profile. By default many SoC H.264 encoders
        // buffer several frames for rate control and use B-frames, which adds
        // 100ms-1s of end-to-end delay (exactly the lag users see in Discord).
        // CBR + realtime priority + zero B-frames + the low-latency flag keep
        // the encoder streaming with the freshest frame.
        boolean latencyKeys = true;
        if (Build.VERSION.SDK_INT >= 21) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            // KEY_PRIORITY_REALTIME == 256 (not exposed as a constant in this
            // SDK jar, so use the documented value).
            format.setInteger(MediaFormat.KEY_PRIORITY, 256);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        }

        try {
            codec = MediaCodec.createEncoderByType(mime);
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (RuntimeException e1) {
                // Some encoders reject the low-latency keys (KEY_MAX_B_FRAMES /
                // KEY_LOW_LATENCY / CBR are hints, not promises). Retry with the
                // plain baseline config so H.264 still works on those devices
                // instead of falling all the way back to jpg. A failed
                // configure() can leave the codec in an undefined state on some
                // HALs, so the retry uses a FRESH codec instance.
                Logs.i("encoder rejected latency keys — retrying baseline (" + mime + ")");
                try {
                    codec.release();
                } catch (Exception ignored) {
                }
                codec = null;
                codec = MediaCodec.createEncoderByType(mime);
                MediaFormat plain = new MediaFormat();
                plain.setString(MediaFormat.KEY_MIME, mime);
                plain.setInteger(MediaFormat.KEY_WIDTH, width);
                plain.setInteger(MediaFormat.KEY_HEIGHT, height);
                plain.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
                plain.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                plain.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                plain.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                codec.configure(plain, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            inputSurface = codec.createInputSurface();
            codec.start();
        } catch (RuntimeException e) {
            // MediaCodec.createEncoderByType/configure/start throw unchecked
            // CodecException/IllegalArgumentException when the device lacks the codec
            // or rejects the config (e.g. H.264 at an unusual size). Callers expect
            // IOException to mean "encoder unavailable" — convert so they fall back
            // to jpg instead of crashing.
            if (codec != null) {
                try {
                    codec.release();
                } catch (Exception ignored) {
                }
                codec = null;
            }
            if (inputSurface != null) {
                inputSurface.release();
                inputSurface = null;
            }
            throw new IOException("encoder unavailable: " + mime, e);
        }
        running = true;
        pump = new Thread(new Runnable() {
            @Override
            public void run() {
                pumpLoop();
            }
        }, "enc-pump");
        pump.start();
        Logs.i("encoder started: " + mime + " " + width + "x" + height + " @" + fps + " " + bitrateKbps + "kbps");
        return inputSurface;
    }

    /** Attach (or replace) the client sink, replaying the cached codec config first. */
    public void attachSink(FrameSink s) {
        sink = s;
        lastWriteMs = System.currentTimeMillis();
        byte[] config = lastConfig;
        if (config != null && s != null) {
            try {
                s.writeFrame(0L, config, config.length);
            } catch (IOException e) {
                s.close();
                if (sink == s) {
                    sink = null;
                }
            }
        }
    }

    public synchronized void stop() {
        running = false;
        if (pump != null) {
            try {
                pump.join(500);
            } catch (InterruptedException ignored) {
            }
            pump = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            codec.release();
            codec = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        sink = null;
        lastConfig = null;
    }

    private void pumpLoop() {
        while (running && codec != null) {
            int idx;
            try {
                idx = codec.dequeueOutputBuffer(info, 10000);
            } catch (IllegalStateException e) {
                // codec.stop()/release() raced the pump (normal during teardown)
                break;
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            }
            if (idx < 0) {
                continue;
            }
            ByteBuffer buffer;
            try {
                buffer = codec.getOutputBuffer(idx);
            } catch (IllegalStateException e) {
                break; // codec released while we were mid-frame
            }
            int size = info.size;
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (size > 0 && buffer != null) {
                if (outBuf == null || outBuf.length < size) {
                    outBuf = new byte[size];
                }
                buffer.position(0);
                buffer.limit(size);
                buffer.get(outBuf, 0, size);
            }
            try {
                codec.releaseOutputBuffer(idx, false);
            } catch (IllegalStateException e) {
                break;
            }

            if (size > 0 && buffer != null) {
                if (config) {
                    // The config is retained (replayed to future clients) — it must
                    // be a private copy, not the reused per-frame buffer.
                    lastConfig = Arrays.copyOf(outBuf, size);
                }
                FrameSink s = sink;
                if (s != null) {
                    try {
                        s.writeFrame(info.presentationTimeUs, outBuf, size);
                        lastWriteMs = System.currentTimeMillis();
                    } catch (IOException e) {
                        s.close();
                        if (sink == s) {
                            sink = null;
                        }
                    }
                }
            }
            if (eos) {
                break;
            }
        }
        Logs.i("encoder pump stopped");
    }
}
