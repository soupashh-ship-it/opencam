#!/bin/bash
set -e
cd '/e/Reverse Engineer Droid/OpenCam'
git config user.name 'soupashh-ship-it' 2>/dev/null
git config user.email 'soupashh-ship-it@users.noreply.github.com' 2>/dev/null
git add -A
git status --short | head -12
git commit -q -m "perf: unlock 30-60fps streaming on both platforms

Android (MJPEG path):
- toNv21 bulk row-copy fast path: Y plane via one native memcpy per row,
  chroma rows staged into scratch arrays, instead of ~3M bounds-checked
  ByteBuffer.get() calls per 1080p frame (was the ~20fps bottleneck)
- ISP direct-JPEG capture (ImageFormat.JPEG reader) used when the camera
  advertises the size — zero YUV->NV21->libjpeg conversion; one-shot YUV
  fallback on devices that reject JPEG in repeating requests
- JPEG reader capped at 1 in-flight image (many devices throw otherwise)
- GrowBuf zero-copy JPEG delivery (no toByteArray() copy per frame)
- direct-JPEG path skipped in encoded mode to avoid ERROR_MAX_IN_FLIGHT

Windows client:
- H.264 is now the default stream codec (MJPEG at 1080p/92 is 18-48MB/s,
  too heavy for WiFi; H.264 makes 1080p60 realistic)
- fall back to MJPEG automatically if the 'av' package is missing
  (was a hard disconnect)
- BILINEAR instead of LANCZOS resize in preview + vcam feeder

Both verified: APK build green, client selftest green, exe selftest green."
git push origin main 2>&1 | tail -2
echo COMMITTED=$(git log --oneline -1)
