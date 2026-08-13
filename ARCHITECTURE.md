# OpenCam architecture

OpenCam turns an Android phone into a network or USB webcam for OBS by
implementing the server side of the DroidCam OBS plugin protocol.

```text
Camera2 ──► GPU rotation / MediaCodec or MJPEG ─┐
AudioRecord ──► AAC MediaCodec ──────────────────┼─► StreamManager ─► TCP server :4747
NsdManager / mDNS ───────────────────────────────┘
                                      │
                                      └────────────► OBS + DroidCam plugin
```

## Ownership and threading

`OpenCamApplication` creates one process-wide `StreamManager`. The activity,
foreground service and `CameraViewModel` all resolve that same instance, so an
Android activity recreation or process-restore path cannot dereference a null
or stale holder.

`StreamManager` serializes lifecycle and rebuild operations on one manager
`HandlerThread`. Camera2 state is independently confined to the camera
`HandlerThread`. Network request parsing uses a bounded executor, while each
accepted persistent media client has a bounded writer queue. This prevents UI,
Camera2 callback, server and encoder threads from mutating the same lifecycle
state concurrently.

## Main components

| Component | Responsibility |
|---|---|
| `camera/Camera2Controller.kt` | Camera selection, capture sessions, video-oriented request template, FPS selection, zoom crop, exposure, white balance, EIS, torch and zoom-aware tap focus |
| `util/CameraRotation.kt` | Shared normalized sensor/display rotation equations for front and back cameras |
| `util/PreviewTransform.kt` | Uniform center-crop TextureView transform without stretching |
| `encode/VideoEncoder.kt` | H.264/HEVC MediaCodec encoder with deterministic native-resource cleanup |
| `encode/GlRotator.kt` | EGL/GLES rotation into the encoder surface, with synchronous texture/program/context destruction |
| `encode/AudioEncoder.kt` | AudioRecord to AAC with cumulative-sample timestamps and guarded initialization cleanup |
| `encode/Bitstream.kt` | Safe ByteBuffer extraction and AVCC-to-Annex-B conversion |
| `server/StreamServer.kt` | Bounded request executor, protocol handshake, client limits and media writers |
| `server/Protocol.kt` | Method-aware request parsing and 12-byte frame headers |
| `stream/StreamManager.kt` | Camera/encoder/server/mDNS orchestration and serialized rebuilds |
| `service/StreamingService.kt` | Camera/microphone foreground service and renewable safety-bounded wake lock |
| `discovery/NsdHelper.kt` | `_droidcamobs._tcp` advertisement and multicast-lock ownership |
| `ui/CameraScreen.kt` | Compose UI, live display-rotation listener, preview, controls, committed port editing and QR dialog |

## Orientation pipeline

The display rotation is observed with `DisplayManager.DisplayListener`, not
read once as an ordinary Compose value. Every 0°, 90°, 180° or 270° change is
sent to `StreamManager`, which rebuilds the encoded dimensions and transform.
The output rotation is:

- back camera: `(sensorOrientation - displayRotation + 360) % 360`
- front camera: `(sensorOrientation + displayRotation) % 360`

For a 90°/270° transform the encoded width and height are swapped. H.264/HEVC
rotation is performed by `GlRotator`; MJPEG uses reusable YUV/NV21 buffers,
rotates those planes, and compresses the result to JPEG. Preview and stream use
the same normalized rotation source so portrait phone use no longer produces a
landscape stream.

## Rebuild and resource lifecycle

A rebuild performs the following sequence:

1. invalidate older asynchronous generations;
2. wait for the Camera2 session to close;
3. release GL before its encoder surface;
4. stop and release video/audio codecs and MJPEG resources;
5. create one encoder attempt, explicitly release it on failure, and use a
   fresh AVC encoder only when HEVC fallback is needed;
6. create the new camera session;
7. publish state only if the callback still belongs to the active generation.

Camera-device switches use a bounded device-close barrier to avoid
`CAMERA_IN_USE` races. Stop/start is safe because preview surfaces and the
restartable MJPEG worker are recreated rather than retained in a dead state.

## Server behavior

The TCP server owns all sockets through one close path. Null or incomplete
requests cannot leak descriptors. Stream handshakes are recognized without a
mandatory 500 ms wait. HTTP headers detect both CRLF and LF-only terminators.
No read is performed merely to test whether an idle client is alive, so future
protocol bytes cannot be consumed accidentally.

The server is intentionally unauthenticated for plugin compatibility. Use it
only on trusted networks and stop streaming when finished.

## Release security

Backups are disabled for app data. Signing credentials and keystores are not
part of the source tree. Release tasks fail when complete signing material is
missing, and CI verifies the APK signature before publishing an artifact.
