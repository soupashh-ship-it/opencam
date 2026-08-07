# OpenCam

An **original, open-source** Android app that turns your phone into a Wi-Fi webcam for
[OBS Studio](https://obsproject.com/) — compatible with the same on-the-wire protocol family that
the DroidCam OBS plugin and classic MJPEG clients speak, so existing desktop tools can connect.


## Why

Commercial phone-webcam apps charge for basic functionality (HD video, remote control, etc.).
OpenCam is free, auditable, dependency-free (zero AndroidX, zero ad SDKs), and easy to build from
source.

## Features

- **Wi-Fi HTTP server** on a configurable port (default **4747**)
- **Video**
  - MJPEG over HTTP: `GET /video` — works in browsers, `ffplay`, VLC, and OBS via
    *Media Source → URL*
  - Modern framed streams for the OBS plugin / DroidCam-style clients:
    `GET /v5/video/{jpg|avc|hevc}/{W}x{H}?client=N` and `GET /v4/video/...` — 12-byte
    `[pts:8 LE][len:4 LE]` framing
  - Legacy OBS-plugin framing via `CMD /v3/video/...` (client=600)
  - H.264 (AVC) and H.265 (HEVC) via `MediaCodec`, JPEG via `ImageReader` + `YuvImage`
- **Audio**: AAC-LC (`MediaCodec`), `GET /v2/audio` and `GET /v1/audio.2`, raw AAC frames in the
  same 12-byte framing
- **Control API**: zoom, exposure/EV, ISO, shutter, white balance (+lock), autofocus (+manual
  focus), torch, mic mute, camera switching, tally, battery — JSON `GET /v1/camera/info` etc.
- **mDNS discovery**: `_droidcamobs._tcp.` via `NsdManager`
- **Web remote page** served at `/remote`
- Zero third-party dependencies — plain Android framework APIs (minSdk 24)

## Build

Open in Android Studio (it will offer to generate the Gradle wrapper) or:

```bash
cd OpenCam
gradle wrapper            # or: use Android Studio's wrapper fix
./gradlew assembleDebug
```

No network dependencies beyond the Android Gradle Plugin; the app code itself uses only
`android.*` APIs. You can even type-check the sources without Gradle:

```bash
javac --release 8 -classpath "$ANDROID_HOME/platforms/android-34/android.jar" \
  $(find app/src/main/java -name '*.java')
```

Release APKs are signed with the shared key in [`signing/`](signing/) (committed on purpose so
local builds and GitHub Actions produce the same signature — replace it with your own before
publishing to any store).

## CI / Releases

* **Actions → CI** — on every push to `main` it builds the signed release APK and runs the
  Windows client's self-test against the mock phone.
* **Actions → Release** — pushing a `v*` tag (e.g. `git tag v0.1.1 && git push origin v0.1.1`)
  builds the signed APK **and** the standalone Windows client exe, then publishes a
  GitHub Release with both attached.
* **Releases** — ready-to-install APK + `OpenCamClient.exe`: <https://github.com/soupashh-ship-it/opencam/releases>

## Usage

1. Install, grant **Camera**, **Microphone** (and **Notifications** on Android 13+).
2. Connect phone and PC to the **same Wi-Fi**.
3. Tap **Start Streaming** — the app shows `http://<ip>:<port>`.
4. In OBS: *Sources → Add → Media Source → new URL* `http://<ip>:<port>/video`.
   Or use the DroidCam OBS plugin pointing at the same address.

## Architecture

```
MainActivity (preview + controls)
      │ bind
StreamService (foreground, camera + mic)
      ├─ HttpServer (ServerSocket :4747, keep-alive)
      │    └─ ControlApi (route table) → Service actions
      ├─ CameraController (Camera2, HandlerThread)
      │    ├─ MjpegProducer (ImageReader → JPEG)
      │    └─ VideoEncoderPipeline (MediaCodec AVC/HEVC)
      ├─ AudioStream (AudioRecord → MediaCodec AAC-LC)
      ├─ FrameSink implementations:
      │    ├─ FramedSink  (12-byte pts+len framing — modern clients)
      │    ├─ MjpegSink   (multipart/x-mixed-replace — classic /video)
      │    └─ LegacySink  (9-byte header + 4-byte length — CMD /v3)
      └─ Discovery (NsdManager _droidcamobs._tcp.)
```

## Wire protocol cheat-sheet

| Endpoint | Type | Meaning |
|---|---|---|
| `GET /video` | MJPEG multipart | classic webcam stream (single client) |
| `GET /v5/video/{codec}/{W}x{H}?client=N` | framed | modern stream; codec ∈ jpg/avc/hevc |
| `CMD /v3/video/{codec}/{W}x{H}` | framed-legacy | OBS plugin classic protocol |
| `GET /v2/audio` · `/v1/audio.2` | framed AAC | audio stream |
| `GET /v1/camera/info` | JSON | camera state (zoom/ev/iso/ss/wb/af/torch/mute…) |
| `PUT /v3/camera/{zoom,ev,ss,iso,mf}/{v}` | control | camera controls |
| `PUT /v1/tally?tally={idle,preview,program}` | control | OBS tally light |
| `GET /v1/phone/battery_info` | JSON | battery level/state |

Framed packets: `[int64 LE pts µs][int32 LE length][payload]`; stream end = `(-1L, -1)`.
MJPEG boundary: `--dcmjpeg` (established interop constant). Legacy magic: `F5 E8 B5 D0`.

## Windows desktop client

A DroidCam-style desktop viewer/controller lives in [`windows-client/`](windows-client/):
live MJPEG preview, AAC microphone audio (via ffplay), camera switch / torch / mute,
zoom / EV / white-balance sliders, and battery display. No device needed to test it —
`python opencam_client.py --selftest` runs the full protocol against a built-in mock phone.

```bash
cd windows-client
python opencam_client.py          # GUI
```

- **Scan** button — auto-discovers OpenCam phones on your LAN (subnet probe of port 4747,
  dependency-free; pick a phone from the dropdown to connect).
- **Virtual cam** button — exposes the phone stream as a real Windows camera
  (**"OpenCam Virtual Camera"**), selectable in Discord, Zoom, WhatsApp, Google Meet and any
  app that lists webcams — the same mechanism DroidCam Client uses. One-time admin
  registration (a UAC prompt) installs a bundled DirectShow filter (see
  [`windows-client/vcam/`](windows-client/vcam/)); after that, toggling **Virtual cam** while
  connected feeds the phone's frames to the device. Add `--register-vcam` to install it from
  the command line, or use the `register_vcam.bat` / `unregister_vcam.bat` helpers.
- Double-click `run_client.bat` for a one-click source launch (installs Pillow if needed).
- `build_exe.bat` builds a standalone `dist/OpenCamClient.exe` (PyInstaller, no Python
  required on the target machine; the build runs the self-test before shipping).

## Roadmap / TODOs

- [x] Windows desktop client (preview, audio, camera controls)
- [ ] Native JPEG encoder (or `ImageReader(JPEG)`) for high-FPS MJPEG at >1080p
- [ ] Multiple simultaneous video clients
- [ ] OBS browser-source status overlay (tally light UI)
- [ ] USB/ADB transport (like the desktop companion apps)
- [ ] WebRTC low-latency preview
