package io.opencam.webcam.video;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;

import io.opencam.webcam.net.FrameSink;

import java.io.ByteArrayOutputStream;
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

    private final HandlerThread encodeThread;
    private final Handler encodeHandler;

    protected MjpegProducer(int width, int height, int quality, int imageFormat) {
        this.width = width;
        this.height = height;
        this.quality = quality;
        this.reader = ImageReader.newInstance(width, height, imageFormat, 4);
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

    /** Hand a JPEG to the current sink (dropped when no client is attached). */
    protected void deliver(byte[] jpeg) {
        FrameSink s = sink;
        if (s == null) {
            return;
        }
        try {
            s.writeFrame(System.nanoTime() / 1000L, jpeg, jpeg.length);
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

    /** Default implementation: YUV_420_888 → NV21 → YuvImage → JPEG. Works on every device. */
    public static MjpegProducer yuv(int width, int height, int quality) {
        return new MjpegProducer(width, height, quality, ImageFormat.YUV_420_888) {
            // Buffers are reused across frames (this producer runs single-threaded on its
            // dedicated encode thread) to avoid ~1.5 byte-per-pixel allocation churn.
            private byte[] nv21Buf;
            private final ByteArrayOutputStream jpegBuf = new ByteArrayOutputStream(64 * 1024);

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
                    if (!toNv21(image, nv21Buf, width, height)) {
                        return;
                    }
                    YuvImage yuv = new YuvImage(nv21Buf, ImageFormat.NV21, width, height, null);
                    jpegBuf.reset();
                    yuv.compressToJpeg(new Rect(0, 0, width, height), quality, jpegBuf);
                    deliver(jpegBuf.toByteArray());
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

    /**
     * Alternative: capture JPEG directly from the ISP via an ImageFormat.JPEG reader.
     * Much faster than the YUV path, but a minority of devices reject JPEG in repeating
     * requests. Not used by default.
     */
    public static MjpegProducer jpegReader(int width, int height, int quality) {
        return new MjpegProducer(width, height, quality, ImageFormat.JPEG) {
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
                    byte[] jpeg = new byte[size];
                    buf.get(jpeg);
                    deliver(jpeg);
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
    private static boolean toNv21(Image image, byte[] nv21, int width, int height) {
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
