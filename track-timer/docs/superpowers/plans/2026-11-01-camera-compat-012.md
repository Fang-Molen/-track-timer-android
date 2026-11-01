# Camera Compatibility 0.1.2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Deliver controlled normal/high-speed camera tests and lightweight log export.
**Architecture:** A camera mode/capability selector produces exact CameraConfig values. CameraSessionSetup routes ordinary versus constrained-high-speed sessions; CameraProbe uses only requested surfaces. Existing export gains an explicit JSON-only option.
**Tech Stack:** Kotlin, Android Camera2/MediaRecorder/Views, JUnit4/Robolectric, SDK35/min26.
**Spec:** ../specs/2026-11-01-camera-compat-012-design.md

## Global Constraints
- Explicit60fps modes never silently fallback; automatic mode retains60/30 fallback.
- Real measured capture/encoded FPS and unverified timing flags remain authoritative.
- No private vendor keys, global volume changes, raw-user-media uploads or existing-file destruction.

## Review Focus
- Encoder-only recording must work without a preview surface or ready TextureView.
- Unsupported requested size/range must produce a labeled error rather than a misleading fallback result.
- High-speed mode must use dedicated session and repeating burst; API26 retains its older creation method.
- Camera startup timeout/cancel/late callbacks must release resources and restore UI.
- JSON-only exports must exclude media and pending files; full export must remain available.

### Task 1: Camera mode selection and session execution
Files: CameraModes.kt (new), DeviceReport.kt, CameraSessionSetup.kt, CameraProbe.kt, CameraModesTest.kt (new), CameraSessionSetupTest.kt.
Interfaces: CameraTestMode enum; CameraModes.select(capabilities, mode): CameraConfig?; DeviceReport.cameraConfig(context, mode=AUTOMATIC); CameraSessionSetup.open(..., highSpeed=false), repeat(..., highSpeed=false).
- [x] Write and run failing tests: fixed1080p selection, no-preview ignores preview constraints, no silent60->30, explicit120 capability, modern/legacy high-speed creation, burst submission.
- [x] Implement capability selector and route exact requested outputs/session type. Preserve ordinary-mode API compatibility and record mode/config/result evidence even on failure.
- [x] Add bounded startup timeout to return controls on a stuck camera configuration; cancellation closes late resources.
- [x] Run focused camera tests and commit.

### Task 2: Mode UI and small exports
Files: MainActivity.kt, ReportStore.kt, MainActivityTest.kt, ReportStoreTest.kt (new).
Interfaces: ReportStore.export(output, logsOnly=false); independent request codes for full/logs exports.
- [x] Write and run failing tests for logs-only ZIP contents, full ZIP preservation, new controls/defaultmode, mode switching/no-preview copy and canceled file picker recovery.
- [x] Add mode selector disabled during recording, latest-per-mode results, and logs-only export button using SAF. No unrequested hardware starts on selection.
- [x] Run UI/export regression tests and commit.

### Task 3: Verify and deliver0.1.2
Files: build.gradle.kts, README/checklist, verification and existing repository deliverables/HANDOFF.
- [x] Set versionCode3/versionName0.1.2-probe; run core/app tests, assembleDebug, lintDebug, APK signature/manifest checks.
- [x] One independent whole-branch review; important findings get reproducing tests and a fix pass.
- [x] Prepare standalone APK/checksum and concise testing instructions; preserve old assets. Delivery uses fast-forward/push of the existing repo; final response records remote byte verification.
