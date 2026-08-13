# OpenCam — free wireless phone webcam for OBS

OpenCam turns your Android phone into a high-quality **wireless (or USB) webcam**
that works directly with the
[droidcam-obs-plugin](https://github.com/dev47apps/droidcam-obs-plugin) — the
same plugin the commercial *DroidCam* app uses. No plugin to write, no paid app
to unlock: **every feature is free and built in.**

| Feature | OpenCam |
|---|---|
| Video: H.264, HEVC (H.265), MJPEG | ✅ |
| Resolution up to 4K (camera dependent) | ✅ |
| FPS up to 60 (camera dependent) | ✅ |
| Audio streaming (AAC) | ✅ |
| Auto-discovery via mDNS (`_droidcamobs._tcp`) | ✅ |
| Wired USB mode (plugin does `adb forward` itself) | ✅ |
| Torch / flashlight | ✅ |
| Pinch & slider zoom | ✅ |
| Tap-to-focus | ✅ |
| Front / back / wide / tele lenses | ✅ |
| Mirror effect (flip stream + preview) | ✅ |
| Battery & tally reporting to OBS | ✅ |
| Background streaming (screen off) | ✅ |
| QR code with connection info | ✅ |

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — how the app works under the hood
- [PROTOCOL.md](PROTOCOL.md) — the exact wire protocol it implements
- [VALIDATION.md](VALIDATION.md) — checks performed for the v1.4.0 repair
- [AUDIT_REMEDIATION.md](AUDIT_REMEDIATION.md) — disposition of all 38 supplied findings
- [SECURITY.md](SECURITY.md) — signing-key rotation and network-risk notes

## How it works

The phone runs a small TCP server (default port **4747**) implementing the
DroidCam **v5** wire protocol. The OBS plugin connects to it, requests a video
format, and receives a framed H.264/HEVC/MJPEG stream plus an AAC audio stream.
Discovery happens over mDNS. See [PROTOCOL.md](PROTOCOL.md) for the exact wire
format.

## Build

Requirements: JDK 17+, Android SDK (platform 36 / build-tools 36).

```bash
# open the project in Android Studio, or from the command line:
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

> On Windows use `gradlew.bat`.

## Install & use

1. Install the APK on your phone, grant **Camera** and **Microphone**
   permissions (and notification permission on Android 13+).
2. Make sure the phone and PC are on the **same Wi-Fi network**.
3. Connect from your PC in one of two ways:
   - **Standalone PC app (no OBS):** run the OpenCam Studio PC client
     ([`pc-client-native/`](pc-client-native/)). It discovers the phone over
     mDNS (or take its IP from the app/QR) and shows the camera in a window —
     with mirror, audio, screenshots and live phone controls. Run it with
     `npm start` from `pc-client-native/`, or build a portable Windows `.exe`
     with `npm run build`.
   - **OBS:** install the **DroidCam OBS plugin** from
     [droidcam.app/obs](https://droidcam.app/obs) (OBS → Tools → "DroidCam"
     install helper). In OBS: **+ → DroidCam → Refresh** — your phone appears
     as `OpenCam-XXXX ... (WiFi)`. Select it and click **Activate**.
     - No discovery? Enter the phone's **IP** and **port 4747** manually (shown
       in the app; QR button in the app shows the same info).
4. Optionally enable **Enable Audio** in the plugin properties.

### Mirror effect

The mirror toggle (bottom control bar) flips the stream left/right — handy for
front-camera calls and presentations. It mirrors both the phone preview and the
video sent to the PC so what you see is what the PC receives. The PC client has
its own mirror toggle too.

### USB (wired) mode

Enable **USB debugging** on the phone, plug it in, and install the Android
platform tools. The plugin auto-detects ADB devices and sets up the port
forward itself — just pick the device in the plugin's device list.

### Streaming with the screen off

OpenCam runs as a foreground service, so the stream keeps working when you
switch apps or turn the screen off. On some OEMs you must also
**disable battery optimization** for OpenCam (Settings → Apps → OpenCam →
Battery → Unrestricted), otherwise the OS may kill background streaming.

## Troubleshooting

- **Plugin can't find the phone** → same Wi-Fi? AP isolation enabled on the
  router? Try entering the IP manually. Some routers block mDNS between
  clients.
- **No video after connecting** → check the app's status line for the active
  codec/resolution; if it shows an error, the camera may not support the
  requested resolution (pick a smaller one).
- **Video orientation looks stale** → rotate once after activation or reconnect
  the OBS source. OpenCam observes 0°/90°/180°/270° display changes and rebuilds
  the encoded stream; the plugin reconnects to the new dimensions.
- **Audio missing** → enable *Enable Audio* in the plugin properties.
- **Stream stutters** → lower the resolution/fps or bitrate in the app
  settings.
- **Port already in use** → change the port in OpenCam settings (e.g. 4748)
  and use that port in the plugin.

## Security

OpenCam has **no authentication** — like DroidCam, anyone on the same network
can connect to the stream port and pull your camera/mic feed. Only run it on
networks you trust, and stop the stream when you're done (toggle in the app or
via the notification).

## Releases & CI

Every pushed tag starting with `v` triggers a GitHub Actions workflow that
builds, signs, verifies and publishes the APK. All four signing secrets are
required; the workflow and Gradle release tasks fail instead of publishing an
unsigned artifact. Local debug builds do not need signing material.

- Workflow: [.github/workflows/release.yml](.github/workflows/release.yml)
- Latest release: <https://github.com/soupashh-ship-it/opencam/releases/latest>

## License

[MIT](LICENSE) © soupashh-ship-it

## License / ethics

OpenCam is an original, clean-room implementation of the *wire protocol* that
the GPL-licensed droidcam-obs-plugin speaks; it contains no code from DroidCam.
The name "DroidCam" belongs to its owner — OpenCam is not affiliated with it.
Use it for your own streaming setups. ⚠️ Respect local laws and the privacy of
people around you when using any camera app.

## Project layout

```
app/src/main/java/com/opencam/
├── server/     StreamServer + Protocol (the OBS-plugin wire protocol)
├── encode/     H.264/HEVC (MediaCodec), AAC (MediaCodec), Annex-B conversion
├── camera/     Camera2 controller (session, zoom, torch, focus, exposure)
├── stream/     StreamManager — orchestrates camera + encoders + server + mDNS
├── discovery/  mDNS advertisement of _droidcamobs._tcp
├── service/    Foreground service for background streaming
└── ui/         Jetpack Compose UI (preview, controls, settings, QR)

pc-client-native/  OpenCam Studio — native Windows PC client (Electron)
```
