# Track Timer Device Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Deliver an installable Android diagnostic APK to validate the user's 60 fps phones, audio timestamps and local-network clock calibration.

**Architecture:** A pure Kotlin core owns clock estimation, frame analysis, tone detection and the bounded calibration protocol. Native Android adapters acquire Camera2/MediaRecorder capture metadata and AudioRecord samples; one foreground Activity controls diagnostics and exports a ZIP of recordings and JSON/CSV evidence. All data stays on the phones unless the user explicitly exports it.

**Tech Stack:** Kotlin 2.1.10; Android Gradle Plugin 8.9.1; Gradle 8.11.1; compile/target SDK 35; min SDK 26; JDK 21 with JVM target 17; platform Android views; JUnit 4.13.2.

**Spec:** `docs/superpowers/specs/2026-11-01-track-timer.md` — this plan implements Stage A only.

## Global Constraints

- 60 fps is the first target; enumerate and measure actual third-party camera support.
- No race results, claimed 0.05 s accuracy, automatic torso detection or fake timestamps in this diagnostic APK.
- App-scoped monotonic-clock session IDs invalidate old calibration after process restart; wall-clock time is used only for human-readable report labels.
- Audio and camera diagnostics can run together; leaving the Activity stops hardware safely and retains completed diagnostic data.
- Permissions are requested at runtime, denied permissions remain recoverable, and export uses the system document picker.
- No server/account, analytics, or remote network service. Both phones join the same Wi-Fi/hotspot manually.

## Review Focus

- Empty/invalid/negative or inconsistent timestamp exchanges must not produce a usable offset.
- Socket clients that stall, send oversized lines or use the wrong pairing code must not hang the host or receive calibration timestamps.
- Advertised 60 fps may be unavailable with the selected recording size, or may actually deliver 30 fps; both cases must be visible.
- Missing audio hardware timestamps, background noise and app interruption must not become a trusted start event.
- Export while diagnostics finish or Activity recreation must not corrupt files or leak microphone/camera resources.

## File structure

- `core/src/main/kotlin/cn/tracktimer/core/ClockSync.kt`: timestamp exchange bounds, calibration and drift diagnostics.
- `core/src/main/kotlin/cn/tracktimer/core/FrameAnalysis.kt`: measured cadence and gaps from capture timestamps.
- `core/src/main/kotlin/cn/tracktimer/core/ToneDetector.kt`: conservative beep candidate detection in PCM.
- `core/src/main/kotlin/cn/tracktimer/core/SyncProtocol.kt`: authenticated bounded text protocol and loopback-testable TCP host/client.
- `core/src/test/kotlin/cn/tracktimer/core/*Test.kt`: independent numerical, signal and socket tests.
- `app/src/main/java/cn/tracktimer/app/DeviceReport.kt`: capability enumeration and report creation.
- `app/src/main/java/cn/tracktimer/app/CameraProbe.kt`: preview, 60/30 fps recording and capture metadata.
- `app/src/main/java/cn/tracktimer/app/AudioProbe.kt`: microphone recording, test tone, hardware timestamp samples and WAV.
- `app/src/main/java/cn/tracktimer/app/ReportStore.kt`: atomic evidence storage and ZIP export.
- `app/src/main/java/cn/tracktimer/app/MainActivity.kt`: Chinese diagnostic controls and lifecycle handling.

### Task 1: Buildable project and clock/frame core

**Interfaces:** `Exchange(t1,t2,t3,t4)` provides bounds; `ClockSync.estimate(samples,localSession,remoteSession): Calibration`; `FrameAnalysis.analyze(timestampsNs,targetFps): FrameReport`.

- [x] Configure Gradle modules, local toolchains and Android manifest; create core tests before implementation.
- [x] Verify tests reject invalid samples, recover a known +123 ms offset under asymmetric delays, preserve uncertainty and detect 30 fps/large frame gaps.
- [x] Implement clock bounds and frame analysis; verify `./gradlew :core:test` passes.
- [x] Commit the build and timing core.

### Task 2: Pairing and calibration over real sockets

**Interfaces:** `SyncServer(token,sessionId,clock).start(port): Int`, `close()`; `SyncClient.measure(host,port,token,localSession,clock,count): SyncMeasurement`.

- [x] Write loopback integration tests for known clock offset, wrong pairing code, oversized request, timeout and close.
- [x] Observe failure, implement bounded line parsing, session-aware requests and repeated timestamp exchanges.
- [x] Verify `./gradlew :core:test` and commit.

### Task 3: Audio candidate detection and Android capture adapters

**Interfaces:** `ToneDetector.detect(samples,sampleRate,frequency): ToneCandidate?`; adapters provide JSON diagnostic results through callbacks, never a race start timestamp claim.

- [x] Write tests using silence/noise and a generated 1800 Hz beep with known onset; verify missing candidates and time-localization behavior.
- [x] Implement detector and pass `:core:test`.
- [x] Implement capability report, Camera2 preview/MP4/capture timestamp logging and AudioRecord/tone/WAV capture.
- [x] Run Android compilation and lint, inspect hardware lifecycle and missing-timestamp paths; retain true hardware validation in the device checklist.
- [x] Commit capture diagnostics.

### Task 4: Chinese diagnostic UI, durable reports and installation guide

**Interfaces:** `ReportStore` writes atomic JSON and exports completed report files; Activity starts/stops probes and calibration jobs without blocking main thread.

- [x] Build four clear panels: device report, two-phone calibration, camera/audio probe, export.
- [x] Require runtime camera/audio permissions; display actual recording state, uncertainty and unverified status; prevent conflicting duplicate operations.
- [x] Stop/release hardware on lifecycle exit and cancel network jobs; export only stable report files via system picker.
- [x] Write README and true-device test steps, then run `:core:test :app:assembleDebug :app:lintDebug`.
- [x] Verify APK signing/package contents, review the whole implementation and fix material findings; package APK and source archive.
- [x] Commit final deliverables and record what remains untested on physical devices.

## Execution choice and self-review

User explicitly asked to begin after design discussion; proceed inline without another approval round. A fresh reviewer examines the whole implementation at completion. The four tasks cover all Stage A requirements; race results and later phases deliberately remain outside this APK. Android hardware integrations are validated by compilation/lint plus an explicit real-device checklist, not mocked precision claims.

## Additional verification

Four Robolectric API 28 tests were added after the software emulator failed to finish booting. They caught a TextureView background startup crash and verify permission denial, host shutdown/restart and calibration cancellation controls. Test dependencies do not ship in the APK. Physical-device precision remains unverified.
