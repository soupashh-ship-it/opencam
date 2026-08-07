package io.opencam.webcam.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Legacy framing used by older OBS-plugin clients (the "client=600" classic protocol).
 *
 * <p>On connect, a 9-byte header is written:
 *
 * <pre>
 *   [uint16 BE width][uint16 BE height][uint8 frame interval ms][F5 E8 B5 D0 (magic)]
 * </pre>
 *
 * then each frame as:
 *
 * <pre>
 *   [int32 LE length][frame data]
 * </pre>
 *
 * These byte layouts are protocol/interoperability facts (see REVERSE_ENGINEERING_REPORT.md).
 */
public final class LegacySink implements FrameSink {

    public static final byte[] MAGIC = {(byte) 0xF5, (byte) 0xE8, (byte) 0xB5, (byte) 0xD0};

    private final String label;
    private final int width;
    private final int height;
    private final int intervalMs;

    private Socket socket;
    private OutputStream out;
    private final ByteBuffer lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);

    public LegacySink(int width, int height, int intervalMs) {
        this.label = "LEGACY " + width + "x" + height;
        this.width = width;
        this.height = height;
        this.intervalMs = intervalMs;
    }

    @Override
    public void open(Socket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        byte[] header = new byte[9];
        header[0] = (byte) ((width >> 8) & 0xFF);
        header[1] = (byte) (width & 0xFF);
        header[2] = (byte) ((height >> 8) & 0xFF);
        header[3] = (byte) (height & 0xFF);
        header[4] = (byte) (intervalMs & 0xFF);
        System.arraycopy(MAGIC, 0, header, 5, 4);
        out.write(header);
        out.flush();
    }

    @Override
    public void writeFrame(long ptsUs, byte[] data, int len) throws IOException {
        if (out == null) {
            throw new IOException("sink not open");
        }
        lenBuf.clear();
        lenBuf.putInt(len);
        out.write(lenBuf.array(), 0, 4);
        out.write(data, 0, len);
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
