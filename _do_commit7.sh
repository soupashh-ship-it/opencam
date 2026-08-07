#!/bin/bash
set -e
cd '/e/Reverse Engineer Droid/OpenCam'
git config user.name 'soupashh-ship-it' 2>/dev/null
git config user.email 'soupashh-ship-it@users.noreply.github.com' 2>/dev/null
git add -A
git status --short | head -5
git commit -q -m "fix: Connect now auto-finds the phone when the saved IP is stale

Pressing Connect (or auto-connect) dialed the saved IP even after DHCP
reassigned the phone, so it failed with 'connection refused' while the
phone sat discoverable on the network — the scan found it, but Connect
ignored the results unless the user manually picked from the dropdown.

- If the primary host (and any explicit candidates) fail, the client now
  runs a network scan automatically and connects to whatever phone it
  finds, before giving up with an error.
- Scan with exactly one result auto-connects immediately (no manual pick).
- Connect button re-enabled + status feedback through each fallback step;
  double-connect racing is guarded with an in-flight flag.

Verified against the live phone: stale IP fails -> scan finds phone ->
auto-connects (1080p60 H.264, virtual camera up, no stream errors)."
git push origin main 2>&1 | tail -2
echo COMMITTED=$(git log --oneline -1)
