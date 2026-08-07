package io.opencam.webcam.video;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.RggbChannelVector;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import io.opencam.webcam.util.Logs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Camera2 capture session manager. Renders to an optional preview {@link Surface}, feeds an
 * optional {@link MjpegProducer}, and optionally an encoded-video input {@link Surface}.
 * All camera operations run on a dedicated handler thread.
 */
public class CameraController {

    public interface Listener {
        void onOpened(int width, int height);

        void onError(String message);
    }

    private final CameraManager manager;
    private final HandlerThread cameraThread;
    private final Handler handler;

    private Listener listener;
    private CameraDevice camera;
    private CameraCaptureSession session;
    // Written on the camera thread, read from the HTTP thread by infoJson() -> must be volatile.
    private volatile CameraCharacteristics characteristics;
    private CaptureRequest.Builder builder;
    private volatile String cameraId;

    private int width;
    private int height;
    private int fps;
    private Surface previewSurface;
    private MjpegProducer mjpeg;
    private Surface encoderSurface;
    // False in encoded mode: the MJPEG ImageReader is a dormant session target then, and
    // a full-res YUV/JPEG reader alongside the encoder surface forces the HAL to produce
    // frames for it every cycle — on most devices that caps the whole session at ~30fps
    // no matter what fps is requested. Excluding it leaves the encoder surface + preview
    // alone, which is how the 1080p60 path actually reaches 60.
    // Written from open()/setStreamOutputs() on the main/HTTP threads, read on the
    // camera handler thread by createSessionLocked() — must be volatile.
    private volatile boolean includeMjpegReader = true;
    // True while createCaptureSession() is in flight — a second call while one is
    // configuring fails with "session busy" on many HALs. restartSessionLocked()
    // coalesces into restartQueued instead of issuing a parallel create.
    private boolean creatingSession;
    private boolean restartQueued;
    private volatile boolean running;
    private boolean closing;
    private int openAttempts;
    // Set while restart() is tearing down the old session and bringing up a new one.
    // Guards onClosed() from re-creating the session while the replacement is still
    // configuring (a second createCaptureSession in flight fails with "session busy").
    private boolean reopening;

    // ---- control state (volatile so infoJson() can be read from any thread) ----
    private volatile float zoom = 1f;
    private volatile float maxZoom = 1f;
    private volatile boolean torch;
    private volatile boolean wbLock;
    private volatile boolean aeLock;
    private volatile boolean exposureLock;
    private volatile int wbMode = -1;   // -1 auto, 0..7 presets, 8 manual
    private volatile int wbLevel = 50;
    private volatile float ev = 0f;
    private volatile float iso = -1f;   // -1 auto
    private volatile float shutterS = -1f; // seconds, -1 auto
    private volatile int afMode = -1;   // -1 auto
    private volatile float focusDistance = 0f; // diopters, 0 auto

    public CameraController(Context context) {
        manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        cameraThread = new HandlerThread("opencam.camera");
        cameraThread.start();
        handler = new Handler(cameraThread.getLooper());
    }

    /** Open a camera. Returns immediately; result arrives via {@code listener}. */
    public void open(final String id, int w, int h, int targetFps,
                     Surface preview, MjpegProducer mjpegProducer, Surface enc,
                     boolean includeMjpeg, Listener l) {
        this.listener = l;
        this.cameraId = id;
        this.width = w;
        this.height = h;
        this.fps = targetFps;
        this.previewSurface = preview;
        this.mjpeg = mjpegProducer;
        this.encoderSurface = enc;
        this.includeMjpegReader = includeMjpeg;
        this.closing = false;
        handler.post(new Runnable() {
            @Override
            public void run() {
                openLocked(id);
            }
        });
    }

