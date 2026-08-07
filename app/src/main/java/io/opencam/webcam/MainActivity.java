package io.opencam.webcam;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import io.opencam.webcam.util.Logs;

/**
 * Launcher activity: live preview, start/stop streaming, connection address, camera switch,
 * torch, and a link to settings.
 */
public class MainActivity extends Activity
        implements StreamService.Listener, SurfaceHolder.Callback {

    private SurfaceView preview;
    private TextView status;
    private TextView address;
    private TextView videoUrl;
    private Button btnStream;
    private Button btnCamera;
    private Button btnTorch;
    private View statusDot;
    private View placeholder;
    private TextView badgeLive;
    private ImageButton btnCopy;

    private StreamService service;
    private boolean bound;
    private boolean torchOn;
    private boolean permissionGranted;
    private String lastError = "";

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((StreamService.LocalBinder) binder).getService();
            bound = true;
            service.setListener(MainActivity.this);
            SurfaceHolder holder = preview.getHolder();
            if (holder.getSurface() != null && holder.getSurface().isValid()) {
                service.setPreviewSurface(holder.getSurface());
            }
            lastError = service.getLastError();
            updateUi();
            applyMirror();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preview = findViewById(R.id.preview);
        status = findViewById(R.id.status);
        address = findViewById(R.id.address);
        videoUrl = findViewById(R.id.video_url);
        btnStream = findViewById(R.id.btn_stream);
        btnCamera = findViewById(R.id.btn_camera);
        btnTorch = findViewById(R.id.btn_torch);
        ImageButton btnSettings = findViewById(R.id.btn_settings);
        statusDot = findViewById(R.id.status_dot);
        placeholder = findViewById(R.id.preview_placeholder);
        badgeLive = findViewById(R.id.badge_live);
        btnCopy = findViewById(R.id.btn_copy);
        // idle dot color until the first state callback arrives after bind
        setStatusDot(getResources().getColor(R.color.status_idle));

        preview.getHolder().addCallback(this);

        // Bind up front (not just on Start tap) so the preview surface is wired to the
        // service before streaming begins — otherwise the camera can open with a null
        // preview and the first frames show nothing.
        bindService(new Intent(MainActivity.this, StreamService.class), connection,
                Context.BIND_AUTO_CREATE);

        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAddress();
            }
        });

        btnStream.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!permissionGranted) {
                    requestPermissions();
                    return;
                }
                if (service != null && service.getState() == StreamService.STATE_RUNNING) {
                    service.stopStreaming();
                } else {
                    startService(new Intent(MainActivity.this, StreamService.class));
                }
            }
        });

        btnCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) {
                    service.switchCamera();
                }
            }
        });

        // Long-press the address / OBS URL to copy it to the clipboard.
        View.OnLongClickListener copyUrl = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return copyAddress();
            }
        };
        address.setOnLongClickListener(copyUrl);
        videoUrl.setOnLongClickListener(copyUrl);

        btnTorch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) {
                    torchOn = !torchOn;
                    service.setTorchOn(torchOn);
                    updateTorchUi();
                }
            }
        });

        // initial (off) styling for the torch card
        updateTorchUi();
    }

    /**
     * Copy just the streaming URL (without any label prefix) to the clipboard.
     *
     * @return true when an address was copied.
     */
    private boolean copyAddress() {
        String text = address.getText().toString();
        int scheme = text.indexOf("http://");
        if (scheme < 0) {
            return false;
        }
        String url = text.substring(scheme);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("OpenCam URL", url));
        Toast.makeText(MainActivity.this, R.string.url_copied, Toast.LENGTH_SHORT).show();
        return true;
    }

    /** Reflect the torch state in its control card (accent border + tint when on). */
    private void updateTorchUi() {
        btnTorch.setBackgroundResource(torchOn ? R.drawable.bg_btn_active : R.drawable.bg_btn_secondary);
        // Compound-drawable tint list (not setTint on the drawable): the framework
        // re-applies the XML drawableTint on every state change, which would wipe a
        // plain setTint() as soon as the button is pressed/released.
        btnTorch.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(torchOn ? R.color.accent : R.color.text_secondary)));
    }

    /** Color the status-pill dot for the current stream state. */
    private void setStatusDot(int color) {
        if (statusDot != null && statusDot.getBackground() != null) {
            statusDot.getBackground().setTint(color);
        }
    }

    /** Swap the main CTA between the accent Start pill and the danger Stop pill. */
    private void setStreamButton(boolean streaming) {
        btnStream.setBackgroundResource(streaming ? R.drawable.bg_btn_stop : R.drawable.bg_btn_primary);
        btnStream.setTextColor(streaming ? 0xFFFFFFFF : 0xFF001014);
    }

    @Override
    protected void onResume() {
        super.onResume();
        permissionGranted = hasPermissions();
        if (!permissionGranted) {
            requestPermissions();
        }
        if (service != null) {
            lastError = service.getLastError();
        }
        applyMirror();
        refreshCameraButton();
    }

    /**
     * The camera-switch button only makes sense when the phone has more than one camera.
     * Before the service has opened the camera we keep it disabled.
     */
    private void refreshCameraButton() {
        boolean usable = service != null && service.getCameraCount() > 1;
        btnCamera.setEnabled(usable);
        btnCamera.setAlpha(usable ? 1f : 0.45f);
    }

    /**
     * Mirror the preview when the front (selfie) camera is active, so the phone screen
     * behaves like a mirror. Only the on-device preview flips — the stream stays
     * unmirrored (what viewers see).
     */
    private void applyMirror() {
        if (preview == null) {
            return;
        }
        preview.setScaleX(service != null && service.isFrontCamera() ? -1f : 1f);
    }

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            perms = new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS};
        }
        requestPermissions(perms, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        permissionGranted = hasPermissions();
    }

    // ---- SurfaceHolder.Callback ------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // Give the surface an explicit size. A SurfaceView whose surface is created
        // before layout assigns its size (height=0dp + weight) ends up as a 0x0 layer:
        // the camera still fills the buffer but nothing is displayed.
        //
        // The surface is sized to the camera's landscape aspect (letterboxed inside the
        // portrait view) so the 16:9 feed isn't stretched or rotated into a portrait box.
        int w = preview.getWidth();
        int h = preview.getHeight();
        String res = Prefs.resolution(MainActivity.this);
        int rw = 1280, rh = 720;
        try {
            String[] parts = res.split("x");
            rw = Integer.parseInt(parts[0].trim());
            rh = Integer.parseInt(parts[1].trim());
        } catch (Exception ignored) {
        }
        Logs.i("surfaceCreated view=" + w + "x" + h
                + " frame=" + holder.getSurfaceFrame().width() + "x" + holder.getSurfaceFrame().height());
        if (w > 0) {
            int ph = Math.max(1, w * rh / rw);
            if (ph > h) {
                ph = h; // tall resolution selected: fit the view height instead
            }
            holder.setFixedSize(w, ph);
        }
        if (service != null) {
            service.setPreviewSurface(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Logs.i("surfaceChanged " + width + "x" + height);
        if (service != null) {
            service.setPreviewSurface(holder.getSurface());
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (service != null) {
            service.setPreviewSurface(null);
        }
    }

    // ---- StreamService.Listener -------------------------------------------------

    @Override
    public void onStateChanged(int state) {
        switch (state) {
            case StreamService.STATE_RUNNING:
                status.setText(R.string.status_streaming);
                btnStream.setText(R.string.stop_stream);
                setStreamButton(true);
                setStatusDot(getResources().getColor(R.color.success));
                badgeLive.setVisibility(View.VISIBLE);
                placeholder.setVisibility(View.GONE);
                applyMirror();
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                break;
            case StreamService.STATE_STARTING:
                status.setText(R.string.status_starting);
                btnStream.setText(R.string.stop_stream);
                setStreamButton(true);
                setStatusDot(getResources().getColor(R.color.accent));
                placeholder.setVisibility(View.GONE);
                break;
            case StreamService.STATE_ERROR:
                status.setText(lastError != null && !lastError.isEmpty()
                        ? lastError : getString(R.string.status_error));
                btnStream.setText(R.string.start_stream);
                setStreamButton(false);
                setStatusDot(getResources().getColor(R.color.danger));
                badgeLive.setVisibility(View.GONE);
                placeholder.setVisibility(View.VISIBLE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                break;
            default:
                status.setText(R.string.status_stopped);
                btnStream.setText(R.string.start_stream);
                setStreamButton(false);
                setStatusDot(getResources().getColor(R.color.status_idle));
                badgeLive.setVisibility(View.GONE);
                placeholder.setVisibility(View.VISIBLE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                break;
        }
        refreshCameraButton();
    }

    @Override
    public void onError(String message) {
        lastError = message;
        if (message != null && !message.isEmpty()) {
            status.setText(message);
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onCameraChanged() {
        applyMirror();
        refreshCameraButton();
    }

    @Override
    public void onAddressChanged(String addr) {
        // address changes are surfaced through updateUi()
    }

    private void updateUi() {
        if (service == null) {
            return;
        }
        String ip = service.getAddress();
        int port = service.getPort();
        if (ip == null || ip.isEmpty()) {
            // Not streaming yet (or the cached address is stale): show the phone's
            // LAN address on demand so the user knows what to type on the PC even
            // before tapping Start Streaming.
            ip = service.currentIp();
        }
        if (port == 0) {
            port = Prefs.port(this);
        }
        if (ip == null || ip.isEmpty()) {
            address.setText(R.string.address_hint);
            videoUrl.setText("");
        } else {
            address.setText("http://" + ip + ":" + port);
            videoUrl.setText(getString(R.string.video_url_hint, "http://" + ip + ":" + port));
        }
        onStateChanged(service.getState());
    }

    @Override
    protected void onDestroy() {
        // Drop our listener reference before unbinding: the foreground service
        // outlives this activity, so a stale listener would pin the whole
        // activity (views, window, context) in memory until the service stops.
        if (service != null) {
            service.setListener(null);
        }
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }
}
