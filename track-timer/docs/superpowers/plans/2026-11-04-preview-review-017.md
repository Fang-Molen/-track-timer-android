# Preview and review repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans inline with one independent final review.

**Goal:** Correct distorted preview and allow video browsing when clock validation fails, without accepting untrusted timing.
**Architecture:** Extract back-camera TextureView geometry, separate playable frame count from trusted time mapping, expose existing clock rejection reasons.
**Tech Stack:** Kotlin Android Camera2/TextureView/MediaMetadataRetriever; JUnit/Robolectric.
**Spec:** ../specs/2026-11-04-preview-review-017.md

## Global Constraints
- Version8/0.1.7-field, min26/target35, exact index28+, same signing/repo, retain originals.
- Do not relax clock/mapping gates or claim underlying failed field alignment fixed without logs.
- No nearest-time/nominal-fps timing fallback; only trusted rawPTS allows save.

## Review Focus
- Portrait90/270 sensor mount, changed view dimensions, nonzero display rotation: proportional and no duplicate orientation/mirror.
- Failed/missing clock metadata: first frame and navigation work, time/save stay unavailable.
- Report/session/video mismatch or missing frame count: no trusted timing, clear playable/unsupported state.
- Asynchronous old frame/closed activity: existing generation gate and serialized release retained.
- Diagnostic reasons must match existing criteria without changing valid/invalid classification.

### Task1: Preview geometry
Files: new CameraPreviewTransform.kt, FinishActivity.kt; CameraPreviewTransformTest.kt.
Interface: matrix(viewWidth,viewHeight,bufferWidth,bufferHeight,sensorOrientation,displayRotationDegrees):Matrix.
- [x] Test hand-derived720x1280 upright pixels in1080x640 view: frame rect(360,0,720,640), equal pixel scale, positive direction;90/270 sensor same portrait; display90/180 and resized view remain centered/proportional.
- [x] RED then minimal geometry + on-layout update, full core/app GREEN, commit.

### Task2: Playable video and clock diagnostics
Files: RaceReviewActivity.kt, HighSpeedCalibration.kt, FieldEvidence.kt; existing review/calibration tests.
Interfaces: independent frameCount and optional FrameTimeline; HighSpeedCalibration.failedChecks(report):List<String>, failureDescription(report):String.
- [x] Add UI regression for failed clock with readable3-frame video: first bitmap/nonzero index navigation, no elapsed/save/results; old session valid path preserved; mismatched report and missing metadata also cannot save.
- [x] RED then separate video setup from timeline validation; keep exact-index loading/generation gate.
- [x] Test rejected fixtures yield corresponding diagnostics while clean120/240 and file-rounding-only remain same pass/fail; implement diagnostic fields, full suite/assemble/lint GREEN; commit.

### Task3: Review and delivery
- [x] One independent whole-branch review; consequential fixes RED→GREEN.
- [x] APK version/signature/bytes, validation evidence/guide; fresh fetch/FF upload original repo; compare remote blob bytes and rootREADME.
