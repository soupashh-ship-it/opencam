# OpenCam Client (Windows)

A small DroidCam-style desktop client for the **OpenCam** phone app. Point it at
your phone's IP, and you get a live preview window plus camera controls — the
same control surface as the phone's web remote, but as a native-feeling desktop
app.

## What it does

| Feature | How |
|---|---|
| Live video preview | `GET /video` (MJPEG) **or** `GET /v5/video/avc` (H.264) **or** `GET /v5/video/hevc` (H.265) — pick the codec in the top bar |
| Microphone audio | `GET /v2/audio` (AAC), played through ffplay |
| Codec + quality sync | The client pushes its codec/quality choice to the phone (`/v1/phone/codec`, `/v1/phone/bitrate`, `/v1/phone/jpeg_quality`) so both sides match — quality is driven by the **Quality** preset or the **Bitrate**/JPEG quality selector |
| Switch camera / torch / mute | `PUT /v1/camera/*` |
| Zoom, exposure (EV), white balance sliders | `PUT /v3|camera/zoom`, `/v3/camera/ev`, `/v2/camera/wb_level` |
| Auto-focus trigger | `PUT /v1/camera/autofocus` |
| Battery + device info | `GET /v1/phone/*` |
| Expose as a Windows webcam | DirectShow virtual camera via pyvirtualcam — "OpenCam Virtual Camera" appears in Discord, OBS, Zoom, Meet, browsers (⚠ not WhatsApp — see below) |

The sliders only appear for the features your phone's camera advertises
(via `/v1/camera/info`), so a cheap camera with no zoom just won't show a zoom
slider.

## Requirements

* Windows 10/11
* Python 3.8+ — from <https://www.python.org/downloads/> (tick *Add to PATH*)
* **Pillow** — `run_client.bat` installs it automatically; or manually
  `python -m pip install pillow`
* **av (PyAV)** — needed for H.264/H.265 streaming (bundles FFmpeg decode);
  `python -m pip install av`. MJPEG works without it.
* **ffmpeg/ffplay** (optional, for audio only) — <https://ffmpeg.org/download.html>
  or `winget install Gyan.FFmpeg`
* **pyvirtualcam + numpy** (optional, only for the *Virtual cam* feature) —
  `python -m pip install pyvirtualcam numpy`

## Usage

1. On the phone: open **OpenCam**, tap **Start Streaming**, and note the IP shown
   (e.g. `192.168.1.34`).
2. On the PC: double-click `run_client.bat` (or `python opencam_client.py`).
3. Enter the phone's IP, leave the port at `4747`, click **Connect**.

The last IP/port is remembered for next time (`opencam_client.json`). On launch the
client **auto-connects** to the last phone and, if enabled, switches on the **Virtual
cam** — so it's ready for Discord/Zoom as soon as it opens. Both behaviours can be
toggled with the *Auto-connect* and *Virtual cam on connect* checkboxes in the top
bar (persisted per machine).

### Choosing the video codec & quality

Next to the port field there are three dropdowns:

* **Codec** — `MJPEG` (max compatibility) or `H.264` / `H.265 / HEVC` (much
  better quality-per-bit; the phone hardware-encodes and the client decodes
  with bundled FFmpeg).
* **Quality** — quick presets that map onto the right knob for the selected
  codec: `Low` / `Medium` / `High` / `Ultra`. For H.264/H.265 they set the
  **bitrate** (3000 → 20000 kbps); for MJPEG they set the phone's **JPEG
  quality** (70 → 96%). Picking `Custom` hands control to the exact selectors.
* **Bitrate** (H.264/H.265 only) — 2000–20000 kbps, sent to the phone via
  `/v1/phone/bitrate`, so the encoder really runs at the bitrate you pick.
  For MJPEG the phone's JPEG quality is pushed via `/v1/phone/jpeg_quality`
  instead — that's the control that actually affects MJPEG sharpness.

Changing any of these while connected re-syncs the phone and reconnects the
stream automatically. Your choices are remembered for next launch, and the
header shows the live codec + bitrate/quality the phone reports back.

