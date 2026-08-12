# OpenCam Release Notes

## v1.2.0 (2026-08-12)

UI release — the Windows client gets a complete visual overhaul, and a few more reliability fixes ship with it.

### Windows client — modern UI
- **New design system.** The desktop client now uses the same brand palette as the Android app (deep `#0B0F13` backgrounds, `#161E26` cards, cyan `#00C4FF` accent) so both halves of OpenCam look like one product.
- **Redesigned layout.** A proper app chrome: brand header with version chip, a connection-status pill (gray *Disconnected* → amber *Connecting/Reconnecting* → green *Connected* → red *Error*), a battery icon that fills with green/amber/red, a bottom status bar, and grouped controls.
- **Modern controls.** Rounded flat buttons with hover/pressed states (accent **Connect**, red **Stop stream**, tinted on-state for Mute/Virtual cam/Mirror), rounded inputs with an accent focus ring, iOS-style toggle switches replacing the old checkboxes, and smooth rounded sliders replacing the chunky `tk.Scale` for Zoom/EV/WB.
- **Video viewport polish.** A pulsing **LIVE** badge, a stream-info chip (resolution · codec · fps), a VCAM-ON chip, and a proper idle placeholder (camera glyph + “Connect to your phone”) instead of a black void.
- **Crisp rendering on high-DPI displays** — the client now sets DPI awareness on Windows instead of letting the OS blurry-scale it.
- Layout rebalanced for the new chrome: 1080×700 default, 820×560 minimum.

### Windows client — reliability
- **Video stream loss is now surfaced immediately.** If the phone closes the socket mid-stream (idle watchdog, restart), the client reports it and reconnects instead of sitting on a black screen labeled “connected”.
- **Dead H.264/H.265 streams no longer hang.** A 6s decode watchdog force-closes a stream that delivers bytes but never a decodable frame, and the demuxer feed now times out after 5s of silence — both exit cleanly with a clear message instead of blocking forever.
- **First connect now starts the video/audio streams reliably** (the connected flag was previously set only after the streams had already been skipped).

### Validation
- Windows client self-test passes 12/12; mock server loops its stream until the client disconnects (as a real phone would).

---

## v1.1.2 (2026-08-11)

Latency release — eliminates the "video plays seconds behind, then jumps forward, then lags again" cycles by removing every place a frame backlog can build on either side of the connection.

### Windows client
- **Fixed: MJPEG video fell seconds behind real time (then jumped forward).** JPEG decoding ran on the socket-read thread — at ~90ms per 720p frame that's slower than the phone's ~37ms frame interval, so the TCP buffer built an ever-growing backlog. The MJPEG path now mirrors the H.264 design: a reader thread parses frames off the socket as fast as possible into a bounded latest-wins queue (max 2 frames), and a separate loop decodes them. Lag is now bounded at ~2 frames (~20–50ms measured against a timestamped mock stream) instead of growing without bound.
- **Fixed: H.264/H.265 decode backlog.** The frame queue could hold up to 128 packets (~4s of video) before dropping one. It now stays at ≤2 packets, so the decoder always works through the freshest frames; with the phone's 1s keyframe interval any dropped-packet chain re-syncs in under a second.
- **Faster failure detection: stream read timeouts 12s → 5s** (video, encoded video, and audio). A dead link is now noticed and reconnected in ~5s instead of ~12s.
- **Faster recovery: reconnect backoff 1.5s→3s→6s→12s → 1s→2s→3s→4s.** A transient blip now costs ~5s of frozen video instead of ~15s.

### Android app
- **Fixed: MJPEG encode backlog on the phone.** The JPEG producer now keeps at most one encode in flight — frames that arrive while an encode is pending are drained and dropped so the newest image is always what gets delivered. A slow encode or a saturated client socket can no longer make the phone's frame backlog grow.
- **Faster H.264 re-sync: keyframe (IDR) interval 2s → 1s.** After any packet drop or reconnect the decoder recovers on the next keyframe — worst case halved from 2s to 1s.
- **Faster stale-sink reclaim: client idle watchdog 12s → 5s** (checked every 2s). A stalled session is freed in ~5–7s instead of ~12–16s.

### Validation
- MJPEG end-to-end lag measured against a timestamped mock phone: **avg ~21ms, max ~143ms** (was ~4.5s+ growing) — real-time.
- Windows client self-test passes 12/12; Android app compiles clean (both run as gates in the release pipeline).

---

## v1.1.1 (2026-08-11)

Hotfix release — fixes the connection failures reported between the Android app and the Windows client (blank screen on connect, endless CMD popups, 15s+ stale video).

