#!/bin/bash
set -e
cd '/e/Reverse Engineer Droid/OpenCam'
rm -f _check_apk.py
rm -rf app/build/outputs/apk/debug/arsc_check
git config user.name 'soupashh-ship-it' 2>/dev/null
git config user.email 'soupashh-ship-it@users.noreply.github.com' 2>/dev/null
git add -A
git status --short | head -8
git commit -q -m "android: default stream codec is now H.264/AVC

MJPEG at 1080p/quality-92 is 18-48MB/s over WiFi, which caps frame rate;
H.264 delivers the same quality at a few Mbps and is the 1080p60-capable
path (matches the Windows client's default). The settings spinner now
lists 'H.264 / AVC (default)' first; MJPEG remains selectable for
compatibility and legacy clients still negotiate per-connection."
git push origin main 2>&1 | tail -2
echo COMMITTED=$(git log --oneline -1)
