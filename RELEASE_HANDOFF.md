# OpenCam 1.6.2 — Release Handoff

This archive contains the reviewed OpenCam Android + OpenCam Studio Windows source after the final connection-hardening and polish pass.

## Compatibility boundary

The Android↔Windows TCP protocol was preserved: existing `/v5/video/...`, `/v1/status`, settings, and 12-byte media framing semantics were not changed. The desktop client was hardened around that existing boundary.

## Verification performed

- Windows wire-protocol regression suite: 10/10 passed.
- Node syntax checks: passed.
- `git diff --check`: passed.
- Kotlin parser probe: no Kotlin syntax/parser errors; Android SDK/AndroidX dependencies are unavailable in the repair environment, so a full Gradle build was not possible here.
- No tracked signing keystore or executable build artifact is included.

Build the Android APK from the included Gradle project and the Windows client from `pc-client-native` in an environment with the required SDK/dependencies installed.
