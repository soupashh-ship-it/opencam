#!/bin/bash
set -e
cd '/e/Reverse Engineer Droid/OpenCam'
git config user.name 'soupashh-ship-it' 2>/dev/null
git config user.email 'soupashh-ship-it@users.noreply.github.com' 2>/dev/null
git add -A
git status --short | head -10
git commit -q -m "fix: unlock real 60fps and stop intermittent app crashes

Low fps (still ~30 even with H.264 selected on good devices):
- The dormant MJPEG ImageReader was ALWAYS a capture-session target, even in
  encoded mode — forcing the HAL to produce full-res YUV frames alongside the
  encoder surface, which caps the whole session at ~30fps on most devices.
  CameraController now takes includeMjpegReader (wired from usingEncoder) so
  the H.264 path runs with only encoder surface + preview; the reader is
  re-added atomically via setStreamOutputs() when a client switches to jpg.
- Windows client preview poll 40ms -> 16ms (was a hard 25fps display cap).

Intermittent crashes:
- MediaCodec.createEncoderByType/configure/start throw unchecked
  CodecException/IllegalArgumentException, but openCameraPipeline, ensureCodec
  and ensureAudio only caught IOException — with H.264 now the default, devices
  lacking/failing the encoder crashed the app. All encoder/audio start paths
  now catch RuntimeException (fall back to jpg / no-audio), and
  VideoEncoderPipeline/AudioStream convert codec RuntimeExceptions to
  IOException with full resource cleanup.
- Session lifecycle hardening: creatingSession flag prevents a second
  createCaptureSession while one is configuring (\"session busy\"); queued
  restarts drain via onConfigured; encoder surface is released only after the
  camera session has swapped away from it (500ms defer) to avoid session
  config failures on codec switch.
- startAudioClient closes the socket when audio fails to start (was a leak
  the watchdog couldn't reclaim)."
git push origin main 2>&1 | tail -2
echo COMMITTED=$(git log --oneline -1)
