package io.opencam.webcam.net;

/** Describes one requested video stream (codec, size, client type). */
public final class VideoSpec {

    public static final int CLIENT_LEGACY = 600; // old OBS plugin protocol
    public static final int CLIENT_CLASSIC_MJPEG = 1; // classic /video client

    public final String codec; // "jpg" | "avc" | "hevc"
    public final int width;
    public final int height;
    public final int client; // client id from query string; -1 if unknown
    public final String rawRequest;

    public VideoSpec(String codec, int width, int height, int client, String rawRequest) {
        this.codec = codec;
        this.width = width;
        this.height = height;
        this.client = client;
        this.rawRequest = rawRequest;
    }

    public boolean isLegacy() {
        return client == CLIENT_LEGACY;
    }

    /** Frame interval (ms) used by the legacy protocol. */
    public int legacyIntervalMs() {
        return "hevc".equals(codec) ? 50 : 33;
    }

    @Override
    public String toString() {
        return codec + " " + width + "x" + height + " client=" + client;
    }
}