> **Tip:** H.264 @ 8–12 Mbps looks dramatically better than MJPEG on the same
> network, especially at 1080p and above, and uses less bandwidth to boot.

### Using it as a webcam in OBS

The desktop app is a viewer/controller. To bring the camera *into* OBS directly,
add a **Media Source** with the URL `http://<phone-ip>:4747/video` — OpenCam
speaks plain MJPEG, which OBS, VLC and browsers all accept.

### Using it as a webcam anywhere (Discord, OBS, Zoom, Meet)

Click **Virtual cam** in the controls row. OpenCam registers an
**"OpenCam Virtual Camera"** DirectShow device (one-time admin prompt) and,
while it's ON, the phone stream is exposed as a real Windows camera that
Discord, OBS, Zoom, Google Meet, browsers and most desktop apps can pick — right
next to "OBS Virtual Camera" and "DroidCam Video". OBS's own entry is never
touched: OpenCam adds its own device alongside it instead of renaming anything.

* The underlying filter driver is the one OBS Studio ships (free). The client
  bundles its own copy and falls back to it if OBS isn't installed.
* First use asks for admin once (UAC) to write the device entry to the
  registry; after that it just works — the device persists even when the app
  is closed, and apps see a black/standby frame until you stream.
* Toggling the button off (or disconnecting) stops feeding frames and closes
  the camera; "OBS Virtual Camera" stays intact the whole time.

> **⚠ WhatsApp (and other UWP/MSIX store apps) will NOT list this camera** — and
> they don't list "OBS Virtual Camera" either. Windows has two camera
> enumerations: DirectShow (used by Discord, OBS, browsers, Zoom, Teams) lists
> every software camera; the UWP/Media-Foundation path used by Store-packaged
> apps like WhatsApp only surfaces **kernel-streaming (driver) cameras**.
> DroidCam appears there only because it installs a kernel driver. Fixing this
> for OpenCam means shipping a virtual-camera kernel driver (or a Windows 11
> "Camera Frame Server" custom source) — a separate, larger project. Everything
> else already works.

## Troubleshooting

* **"phone returned 200 … (is another client already connected?)"** — OpenCam
  allows one video client at a time. Close the phone's web remote page (or this
  app) and connect again.
* **No sound** — install FFmpeg/ffplay and restart the app. Check the phone's
  *Settings → Stream audio* is on.
* **Can't connect** — make sure the phone and PC are on the same network, the
  phone is *streaming* (server runs only while streaming), and the firewall
  allows inbound on port 4747.

## Self-test (no device needed)

```
python opencam_client.py --selftest
```

Starts a mock phone server on localhost and verifies phone info, camera info,
control endpoints, the MJPEG video stream, codec/bitrate sync, **and** a real
H.264 framed stream (the mock encodes with bundled libx264) end to end.

## Standalone .exe (no Python required)

Run `build_exe.bat` once to produce a single portable `dist\OpenCamClient.exe`
(~80 MB, PyAV bundles FFmpeg) with PyInstaller. The build runs the client's own
self-test against a mock phone as a final check, so a broken bundle fails the
build. Copy the exe to any Windows 10/11 machine and double-click it — no
Python, Pillow or ffmpeg install needed (audio just disables gracefully if
ffplay isn't on the target machine).

The exe keeps its remembered IP/port in `opencam_client.json` **next to itself**
so it stays portable; if that folder isn't writable (e.g. Program Files) it falls
back to `%APPDATA%\OpenCamClient\`. Notes:

* The exe is unsigned, so Windows SmartScreen may warn on first run —
  click **More info → Run anyway**.
* One-file exes unpack to a temp dir on each launch, so startup takes ~2–5 s.
  That's normal.

## Files

* `opencam_client.py` — the client (GUI + protocol)
* `mock_server.py` — fake phone used by `--selftest` (also runnable standalone; encodes real H.264 with PyAV)
* `run_client.bat` — double-click launcher (source mode)
* `build_exe.bat` — builds the standalone `dist\OpenCamClient.exe`
