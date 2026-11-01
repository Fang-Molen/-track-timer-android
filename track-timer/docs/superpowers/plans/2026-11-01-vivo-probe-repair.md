# Vivo Probe Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Deliver 0.1.1-probe with consistent media playback and measurable camera session configuration.
**Architecture:** Keep the existing probes and report schema; extract a shared short-tone player for microphone and playback-only paths. Submit the record request when configuring the camera session and capture actual result metadata.
**Tech Stack:** Kotlin, Android platform API26–35, Gradle, JUnit4/Robolectric.
**Spec:** ../specs/2026-11-01-vivo-probe-repair.md

## Global Constraints

- minSDK26, targetSDK35, 150 ms / 1800 Hz test tone, ordinary 60/30 fps video.
- No automatic global volume changes, no precision claims without device evidence, no raw personal recordings in GitHub.
- Preserve original repository README and old artifacts.

## Review Focus

- Focus denied or muted media must produce actionable diagnostics without opening the microphone unnecessarily.
- Leaving foreground before or during playback must prevent/stop sound and release audio focus.
- A slow preview stream must not be advertised as a usable 60 fps recording combination.
- API26/27 must not execute API28 SessionConfiguration; request/result mismatches must be visible.
- A successful playback-head advance is not proof of audible sound or an acoustic start timestamp.

### Task 1: Consistent audio playback and isolated audition

Files: app/.../TestTonePlayer.kt (new), app/.../AudioProbe.kt, app/.../MainActivity.kt, app/src/test/.../TestTonePlayerTest.kt (new), MainActivityTest.kt.
Interfaces: TestTonePlayer(context, sampleRate), play(), snapshot(): JSONObject, close(); AudioProbe(..., playbackOnly: Boolean = false).
- [ ] Add failing tests for media volume routing, zero-volume/focus denial diagnostics, and independent audition controls using production playback policy and platform shadows.
- [ ] Run focused tests and observe expected failure.
- [ ] Share the short tone player between the existing microphone test and a no-microphone audition; log focus, route, mute/volume, play state and head progress. Stop on cancel/focus loss; preserve diagnostic errors and collected data.
- [ ] Run audio/UI tests; commit verified source change.

### Task 2: Session FPS and returned camera evidence

Files: app/.../DeviceReport.kt, CameraProbe.kt, CameraSessionSetup.kt (new), MainActivity.kt, app/src/test/.../CameraSessionSetupTest.kt (new).
Interfaces: camera selection uses supported recorder and SurfaceTexture sizes/durations; CameraSessionSetup configures a session using one request for initial parameters and repeating capture.
- [ ] Add failing tests for mismatched preview throughput and modern/legacy session routing, including exact requested FPS in session parameters.
- [ ] Run focused tests and observe expected failure.
- [ ] Create record request before the session; on API28+ submit as session parameters; report actual AE range/frame duration/control metadata. Clearly warn when measured FPS is below 90% of requested FPS; preserve legacy API path.
- [ ] Run camera/UI regression tests; commit verified source change.

### Task 3: Version, verification and delivery

Files: app/build.gradle.kts, ReportStore.kt, README.md, docs/device-validation-checklist.md, verification/*, repository deliverables/ and HANDOFF.md.
- [ ] Set versionCode2 and versionName0.1.1-probe and consistent report/UI version.
- [ ] Run :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug, verify signature matches 0.1.0 and manifest version, APK digest.
- [ ] Obtain one independent whole-branch review, address important findings with regression tests.
- [ ] Add concise reproduction notes and new APK plus SHA256; preserve old APK. Fetch remote; fast-forward only; push existing repo and verify remote APK bytes.

Expected automated result: all tests/build/lint pass; physical sound and actual60fps require user retest and are not acceptance claims.
