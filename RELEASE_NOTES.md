# OpenCam v1.6.2 — final release polish and connection hardening

## Android

- Android reports the actual Gradle app version in mDNS discovery and `/v1/status`, eliminating stale version metadata after upgrades.
- Version bumped to `1.6.2` / versionCode `11`.

## Windows OpenCam Studio

- Fixed stale TCP socket callbacks that could trigger duplicate reconnect loops after a manual reconnect or rapid stream restart.
- Added explicit socket keepalive and `TCP_NODELAY` for a more predictable interactive stream path.
- Corrupt frame headers now force a clean reconnect instead of attempting unsafe byte-level resynchronization.
- Reworked the frame parser to avoid repeated `Buffer.concat()` allocations for every packet.
- Wi-Fi scanning now uses each local IPv4 interface's actual subnet, limits concurrent probes, validates `/v1/status`, and avoids launching hundreds of simultaneous requests.
- Removed the hardcoded example phone IP from the shipped UI; the saved address or network scan is used instead.
- The native desktop preview is explicitly MJPEG-only, matching its decoder. H.264/H.265 options that previously could produce a black preview are no longer offered.
- When the phone switches itself to a non-MJPEG codec while the desktop client is connected, the desktop client restores MJPEG and explains why.
- Added live synchronization for phone FPS, bitrate, zoom, torch, lens, resolution and battery status.
- Removed the online Google Fonts dependency so the desktop interface renders consistently offline.
- Added visible keyboard-focus outlines for accessibility and more reliable keyboard navigation.

## Validation

- Windows wire-protocol test suite: **10/10 passed**.
- Node syntax checks pass for all JavaScript entry points.
- A full Android Gradle build could not be executed in this container because Gradle/Android dependencies are not locally available and outbound dependency download is disabled.

---

# OpenCam v1.6.1 — PC client connect fixes (OpenCam Studio)

Fixes the Windows **OpenCam Studio** client so it reliably connects to the
phone and actually shows video.

- **MJPEG is enforced for the built-in preview.** The desktop viewer can only
decode JPEG frames — picking H.264/H.265 previously "connected" but showed a
blank screen because the frames could not be decoded. Selecting H.26x now
switches back to MJPEG with a clear notice (the phone app itself can still
stream whatever codec you choose on the phone).
- **"Connected" only shows once video arrives.** The green dot no longer
appears on a bare TCP connect — it turns on when the first frame renders, with
a live `MJPEG WxH` readout.
- **Clear failures instead of endless retries.** An unreachable phone now
explains what to check (phone app open and streaming, same Wi-Fi, correct IP),
retries with backoff, and stops after 9 attempts with guidance. If the phone
app is outdated (pre-v1.5.3) the client says so — an old server kicks every
reconnect, which previously looked like a permanent "won't connect".
- **Hardened framing parser** (`pc-client-native/stream-parser.js`, corruption
resync) covered by an automated wire test against a simulated phone
(`pc-client-native/test_stream_parser.js`, 10 checks).

### Run it
From `pc-client-native`: `npm start` (or rebuild the portable exe with
`npm run build`).

---

# OpenCam v1.5.3 — PC client overhaul, rotation fix, two-way settings sync

This release focuses on the Windows PC client. It fixes the picture when the
phone is held sideways, makes the phone and PC settings stay in sync (no more
"locked" resolution/codec), and gives the PC app a proper control panel.

## What changed

### Windows PC client (pc-client/opencam_pc.py)

- **Rotate control.** The phone streams a fixed portrait picture (the app is
  locked to portrait), so holding the phone sideways showed a sideways video on
  the PC. The client now has a **Rotate button (0/90/180/270°)** — press it (or
  `R`) once when you hold the phone landscape and the video turns upright.
  Screenshots respect the rotation too.
- **New control panel.** The window now has Connection / Stream / Camera /
  Actions sections: rotate, mirror, zoom, torch, camera flip, screenshot.
- **More options.** **H.265 (HEVC)** added to the codec list (needs `av`),
  resolutions up to **3840x2160 (4K)**, plus **FPS (15/24/30/60)** and a
  **bitrate slider (1–50 Mbps)**.
- **Control the phone from the PC.** Torch, zoom, flip camera, fps, bitrate and
  audio are sent to the phone live (new `PUT /v1/settings` endpoint).
- **Settings remembered** between runs (`%APPDATA%\OpenCamPC\settings.json`).

### Settings sync (both apps)

- **The phone's settings are the source of truth.** The server no longer lets a
  plain reconnect revert settings you changed in the app. Before, the PC client
  re-sent its own fixed codec/resolution on every reconnect, so changing the
  app to H.265 / 1080p was instantly undone ("locked"). Now app-side changes
  stick, and the PC client follows them.
- **The PC client follows the phone.** A new `GET /v1/status` endpoint reports
  the phone's actual codec, resolution, fps, battery, zoom and version. The PC
  client reads it on connect and every few seconds, syncs its dropdowns, shows
  the live status (e.g. `1920x1080 MJPEG · 30 fps · battery 87% · phone v1.6.2`),
  and decodes whatever codec the phone is actually streaming — even if you pick
  a different one in the app while connected.
- **Picking a codec/resolution in the PC client still works** — it is honored
  as a genuine new request and switches the stream.

## Notes

- First connection uses the PC client's chosen codec/resolution; after that,
  changes made on the phone stick until you pick something new on the PC.
- Streaming and OBS compatibility are unchanged; the new endpoints are additive.
- If the phone streams H.265 while the PC lacks the `av` package, the client
  shows a clear hint instead of a black screen.

## Build status

`:app:compileDebugKotlin` passes; PC client wire-protocol tests pass.
