package io.opencam.webcam.net;

import android.content.Context;

import java.io.IOException;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Route table for the embedded HTTP server. Mirrors the endpoint surface documented in
 * REVERSE_ENGINEERING_REPORT.md §8 (endpoints are protocol/interoperability facts).
 *
 * <p>Control actions are passed to {@link Host} as an action id + float value, mirroring the
 * message-arg style of the original implementation:
 * ZOOM=0, WB_MODE=1, AF_MODE=2, EV=3, WB_LEVEL=4, MIC_TOGGLE=7, TALLY=8, AF_TRIGGER=9,
 * SHUTTER=16, ISO=24, MF=32.
 */
public final class ControlApi {

    public static final int ZOOM = 0;
    public static final int WB_MODE = 1;
    public static final int AF_MODE = 2;
    public static final int EV = 3;
    public static final int WB_LEVEL = 4;
    public static final int MIC_TOGGLE = 7;
    public static final int TALLY = 8;
    public static final int AF_TRIGGER = 9;
    public static final int SHUTTER = 16;
    public static final int ISO = 24;
    public static final int MF = 32;

    /** Implemented by StreamService. */
    public interface Host {
        int batteryLevel();

        int batteryState();

        String deviceName();

        /** Full phone/stream configuration as JSON (used by the desktop client). */
        String phoneInfoJson();

        String[] cameraList();

        void setActiveCamera(int index);

        String cameraInfoJson();

        /** Start a video stream for a client. Returns false if busy/unavailable. */
        boolean startVideoClient(VideoSpec spec, Socket socket);

        /** Start the AAC audio stream for a client. Returns false if busy/unavailable. */
        boolean startAudioClient(Socket socket);

        void onControl(int actionId, float value);

        void onTorchToggle();

        void onWbLockToggle();

        void onAeToggle();

        void onExposureLockToggle();

        void onTally(int state);

        void onRestart();

        void onStop();

        /** Change the phone's video codec setting (jpg|avc|hevc), applied live. */
        void setPhoneCodec(String codec);

        /** Change the phone's encoded-video bitrate (kbps), applied live. */
        void setPhoneBitrate(int kbps);
    }

    private static final Pattern WxH = Pattern.compile("(\\d+)x(\\d+)");

    private ControlApi() {
    }

