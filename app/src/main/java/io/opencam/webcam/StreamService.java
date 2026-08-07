package io.opencam.webcam;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Surface;

import io.opencam.webcam.audio.AudioStream;
import io.opencam.webcam.mdns.Discovery;
import io.opencam.webcam.net.ControlApi;
import io.opencam.webcam.net.FrameSink;
import io.opencam.webcam.net.FramedSink;
import io.opencam.webcam.net.HttpServer;
import io.opencam.webcam.net.LegacySink;
import io.opencam.webcam.net.MjpegSink;
import io.opencam.webcam.net.VideoSpec;
import io.opencam.webcam.util.Logs;
import io.opencam.webcam.video.CameraController;
import io.opencam.webcam.video.MjpegProducer;
import io.opencam.webcam.video.VideoEncoderPipeline;

import java.io.IOException;
import java.net.Socket;
import java.util.Locale;

/**
 * Foreground service that owns the whole streaming stack: HTTP server, camera, encoders and
 * mDNS. Implements {@link ControlApi.Host}, so HTTP control endpoints land here.
 */
public class StreamService extends Service implements ControlApi.Host {

    public static final int STATE_STOPPED = 0;
    public static final int STATE_STARTING = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_ERROR = 3;

    /** Explicit intent action for the notification's Stop button. */
    public static final String ACTION_STOP = "io.opencam.webcam.STOP";

    /** UI observer. */
    public interface Listener {
        void onStateChanged(int state);

        void onAddressChanged(String address);

        /** Human-readable error detail (camera failure, missing permission, …). */
        void onError(String message);

