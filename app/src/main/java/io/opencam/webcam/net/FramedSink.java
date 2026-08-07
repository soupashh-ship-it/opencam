package io.opencam.webcam.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The modern framed protocol used by v4/v5 clients (including the DroidCam OBS plugin and
 * desktop client). Every packet is:
 *
 * <pre>
 *   [int64 LE  presentation timestamp (µs)]  (-1 = end-of-stream)
 *   [int32 LE  payload length in bytes]      (-1 = end-of-stream)
 *   [payload]  one encoded frame
 * </pre>
 *
 * This framing is a protocol/interoperability fact (documented in REVERSE_ENGINEERING_REPORT.md).
 */
public final class FramedSink implements FrameSink {

    private static final long EOF_PTS = -1L;
    private static final int EOF_LEN = -1;

    private final String label;
    private final ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
    private Socket socket;
    private OutputStream out;

    public FramedSink(String label) {
        this.label = label;
    }

    @Override
    public void open(Socket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
    }

    @Override
    public void writeFrame(long ptsUs, byte[] data, int len) throws IOException {
        if (out == null) {
            throw new IOException("sink not open");
        }
        header.clear();
        header.putLong(ptsUs);
        header.putInt(len);
        out.write(header.array(), 0, 12);
        out.write(data, 0, len);
    }

    @Override
    public void close() {
        if (socket == null) {
            return;
        }
        try {
            // Standard end-of-stream marker for this protocol.
            header.clear();
            header.putLong(EOF_PTS);
            header.putInt(EOF_LEN);
            out.write(header.array(), 0, 12);
            out.flush();
        } catch (IOException ignored) {
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