### Windows client
- **Fixed: endless CMD console popups while reconnecting.** ffplay discovery/launch now runs with `CREATE_NO_WINDOW` — every reconnect cycle used to flash a console window because the audio stream was restarted (and ffplay re-detected) on each video drop.
- **Fixed: blank screen / reconnect churn on connect.** The client pushed its bitrate on *every* connect, and the phone restarts its whole pipeline (HTTP server included) whenever the bitrate actually changes — so the stream opened right after hit connection-refused and burned through 1.5s→3s→6s→12s backoff retries. The client now pushes bitrate/JPEG quality only when they differ from the phone's reported values, and waits for the phone's server to come back up before opening the stream — it connects on the first try.
- **Fixed: video-only reconnects no longer tear down a healthy audio stream** (no more interrupted mic + no extra ffplay launches on every reconnect).
- **Fixed: 15s+ stale video when the virtual camera's consumer is slow.** The DirectShow virtual-camera feed ran synchronously on the video reader thread; a slow consumer (Discord/OBS) blocked the reader, froze the preview, and tripped the phone's 12s idle watchdog. The feed now runs on a background latest-wins feeder thread (the same design as the Media Foundation backend), so the video reader can never block on the vcam.
- **Latency: TCP_NODELAY on video/audio sockets** so the small per-frame protocol headers are never held back by Nagle's algorithm.

### Android app
- **Fixed: redundant pipeline restarts on connect.** `setPhoneBitrate` / `setPhoneJpegQuality` no longer restart the whole pipeline when the value is unchanged (the desktop client re-syncs its settings on every connect).
- **Latency: TCP_NODELAY on accepted HTTP sockets** — stream sockets now send the first byte immediately instead of waiting for an ACK.

### Validation
- Windows client self-test passes 12/12 (MJPEG + H.264 decode, discovery, controls, rotate/mirror, codec sync).
- Android app compiles clean (`assembleRelease` in CI).
- Both run as a gate in the GitHub Actions release pipeline.

---

## v1.1.0 (2026-08-11)

Stability release — the result of a full deep audit of the Android app and the Windows client. Every found bug is fixed; both sides build and pass their test gates.

### Android app
- **Fixed: camera hardware leak on fast camera switching.** A stale camera-open could complete after a switch and was never closed — the camera stayed busy for other apps and could hijack the session of the camera that actually won. Each open now tracks its own id and closes leftover devices.
- **Fixed: orphaned client sockets.**
  - Requesting H.264/H.265 on a device without an encoder no longer "connects" a client that can never receive frames (its socket used to sit open forever). The client is now rejected cleanly.
  - After a settings restart, clients that can't be re-attached (no encoder / no audio stream) are closed instead of leaking their connections.
  - A session-reconfiguration crash no longer leaves a dangling client reference.
- Removed an unused import; no behavior change.

### Windows client
- **Fixed: crash after disconnecting.** A scheduled stream restart firing after Disconnect could crash on a cleared phone reference; now guarded.
- **Fixed: connect/disconnect race.** A slow auto-connect finishing after you pressed Disconnect no longer flips the app back to "connected".
- **Fixed: potential UI freeze on disconnect** when the H.264/H.265 frame queue was full — EOF is now delivered reliably without ever blocking the UI thread.
- **Fixed: the packaged .exe now actually bundles the Media Foundation virtual camera** (Win11 22H2+). Previously the frozen exe only shipped the DirectShow backend, so WhatsApp/Teams support silently fell back in installed builds. The exe grew to ~57 MB to carry it.
- Client now shows its version in the window title.
- Cleaned up invalid-escape warnings in the virtual-camera registration script (output bytes unchanged).

### Validation
- Android: builds clean (`assembleRelease` in CI).
- Windows: client self-test against the mock phone server passes 12/12 (MJPEG + H.264 decode, discovery, controls, rotate/mirror, codec sync).
- Both runs as a gate in the GitHub Actions release pipeline.

### Known limitations (by design)
- Streaming has no PIN/auth — anyone on the same Wi-Fi can connect (same model as DroidCam).
- The shared release keystore is committed for seamless sideload upgrades; replace it before any real distribution.
- Windows audio needs `ffplay` (FFmpeg) installed; video works without it.
- The virtual camera appears in WhatsApp/Teams only on Windows 11 22H2+ (Media Foundation backend); on older Windows it works in Discord/OBS/Chrome/Zoom etc.

---

## v1.0.2 (2026-08-11)

Deep-audit fix batch — see the v1.1.0 notes (identical fix set; v1.1.0 is the same code with the release-notes pipeline added).

## v1.0.1 (2026-08-10)

- Fixed the PC client self-test on Python 3.13 (`threading.Thread.start()` name collision in the mock server).
- Added Rotate (0/90/180/270°) + Mirror controls to the Windows client — fixes a sideways picture when the phone is held in landscape; applies to the preview and the virtual camera and persists across launches.
- Fixed a config bug where reconnecting wiped the saved rotate/mirror state.

## v1.0.0 (2026-08-10)

- Restructured project: rewritten Java Android app (`io.opencam.webcam`) + Python Windows client (`windows-client/`) with a DirectShow virtual camera.
- Android: MJPEG + H.264 + H.265 streaming, AAC audio, full camera control API, mDNS discovery, web remote at `/remote`, battery info.
- Windows client: live preview, codec/quality sync with the phone, LAN scan, camera controls, virtual camera for Discord/OBS/Chrome.
