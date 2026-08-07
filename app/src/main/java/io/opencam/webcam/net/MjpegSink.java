package io.opencam.webcam.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Classic MJPEG-over-HTTP ("motion JPEG") used by browsers, {@code ffplay}, VLC and OBS
 * "Media Source". Responses look like:
 *
 * <pre>
 * HTTP/1.1 200 OK
 * Access-Control-Allow-Origin: *
 * Content-Type: multipart/x-mixed-replace;boundary=dcmjpeg
 * ...
 *
 * --dcmjpeg
 * Content-Type: image/jpeg
 * Content-Length: N
 *
 * &lt;jpeg bytes&gt;
 * </pre>
 *
 * {@code --dcmjpeg} is the established interop constant used by classic clients. The header
 * advertises the boundary token without the leading {@code --} (per the multipart spec),
 * while each part uses the full {@code --dcmjpeg} delimiter.
 */
public final class MjpegSink implements FrameSink {

    public static final byte[] BOUNDARY = "--dcmjpeg".getBytes(StandardCharsets.US_ASCII);
    private static final String BOUNDARY_TOKEN = "dcmjpeg";

    private final String label;
    private Socket socket;
    private OutputStream out;

    /** The one-time connection header — static, written as a single byte[]. */
    private static final byte[] HEAD_BEGIN = ("HTTP/1.1 200 OK\r\n"
            + "Access-Control-Allow-Origin: *\r\n"
            + "Access-Control-Allow-Methods: GET\r\n"
            + "Content-Type: multipart/x-mixed-replace;boundary=" + BOUNDARY_TOKEN + "\r\n"
            + "Connection: Keep-Alive\r\n"
            + "Expires: 0\r\n"
            + "Cache-Control: no-store, must-revalidate\r\n"
            + "\r\n").getBytes(StandardCharsets.US_ASCII);

    /** Fixed part of the per-part header; only Content-Length varies. Built from
     *  {@link #BOUNDARY} so the boundary token has a single source of truth. */
    private static final byte[] PART_PREFIX;

    static {
        byte[] boundary = BOUNDARY;
        byte[] mid = ("Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: ").getBytes(StandardCharsets.US_ASCII);
        PART_PREFIX = new byte[boundary.length + mid.length];
        System.arraycopy(boundary, 0, PART_PREFIX, 0, boundary.length);
        System.arraycopy(mid, 0, PART_PREFIX, boundary.length, mid.length);
    }
    private static final byte[] PART_END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    /** Reused per-frame scratch for the Content-Length digits (max 10 digits). */
    private final byte[] lenDigits = new byte[10];

    public MjpegSink(String label) {
        this.label = label;
    }

    @Override
    public void open(Socket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        out.write(HEAD_BEGIN);
        out.flush();
    }

    @Override
    public void writeFrame(long ptsUs, byte[] data, int len) throws IOException {
        if (out == null) {
            throw new IOException("sink not open");
        }
        // Zero-allocation per-frame framing: static prefix bytes + a few length
        // digits written into a reused scratch buffer (avoids a StringBuilder +
        // toString() + getBytes() trio for every frame at 30 fps).
        out.write(PART_PREFIX);
        int n = writeLen(len, lenDigits);
        out.write(lenDigits, 0, n);
        out.write(PART_END);
        out.write(data, 0, len);
        out.write(CRLF);
    }

    /** Write the decimal digits of v (right-justified) into dst; returns the digit count. */
    private static int writeLen(int v, byte[] dst) {
        int i = dst.length;
        do {
            dst[--i] = (byte) ('0' + (v % 10));
            v /= 10;
        } while (v > 0);
        return dst.length - i;
    }

    @Override
    public void close() {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        out = null;
    }

    @Override
    public String name() {
        return label;
    }
}
