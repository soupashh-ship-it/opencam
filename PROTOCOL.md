# DroidCam OBS wire protocol — implementation notes

OpenCam implements the server side of the protocol used by the open-source
`droidcam-obs-plugin`. The plugin is the client and the Android phone is the
server.

## Discovery (mDNS)

OpenCam advertises `_droidcamobs._tcp` with Android `NsdManager`. The service
contains PTR/SRV records and TXT attributes for the device name, model and app
version. A multicast lock is held while discovery is active so the phone can
continue answering discovery queries with the display off.

## Transport

All requests use one TCP port, **4747** by default. Each stream or status
request opens a dedicated connection.

| Request | Purpose |
|---|---|
| `GET /v5/video/{avc|jpg|hevc}/{W}x{H}/port/{P}/os/.../obs/.../client/.../hdr/{0|1}/nonce/{N}/` | Video stream |
| `GET /v2/audio` | AAC audio stream |
| `GET /battery HTTP/1.1\r\n\r\n` | Battery percentage |
| `PUT /v1/tally/{program|preview|idle}/ HTTP/1.1\r\n\r\n` | OBS tally state |
| `GET /ping` | Liveness |
| `GET /v1/status` | JSON snapshot of the current stream + phone state |
| `PUT /v1/settings?k=v&...` | Apply stream/camera settings pushed by a client |

Video and audio requests normally arrive without CRLF or an HTTP version.
OpenCam therefore recognizes a complete known stream path as soon as it has
arrived and uses a short quiet-period fallback only for malformed or unusual
clients. This avoids the previous fixed half-second startup delay. HTTP-style
requests drain headers through either `\r\n\r\n` or bare `\n\n`.

Video and audio connections receive no HTTP response; framed media begins on
the same socket. Battery, tally, ping, status and settings use ordinary HTTP
responses. Tally is accepted only through `PUT`, and invalid tally states are
rejected.

### `GET /v1/status`

Returns a JSON object describing what the phone is currently streaming:
`version`, `codec` (wire name), `width`, `height`, `streamWidth`,
`streamHeight`, `fps`, `actualFps`, `bitrateMbps`, `jpegQuality`,
`audioEnabled`, `mirror`, `torch`, `lens`, `frontFacing`, `port`, `running`,
`battery`, `tally`, `sensorOrientation`, `maxZoom`, `zoom`, `videoClients` and
`ip`. Used by the PC client to follow the phone's settings and to pick the
right decoder.

### `PUT /v1/settings?k=v&k2=v2`

Applies settings pushed by a client. Recognized keys: `codec` (wire name),
`width`, `height`, `fps`, `bitrate`, `jpeg`, `mirror`, `audio`, `torch`,
`lens` (`back`, `front`, `back_wide`, `back_tele`) and `zoom`. Unknown or
invalid values are ignored; values are range-checked like app-side changes.
Settings are persisted and applied through the same pipeline as the app's own
settings sheet.

For resilience, request parsing runs on a bounded executor. OpenCam accepts at
most four persistent video clients and four persistent audio clients at once;
additional stream connections receive `503 Service Unavailable`.

## Frame format

```text
+----------------+------------------+---------------------------+
| PTS (8 bytes)  | length (4 bytes) | payload (length bytes)    |
| big-endian µs  | big-endian       | H.264/HEVC/MJPEG/AAC      |
+----------------+------------------+---------------------------+
```

- `PTS == 0xFFFFFFFFFFFFFFFF` identifies a codec-configuration packet.
- `length == 0xFFFFFFFF` is the protocol stop marker.
- Zero-length payloads and payloads above the protocol limit are rejected.

The frame packet is validated before allocation. One packet is created per
encoded frame and shared with all connected clients. Every client has a bounded
drop-oldest queue, so a stalled receiver cannot block the camera or encoder.

## Codec handling

- **H.264 / HEVC:** Android `MediaCodec` surface input. Length-prefixed NAL units
  are validated and converted to Annex-B. A full AVCC length walk is attempted
  before treating an ambiguous `00 00 00 01` prefix as Annex-B.
- **MJPEG:** Camera2 YUV output through `ImageReader` is copied into reusable
  NV21 buffers, rotated without RGB Bitmap allocation, then compressed to JPEG.
  Rotation is baked into the pixels because clients do not reliably honor EXIF
  orientation. MJPEG is capped to a practical 1080p height.
- **AAC:** 48 kHz mono PCM from `AudioRecord` is encoded as AAC-LC. Timestamps
  are derived from the cumulative sample count, preventing per-buffer integer
  truncation from accumulating into A/V drift. Vendor ADTS wrappers are removed
  before transmission.

## Session behavior

The video path carries the requested codec and resolution. When they differ
from the active configuration *and are genuinely new*, OpenCam persists the
requested values, rebuilds the camera/encoder pipeline, closes existing media
sockets, and lets the plugin reconnect. A reconnect that re-sends the same
request it sent before is treated as a retry, not a new choice — it does not
revert settings the user changed in the app. Width, height, ports and frame
lengths are range-checked before use.

Camera session shutdown is asynchronous on Android. OpenCam serializes camera,
codec and GL rebuilds and waits for the old capture session to stop targeting
surfaces before those surfaces are destroyed.

USB mode needs no app-side protocol change: the desktop plugin establishes an
ADB TCP forward to the same server port.