    /**
     * Handle one HTTP request.
     *
     * @return true if the request was consumed (response written or socket handed off),
     * false if the connection should be closed.
     */
    public static boolean handle(String requestLine, Socket socket, Context context, Host host)
            throws IOException {
        String method = requestLine.contains(" ") ? requestLine.substring(0, requestLine.indexOf(' ')) : "";
        int first = requestLine.indexOf(' ');
        int second = requestLine.indexOf(' ', first + 1);
        String path = second > first ? requestLine.substring(first + 1, second) : requestLine.substring(first + 1);

        // ---- video streams -------------------------------------------------
        if (path.equals("/video")) {
            VideoSpec spec = new VideoSpec("jpg", 1280, 720, VideoSpec.CLIENT_CLASSIC_MJPEG, requestLine);
            if (!host.startVideoClient(spec, socket)) {
                HttpResponse.sendHtml(socket, BUSY_HTML);
            }
            return true;
        }
        if (path.startsWith("/v5/video/") || path.startsWith("/v4/video/")) {
            VideoSpec spec = parseFramedVideo(requestLine, path);
            if (spec == null) {
                HttpResponse.sendError(socket, "400 Bad Request");
                return true;
            }
            if (!host.startVideoClient(spec, socket)) {
                HttpResponse.sendHtml(socket, BUSY_HTML);
            }
            return true;
        }
        if (requestLine.startsWith("CMD /v3/video/")) {
            VideoSpec spec = parseLegacyVideo(requestLine, path);
            if (spec == null) {
                HttpResponse.sendError(socket, "400 Bad Request");
                return true;
            }
            if (!host.startVideoClient(spec, socket)) {
                HttpResponse.sendHtml(socket, BUSY_HTML);
            }
            return true;
        }

        // ---- audio streams -------------------------------------------------
        if (path.equals("/v2/audio") || path.equals("/v1/audio.2")) {
            if (!host.startAudioClient(socket)) {
                HttpResponse.sendError(socket, "503 Service Unavailable");
            }
            return true;
        }

        // ---- control API ---------------------------------------------------
        if (path.equals("/") || path.equals("/index")) {
            HttpResponse.sendRedirect(socket, "/remote");
            return true;
        }
        if (path.equals("/remote")) {
            HttpResponse.sendAsset(context, socket, "remote.html");
            return true;
        }
        if (path.equals("/favicon.ico") || path.startsWith("/assets/")) {
            HttpResponse.sendAsset(context, socket, path.substring(1));
            return true;
        }
        if (path.equals("/v1/phone/battery_info")) {
            HttpResponse.sendJson(socket, "{\"level\":" + host.batteryLevel()
                    + ",\"state\":" + host.batteryState() + "}");
            return true;
        }
        if (path.equals("/v1/phone/battery_level") || path.equals("/battery")) {
            HttpResponse.sendText(socket, String.valueOf(host.batteryLevel()));
            return true;
        }
        if (path.equals("/v1/phone/name")) {
            HttpResponse.sendText(socket, host.deviceName());
            return true;
        }
        if (path.equals("/v1/phone/info")) {
            HttpResponse.sendJson(socket, host.phoneInfoJson());
            return true;
        }
        if (path.startsWith("/v1/phone/codec/")) {
            // Desktop client setting the video codec (jpg|avc|hevc) so the phone's
            // own settings stay in sync with the client's choice.
            String codec = path.substring("/v1/phone/codec/".length()).trim();
            if (codec.equals("jpg") || codec.equals("avc") || codec.equals("hevc")) {
                host.setPhoneCodec(codec);
            }
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.startsWith("/v1/phone/bitrate/")) {
            int kbps = intPathParam(path, "/v1/phone/bitrate/");
            if (kbps > 0) {
                host.setPhoneBitrate(kbps);
            }
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.equals("/v1/camera/camera_list")) {
            StringBuilder sb = new StringBuilder();
            for (String name : host.cameraList()) {
                sb.append(name).append('\n');
            }
            HttpResponse.sendText(socket, sb.toString());
            return true;
        }
        if (path.equals("/v1/camera/info")) {
            String info = host.cameraInfoJson();
            if (info == null) {
                HttpResponse.sendError(socket, "503 Service Unavailable");
            } else {
                HttpResponse.sendJson(socket, info);
            }
            return true;
        }
        if (path.startsWith("/v1/camera/active/")) {
            int id = intPathParam(path, "/v1/camera/active/");
            if (id >= 0) {
                host.setActiveCamera(id);
            }
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.equals("/v1/camera/mic_toggle")) {
            host.onControl(MIC_TOGGLE, 0);
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.equals("/v1/camera/torch_toggle")) {
            host.onTorchToggle();
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.equals("/v1/camera/wb_mode") || path.startsWith("/v1/camera/wb_mode/")) {
            int m = intPathParam(path, "/v1/camera/wb_mode/");
            if (m >= 0) {
                host.onControl(WB_MODE, m);
            }
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.equals("/v1/camera/wbl_toggle")) {
            host.onWbLockToggle();
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.equals("/v1/camera/ae_toggle")) {
            host.onAeToggle();
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.equals("/v1/camera/el_toggle")) {
            host.onExposureLockToggle();
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.equals("/v1/camera/autofocus_mode") || path.startsWith("/v1/camera/autofocus_mode/")) {
            int m = intPathParam(path, "/v1/camera/autofocus_mode/");
            if (m >= 0) {
                host.onControl(AF_MODE, m);
            }
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.equals("/v1/camera/autofocus")) {
            host.onControl(AF_TRIGGER, 0);
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.startsWith("/v3/camera/zoom/")) {
            host.onControl(ZOOM, floatPathParam(path, "/v3/camera/zoom/"));
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.startsWith("/v3/camera/ev/")) {
            host.onControl(EV, floatPathParam(path, "/v3/camera/ev/"));
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.startsWith("/v3/camera/ss/")) {
            host.onControl(SHUTTER, floatPathParam(path, "/v3/camera/ss/"));
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.startsWith("/v3/camera/iso/")) {
            host.onControl(ISO, floatPathParam(path, "/v3/camera/iso/"));
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.startsWith("/v2/camera/wb_level/")) {
            host.onControl(WB_LEVEL, floatPathParam(path, "/v2/camera/wb_level/"));
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.startsWith("/v3/camera/mf/")) {
            host.onControl(MF, floatPathParam(path, "/v3/camera/mf/"));
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.equals("/v1/restart")) {
            host.onRestart();
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.equals("/v1/stop")) {
            host.onStop();
            HttpResponse.sendText(socket, "");
            return true;
        }
        if (path.startsWith("/v1/tally")) {
            // The DroidCam desktop client sends the state in the path (PUT /v1/tally/{state}/);
            // the web remote uses a query param. Accept both.
            String tally = queryParam(requestLine, "tally");
            if (tally == null) {
                String rest = path.substring("/v1/tally".length()).replace("/", "").trim();
                if (!rest.isEmpty()) {
                    tally = rest;
                }
            }
            int state = -1;
            if (tally != null) {
                if (tally.contains("idle")) {
                    state = 1;
                } else if (tally.contains("preview")) {
                    state = 2;
                } else if (tally.contains("program")) {
                    state = 3;
                }
            }
            if (state > 0) {
                host.onTally(state);
            }
            HttpResponse.sendText(socket, "");
            return true;
        }

        // ---- known-unsupported legacy endpoints ---------------------------
        if (path.startsWith("/v1/video") || path.startsWith("/v1/audio")
                || path.startsWith("/v3/video/") || path.startsWith("/v2/video")
                || path.startsWith("/v6/video") || path.startsWith("/v7/video")
                || path.startsWith("/v4/audio")) {
            HttpResponse.sendText(socket, "this client uses an unsupported legacy protocol");
            return true;
        }

        HttpResponse.sendError(socket, "404 Not Found");
        return true;
    }

