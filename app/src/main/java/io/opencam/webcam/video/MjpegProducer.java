package io.opencam.webcam.video;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;

import io.opencam.webcam.net.FrameSink;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Captures camera frames as JPEG and pushes them to whatever {@link FrameSink} is attached
 * (the MJPEG client). Implementations differ only in how JPEG bytes are produced.
 */
public abstract class MjpegProducer {

    public final int width;
    public final int height;
    public final int quality;
    public final ImageReader reader;

    /** The currently attached client sink; null when nobody is watching. */
    public volatile FrameSink sink;

    /** Wall-clock time (ms) of the last successful frame write to the sink. Used by
     *  the service's watchdog to reclaim clients whose socket died silently — the
     *  only other way to notice is a failed write, which never happens if frames
     *  have stopped (half-open TCP, stalled camera). */
    public volatile long lastWriteMs;

    private final HandlerThread encodeThread;
    private final Handler encodeHandler;

    protected MjpegProducer(int width, int height, int quality, int imageFormat) {
        this(width, height, quality, imageFormat,
                // ImageReader for JPEG is limited to 1 in-flight image on many devices
                // (larger values throw IllegalArgumentException); YUV can hold 4 so a
                // slower encode never stalls the camera pipeline.
                imageFormat == ImageFormat.JPEG ? 1 : 4);
    }

    protected MjpegProducer(int width, int height, int quality, int imageFormat, int maxImages) {
        this.width = width;
        this.height = height;
        this.quality = quality;
        this.reader = ImageReader.newInstance(width, height, imageFormat, maxImages);
        // JPEG encoding (YUV -> NV21 -> compressToJpeg) is moved off the camera handler
        // thread onto its own looper. At 1440p+ a single encode can take 30-80 ms; doing
        // it on the camera thread stalled session reconfigs and capture requests.
        encodeThread = new HandlerThread("opencam.encode");
        encodeThread.start();
        encodeHandler = new Handler(encodeThread.getLooper());
    }

