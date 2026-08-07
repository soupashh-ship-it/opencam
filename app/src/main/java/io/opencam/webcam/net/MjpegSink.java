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

    public MjpegSink(String label) {
        this.label = label;
    }

    @Override
    public void open(Socket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 200 OK\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET\r\n");
        sb.append("Content-Type: multipart/x-mixed-replace;boundary=")
          .append(BOUNDARY_TOKEN).append("\r\n");
        sb.append("Connection: Keep-Alive\r\n");
        sb.append("Expires: 0\r\n");
        sb.append("Cache-Control: no-store, must-revalidate\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Override
    public void writeFrame(long ptsUs, byte[] data, int len) throws IOException {
        if (out == null) {
            throw new IOException("sink not open");
        }
        StringBuilder sb = new StringBuilder(96);
        sb.append("--dcmjpeg\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET\r\n");
        sb.append("Content-Type: image/jpeg\r\n");
        sb.append("Content-Length: ").append(len).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(data, 0, len);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
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
