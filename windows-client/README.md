# OpenCam Client (Windows)

A small DroidCam-style desktop client for the **OpenCam** phone app. Point it at
your phone's IP, and you get a live preview window plus camera controls — the
same control surface as the phone's web remote, but as a native-feeling desktop
app.

## What it does

| Feature | How |
|---|---|
| Live video preview | `GET /video` (MJPEG), decoded with Pillow, 60 fps-capable |
| Microphone audio | `GET /v2/audio` (AAC), played through ffplay |
| Switch camera / torch / mute | `PUT /v1/camera/*` |
| Zoom, exposure (EV), white balance sliders | `PUT /v3|camera/zoom`, `/v3/camera/ev`, `/v2/camera/wb_level` |
| Auto-focus trigger | `PUT /v1/camera/autofocus` |
| Battery + device info | `GET /v1/phone/*` |

The sliders only appear for the features your phone's camera advertises
(via `/v1/camera/info`), so a cheap camera with no zoom just won't show a zoom
slider.

## Requirements

* Windows 10/11
* Python 3.8+ — from <https://www.python.org/downloads/> (tick *Add to PATH*)
* **Pillow** — `run_client.bat` installs it automatically; or manually
  `python -m pip install pillow`
* **ffmpeg/ffplay** (optional, for audio only) — <https://ffmpeg.org/download.html>
  or `winget install Gyan.FFmpeg`

## Usage

1. On the phone: open **OpenCam**, tap **Start Streaming**, and note the IP shown
   (e.g. `192.168.1.34`).
2. On the PC: double-click `run_client.bat` (or `python opencam_client.py`).
3. Enter the phone's IP, leave the port at `4747`, click **Connect**.

The last IP/port is remembered for next time (`opencam_client.json`).

### Using it as a webcam in OBS

The desktop app is a viewer/controller. To bring the camera *into* OBS directly,
add a **Media Source** with the URL `http://<phone-ip>:4747/video` — OpenCam
speaks plain MJPEG, which OBS, VLC and browsers all accept.

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
control endpoints, and the MJPEG video stream end to end.

## Standalone .exe (no Python required)

Run `build_exe.bat` once to produce a single portable `dist\OpenCamClient.exe`
(~20 MB) with PyInstaller. The build runs the client's own self-test against a
mock phone as a final check, so a broken bundle fails the build. Copy the exe to
any Windows 10/11 machine and double-click it — no Python, Pillow or ffmpeg
install needed (audio just disables gracefully if ffplay isn't on the target
machine).

The exe keeps its remembered IP/port in `opencam_client.json` **next to itself**
so it stays portable; if that folder isn't writable (e.g. Program Files) it falls
back to `%APPDATA%\OpenCamClient\`. Notes:

* The exe is unsigned, so Windows SmartScreen may warn on first run —
  click **More info → Run anyway**.
* One-file exes unpack to a temp dir on each launch, so startup takes ~2–5 s.
  That's normal.

## Files

* `opencam_client.py` — the client (GUI + protocol)
* `mock_server.py` — fake phone used by `--selftest` (also runnable standalone)
* `run_client.bat` — double-click launcher (source mode)
* `build_exe.bat` — builds the standalone `dist\OpenCamClient.exe`