        /** The active camera changed (e.g. via switch or the control API). */
        void onCameraChanged();
    }

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public StreamService getService() {
            return StreamService.this;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private int state = STATE_STOPPED;
    private String ipAddress = "";
    private String lastError = "";

    private HttpServer server;
    private CameraController camera;
    private MjpegProducer mjpeg;
    private VideoEncoderPipeline pipeline;
    private AudioStream audio;
    private Discovery discovery;

    private volatile FrameSink videoSink;
    private volatile FrameSink audioSink;

    private Surface previewSurface;
    private String[] cameraIds = new String[0];
    private int cameraIndex;
    private boolean micMuted;
    private boolean torchOn;
    private boolean usingEncoder;
    private Surface encoderSurface;

    private int width;
    private int height;
    private int fps;
    private int port;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;

    private static final String CHANNEL_ID = "stream";

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCam:WakeLock");
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OpenCam:WifiLock");
        multicastLock = wm.createMulticastLock("OpenCam");
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // Tapped Stop on the foreground notification.
            stopStreaming();
            return START_NOT_STICKY;
        }
        startStreaming();
        // NOT_STICKY: never silently restart streaming after a process kill — the user
        // explicitly controls when it runs.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        releaseLocks();
        super.onDestroy();
    }

    // ---- public API for the UI ------------------------------------------------

    public void setListener(Listener l) {
        listener = l;
    }

    public int getState() {
        return state;
    }

    public String getAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }

    /** Last error detail (shown by the UI on STATE_ERROR). */
    public String getLastError() {
        return lastError;
    }

    /** True when the selected camera is the front-facing (selfie) one. */
    public boolean isFrontCamera() {
        return camera != null && camera.isFront();
    }

    /** Number of cameras on this device (0 until the first start). */
    public int getCameraCount() {
        return cameraIds.length;
    }

    public void setPreviewSurface(Surface surface) {
        if (previewSurface == surface) {
            return; // no change: don't churn the capture session on every surface callback
        }
        previewSurface = surface;
        if (camera != null) {
            camera.setPreviewSurface(surface);
        }
    }

    public void setTorchOn(boolean on) {
        torchOn = on;
        if (camera != null) {
            camera.toggleTorch();
        }
    }

    public void switchCamera() {
        if (cameraIds.length <= 1) {
            return;
        }
        cameraIndex = (cameraIndex + 1) % cameraIds.length;
        restartCamera();
        // Mirror state is refreshed from cameraListener.onOpened() once the new camera's
        // characteristics are loaded — notifying here would read the old camera's facing.
    }

    public void startStreaming() {
        if (state == STATE_RUNNING || state == STATE_STARTING) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastError = "Camera or microphone permission is not granted";
            setState(STATE_ERROR);
            notifyError(lastError);
            return;
        }
        setState(STATE_STARTING);
        main.post(new Runnable() {
            @Override
            public void run() {
                doStart();
            }
        });
    }

    public void stopStreaming() {
        main.post(new Runnable() {
            @Override
            public void run() {
                doStop();
            }
        });
    }

    // ---- startup / shutdown ---------------------------------------------------

    private void doStart() {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            cameraIds = cm.getCameraIdList();
        } catch (Exception e) {
            Logs.e("camera list failed", e);
            setState(STATE_ERROR);
            return;
        }
        if (cameraIds.length == 0) {
            setState(STATE_ERROR);
            return;
        }
        cameraIndex = Math.min(cameraIndex, cameraIds.length - 1);
        port = Prefs.port(this);
        parseResolution(Prefs.resolution(this));
        fps = Prefs.fps(this);

        ipAddress = computeIp();
        lastError = "";
        setState(STATE_RUNNING);

        updateNotification();
        acquireLocks();
        openCameraPipeline();
        // Idempotent start: if a previous doStart() left a live listener (possible when two
        // starts race the STARTING guard), shut it down first — otherwise the old socket is
        // orphaned, leaks port 4747, and /v1/stop can never close it.
        if (server != null) {
            server.shutdown();
            server = null;
        }
        startServer();
        startDiscovery();
    }

    private void doStop() {
        detachSinks();
        if (server != null) {
            server.shutdown();
            server = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (mjpeg != null) {
            mjpeg.close();
            mjpeg = null;
        }
        if (pipeline != null) {
            pipeline.stop();
            pipeline = null;
        }
        if (audio != null) {
            audio.stop();
            audio = null;
        }
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
        usingEncoder = false;
        encoderSurface = null;
        releaseLocks();
        stopForeground(true);
        setState(STATE_STOPPED);
    }

    private void startServer() {
        server = new HttpServer(port, new HttpServer.Host() {
            @Override
            public boolean handleRequest(String requestHead, Socket socket) {
                try {
                    return ControlApi.handle(requestHead, socket, StreamService.this, StreamService.this);
                } catch (Exception e) {
                    Logs.e("request error", e);
                    return false;
                }
            }
        });
        server.start();
    }

    private void startDiscovery() {
        if (Prefs.nsdEnabled(this)) {
            discovery = new Discovery(this);
            discovery.start(Prefs.deviceName(this), port);
        }
    }

    private void openCameraPipeline() {
        closePipeline();
        String codec = Prefs.codec(this);
        boolean encoded = codec.equals("avc") || codec.equals("hevc");
        int bitrate = Prefs.bitrateKbps(this);
        // The YUV reader surface is always a capture-session target (even in encoded
        // mode, where the encoder surface joins it) — clamp the requested resolution to
        // a size the camera actually supports for YUV so the setting applies instead of
        // being silently ignored or failing the session (e.g. 4K on a 1080p-max sensor).
        int[] sz = CameraController.pickSupportedSize(
                (CameraManager) getSystemService(Context.CAMERA_SERVICE),
                cameraIds[cameraIndex], width, height);
        if (sz[0] != width || sz[1] != height) {
            Logs.i("resolution " + width + "x" + height
                    + " -> " + sz[0] + "x" + sz[1] + " (closest supported)");
            width = sz[0];
            height = sz[1];
        }
        if (encoded) {
            try {
                pipeline = new VideoEncoderPipeline(
                        codec.equals("hevc") ? VideoEncoderPipeline.HEVC : VideoEncoderPipeline.AVC);
                encoderSurface = pipeline.start(width, height, fps, bitrate);
                usingEncoder = true;
            } catch (IOException e) {
                Logs.e("encoder start failed, falling back to jpg", e);
                usingEncoder = false;
            }
        } else {
            usingEncoder = false;
        }
        mjpeg = MjpegProducer.yuv(width, height, Prefs.jpegQuality(this));
        camera = new CameraController(this);
        camera.open(cameraIds[cameraIndex], width, height, fps,
                previewSurface, mjpeg, usingEncoder ? encoderSurface : null, cameraListener);
    }

    private void closePipeline() {
        if (pipeline != null) {
            pipeline.stop();
            pipeline = null;
        }
        if (mjpeg != null) {
            mjpeg.close();
            mjpeg = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        usingEncoder = false;
        encoderSurface = null;
    }

    private void restartCamera() {
        if (camera != null) {
            // Close + reopen sequenced on the camera's own handler thread. The old
            // implementation closed on one thread and opened on a brand-new thread,
            // racing the in-flight close and producing Camera error 2 (MAX_CAMERAS_IN_USE).
            camera.restart(cameraIds[cameraIndex], width, height, fps,
                    previewSurface, mjpeg, usingEncoder ? encoderSurface : null);
        } else {
            openCameraPipeline();
        }
    }

    private final CameraController.Listener cameraListener = new CameraController.Listener() {
        @Override
        public void onOpened(int w, int h) {
            Logs.i("camera opened " + w + "x" + h);
            // The new camera's characteristics are loaded by now — safe moment to refresh
            // the preview mirror (fires on every session reconfig; applyMirror is idempotent).
            notifyCameraChanged();
        }

        @Override
        public void onError(String message) {
            Logs.e("camera: " + message);
            lastError = message;
            main.post(new Runnable() {
                @Override
                public void run() {
                    // Tear the pipeline down cleanly instead of leaving a half-dead
                    // stream (dead camera, but server + sinks still registered).
                    if (state == STATE_RUNNING || state == STATE_STARTING) {
                        doStop();
                    }
                    setState(STATE_ERROR);
                }
            });
            notifyError(message);
        }
    };

    private void parseResolution(String res) {
        String[] parts = res.split("x");
        try {
            width = Integer.parseInt(parts[0].trim());
            height = Integer.parseInt(parts[1].trim());
        } catch (Exception e) {
            width = 1280;
            height = 720;
        }
    }

    private void detachSinks() {
        FrameSink v = videoSink;
        if (v != null) {
            v.close();
            videoSink = null;
        }
        FrameSink a = audioSink;
        if (a != null) {
            a.close();
            audioSink = null;
        }
        if (mjpeg != null) {
            mjpeg.sink = null;
        }
        if (pipeline != null) {
            pipeline.attachSink(null);
        }
        if (audio != null) {
            audio.attachSink(null);
        }
    }

    // ---- ControlApi.Host ------------------------------------------------------

    @Override
    public int batteryLevel() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public int batteryState() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public String deviceName() {
        return Prefs.deviceName(this);
    }

    @Override
    public String phoneInfoJson() {
        String codec = Prefs.codec(this);
        // Report the live (possibly clamped) capture size once streaming has started so
        // the desktop client's label reflects reality (e.g. a 4K request clamped to a
        // 1080p-max sensor shows 1920x1080, not the raw pref). Falls back to the pref
        // before the first start (width/height are 0 until then).
        int w = width, h = height;
        if (w <= 0 || h <= 0) {
            String res = Prefs.resolution(this);
            String[] parts = res.split("x");
            try {
                w = Integer.parseInt(parts[0].trim());
                h = Integer.parseInt(parts[1].trim());
            } catch (Exception ignored) {
            }
        }
        // Device names come from Build.MODEL / user settings and can contain
        // quotes, backslashes or control characters — escape them or the JSON
        // breaks and the desktop client's parser fails.
        String name = deviceName()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ")
                .replace("\t", " ");
        return "{\"name\":\"" + name
                + "\",\"port\":" + Prefs.port(this)
                + ",\"codec\":\"" + codec
                + "\",\"width\":" + w
                + ",\"height\":" + h
                + ",\"fps\":" + Prefs.fps(this)
                + ",\"audio\":" + (Prefs.audioEnabled(this) ? 1 : 0)
                + ",\"audioRate\":" + Prefs.audioSampleRate(this)
                + ",\"audioChannels\":1"
                + ",\"audioBitrate\":" + Prefs.audioBitrateKbps(this)
                + ",\"jpegQuality\":" + Prefs.jpegQuality(this)
                + "}";
    }

    @Override
    public String[] cameraList() {
        String[] names = new String[cameraIds.length];
        for (int i = 0; i < cameraIds.length; i++) {
            names[i] = "Camera " + i;
        }
        return names;
    }

    @Override
    public void setActiveCamera(int index) {
        if (index >= 0 && index < cameraIds.length && index != cameraIndex) {
            cameraIndex = index;
            restartCamera();
        }
    }

    @Override
    public String cameraInfoJson() {
        return camera != null ? camera.infoJson(micMuted) : null;
    }

    @Override
    public boolean startVideoClient(VideoSpec spec, Socket socket) {
        if (state != STATE_RUNNING || videoBusy()) {
            return false;
        }
        try {
            FrameSink sink;
            if (spec.isLegacy()) {
                sink = new LegacySink(spec.width, spec.height, spec.legacyIntervalMs());
            } else if (spec.codec.equals("jpg")) {
                sink = new MjpegSink("MJPEG");
            } else {
                sink = new FramedSink(spec.codec.toUpperCase());
            }
            sink.open(socket);
            videoSink = sink;
            ensureCodec(spec.codec);
            if (spec.codec.equals("jpg")) {
                if (mjpeg != null) {
                    mjpeg.sink = sink;
                }
            } else if (pipeline != null) {
                pipeline.attachSink(sink);
            }
            Logs.i("video client connected: " + spec);
            return true;
        } catch (IOException e) {
            Logs.e("video client failed", e);
            return false;
        }
    }

    @Override
    public boolean startAudioClient(Socket socket) {
        if (state != STATE_RUNNING || !Prefs.audioEnabled(this) || audioBusy()) {
            return false;
        }
        try {
            FrameSink sink = new FramedSink("AAC");
            sink.open(socket);
            audioSink = sink;
            ensureAudio();
            audio.attachSink(micMuted ? null : sink);
            Logs.i("audio client connected");
            return true;
        } catch (IOException e) {
            Logs.e("audio client failed", e);
            return false;
        }
    }

    @Override
    public void onControl(int actionId, float value) {
        if (camera == null) {
            return;
        }
        switch (actionId) {
            case ControlApi.ZOOM:
                camera.setZoom(value);
                break;
            case ControlApi.WB_MODE:
                camera.setWbMode((int) value);
                break;
            case ControlApi.AF_MODE:
                camera.setAfMode((int) value);
                break;
            case ControlApi.EV:
                camera.setEv(value);
                break;
            case ControlApi.WB_LEVEL:
                camera.setWbLevel((int) value);
                break;
            case ControlApi.MIC_TOGGLE:
                micMuted = !micMuted;
                if (audio != null) {
                    audio.attachSink(micMuted ? null : audioSink);
                }
                break;
            case ControlApi.AF_TRIGGER:
                camera.triggerAf();
                break;
            case ControlApi.SHUTTER:
                camera.setShutter(value);
                break;
            case ControlApi.ISO:
                camera.setIso(value);
                break;
            case ControlApi.MF:
                camera.setMf(value);
                break;
            default:
                break;
        }
    }

    @Override
    public void onTorchToggle() {
        if (camera != null) {
            camera.toggleTorch();
            torchOn = !torchOn;
        }
    }

    @Override
    public void onWbLockToggle() {
        if (camera != null) {
            camera.toggleWbLock();
        }
    }

    @Override
    public void onAeToggle() {
        if (camera != null) {
            camera.toggleAe();
        }
    }

    @Override
    public void onExposureLockToggle() {
        if (camera != null) {
            camera.toggleExposureLock();
        }
    }

    @Override
    public void onTally(int tallyState) {
        Logs.i("tally: " + tallyState);
    }

    @Override
    public void onRestart() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (state != STATE_RUNNING) {
                    return;
                }
                // Restart the whole pipeline, not just the server, so setting changes
                // (fps, resolution, port, codec, …) take effect immediately — the old
                // behaviour only rebound the socket and silently kept the old camera
                // config, which made "Restart" useless for applying new settings.
                final FrameSink v = videoSink;
                final FrameSink a = audioSink;
                port = Prefs.port(StreamService.this);
                parseResolution(Prefs.resolution(StreamService.this));
                fps = Prefs.fps(StreamService.this);
                if (server != null) {
                    server.shutdown();
                    server = null;
                }
                openCameraPipeline();
                // Re-attach the still-connected clients to the fresh pipeline.
                if (v != null) {
                    if (v instanceof FramedSink) {
                        if (usingEncoder && pipeline != null) {
                            pipeline.attachSink(v);
                            videoSink = v;
                        } else {
                            Logs.i("framed client dropped after restart — no encoder available");
                        }
                    } else if (mjpeg != null) {
                        mjpeg.sink = v;
                        videoSink = v;
                    }
                }
                if (a != null && audio != null) {
                    audio.attachSink(micMuted ? null : a);
                    audioSink = a;
                }
                startServer();
                updateNotification();
                Logs.i("restarted with fresh settings: " + width + "x" + height + " @" + fps + "fps");
            }
        });
    }

    @Override
    public void onStop() {
        stopStreaming();
    }

    // ---- helpers --------------------------------------------------------------

    /**
     * True while a video client holds the stream. Reads the producer's live sink so the
     * busy flag clears the moment the client disconnects (a stale flag would otherwise
     * refuse every later client until the service restarts).
     */
    private boolean videoBusy() {
        if (usingEncoder) {
            return pipeline != null && pipeline.sink != null;
        }
        return mjpeg != null && mjpeg.sink != null;
    }

    private boolean audioBusy() {
        return audio != null && audio.hasClient();
    }

    private void ensureCodec(String codec) {
        boolean needEncoded = codec.equals("avc") || codec.equals("hevc");
        if (needEncoded == usingEncoder) {
            return;
        }
        if (needEncoded) {
            if (pipeline == null) {
                try {
                    pipeline = new VideoEncoderPipeline(
                            codec.equals("hevc") ? VideoEncoderPipeline.HEVC : VideoEncoderPipeline.AVC);
                    encoderSurface = pipeline.start(width, height, fps, Prefs.bitrateKbps(this));
                } catch (IOException e) {
                    Logs.e("encoder switch failed", e);
                    return;
                }
            }
            usingEncoder = true;
            if (camera != null) {
                camera.setEncoderSurface(encoderSurface);
            }
        } else {
            usingEncoder = false;
            if (camera != null) {
                camera.setEncoderSurface(null);
            }
            if (pipeline != null) {
                pipeline.stop();
                pipeline = null;
            }
        }
    }

    private void ensureAudio() {
        if (audio == null) {
            audio = new AudioStream(Prefs.audioSampleRate(this), 1,
                    Prefs.audioBitrateKbps(this), MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            try {
                audio.start();
            } catch (IOException e) {
                Logs.e("audio start failed", e);
            }
        }
    }

    /**
     * Find the phone's LAN IPv4 address without requiring location / NEARBY_WIFI_DEVICES
     * permissions. On Android 12+ WifiManager#getIpAddress() returns 0 unless the app holds
     * those permissions, which silently hid the streaming address from users. Enumerating
     * NetworkInterfaces needs no permission and also covers USB tethering / Ethernet.
     * Prefers Wi-Fi, then Ethernet, then USB/RNDIS, then hotspot (AP).
     */
    private String computeIp() {
        String best = null;
        int bestScore = -1;
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName().toLowerCase(Locale.US);
                // VPN/tunnel interfaces advertise addresses the PC can't reach — skip them.
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("p2p")) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress() || !(a instanceof java.net.Inet4Address)) {
                        continue;
                    }
                    int score;
                    if (name.startsWith("wlan")) {
                        score = 4;
                    } else if (name.startsWith("eth")) {
                        score = 3;
                    } else if (name.startsWith("usb") || name.startsWith("rndis")) {
                        score = 2;
                    } else if (name.startsWith("ap")) {
                        score = 1; // hotspot: the phone IS the gateway, PC clients reach it here
                    } else {
                        score = 0;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        best = a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (best != null) {
            return best;
        }
        // Fallback for older Android (< 12) where the old API still works.
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip == 0) {
                return "";
            }
            return String.format(Locale.US, "%d.%d.%d.%d",
                    ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Compute the current LAN IP on demand. Used by the UI so the streaming address is
     * visible before (and without) starting the stream.
     */
    public String currentIp() {
        return computeIp();
    }

    private void acquireLocks() {
        try {
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Exception ignored) {
        }
        try {
            if (!wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
        try {
            if (multicastLock != null && !multicastLock.isHeld()) {
                multicastLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private void setState(final int newState) {
        state = newState;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onStateChanged(newState);
                }
            }
        });
    }

    private void notifyError(final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onError(message);
                }
            }
        });
    }

    private void notifyCameraChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onCameraChanged();
                }
            }
        });
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Streaming",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private void updateNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent stopIntent = new Intent(this, StreamService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        String text = ipAddress.isEmpty()
                ? "Streaming on port " + port
                : "Streaming on " + ipAddress + ":" + port;
        Notification n = builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(0, getString(R.string.notification_stop), stopPi)
                .build();
        startForeground(1, n);
    }
}
