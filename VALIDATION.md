# OpenCam v1.6.2 validation record

## Release checks

- Windows wire-protocol regression suite: **10/10 passed**.
- Node syntax checks passed for `pc-client-native/main.js`, `src/renderer.js`, `stream-parser.js` and `test_stream_parser.js`.
- `git diff --check` passed after the release changes.
- Android source was syntax-probed with the available Kotlin compiler; no Kotlin parser errors were reported.
- Android↔Windows connection/framing changes were kept additive: the existing TCP request paths and 12-byte media frame format were not changed.

## Environment limitation

A complete Android Gradle build could not be run in this repair container because the Android SDK/Gradle distribution and external dependencies are unavailable offline. No newly built APK or signed release artifact is claimed in this archive.

The previous audit and validation history follows for traceability.

---

# OpenCam v1.4.0 validation record

## Completed checks

- Kotlin PSI syntax parsing passed for every Kotlin source file and all Kotlin
  Gradle scripts.
- The real `Protocol`, `StreamServer`, `Bitstream` and `CameraRotation` sources
  compiled in an isolated JVM harness.
- Integration tests opened a real loopback TCP server and verified battery,
  ping, `PUT` tally, bare video/audio request parsing, LF-only headers and the
  fast stream handshake.
- Rotation tests covered Android rotation-enum conversion, front/back output
  equations at 0°, 90°, 180° and 270°, and NV21 luma/chroma plane rotation at
  90°, 180° and 270°.
- The real `Camera2Controller` compiled against a Camera2 signature harness,
  including capture-session callbacks, focus regions and close barriers.
- The real `StreamManager`, `Protocol` and `StreamServer` compiled together
  against Android/lifecycle signature stubs.
- XML resources, `libs.versions.toml` and the release workflow parse cleanly.
- `git diff --check` passed.
- Source scans found no Kotlin force-unwrapping, committed keystore, hardcoded
  signing secret, `allowBackup="true"`, TODO/FIXME marker or indefinite
  no-timeout wake-lock acquisition.

## Environment limitation

A complete `./gradlew :app:assembleDebug` or signed release build could not be
run in the repair container because it did not contain an Android SDK/Gradle
distribution and external dependency retrieval was unavailable. Therefore this
archive deliberately does **not** include or claim a newly built APK.

The Gradle wrapper is retained. The release workflow installs Android platform
36/build-tools 36, requires all signing secrets, builds from clean sources and
runs `apksigner verify` before publishing. Run the debug build locally in
Android Studio/JDK 17, and use the CI workflow for a verified signed release.
