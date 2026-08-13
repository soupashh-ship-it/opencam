# Sonnet audit remediation matrix

This matrix maps the 38 findings in the supplied static audit to the repaired
v1.4.0 source. It records the implementation outcome rather than repeating the
original report.

| ID | Outcome | Repair |
|---:|---|---|
| 01 | Fixed | `StreamServer` retains socket ownership until a stream client is handed off; every early return closes it in `finally`. |
| 02 | Fixed | `GlRotator` retains and destroys EGL context/surface, GL texture/program, `SurfaceTexture`, input surface and worker thread synchronously. |
| 03 | Fixed | `MediaCodec` and `AudioRecord` are assigned immediately after creation and released on every partial initialization failure. |
| 04 | Fixed | AAC PTS is derived from cumulative PCM sample frames rather than repeatedly truncated buffer durations. |
| 05 | Fixed | `OpenCamApplication` owns the process-wide manager; `CameraViewModel` no longer force-unwraps a nullable holder. |
| 06 | Fixed in supplied source | No keystore, plaintext password or Git history is shipped. Release signing is secret-driven and fail-closed. Existing exposed credentials must still be rotated; see `SECURITY.md`. |
| 07 | Fixed | Idle media writers no longer read and discard bytes from the inbound stream; TCP keepalive and socket close own liveness. |
| 08 | Fixed | Complete bare video/audio paths are accepted immediately; a 250 ms timeout is only a malformed/fragment fallback. |
| 09 | Fixed | Camera state and capture-request mutations are confined to the camera handler thread. |
| 10 | Fixed | Request parsing uses a bounded 2–4 thread executor with a bounded queue; persistent stream clients are capped. |
| 11 | Fixed | Header draining recognizes both full `\r\n\r\n` and the low 16-bit `\n\n` terminator. |
| 12 | Fixed | Encoder surfaces are nullable-checked and failed encoders are explicitly stopped before fallback/return. |
| 13 | Fixed | HEVC is started once; a failed instance is stopped before a newly constructed AVC encoder is attempted. |
| 14 | Fixed | Tally uses one `matchEntire` result, validates method/state and removes the force unwrap. |
| 15 | Fixed | Android backup is disabled. |
| 16 | Resolved by ownership design | The camera handler is a single process-lifetime thread owned by the single application manager; it no longer accumulates per start/rebuild. Camera resources themselves close on stop. |
| 17 | Fixed | The MJPEG handler thread is created lazily and quit/joined whenever the encoder pipeline stops. |
| 18 | Verified | The reported MJPEG completion path already completed through the camera callback; the rewritten generation-based path now completes or fails exactly once. |
| 19 | Fixed | All rebuild state is serialized on the manager handler with generation invalidation and a single queued rebuild. |
| 20 | Fixed | The battery loop is a cancellable manager-handler runnable, eliminating old/new raw thread overlap. |
| 21 | Fixed | MJPEG now captures YUV, reuses NV21 buffers, rotates planes allocation-free and compresses once to JPEG; the RGB Bitmap decode/rotate/re-encode loop is removed. |
| 22 | Fixed | Port edits commit only on IME Done, focus loss or sheet dismissal. |
| 23 | Fixed | Tap coordinates are transformed through the active aspect/zoom crop before AF/AE regions are submitted. |
| 24 | Fixed | The wake lock has a 30-minute safety timeout and is renewed every 20 minutes only while the service runs. |
| 25 | Fixed | Size clamping uses floating-point scale preservation and rounds to valid even encoder dimensions. |
| 26 | Fixed | Video sessions use `TEMPLATE_RECORD`. |
| 27 | Fixed | Sensor orientation is cached when camera characteristics are selected, avoiding a transient zero fallback. |
| 28 | Fixed | Aspect crop rejects non-finite/non-positive values and invalid sensor dimensions. |
| 29 | Fixed | Wi-Fi, Ethernet and USB interfaces are ranked ahead of cellular; cellular is fallback-only. |
| 30 | Fixed | Activity lookup unwraps `ContextWrapper` layers. |
| 31 | Fixed | AVCC is fully length-walked before Annex-B classification, including the ambiguous one-byte-NAL prefix case. |
| 32 | Improved | Frame headers are written directly into one final packet allocation; the separate header allocation/copy is removed. |
| 33 | Fixed | QR generation uses one `setPixels` call instead of per-pixel JNI calls. |
| 34 | Fixed | Typed `startForeground` is used from API 29 (`Q`). |
| 35 | Fixed | Unneeded `singleTask` launch mode was removed. |
| 36 | Fixed | CI secrets are environment-bound, masked, written with `printf` under `umask 077`, and the APK signature must verify. |
| 37 | Fixed | `StreamClient` is sealed and its socket is private. |
| 38 | Fixed | Pending session work is camera-thread-confined and generation-guarded; stale callbacks close themselves. |

## Additional defects corrected

- `PUT /v1/tally/...` was previously rejected because all non-GET methods were
  discarded.
- The resolution encoded in the OBS video request was parsed but not applied.
- Display rotation was read as a non-observable Compose value while the activity
  handled configuration changes, leaving the encoded stream stuck in its old
  orientation.
- Front and back camera rotation equations were not consistently shared by the
  preview, encoder dimensions and focus mapping.
- A second lens request could race an in-flight `openCamera()` callback before a
  `CameraDevice` existed; close waiters now include the in-flight-open state.
- Release builds could silently be unsigned; both Gradle and CI now fail closed.
