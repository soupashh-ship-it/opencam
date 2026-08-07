package io.opencam.webcam.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;

import io.opencam.webcam.net.FrameSink;
import io.opencam.webcam.util.Logs;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Captures microphone PCM via {@link AudioRecord} and encodes it to AAC-LC
 * ({@code audio/mp4a-latm}, profile 2) with {@link MediaCodec}. Raw AAC frames are pushed to the
 * currently attached {@link FrameSink}; the client decodes them using the negotiated sample rate.
 */
public class AudioStream {

    private volatile FrameSink sink;

    /** Wall-clock time (ms) of the last successful write to the sink (watchdog uses
     *  it to reclaim clients whose socket died silently). */
    public volatile long lastWriteMs;

    private AudioRecord recorder;
    private MediaCodec codec;
    private Thread thread;
    private volatile boolean running;

    private final int sampleRate;
    private final int channels;
    private final int bitrateKbps;
    private final int source;

    public AudioStream(int sampleRate, int channels, int bitrateKbps, int source) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitrateKbps = bitrateKbps;
        this.source = source;
    }

    public void attachSink(FrameSink s) {
        sink = s;
        lastWriteMs = System.currentTimeMillis();
    }

    /** True while an audio client is attached. */
    public boolean hasClient() {
        return sink != null;
    }

    public void start() throws IOException {
        if (running) {
            return;
        }
        int channelMask = channels > 1 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            minBuf = sampleRate / 10 * 2 * channels;
        }
        recorder = new AudioRecord(source, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            recorder = null;
            throw new IOException("AudioRecord failed to initialize");
        }

        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf);
        codec = MediaCodec.createEncoderByType("audio/mp4a-latm");
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                pump();
            }
        }, "audio-stream");
        thread.start();
        Logs.i("audio started: " + sampleRate + "Hz " + channels + "ch " + bitrateKbps + "kbps");
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            recorder.release();
            recorder = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            codec.release();
            codec = null;
        }
        sink = null;
    }

    private void pump() {
        try {
            recorder.startRecording();
        } catch (Exception e) {
            Logs.e("audio record start failed", e);
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] pcm = new byte[8192];
        // Reused per-frame AAC buffer — the sink writes synchronously, so it can be
        // handed to the next frame without allocating a fresh array each time.
        byte[] aac = new byte[1024];

        while (running) {
            try {
                // feed the encoder
                int n = recorder.read(pcm, 0, pcm.length);
                if (n <= 0) {
                    continue;
                }
                int inIdx;
                while ((inIdx = codec.dequeueInputBuffer(1000)) < 0) {
                    if (!running) {
                        return;
                    }
                }
                ByteBuffer input = codec.getInputBuffer(inIdx);
                input.clear();
                // Guard against devices whose AAC input buffers are smaller than the PCM chunk.
                int toWrite = Math.min(n, input.remaining());
                if (toWrite > 0) {
                    input.put(pcm, 0, toWrite);
                }
                codec.queueInputBuffer(inIdx, 0, toWrite, System.nanoTime() / 1000, 0);

                // drain the encoder
                int outIdx;
                while ((outIdx = codec.dequeueOutputBuffer(info, 1000)) >= 0) {
                    ByteBuffer output = codec.getOutputBuffer(outIdx);
                    int size = info.size;
                    if (size > 0 && output != null) {
                        if (aac.length < size) {
                            aac = new byte[size];
                        }
                        output.position(0);
                        output.limit(size);
                        output.get(aac, 0, size);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    FrameSink s = sink;
                    if (s != null && size > 0 && output != null) {
                        try {
                            s.writeFrame(info.presentationTimeUs, aac, size);
                            lastWriteMs = System.currentTimeMillis();
                        } catch (IOException e) {
                            s.close();
                            if (sink == s) {
                                sink = null;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // recorder.read()/codec calls throw IllegalStateException after stop()
                // releases them from the service thread — exit cleanly instead of
                // crashing the audio thread with an uncaught exception.
                if (running) {
                    Logs.e("audio pump error", e);
                }
                break;
            }
        }
        Logs.i("audio stream stopped");
    }
}