    /** Called when the UI preview surface becomes available / changes. */
    public void setPreviewSurface(final Surface surface) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                previewSurface = surface;
                restartSessionLocked();
            }
        });
    }

    /**
     * Atomically set the encoder surface and whether the MJPEG reader is a session target,
     * then rebuild the session once. (Ensuring both in one post avoids two session restarts
     * racing during a codec switch.)
     */
    public void setStreamOutputs(final Surface enc, final boolean includeMjpeg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                encoderSurface = enc;
                includeMjpegReader = includeMjpeg;
                restartSessionLocked();
            }
        });
    }

    /**
     * Close the current camera and reopen it with new settings on the same handler thread.
     * Sequencing close-then-open on one looper avoids the "camera in use" race that occurs
     * when reopening an id while the previous close is still in flight.
     */
    public void restart(final String id, int w, int h, int targetFps,
                        Surface preview, MjpegProducer mjpegProducer, Surface enc,
                        boolean includeMjpeg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                closing = true;
                reopening = true;
                running = false;
                if (session != null) {
                    session.close();
                    session = null;
                }
                if (camera != null) {
                    camera.close();
                    camera = null;
                }
                cameraId = id;
                width = w;
                height = h;
                fps = targetFps;
                previewSurface = preview;
                mjpeg = mjpegProducer;
                encoderSurface = enc;
                includeMjpegReader = includeMjpeg;
                closing = false;
                openLocked(id);
            }
        });
    }

    public void close() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                closing = true;
                running = false;
                if (session != null) {
                    session.close();
                    session = null;
                }
                if (camera != null) {
                    camera.close();
                    camera = null;
                }
            }
        });
        cameraThread.quitSafely();
    }

    /**
     * Pick the closest camera-supported YUV capture size for a requested resolution, so
     * an unsupported setting (e.g. 4K on a 1080p-max sensor) still applies the best real
     * size instead of silently leaving the stream at the old resolution. Returns the
     * request unchanged when the camera can't be queried.
     */
    public static int[] pickSupportedSize(CameraManager manager, String cameraId, int w, int h) {
        try {
            CameraCharacteristics cc = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return new int[]{w, h};
            }
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (sizes == null || sizes.length == 0) {
                sizes = map.getOutputSizes(android.view.SurfaceHolder.class);
            }
            if (sizes == null || sizes.length == 0) {
                return new int[]{w, h};
            }
            int target = w * h;
            float reqRatio = (float) w / h;
            Size best = sizes[0];
            double bestScore = Double.MAX_VALUE;
            for (Size s : sizes) {
                float ratio = (float) s.getWidth() / s.getHeight();
                double ratioDelta = Math.abs(ratio - reqRatio);
                // Aspect ratio dominates, area breaks ties: a 16:9 request never falls
                // back to a 4:3 size (which would stretch the image) — only sizes with
                // the same (or near) ratio compete by closeness of resolution.
                double score = ratioDelta * 1e12 + Math.abs(s.getWidth() * s.getHeight() - target);
                if (score < bestScore) {
                    best = s;
                    bestScore = score;
                }
            }
            return new int[]{best.getWidth(), best.getHeight()};
        } catch (CameraAccessException | IllegalArgumentException e) {
            return new int[]{w, h};
        }
    }

    // ---- controls ------------------------------------------------------------

    public void setZoom(final float z) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                zoom = clamp(z, 1f, maxZoom);
                applyLocked();
            }
        });
    }

    public void toggleTorch() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                torch = !torch;
                applyLocked();
            }
        });
    }

    public void setWbMode(final int mode) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                wbMode = mode;
                applyLocked();
            }
        });
    }

    public void toggleWbLock() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                wbLock = !wbLock;
                applyLocked();
            }
        });
    }

    public void toggleAe() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                aeLock = !aeLock;
                applyLocked();
            }
        });
    }

    public void toggleExposureLock() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                exposureLock = !exposureLock;
                if (exposureLock) {
                    // keep whatever the current AE was doing; just lock it
                }
                applyLocked();
            }
        });
    }

    public void setEv(final float value) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                ev = value;
                applyLocked();
            }
        });
    }

    public void setIso(final float value) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                iso = value <= 0 ? -1f : value;
                applyLocked();
            }
        });
    }

    public void setShutter(final float seconds) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                shutterS = seconds <= 0 ? -1f : seconds;
                applyLocked();
            }
        });
    }

    public void setWbLevel(final int level) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                wbLevel = clamp(level, 0, 100);
                applyLocked();
            }
        });
    }

    public void setAfMode(final int mode) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                afMode = mode;
                applyLocked();
            }
        });
    }

    public void triggerAf() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (builder == null || session == null || !hasAf()) {
                    return;
                }
                try {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CaptureRequest.CONTROL_AF_TRIGGER_START);
                    session.capture(builder.build(), null, handler);
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                    session.capture(builder.build(), null, handler);
                } catch (CameraAccessException e) {
                    Logs.e("AF trigger failed", e);
                }
            }
        });
    }

    public void setMf(final float diopters) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                focusDistance = diopters;
                applyLocked();
            }
        });
    }

    public boolean isRunning() {
        return running;
    }

    public String cameraId() {
        return cameraId;
    }

    /** True when the selected camera faces the user (selfie). */
    public boolean isFront() {
        CameraCharacteristics c = characteristics;
        if (c == null) {
            return false;
        }
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        return facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
    }

    /** Snapshot of the current camera state for GET /v1/camera/info. */
    public String infoJson(boolean audioMute) {
        StringBuilder sb = new StringBuilder(256);
        float mfMax = 0f;
        if (characteristics != null) {
            Float minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            mfMax = minFocus != null ? minFocus : 0f;
        }
        if (mfMax == 0f) {
            mfMax = 10f;
        }
        Range<Integer> isoRange = characteristics != null
                ? characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) : null;
        Range<Long> ssRange = characteristics != null
                ? characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) : null;
        int evMin = 0;
        int evMax = 0;
        if (characteristics != null) {
            Range<Integer> evRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (evRange != null) {
                evMin = evRange.getLower();
                evMax = evRange.getUpper();
            }
        }
        sb.append("{\"active\":").append(running ? 1 : 0);
        sb.append(",\"focusMode\":").append(afMode);
        sb.append(",\"mfValue\":").append(focusDistance > 0 ? focusDistance : -1.0f);
        sb.append(",\"mfMax\":").append(mfMax);
        sb.append(",\"zmValue\":").append(zoom > 1 ? zoom : -1.0f);
        sb.append(",\"zmMin\":1,\"zmMax\":").append(maxZoom);
        sb.append(",\"evValue\":").append((int) ev).append(",\"evMin\":").append(evMin).append(",\"evMax\":").append(evMax);
        sb.append(",\"isoValue\":").append(iso > 0 ? (int) iso : -1);
        sb.append(",\"isoMin\":").append(isoRange != null ? isoRange.getLower() : 100)
          .append(",\"isoMax\":").append(isoRange != null ? isoRange.getUpper() : 6400);
        sb.append(",\"ssValue\":").append(shutterS > 0 ? (long) (shutterS * 1e6) : -1);
        sb.append(",\"ssMin\":").append(ssRange != null ? ssRange.getLower() : 1000L)
          .append(",\"ssMax\":").append(ssRange != null ? ssRange.getUpper() : 1000000L);
        sb.append(",\"wbMode\":").append(wbMode);
        sb.append(",\"wbLock\":").append(wbLock ? 1 : -1);
        sb.append(",\"wbValue\":").append(wbMode == 8 ? wbLevel : -1);
        sb.append(",\"wbMin\":0,\"wbMax\":100");
        sb.append(",\"led_on\":").append(torch ? 1 : -1);
        sb.append(",\"aeLock\":").append(aeLock);
        sb.append(",\"ssLock\":").append(exposureLock);
        sb.append(",\"isoLock\":").append(exposureLock);
        sb.append(",\"audioMute\":").append(audioMute);
        sb.append('}');
        return sb.toString();
    }

    // ---- internal ------------------------------------------------------------

    private void openLocked(String id) {
        try {
            characteristics = manager.getCameraCharacteristics(id);
            Float maxZoomF = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = maxZoomF != null && maxZoomF > 1 ? maxZoomF : 1f;
            if (mjpeg != null) {
                mjpeg.attach(handler);
            }
            openAttempts = 0;
            openCamera(id);
        } catch (CameraAccessException e) {
            Logs.e("camera characteristics failed", e);
            fail("Camera access error: " + e.getMessage());
        } catch (SecurityException e) {
            Logs.e("camera permission missing", e);
            fail("Camera permission missing");
        }
    }

    private void openCamera(final String id) {
        try {
            manager.openCamera(id, stateCallback, handler);
        } catch (CameraAccessException e) {
            // Some HALs briefly report CAMERA_IN_USE / MAX_CAMERAS_IN_USE right after a
            // close (e.g. during a fast camera switch). Retry a couple of times first.
            if (!closing && openAttempts++ < 2) {
                Logs.i("camera busy, retrying (" + openAttempts + ")");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!closing) {
                            openCamera(id);
                        }
                    }
                }, 350);
            } else {
                Logs.e("camera open failed", e);
                fail("Camera access error: " + e.getMessage());
            }
        } catch (SecurityException e) {
            Logs.e("camera permission missing", e);
            fail("Camera permission missing");
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice device) {
            if (closing) {
                // Teardown began while this open was in flight; don't build a session
                // with surfaces that may already be closed.
                device.close();
                return;
            }
            camera = device;
            createSessionLocked();
        }

        @Override
        public void onDisconnected(CameraDevice device) {
            if (closing) {
                return; // shutdown in progress; the service already owns teardown
            }
            device.close();
            camera = null;
            running = false;
            if (listener != null) {
                listener.onError("Camera disconnected");
            }
        }

        @Override
        public void onError(CameraDevice device, int error) {
            if (closing) {
                return; // shutdown in progress; the service already owns teardown
            }
            device.close();
            camera = null;
            running = false;
            if (listener != null) {
                listener.onError("Camera error " + error);
            }
        }
    };

    private void createSessionLocked() {
        if (camera == null) {
            return;
        }
        try {
            List<Surface> targets = new ArrayList<>();
            if (previewSurface != null && previewSurface.isValid()) {
                targets.add(previewSurface);
            }
            if (includeMjpegReader && mjpeg != null && mjpeg.reader.getSurface().isValid()) {
                targets.add(mjpeg.reader.getSurface());
            }
            if (encoderSurface != null && encoderSurface.isValid()) {
                targets.add(encoderSurface);
            }
            if (targets.isEmpty()) {
                fail("No output surfaces available");
                return;
            }
            StringBuilder tb = new StringBuilder("session targets:");
            for (Surface s : targets) {
                tb.append(" [").append(s).append(" valid=").append(s.isValid()).append(']');
            }
            Logs.i(tb.toString());
            builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            for (Surface s : targets) {
                builder.addTarget(s);
            }
            creatingSession = true;
            camera.createCaptureSession(targets, sessionCallback, handler);
        } catch (CameraAccessException e) {
            creatingSession = false;
            Logs.e("create session failed", e);
            fail("Failed to start camera session");
        } catch (IllegalStateException e) {
            // e.g. session busy / device closed while configuring — never crash on it.
            creatingSession = false;
            Logs.e("create session failed", e);
            fail("Failed to start camera session");
        } catch (IllegalArgumentException e) {
            // e.g. a surface was abandoned between the isValid() check and the call.
            creatingSession = false;
            Logs.e("create session failed", e);
            fail("Failed to start camera session");
        }
    }

    private final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession s) {
            creatingSession = false;
            if (camera == null || closing) {
                return;
            }
            session = s;
            reopening = false;
            if (restartQueued) {
                // A surface-set change arrived while this session was configuring —
                // rebuild immediately with the current targets.
                restartQueued = false;
                restartSessionLocked();
                return;
            }
            applyLocked();
            running = true;
            if (listener != null) {
                listener.onOpened(width, height);
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession s) {
            creatingSession = false;
            restartQueued = false;
            fail("Camera session configuration failed");
        }

        @Override
        public void onClosed(CameraCaptureSession s) {
            if (camera != null && !closing && !reopening && session == null) {
                // session was restarted; recreate with the current surface set
                createSessionLocked();
            }
        }
    };

    /** Recreate the capture session (used when the surface set changes). */
    private void restartSessionLocked() {
        if (camera == null) {
            return;
        }
        if (creatingSession) {
            // A createCaptureSession is already in flight — queue the rebuild instead
            // of issuing a parallel one (many HALs reject with "session busy"). The
            // in-flight session's onConfigured/onClosed will drain the queue.
            restartQueued = true;
            return;
        }
        if (session != null) {
            CameraCaptureSession old = session;
            session = null;
            old.close(); // onClosed() will recreate
        } else {
            createSessionLocked();
        }
    }

    private void applyLocked() {
        if (builder == null || session == null) {
            return;
        }
        try {
            // target frame rate (AE-driven) — prefer a range the sensor actually
            // advertises (an unsupported fixed range makes some HALs fall back to 30).
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pickFpsRange(fps));

            // digital zoom
            Rect activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (activeArray != null && zoom > 1f) {
                int cropW = (int) (activeArray.width() / zoom);
                int cropH = (int) (activeArray.height() / zoom);
                int left = (activeArray.width() - cropW) / 2;
                int top = (activeArray.height() - cropH) / 2;
                builder.set(CaptureRequest.SCALER_CROP_REGION,
                        new Rect(left, top, left + cropW, top + cropH));
            } else if (activeArray != null) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, activeArray);
            }

            // torch
            builder.set(CaptureRequest.FLASH_MODE,
                    torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);

            // exposure: manual when ISO or shutter given, else AE + EV
            boolean manualExposure = iso > 0 || shutterS > 0;
            if (manualExposure) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                if (iso > 0) {
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, (int) iso);
                }
                if (shutterS > 0) {
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, (long) (shutterS * 1e6));
                }
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                Range<Integer> evRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                if (evRange != null) {
                    int evStep = 1;
                    Rational step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
                    if (step != null && step.getNumerator() != 0) {
                        evStep = step.getDenominator() / step.getNumerator();
                    }
                    int v = Math.round(ev * evStep);
                    v = clamp(v, evRange.getLower(), evRange.getUpper());
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, v);
                }
            }
            Boolean aeLockSupported = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
            if (aeLockSupported != null && aeLockSupported) {
                // The exposure-lock endpoint drives the same AE lock mechanism.
                builder.set(CaptureRequest.CONTROL_AE_LOCK, aeLock || exposureLock);
            }

            // white balance
            applyWbLocked();

            // focus
            applyFocusLocked();

            session.setRepeatingRequest(builder.build(), null, handler);
        } catch (CameraAccessException e) {
            Logs.e("apply failed", e);
        }
    }

    private void applyWbLocked() {
        boolean manual = wbMode == 8;
        if (manual) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
            RggbChannelVector gains = tempToGains(2000 + (wbLevel * 6000) / 100);
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains);
        } else {
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_FAST);
            int mode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
            switch (wbMode) {
                case 0: mode = CaptureRequest.CONTROL_AWB_MODE_AUTO; break;
                case 1: mode = CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT; break;
                case 2: mode = AWB_MODE_FLUORESCENT_WARM; break;
                case 3: mode = CaptureRequest.CONTROL_AWB_MODE_TWILIGHT; break;
                case 4: mode = CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT; break;
                case 5: mode = CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT; break;
                case 6: mode = CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT; break;
                case 7: mode = CaptureRequest.CONTROL_AWB_MODE_SHADE; break;
                default: break;
            }
            int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
            if (modes != null && Arrays.binarySearch(modes, mode) < 0) {
                mode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
            }
            builder.set(CaptureRequest.CONTROL_AWB_MODE, mode);
        }
        Boolean wbLockSupported = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);
        if (wbLockSupported != null && wbLockSupported) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, wbLock);
        }
    }

    private void applyFocusLocked() {
        boolean hasAf = hasAf();
        if (!hasAf) {
            return;
        }
        if (focusDistance > 0) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            Float minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            if (minFocus != null && minFocus > 0) {
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE,
                        clamp(focusDistance, 0f, minFocus));
            }
            return;
        }
        int mode = CaptureRequest.CONTROL_AF_MODE_AUTO;
        switch (afMode) {
            case 1: mode = CaptureRequest.CONTROL_AF_MODE_MACRO; break;
            case 2: mode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO; break;
            // Camera2 has no INFINITY mode; EDOF (extended depth of field) is the closest
            // analog and is filtered by the availability check below.
            case 3: mode = CaptureRequest.CONTROL_AF_MODE_EDOF; break;
            case 0:
            default: mode = CaptureRequest.CONTROL_AF_MODE_AUTO; break;
        }
        int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (modes != null && Arrays.binarySearch(modes, mode) < 0) {
            mode = CaptureRequest.CONTROL_AF_MODE_AUTO;
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, mode);
    }

    /**
     * Choose the AE target-fps range closest to the requested rate that this sensor
     * actually advertises. Prefers the exact fixed range (e.g. (60,60)); otherwise the
     * range whose upper bound is nearest to (but not below) the target, so a 60fps
     * request on a sensor with only (30,60) still reaches 60 instead of collapsing to 30.
     */
    private Range<Integer> pickFpsRange(int target) {
        if (characteristics == null) {
            return new Range<>(target, target);
        }
        Range<Integer>[] ranges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            return new Range<>(target, target);
        }
        for (Range<Integer> r : ranges) {
            if (r.getLower() == target && r.getUpper() == target) {
                return r;
            }
        }
        Range<Integer> best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Range<Integer> r : ranges) {
            int upper = r.getUpper();
            if (upper < target) {
                continue; // only ranges that can at least reach the target fps
            }
            int dist = upper - target;
            if (dist < bestDist) {
                best = r;
                bestDist = dist;
            }
        }
        if (best == null) {
            // nothing reaches the target (e.g. target 60 but max range is 30) —
            // take the highest available so we still get the best the sensor can do.
            best = ranges[0];
            for (Range<Integer> r : ranges) {
                if (r.getUpper() > best.getUpper()) {
                    best = r;
                }
            }
        }
        return best;
    }

    private boolean hasAf() {
        int[] modes = characteristics != null
                ? characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) : null;
        return modes != null && modes.length > 1;
    }

    private void fail(final String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (closing) {
                    return;
                }
                running = false;
                if (listener != null) {
                    listener.onError(message);
                }
            }
        });
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // AOSP value for CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT_WARM (= WARM_FLUORESCENT),
    // which is missing from some android.jar stubs. 4 is the platform constant.
    private static final int AWB_MODE_FLUORESCENT_WARM = 4;

    /** Approximate white-balance RGB gains for a color temperature (Kelvin). */
    private static RggbChannelVector tempToGains(int kelvin) {
        double t = kelvin / 100.0;
        double red = t <= 66 ? 255 : 329.698727446 * Math.pow(t - 60, -0.1332047592);
        double green = t <= 66
                ? 99.4708025861 * Math.log(t) - 161.1195681661
                : 288.1221695283 * Math.pow(t - 60, -0.0755148492);
        double blue = t >= 66 ? 255 : (t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307);
        red = clamp(red, 0, 255);
        green = clamp(green, 0, 255);
        blue = clamp(blue, 0, 255);
        return new RggbChannelVector(
                (float) (red / 255.0), (float) (green / 255.0),
                (float) (blue / 255.0), 1.0f);
    }
}