    /**
     * Register the frame callback. Runs on this producer's dedicated encode thread so the
     * camera thread never blocks on JPEG compression. (cameraHandler kept for API stability.)
     */
    public void attach(Handler cameraHandler) {
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                MjpegProducer.this.onImageAvailable(r);
            }
        }, encodeHandler);
    }

    protected abstract void onImageAvailable(ImageReader reader);

    /**
     * Convert one YUV_420_888 image into the NV21 buffer. Implementations may
     * special-case the plane layout (contiguous rows) for speed; the base class
     * provides the generic per-pixel fallback.
     */
    protected boolean toNv21Impl(Image image, byte[] nv21, int width, int height) {
        return toNv21Generic(image, nv21, width, height);
    }

    /** Hand a JPEG to the current sink (dropped when no client is attached).
     *  The sink writes synchronously, so the caller's buffer may be reused next frame. */
    protected void deliver(byte[] jpeg, int len) {
        FrameSink s = sink;
        if (s == null) {
            return;
        }
        try {
            s.writeFrame(System.nanoTime() / 1000L, jpeg, len);
            lastWriteMs = System.currentTimeMillis();
        } catch (IOException e) {
            s.close();
            // Only clear if we still own the sink — a newer client may have attached
            // between our read of `sink` and the write failure.
            if (sink == s) {
                sink = null;
            }
        }
    }

    public void close() {
        try {
            reader.close();
        } catch (Exception ignored) {
        }
        try {
            encodeThread.quitSafely();
        } catch (Exception ignored) {
        }
    }

    /**
     * ByteArrayOutputStream subclass that exposes its internal buffer so a JPEG can be
     * handed to the (synchronous) sink without the per-frame toByteArray() copy — at
     * 1080p+ quality 92 a JPEG is ~300-800 KB, so this removes a large allocation per
     * frame at 30 fps.
     */
    private static final class GrowBuf extends java.io.ByteArrayOutputStream {
        GrowBuf(int size) {
            super(size);
        }

        byte[] buffer() {
            return buf;
        }
    }

    /** Default implementation: YUV_420_888 → NV21 → YuvImage → JPEG. Works on every device. */
    public static MjpegProducer yuv(int width, int height, int quality) {
        return new MjpegProducer(width, height, quality, ImageFormat.YUV_420_888) {
            // Buffers are reused across frames (this producer runs single-threaded on its
            // dedicated encode thread) to avoid ~1.5 byte-per-pixel allocation churn.
            private byte[] nv21Buf;
            // scratch for the chroma row-interleave fast path (see toNv21)
            private byte[] uvRowV;
            private byte[] uvRowU;
            private final GrowBuf jpegBuf = new GrowBuf(64 * 1024);

            @Override
            protected void onImageAvailable(ImageReader reader) {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) {
                        return;
                    }
                    if (sink == null) {
                        return; // nobody watching: drop the frame, skip the expensive encode
                    }
                    int need = width * height * 3 / 2;
                    if (nv21Buf == null || nv21Buf.length < need) {
                        nv21Buf = new byte[need];
                    }
                    if (!toNv21Impl(image, nv21Buf, width, height)) {
                        return;
                    }
                    YuvImage yuv = new YuvImage(nv21Buf, ImageFormat.NV21, width, height, null);
                    jpegBuf.reset();
                    yuv.compressToJpeg(new Rect(0, 0, width, height), quality, jpegBuf);
                    deliver(jpegBuf.buffer(), jpegBuf.size());
                } catch (Exception e) {
                    // drop the frame
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }

            @Override
            protected boolean toNv21Impl(Image image, byte[] nv21, int width, int height) {
                Image.Plane[] planes = image.getPlanes();
                if (planes.length < 3) {
                    return false;
                }
                ByteBuffer y = planes[0].getBuffer();
                ByteBuffer u = planes[1].getBuffer();
                ByteBuffer v = planes[2].getBuffer();
                int yRowStride = planes[0].getRowStride();
                int yPixStride = planes[0].getPixelStride();
                int uvRowStride = planes[1].getRowStride();
                int uvPixStride = planes[1].getPixelStride();

                // ---- Y plane: bulk copy per row when contiguous (the common case).
                // The old per-pixel loop did width*height ByteBuffer.get() calls (~2M at
                // 1080p) with bounds checks each — that alone cost 10-40ms/frame and was
                // the main reason MJPEG capped at ~20fps on many devices.
                if (yPixStride == 1) {
                    if (yRowStride == width) {
                        y.position(0);
                        y.get(nv21, 0, width * height);
                    } else {
                        int dst = 0;
                        for (int row = 0; row < height; row++) {
                            y.position(row * yRowStride);
                            y.get(nv21, dst, width);   // one native memcpy per row
                            dst += width;
                        }
                    }
                } else {
                    int yi = 0;
                    for (int row = 0; row < height; row++) {
                        int rb = row * yRowStride;
                        for (int col = 0; col < width; col++) {
                            nv21[yi++] = y.get(rb + col * yPixStride);
                        }
                    }
                }

                // ---- chroma: NV21 wants V,U interleaved per 2x2 block.
                // Bulk-copy each chroma row into scratch arrays (native memcpy), then
                // interleave from the arrays — array reads are far cheaper than the
                // bounds-checked ByteBuffer.get() the old loop used per sample.
                // NOTE: only copy the halfW*uvPixStride bytes we consume. Some devices
                // tightly pack the last plane row (buffer size = stride*(rows-1) +
                // used), so reading the full stride there would overrun the buffer and
                // drop the frame.
                int halfW = width / 2;
                int halfH = height / 2;
                int copyLen = halfW * uvPixStride;
                if (uvRowStride > 0 && uvRowStride >= copyLen) {
                    if (uvRowV == null || uvRowV.length < copyLen) {
                        uvRowV = new byte[copyLen];
                        uvRowU = new byte[copyLen];
                    }
                    int dst = width * height;
                    for (int row = 0; row < halfH; row++) {
                        int rb = row * uvRowStride;
                        v.position(rb);
                        v.get(uvRowV, 0, copyLen);
                        u.position(rb);
                        u.get(uvRowU, 0, copyLen);
                        for (int col = 0; col < halfW; col++) {
                            int idx = col * uvPixStride;
                            nv21[dst++] = uvRowV[idx];
                            nv21[dst++] = uvRowU[idx];
                        }
                    }
                } else {
                    // fallback: fully generic per-sample path (rarely hit)
                    int dst = width * height;
                    for (int row = 0; row < halfH; row++) {
                        int rb = row * uvRowStride;
                        for (int col = 0; col < halfW; col++) {
                            int idx = rb + col * uvPixStride;
                            nv21[dst++] = v.get(idx);
                            nv21[dst++] = u.get(idx);
                        }
                    }
                }
                return true;
            }
        };
    }

    /**
     * Alternative: capture JPEG directly from the ISP via an ImageFormat.JPEG reader.
     * Much faster than the YUV path, but a minority of devices reject JPEG in repeating
     * requests. Not used by default.
     */
    public static MjpegProducer jpegReader(int width, int height, int quality) {
        return new MjpegProducer(width, height, quality, ImageFormat.JPEG) {
            // Reused across frames — the sink writes synchronously.
            private byte[] jpegOut;

            @Override
            protected void onImageAvailable(ImageReader reader) {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) {
                        return;
                    }
                    if (sink == null) {
                        return; // nobody watching: drop the frame, skip encoding
                    }
                    Image.Plane plane = image.getPlanes()[0];
                    ByteBuffer buf = plane.getBuffer();
                    int size = buf.remaining();
                    if (jpegOut == null || jpegOut.length < size) {
                        jpegOut = new byte[size];
                    }
                    buf.get(jpegOut, 0, size);
                    deliver(jpegOut, size);
                } catch (Exception e) {
                    // drop the frame
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }
        };
    }

    /** Convert a YUV_420_888 image into the caller-provided NV21 buffer (Y + interleaved VU). */
    private static boolean toNv21Generic(Image image, byte[] nv21, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            return false;
        }
        ByteBuffer y = planes[0].getBuffer();
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixStride = planes[0].getPixelStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixStride = planes[1].getPixelStride();

        int yIndex = 0;
        for (int row = 0; row < height; row++) {
            int rowBase = row * yRowStride;
            for (int col = 0; col < width; col++) {
                nv21[yIndex++] = y.get(rowBase + col * yPixStride);
            }
        }
        int uvBase = width * height;
        for (int row = 0; row < height / 2; row++) {
            int rowBase = row * uvRowStride;
            for (int col = 0; col < width / 2; col++) {
                int idx = rowBase + col * uvPixStride;
                nv21[uvBase] = v.get(idx);
                nv21[uvBase + 1] = u.get(idx);
                uvBase += 2;
            }
        }
        return true;
    }
}
