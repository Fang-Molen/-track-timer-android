# Capture isolation 0.1.3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Deliver an APK that automatically separates camera-only capture from encoded video in device diagnostics.
**Architecture:** Extend mode selection and the current camera owner with a PRIVATE ImageReader sink. Add a cancellable sequential comparison controller and conservative report analysis. Keep all actual hardware claims pending device execution.
**Tech Stack:** Kotlin, Camera2, ImageReader, MediaRecorder, Android platform UI, JUnit/Robolectric.
**Spec:** ../specs/2026-11-01-capture-isolation-013.md

## Global Constraints
- Existing package, signature, API26 minimum and API35 target; version 0.1.3-probe/4.
- No pixel copies, unbounded queues, private vendor flags, silent mode fallback or claims of verified timing.
- User has authorized implementation and APK delivery; execute inline in existing worktree.

## Review Focus
- Surface type changes confound causal conclusions; missing/short/error reports cannot pass.
- Image consumer exceptions and cancel must close every acquired image and the reader.
- Late camera/step callbacks cannot launch another step after pause/cancel.
- Camera device identity remains fixed across all steps.
- Exhausted/unsupported modes and permission denial must restore usable controls.

### Task 1: Pure capture capability and drain
**Files:** CameraModes.kt, DeviceReport.kt, FrameDrain.kt; CameraModesTest.kt, FrameDrainTest.kt.
**Interfaces:** CameraConfig.captureOnly and forcedCameraId selector; FrameDrain provides image timestamps and close/error counts.
- [ ] Write selection tests: no recorder/preview required; PRIVATE duration enforced; no format fallback; forced camera not substituted. Write image release/error/bounded-drain tests.
- [ ] Run focused tests; expected new behavior fails.
- [ ] Implement exact-mode selection, PRIVATE ImageReader consumption, diagnostic capabilities.
- [ ] Run focused tests; expected pass. Commit.

### Task 2: Camera owner, automatic run and UI
**Files:** CameraProbe.kt, CameraComparison.kt, MainActivity.kt; camera/sequence/UI tests.
**Interfaces:** CameraProbe accepts optional runId and forcedCameraId; reports captureOnly/imageFps/reader timestamps. CameraComparison accepts ordered step completion, cancellation and produces conservative summary.
- [ ] Write tests for finite sufficient dual-stream measurements, format confound wording, cancellation/late completion, failed steps, pure capture no video, and one-click permission denial/UI recovery.
- [ ] Run focused tests; expected missing behavior fails.
- [ ] Integrate reader ownership and reporting; sequence five steps on same device; disable competing actions; save run reports.
- [ ] Run entire core/app unit suite, assemble and lint; expected pass. Commit.

### Task 3: Review and delivery
**Files:** verification/, deliverables/, README/handoff docs.
- [ ] Independent whole-change review; resolve blocking findings with regression tests.
- [ ] Verify APK version/signature, all tests/lint, document physical verification pending.
- [ ] Copy APK/checksum, preserve root README and old artifacts, fresh fetch and fast-forward push existing main; verify remote bytes. Commit and deliver link/instructions.