    // ---- parsing helpers ---------------------------------------------------

    private static VideoSpec parseFramedVideo(String requestLine, String path) {
        String rest = path.startsWith("/v5/video/")
                ? path.substring("/v5/video/".length())
                : path.substring("/v4/video/".length());
        int slash = rest.indexOf('/');
        String codec = slash > 0 ? rest.substring(0, slash).toLowerCase() : rest.toLowerCase();
        if (!codec.equals("jpg") && !codec.equals("avc") && !codec.equals("hevc")) {
            return null;
        }
        int[] wh = findWxH(requestLine);
        if (wh == null) {
            return null;
        }
        int client = intQueryParam(requestLine, "client", -1);
        return new VideoSpec(codec, wh[0], wh[1], client, requestLine);
    }

    private static VideoSpec parseLegacyVideo(String requestLine, String path) {
        String rest = path.startsWith("CMD /v3/video/")
                ? path.substring("CMD /v3/video/".length())
                : path.substring("/v3/video/".length());
        int slash = rest.indexOf('/');
        String codec = slash > 0 ? rest.substring(0, slash).toLowerCase() : rest.toLowerCase();
        if (!codec.equals("jpg") && !codec.equals("avc") && !codec.equals("hevc")) {
            return null;
        }
        int[] wh = findWxH(requestLine);
        if (wh == null) {
            return null;
        }
        return new VideoSpec(codec, wh[0], wh[1], VideoSpec.CLIENT_LEGACY, requestLine);
    }

    private static int[] findWxH(String requestLine) {
        Matcher m = WxH.matcher(requestLine);
        if (!m.find()) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String queryParam(String requestLine, String key) {
        int q = requestLine.indexOf('?');
        if (q < 0) {
            return null;
        }
        String query = requestLine.substring(q + 1);
        String[] parts = query.split("[& ]");
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).equals(key)) {
                return p.substring(eq + 1);
            }
        }
        return null;
    }

    private static int intQueryParam(String requestLine, String key, int def) {
        String v = queryParam(requestLine, key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int intPathParam(String path, String prefix) {
        try {
            return Integer.parseInt(path.substring(prefix.length()));
        } catch (Exception e) {
            return -1;
        }
    }

    private static float floatPathParam(String path, String prefix) {
        try {
            return Float.parseFloat(path.substring(prefix.length()));
        } catch (Exception e) {
            return 0f;
        }
    }

    private static final String BUSY_HTML = "<!doctype html><html><body style=\"background:#1b1b1b;color:#fff;font-family:sans-serif;padding:2em\">"
            + "<h2>Video is already streaming to another client</h2>"
            + "<p><a href=\"/remote\" style=\"color:#00c4ff\">Back to control page</a></p></body></html>";
}
