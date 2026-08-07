#!/bin/bash
set -e
cd '/e/Reverse Engineer Droid/OpenCam'

git config user.name 'soupashh-ship-it' 2>/dev/null || true
git config user.email 'soupashh-ship-it@users.noreply.github.com' 2>/dev/null || true

git add -A
git commit -q -m "fix connection dropping between the phone and the Windows client

Root cause: the phone only noticed a dead client when the next frame write
threw. If the PC vanished (WiFi blip, sleep) or the camera stalled, the sink
stayed attached forever and every reconnect got BUSY until the user restarted
streaming on the phone. On top of that, the client fully disconnected on any
5s stall instead of retrying.

Android:
- watchdog (StreamService): reclaims any video/audio client whose sink hasn't
  received a frame for 12s, freeing the busy slot without a phone restart;
  identity-checked so a new client attaching mid-drop is never closed
- last-write tracking in MjpegProducer/VideoEncoderPipeline/AudioStream
- TCP keepalive on stream sockets (HttpServer) for half-open detection

Windows client:
- auto-reconnect with backoff (1.5/3/6/12s, max 4 attempts) instead of full
  disconnect on stream errors; keeps the phone session + virtual camera alive
- reconnect counter resets when frames flow again; pending guard prevents
  double-scheduled reconnects; gives up cleanly if the phone stays down
- aggressive TCP keepalive (SIO_KEEPALIVE_VALS 5s) so a vanished phone is
  detected quickly, our socket closes, and the phone's sink frees
- socket read timeout 5s -> 12s to ride out phone pipeline restarts

Verified headless: transient outage reconnects, counter resets, permanent
outage gives up; full client selftest passes."

git push origin HEAD 2>&1 | tail -2
echo DONE
