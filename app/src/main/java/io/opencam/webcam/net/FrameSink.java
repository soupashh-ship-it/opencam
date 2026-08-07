package io.opencam.webcam.net;

import java.io.IOException;
import java.net.Socket;

/**
 * A destination for encoded frames. Implementations define the on-the-wire framing:
 * <ul>
 *   <li>{@link FramedSink}  — 12-byte [pts:8 LE][len:4 LE] header (modern v4/v5 clients)</li>
 *   <li>{@link MjpegSink}   — classic multipart/x-mixed-replace MJPEG (browsers, ffplay, OBS Media Source)</li>
 *   <li>{@link LegacySink}  — legacy OBS-plugin framing (9-byte header + 4-byte length)</li>
 * </ul>
 * The encoders only ever talk to this interface, so the wire format is swappable per client.
 */
public interface FrameSink {

    /** Called once when the client connects; write protocol/HTTP headers here. */
    void open(Socket socket) throws IOException;

    /**
     * Write one encoded frame.
     *
     * @param ptsUs presentation timestamp in microseconds (may be 0 if unknown)
     * @param data  frame payload (JPEG, one NAL access unit, or one AAC frame)
     * @param len   valid length of {@code data}
     */
    void writeFrame(long ptsUs, byte[] data, int len) throws IOException;

    /** Close the sink and the underlying socket. */
    void close();

    /** Human-readable label, e.g. "FRAMED/AAC". */
    String name();
}
