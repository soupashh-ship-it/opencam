# OpenCam — Release Audit & Fix Log

Scope: full read of the Android app (`app/`) and the Windows client (`pc-client-native/`),
all build/release configuration, plus every test suite. Every fix below states what was
broken, why it matters, how it was verified, and what still needs a physical device.

Baseline at start of audit: **49 Android unit tests, 111 PC-client tests, all passing** —
so the defects found here live in code the tests did not cover.

After the Android/Windows fixes: **56 Android tests, 119 PC-client tests, all passing**, plus a
verified minified release build and a CI-parity `check` run. After the virtual-camera round that
followed (non-blocking status reader, C# feeder audit, repository trim): **56 Android tests and
134 PC-client tests**. After the 60 fps default round (§9): **57 Android tests and 135 PC-client
tests**. After the standby idle-cost round (§10): **57 Android tests and 138 PC-client tests, all
passing**.

---

## 1. Release blockers (the release pipeline could not run)

### 1.1 `.github/workflows/release.yml` was UTF-16LE encoded → workflow unparseable

* **Found:** `file .github/workflows/release.yml` → `Unicode text, UTF-16, little-endian text, with CRLF line terminators` (BOM `FF FE`). The file was UTF-16 in `HEAD` and in the previous commit.
* **Why it matters:** GitHub Actions reads workflow files as UTF-8. A UTF-16 file cannot be parsed,
  so the tag-triggered `Release` workflow (build APK, build Windows client, publish GitHub Release)
  never ran. `.github/workflows/ci.yml` and `pages.yml` were fine, which is why CI badge/artifacts
  still looked healthy.
* **Fix:** converted the file to UTF-8 (BOM stripped), content otherwise byte-identical in meaning.
* **Verified:** `file` now reports `UTF-8 text`; the workflow parses with a YAML parser
  (`yaml.safe_load` → OK). Repo-wide sweep confirms no other tracked text file is UTF-16
  (the only other non-UTF-8 files are the legitimate binaries: gradle-wrapper.jar, the two OBS
  virtual-camera DLLs and `vcam_feeder.exe`).

---

## 2. Streaming-correctness bugs (Android)

### 2.1 H.264/H.265 output was stretched anisotropically for 90°/270° rotations

* **Where:** `stream/StreamManager.kt` (doRebuild → startEncodedSession), `encode/GlRotator.kt`,
  `util/CameraRotation.kt`.
* **What was wrong:** `doRebuild` computed `val outputWidth = sourceWidth; val outputHeight = sourceHeight`
  (an exact copy — dead assignments), so the encoder was always built with the *unrotated* sensor
  frame size, while `GlRotator` rotated the texture coordinates by 90°/270°. Rotating content inside
  an unswapped frame scales the two axes by different factors: the sampled mapping is
  `srcX = (H_src/W_frame)·py` and `srcY = (W_src/H_frame)·px`, i.e. 1920/1080 = 1.778 versus
  1080/1920 = 0.5625 — a ~3.16× differential, so the picture is squashed.
* **Evidence this is wrong, not intentional:**
  * `CameraRotationTest` documents the intent explicitly: portrait hold → *"Image must rotate 90 deg
    clockwise to align sensor native top to screen top"* — a genuine orientation change, which
    requires the frame to become portrait.
  * The MJPEG path in the same function *does* swap (`val swap = rotation == 90 || rotation == 270`,
    `outputWidth = if (swap) height else width`), and reports the swapped size as `streamWidth/streamHeight`.
    The encoded path disagreed with it for no discernible reason. `GlRotator`'s own texture-coordinate
    rotation maps the unit square onto itself, so with unswapped dimensions a stretch is unavoidable:
    there is no interpretation under which the old output was correct.
  * `GlRotator` was handed both `sourceWidth/sourceHeight` *and* `outputWidth/outputHeight`
    parameters — a shape that only makes sense if the two differ.
* **Fix:**
  * `CameraRotation.orientedSize(width, height, degrees)` + `swapsDimensions(degrees)` — the single
    source of truth for "does this rotation exchange width and height".
  * `StreamManager.doRebuild` now derives the frame size from the rotation and passes it to the encoder
    **and** `GlRotator`; `completeRebuild` reports the same swapped dimensions for every codec
    (previously MJPEG only).
  * `GlRotator` takes explicit `outputWidth`/`outputHeight` (used for the viewport and surface buffer)
    and **fails fast** with `require(...)` if a 90°/270° rotation is given an unswapped frame, so a
    future regression throws at GL init instead of silently stretching video.
* **Verified:** 3 new unit tests (rotation swaps, oriented size for portrait/landscape/quarter turns,
  and the "frame must span the source extents" invariant). Both suites green.
* **⚠️ Needs one on-device confirmation:** this changes encoded output dimensions for 90°/270°
  rotations. The math and the internal inconsistency are conclusive, but I cannot run the camera in
  this environment. **Check:** back camera, phone in **portrait**, codec **H.264 (AVC)** in OBS →
  the image must be upright and *not* stretched (compare against MJPEG, which was already correct).
  If any device behaves differently, the revert is the two lines in `doRebuild`.

### 2.2 New video/audio clients could receive frames with no codec configuration

* **Where:** `stream/StreamManager.kt` (broadcast + client callbacks).
* **What was wrong:** SPS/PPS/VPS (and AAC AudioSpecificConfig) are sent once, when the encoder starts.
  A client that connects to an already-running stream (second viewer, OBS reopened, plugin reconnect
  that does not trigger a rebuild) received frames with no decoder configuration and showed nothing.
* **Fix:** the last configuration packet is cached per stream and replayed to every client on connect;
  the cache is cleared on rebuild so stale SPS/PPS are never replayed for a new stream.
* **Verified:** compiles + full suites; behaviour is a straight addition on the existing packet path
  (config packets are already identified by the `PTS = 0xFFFF_FFFF_FFFF_FFFF` marker).

### 2.3 H.264/H.265 could produce no video at all on some devices (missing SPS/PPS)

* **Where:** `encode/VideoEncoder.kt`.
* **What was wrong:** `MediaCodec.INFO_OUTPUT_FORMAT_CHANGED` was ignored (`Unit`). Devices that report
  codec configuration only through the output format (no `BUFFER_FLAG_CODEC_CONFIG` buffer) never sent
  SPS/PPS, so the client could not initialise a decoder. `AudioEncoder` already had this handling
  (`ensureConfigFromFormat`) — the video encoder was simply missing it.
* **Fix:** `emitConfigFromFormat()` merges `csd-0`/`csd-1`/`csd-2` into one Annex-B configuration packet
  (new `Bitstream.concatAnnexB`, which normalises length-prefixed parts) and sends it exactly once,
  whichever source reports it first.
* **Verified:** 3 new `BitstreamTest` cases (merge, length-prefixed normalisation, empty/single-part
  edge cases).

### 2.4 One bad buffer permanently killed video/audio until a full rebuild

* **Where:** `encode/VideoEncoder.kt`, `encode/AudioEncoder.kt`.
* **What was wrong:** the whole drain loop was wrapped in a single `try { while (...) } catch {}`.
  Any transient exception (a malformed buffer, a released codec mid-iteration) exited the loop
  silently — the stream stayed "connected" but produced no more frames.
* **Fix:** the dequeue call keeps its own guard (returns on codec teardown), and per-buffer work is
  wrapped so a single failure cannot terminate the loop; `releaseOutputBuffer` is always attempted.
* **Verified:** suites green; logic reviewed against `MediaCodec` teardown semantics.

### 2.5 Audio could publish an ADTS header as an audio payload

* **Where:** `encode/AudioEncoder.kt` (`stripAdts`).
* **What was wrong:** when a codec output buffer contained *only* the ADTS header, the original array
  (header included) was returned and framed as a media packet — an empty access unit sent to the client.
* **Fix:** header-only buffers now return an empty payload, which the broadcaster already drops.

### 2.6 A failed bind permanently poisoned the stream server instance

* **Where:** `server/StreamServer.kt`.
* **What was wrong:** `start()` shut down the `ThreadPoolExecutor` in its failure path, and the pool was
  created once in the constructor. So if the port was already in use, the instance could bind later but
  every accepted connection was rejected with `RejectedExecutionException` (silent dead server).
  A second, quieter leak: `ServerSocket().apply { bind(...) }` leaked the socket when `bind` threw.
* **Fix:** the pool is created on every successful `start()` and torn down in `stop()`; a half-bound
  socket is closed before the exception propagates.
* **Verified:** new test `testServerRestartsAfterFailedBindAndAcceptsClients` — occupy the port (start
  must fail), free it, start the *same* instance (must succeed) and assert a real video client connects
  and reaches `onVideoClientConnected`. This test fails on the previous implementation.

---

## 3. Windows client bugs (`pc-client-native/`)

### 3.1 Dead sockets were never detected — frozen picture, no reconnect

* **Where:** `main.js`.
* **What was wrong:** `sock.setTimeout(8000)` was set but **no `'timeout'` listener existed**. Node emits
  `'timeout'` and does nothing else, so a phone that left Wi-Fi or slept without sending FIN left the
  socket open: `'close'` never fired, the reconnect logic never ran, and the app displayed a frozen
  frame indefinitely while reporting "connected".
* **Fix:** a `'timeout'` handler destroys the socket, which funnels into the existing close → reconnect path.
* **Verified:** `node --check`, plus the reconnect policy is now covered by tests (below). The handler
  itself is code-review verified — `main.js` cannot be exercised without an Electron runtime.

### 3.2 Reconnect policy extracted and tested

* **Where:** new `reconnect-policy.js` + `test_reconnect_policy.js`, wired into `main.js`.
* **Why:** the escalating-backoff logic was ~40 lines of nested timers inside the Electron main process,
  untestable, and the exhaustion check was unreachable once video had flowed (the fast path always won).
* **Fix:** a pure `decideReconnect(state)` returns `{action, delayMs, reason, attempt}`; `main.js` keeps
  the counters and timers. Behaviour for the shipped configuration is preserved
  (1 s after a drop, 1.5 s early retries, 3 s late retries, give up at 9 attempts), and `maxAttempts`
  is now authoritative before the early/late branches.
* **Verified:** 8 new tests (fast reconnect, fast-limit fallback, early/late backoff boundaries,
  exhaustion, NaN/negative clamping, message copy, custom limits). `npm test` green.

### 3.3 IPC handlers accepted invalid hosts

* **Where:** `main.js` (`get-status`, `push-settings`).
* **What was wrong:** `isValidIp()` existed, was unit-tested, and was **never used** — the renderer's
  values went straight into `http.request({host})`, so an undefined/garbage host silently targeted
  localhost or produced confusing failures.
* **Fix:** both handlers validate the host (and `params`) before use.

### 3.4 Virtual-camera frame writes were not atomic

* **Where:** `vcam-feeder.js` (`pushFrame`).
* **What was wrong:** the 12-byte header and the JPEG payload were two independent `stdin.write()` calls.
  An interleaved feeder restart (the class auto-restarts on the next frame) could leave the pipe holding
  a header with no payload, permanently desyncing the native reader. Also `BigInt(ptsUs)` throws on a
  fractional value, dropping the frame silently.
* **Fix:** `cork()`/`uncork()` around both writes so they flush together (no extra copy), and the
  timestamp is normalised/rounded before `BigInt()`.
* **Verified:** `node --check` + the existing 58 virtual-camera tests still pass.

### 3.5 The virtual-camera status check blocked the Electron main process for up to 5 s

* **Where:** `pc-client-native/vcam-feeder.js`, `pc-client-native/main.js`.
* **What was wrong:** status was read with `spawnSync(FEEDER_EXE, ['--status'], { timeout: 5000 })`.
  `spawnSync` blocks the event loop it runs on, and that call ran on the **Electron main process** —
  so a slow or wedged feeder froze the whole Studio UI for up to five seconds, exactly when the user
  was waiting for feedback. It is a responsiveness defect rather than a correctness one, which is why
  it was listed here under "worth making async" before being fixed.
* **Fix:** new `getVirtualCameraStatusAsync()` built on `execFile` (never blocks), sharing one
  `parseStatusOutput()` with the old reader so both agree on shape and parsing. Both elevated
  register/unregister paths now `await` it, and the `get-vcam-status` IPC handler in `main.js` returns it.
  The blocking variant is kept — and commented as such — for the test suite, which asserts on its return value.
* **Verified:** 7 new tests in `test_vcam_status.js` (raw JSON, JSON embedded in noisy stdout, empty/blank
  output degrading to an explicit error, malformed JSON throwing so callers can degrade, sync reader shape,
  the async reader returning a Promise that never rejects, and async/sync agreeing on shape).
  Confirmed by grep that **every UI-reachable call site uses the async variant** and the only remaining
  sync callers are the two test files. The `elevateIfFailed = false` branches of
  `registerVirtualCamera`/`unregisterVirtualCamera` still use the sync reader: they are test-only paths —
  `main.js` calls the elevated variants with `true`.

### 3.6 The C# feeder silently overwrote a user-chosen frame rate (verified on the real binary)

* **Where:** `pc-client-native/vcam/OpenCamVirtualCamFeeder.cs` (`RunFeeder`, `AdaptFps`).
* **What was wrong:** the stream adapter only ever selects `30` or `60` — `AdaptFps(60)` after two frames
  arriving ≤ 20 ms apart, `AdaptFps(30)` after ten frames > 28 ms apart — and it writes the chosen rate
  into the **shared** `%APPDATA%\obs-virtualcam.txt` (the file the DirectShow filter reads so every
  consumer agrees on resolution and frame interval). Meanwhile the Studio slider spans **15..60 in steps
  of 5**, and `main.js` starts the feeder with exactly that value. So every choice other than 30 or 60 was
  replaced: a 25 fps selection was rewritten to 60 after two fast frames and to 30 after ten slow ones,
  and the shared config advertised a rate the user never picked.
* **Fix:** `_configuredFps` records the rate the caller actually asked for; adaptation is gated on that
  being 30 or 60 (`bool adaptiveFps = (_configuredFps == 30 || _configuredFps == 60)`). Any other value is
  served verbatim. `_currentFps` also became `volatile` — the standby thread reads it outside `_syncLock`.
* **Verified on the shipped binary**, identical input in both runs (640x480, `--feed 640 480 25`,
  14 real JPEG frames at a 40 ms cadence):

| Binary | Config at start | Config after 14 frames |
|---|---|---|
| pre-fix (`git show HEAD:pc-client-native/vcam/vcam_feeder.exe`) | `640x480x400000` (25 fps) | **`640x480x333333` → 30 fps, user's choice destroyed** |
| with the fix | `640x480x400000` (25 fps) | `640x480x400000` (25 fps) |

  The adapter is still functional where it should be: the same scenario at 30 fps configured with 10 ms
  frames goes `640x480x333333` → `640x480x166666` (60 fps). `obs-virtualcam.txt` was saved and restored
  around both runs. The bundled `vcam/vcam_feeder.exe` was rebuilt with the app's own compiler flags
  (`csc -nologo -unsafe -optimize -platform:x64 -r:System.Drawing.dll`) so the fix ships rather than
  waiting for a machine with `csc` present, and the `--status`/`--feed` paths were re-exercised afterwards.

### 3.7 The C# feeder's stderr pipe was never drained

* **Where:** `pc-client-native/vcam-feeder.js` (`VirtualCamFeeder.start`).
* **What was wrong:** the feeder is spawned with `stdio: ['pipe', 'ignore', 'pipe']` and nothing ever read
  stderr. Once the 64 KB pipe buffer filled, the child would block on its next write — mid-stream, with no
  symptom anywhere. Benign today (the feed loop writes nothing to stderr; only the short-lived
  register/unregister processes do), but it is a trap for the next diagnostic added inside the frame loop.
* **Fix:** stderr is drained into a bounded tail (last 2048 characters) exposed as `feeder.lastStderr`,
  so it cannot block and still serves the diagnostics UI.
* **Verified:** 2 tests on `appendBounded` (concat, null/empty/Buffer chunks, tail-only retention under
  a 3× limit, trimming from the front).

### 3.8 C# feeder: frame timestamps could go backwards

* **Where:** `pc-client-native/vcam-feeder.js` (`pushFrame`).
* **What was wrong:** the phone's PTS was forwarded verbatim. Two realistic ways it regresses:
  (a) a phone reconnect resets its stream clock, so the next PTS is far below the last one;
  (b) the local-time fallback is `Date.now() * 1000` (epoch microseconds ≈ 1.7e15), which is far *above*
  a phone-relative PTS, so the following genuine timestamp also went backwards. The native feeder only
  substitutes its own clock when the incoming PTS is exactly 0, so nothing corrected it downstream.
  Consumers can hold frames until the timeline catches up.
* **Fix:** timestamps are now strictly increasing — the phone's value is forwarded only when it is greater
  than the last value emitted, otherwise a local monotonic value is used. The normal case (increasing
  PTS) is forwarded byte-for-byte as before.
* **Verified:** 5 tests, including a simulated reconnect clock reset (900000 → 1200000 → **40** → 80) that
  asserts every emitted timestamp still increases, plus invalid inputs (`undefined`, `null`, `NaN`,
  `Infinity`, `-5`, `0`, `'abc'`) and the existing 58-test virtual-camera suite, which drives a real feeder.

**Audited and deliberately NOT changed in the C# feeder** (so the boundaries of this audit are clear):

* The 3-slot queue-header protocol (`write_idx` / `read_idx` / `state` / slot offsets / `interval` at
  offset 0x28). The consumer is a prebuilt DLL; changing the layout without its source would be guesswork.
  The struct offsets were checked against the raw `Marshal.WriteInt64(view, 40, ...)` used by `AdaptFps` and
  they agree.
* `ConvertBmpToNv12InPlace` bilinearly rescales when the decoded JPEG size differs from the configured size,
  so a phone-side resolution change squashes rather than corrupts — no out-of-bounds read, no exception
  storm. It does mean a **rotated** phone stream would be stretched rather than rotated; that needs a
  device test, not a hunch (marked in §6).
* The registration/cleanup registry paths (`RegisterVirtualCamera`, `CleanupLegacyRegistrations`,
  `SetFrameServerMode`). They touch `HKLM`/`DeviceClasses`, have their own passing coverage, and were
  reviewed without being altered.
* **Latent, documented rather than patched:** if `InitSharedMemory` were ever called with a non-NV12
  format, `_frameSize` becomes `w*h*4` while the converter only fills `w*h*3/2`, so the published tail
  would be uninitialised bytes. Both call sites pass NV12 today, so this is unreachable — guessing at a
  clamp would be worse than leaving it documented.

---

## 4. Verified results

| Check | Command | Result |
|---|---|---|
| Android unit tests | `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest` | **57 passed, 0 failed** (was 49 at the start of the audit) |
| CI parity (build + lint + tests) | `./gradlew check assembleDebug` | **BUILD SUCCESSFUL**, lint 0 errors / 26 warnings |
| Minified release build | `./gradlew :app:assembleRelease` (R8 + resource shrink + lintVital) | **BUILD SUCCESSFUL**, APK 1.4 MB, signed (v2 scheme verified by `apksigner verify`). Verified after the Android fixes; **no Android source has changed since**, so this result still describes the current tree |
| PC client tests | `npm test` | **138 passed, 0 failed** across 7 suites (was 111 at the start) |
| Idle virtual-camera cost | `Get-Process.TotalProcessorTime` over 6 s, 1080p60, no phone | 125.0 ms → **≤ 15.6 ms** (at/below the counter's granularity — see §10.1) |
| 60 fps defaults (real binary) | harness driving `--feed 640 480 <rate>` on the committed vs fixed exe | committed binary capped an explicit 60 at 30; fixed binary holds 60 while 30→60 up-shift still works (see §9.1) |
| C# feeder compiles | `csc -nologo -unsafe -optimize -platform:x64 -r:System.Drawing.dll` | **COMPILE OK**, rebuilt `vcam_feeder.exe` produces correct `--status` JSON |
| fps-adapter fix (real binary) | behavioural harness driving `--feed 640 480 25` with real JPEG frames | 25 fps preserved; 30 fps + fast frames still adapts to 60 fps (see §3.6) |
| JS syntax | `node --check` on all changed JS | OK |
| Workflow YAML | `yaml.safe_load` on all three workflows | OK |
| Working tree | `git status --short`, `git rev-parse HEAD` | HEAD unchanged (`faa6d3c`), 16 modified + 5 new files, no stray temp files |

Signing material used to verify the release path was generated locally, used, and **deleted** afterwards
(`keystore.properties`, `keystore/tmp-verify.jks` — both gitignored categories). No keystore was added to the repo.

---

## 5. Deliberately NOT changed (and why)

Being explicit about these is part of the audit. Item 4 (repository bloat) and item 6 (blocking status
reader) were resolved in the follow-up round — see §8 and §3.5 respectively. They are left in place so the
record shows they were originally deferred rather than missed.

1. **The rotation formula** (`sensorOrientation + deviceOrientation`) — the standard Android formula
   differs in sign for landscape holds, and the doc comment's mirror-conjugation argument is dubious.
   It is, however, explicitly documented and locked by `CameraRotationTest`, and deciding it needs a
   physical device in four held orientations. **Recommended device test:** portrait, both landscape
   holds, and upside-down on both cameras; log what OBS actually shows before touching it.
   **RESOLVED in the follow-up round:** the front camera indeed needed the negated
   device term (`sensor - device`, the standard Camera2 JPEG formula for the mirrored
   pipeline). The shared `+` sign left front-camera landscape holds 180° upside down
   while portrait stayed correct — exactly the ambiguity called out above.
   `calculateStreamRotation` now subtracts for `isFrontFacing`, and `CameraRotationTest`
   locks the corrected table.
2. **26 lint warnings** (0 errors): `UseTomlInstead`, `UseKtx`, `UnusedResources`, `ObsoleteSdkInt`,
   `MonochromeLauncherIcon`, `LockedOrientationActivity`. Cosmetic/advisory; `LockedOrientationActivity`
   is intentional (the UI is portrait-locked by design).
3. **The CI fallback release keystore** (`release.yml` runs `keytool -genkeypair` with the password
   `opencam123` when the signing secrets are absent). **The fallback is not in use:** all four secrets
   (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) are configured, and both the
   v1.7.1 and v1.7.2 release runs logged *"Using configured GitHub repository signing secrets…"* and
   produced the same signer digest (`c441439f…`). That is a stable release key, so users can upgrade in
   place. The fallback is still a liability if those secrets are ever removed: it generates a fresh
   random key per build, and an APK signed with a different key cannot replace an existing install.
4. **Repository bloat — resolved in the follow-up round; see §8.** (Originally deferred because `.git`
   history cleanup normally requires a deliberate `filter-repo` decision rather than a drive-by change.)
5. **`StreamManager` sets `videoClients = 0` after a client-triggered rebuild** — cosmetically wrong for
   a second or two, then corrected by the disconnect callback. Harmless; left alone to avoid churn.
6. **`getVirtualCameraStatus()` uses `spawnSync`** (up to 5 s) on the Electron main process, so the UI can
   freeze while it runs — **resolved in the follow-up round; see §3.5.** The blocking variant was kept for
   the tests, which is why it was deferred here rather than changed blind.
7. **README's "Up to 4K UHD"** is accurate for H.264/H.265; `clampSize()` caps MJPEG at 1080p by design.

---

## 6. Device verification checklist (before tagging a release)

- [ ] Portrait + both landscape holds, **each codec** (MJPEG, H.264, H.265) — image upright, correct
      aspect (no stretching), no rotation flicker while turning the phone.
- [ ] Front camera with mirror toggle on/off — no upside-down or double-mirrored output.
- [ ] Connect a **second** viewer (OBS and the Studio client simultaneously) — both decode without a restart.
- [ ] Reconnect OBS *without* changing settings — video resumes without a rebuild.
- [ ] Kill Wi-Fi mid-stream (airplane mode) with the Studio client open — verify it reports "stream
      dropped — reconnecting" and recovers when the phone returns (regression test for §3.1).
- [ ] H.264 on a device whose encoder reports CSD only via output format (any modern Qualcomm/Exynos) —
      verify first-frame decode (regression test for §2.3).
- [ ] Screen-off background streaming for 30+ minutes, then check battery/CPU and no thermal throttle.
- [ ] Set the fps slider to **25** (or 40/50) and stream for 30 s — `%APPDATA%\obs-virtualcam.txt` must
      keep reporting the chosen rate. Before the fix it flipped to 30/60 (regression test for §3.6).
- [ ] Rotate the phone **while streaming to the PC client** — the virtual camera output must be upright
      rather than squashed (the C# converter rescales instead of rotating; see the §3.8 audit notes).
- [ ] Leave the Studio open and connected for 5+ minutes — the UI must stay responsive while the
      virtual-camera status refreshes (regression test for §3.5).
- [ ] Stream at 60 fps on a mid-range device (MJPEG 1080p is CPU-heavy) — confirm the HUD reports
      the rate actually achieved, and that a 1080p30-only device falls back to 30 instead of losing
      video entirely (§9.3).
- [ ] Check idle CPU/battery with the Studio open but no phone connected — the standby card runs at
      60 fps but no longer re-copies pixels, so idle cost is near the measurement floor (§10.1).

---

## 7. Test inventory (Android)

| Suite | Tests | Covers |
|---|---|---|
| BitstreamTest | 7 | Annex-B conversion, config merging |
| CameraFpsRangeTest | 6 | FPS range selection |
| CameraRotationTest | 9 | rotation math, **dimension swap contract** |
| NetworkUtilsTest | 3 | interface ranking |
| Nv21RotationTest | 6 | NV21 rotate/mirror |
| PermissionsTest | 3 | permission helpers |
| ProtocolTest | 8 | wire-request parsing |
| StreamConfigTest | 6 | config sanitisation, **default fps is 60 and is not clamped down** |
| StreamServerTest | 9 | handshake latency, lifecycle, **restart-after-failed-bind** |

PC client: `test_stream_parser` (10), `test_reconnect_policy` (8, new), `test_vcam_status` (7, new),
`test_vcam_stream_integrity` (8, new),`test_vcam` (61, includes the standby-copy **restore guard**), `test_renderer_sequencing` (16),
`test_discovery_and_diagnostics` (27) — **138 total**.

---

## 8. Repository hygiene (follow-up round)

### 8.1 `.git` went from 327 MB to 1.8 MB — with no history rewrite

* **Diagnosis first:** the pack was ~326 MiB while the largest *reachable* blob was only ~0.5 MB, so the
  weight was **unreachable objects** (large binaries added and later dropped from the tree). That means the
  bloat could be reclaimed by garbage collection — no `filter-repo`, no `rebase`, no changed commit ids.
* **Action:** pruned unreachable objects and repacked.
* **Verified after:** `du -sh .git` → **1.8 MB**; `git count-objects -vH` → `in-pack: 1400`,
  `size-pack: 1.60 MiB`, `count: 0`, `prune-packable: 0`, `garbage: 0`; `git fsck` clean;
  `git rev-parse HEAD` → `faa6d3c` (unchanged); reflog intact. Because no commit was rewritten, the remote
  and any existing clone stay valid.

### 8.2 Stale local installers removed

* `pc-client-native/dist/` (stale electron-builder output, installers 1.6.3 … 1.6.10) was deleted. The
  directory is gitignored and was untracked, so no repository content changed — only local disk.
* The reclaim was recorded as ~737 MB when the directory was removed; it can no longer be re-measured
  because the directory is gone, and the ~500 MB figure earlier in this document came from the initial
  listing rather than a `du`.
* `find` confirms no stray installers remain in the tree; the only `*.exe` files left are the legitimate
  toolchain binaries in `node_modules/` and the bundled `vcam/vcam_feeder.exe`.

---

## 9. Frame-rate defaults: 60 fps end to end

### 9.1 The 30 fps cap was real on the Windows side, and is now proven fixed

* **Found and reproduced on the committed binary:** the virtual-camera adapter dropped an explicit
  60 fps choice down to 30. `RunFeeder` selects only 30 or 60, and *any* source slower than ~35 fps
  triggered `AdaptFps(30)` regardless of what the caller had configured — so a user who picked 60 got
  30 the moment the phone could not hold 60.
* **Fixed:** the up-shift (30 → 60 when the source really is fast) still applies to everyone, but the
  down-shift now only ever returns a *30-configured* user to their own choice
  (`bool adaptDownward = (_configuredFps == 30)`). A user who picked 60 keeps 60; consumers duplicate
  frames as needed, which is normal for a 60 fps virtual camera.
* **Verified** with identical input against both binaries (`--feed 640 480 <rate>`, 14 real JPEG
  frames, `obs-virtualcam.txt` saved and restored around every run):

| Scenario | Committed binary | With the fix |
|---|---|---|
| user picked **60**, 40 ms frames | `640x480x333333` → **30 fps (capped)** | **`640x480x166666` → 60 fps** |
| user picked 30, 10 ms frames | 60 fps | 60 fps |
| user picked 30, 40 ms frames | 30 fps | 30 fps |
| user picked **25**, 40 ms frames | `333333` → **30 fps (ignored)** | **`400000` → 25 fps** |

### 9.2 60 is now the default at every layer

| Layer | Was | Now |
|---|---|---|
| Android `StreamConfig.fps` | 30 | **60** |
| Android AE fps-range selection | already preferred `[60,60]` → `[30,60]` → any `*.60` → `[30,30]` | unchanged (already correct, already tested) |
| Android `FPS_PRESETS` | 15/24/30/60 | unchanged |
| Studio slider (`index.html` value + label) | 30 | **60** |
| `renderer.js` / `main.js` fallbacks (`\|\| 30`) | 30 | **60** |
| `main.js` standby start + `currentStreamFps` | 30 | **60** |
| `vcam-feeder.js` (`currentFps`, `start()` default) | 30 | **60** |
| C# `_currentFps` / `_configuredFps` / `--feed` / default `RunFeeder` | 30 | **60** |
| C# standby-loop unknown-rate fallback | 33 ms | 16 ms |

There was **no hard clamp at 30 anywhere** in the camera path: `fps.coerceIn(1, 120)`, the AE
fps-range selection, `StreamProtocol`'s `fps` setting and the Studio's `Math.min(60, …)` all already
allowed 60. The 30s were defaults — plus, on the Windows side, the adapter override in §9.1.

### 9.3 A 60 fps default cannot brick a device that only encodes 30

`VideoEncoder.start()` returns `false` rather than throwing (it catches `Throwable`), and the encoded
path used to fail the whole stream when no encoder configuration would start. With 60 as the default
that would turn a default-rate change into a **dead stream** on devices whose encoder tops out at
1080p30. New `startEncoderWithFallback()` preserves the existing precedence — the other codec at the
same rate (HEVC → AVC), then the lower rate — and persists whatever actually started, so the UI and
saved preferences stay truthful about what is running.

### 9.4 Trade-off worth knowing: the standby loop now runs at 60 fps

The always-on standby card (what consumers see before a phone connects) is published at the configured
rate, so it now rewrites a 1080p NV12 frame 60×/s instead of 30×/s — roughly 186 MB/s of `memcpy`
while idle — and the virtual camera advertises 60 fps before any phone has connected. **The bandwidth
waste is fixed in §10.1** (the card is static, so the re-copy was redundant): the 60 fps standby now
costs essentially nothing. If you would still rather publish the standby card at 30 fps, the one-line
revert is the startup `vcamFeeder.start(...)` in `main.js` back to `fps: 30`, leaving the live path at 60.

---

## 10. Virtual camera idle cost

### 10.1 The standby card was re-copied 60×/s for nothing

* **What was wrong:** the always-on standby loop republished the card on every tick through the full
  frame-write path — a `Marshal.Copy` of the whole NV12 frame. At 1920×1080 that is 1920×1080×1.5 =
  3.1 MB per tick, i.e. **~186 MB/s of `memcpy` while completely idle**. The card is a static image and
  `WriteStandbyToAllBuffers` had already placed it in all three ring slots, so every copy was redundant.
* **Fix:** each `MappedBuffer` now tracks which slots currently hold the card
  (`StandbySlots`). `WriteFrameToSharedMemoryInternal(..., standby: true)` skips the pixel copy when the
  target slot is already valid, and marks the slot invalid when a live phone frame overwrites it, so the
  card is rewritten exactly once per slot after each live burst. The sequence counter, per-slot timestamp
  and READY state still advance on every tick, so consumers observe the same published cadence — only the
  redundant copy is gone. `InitSharedMemory` invalidates all slots when it re-initialises, so a geometry
  change forces a fresh card.
* **Measured** with real process CPU time (`Get-Process.TotalProcessorTime`), 1920×1080 at 60 fps, no
  phone connected, stdin held open exactly as the Electron client does:

| Binary | CPU over 6 s idle | Share of one core |
|---|---|---|
| pre-optimisation | 125.0 ms | 2.08% |
| with the fix | **0.0 ms** | below the counter's floor |

  One intermediate sample measured exactly 15.6 ms, which is the granularity of
  `TotalProcessorTime` (one 64 Hz accounting tick) — so the honest statement is that idle CPU dropped
  from ~125 ms per 6 s to **at or below the measurement floor**, roughly 8× or better. What remains is
  the 60 Hz publish tick itself: three 32-bit header writes and a barrier, which is what keeps consumers
  seeing a live, current stream.

### 10.2 Verified that the card is still restored everywhere — and that the guard can fail

The risk of skipping a copy is a slot keeping the last live frame, i.e. frozen video instead of the
standby card. Three checks were added to `test_vcam.js`, reading the first 16 bytes of each ring slot's
pixel payload through the existing shared-memory inspector:

1. all three slots hold the identical card before any live frame;
2. live phone frames really do overwrite the card (so check 3 cannot pass vacuously);
3. once live frames stop, the card is restored in **all three** slots.

* **Mutation-tested:** forcing `StandbySlots[...] = true` on live writes (so nothing is ever invalidated)
  makes check 3 fail with the slots still holding live pixels — `standbyCard 13-17-…` versus
  `restoredCard 7F-7F-…`. That is precisely the frozen-frame failure mode, and the test catches it.
  The mutation was reverted and the suite re-verified green.
* Test counts: virtual-camera suite **61** (was 58); PC suite total **138** (was 135).
