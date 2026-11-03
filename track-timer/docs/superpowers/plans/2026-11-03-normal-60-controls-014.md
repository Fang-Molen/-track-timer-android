# Normal 60 FPS controls 0.1.4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans inline, with one independent final review.

**Goal:** Deliver controlled normal60 stream-use-case/manual-sensor experiments and honest high-speed statistics.
**Architecture:** Capability-gated CameraConfig extends existing Camera2 owner; paired nine-step sequence reuses cancellation and export. Separate metadata/video statistics preserve evidence boundaries.
**Tech Stack:** Kotlin/Android Camera2/MediaRecorder, JUnit/Robolectric.
**Spec:** ../specs/2026-11-03-normal-60-controls-014.md

## Global Constraints
- min26/target35, existing signing identity; version5/0.1.4-probe.
- Fixed camera, no silent fallback, no proprietary settings, no claims of actual60 without hardware results.
- Keep accepted user workflow: one button, foreground, small ZIP.

## Review Focus
- Older API and absent capabilities must fail explicitly before unsupported calls.
- Manual exposure must turn AE off and honor all sensor ranges; requested versus actual remain separate.
- High-speed sparse results cannot become false30 or fabricated per-frame timestamps.
- Short/cancelled/errored measurements cannot pass video or normal gates.
- Existing cancellation, permission return, and camera ownership must remain safe across nine steps.

### Task 1: Capability/request/session controls
Files: CameraModes, DeviceReport, CameraSessionSetup, CameraProbe; corresponding tests.
Interfaces: CameraConfig.streamUseCase:Long and manualSensor:ManualSensorSettings?; open(...,streamUseCase:Long=0).
- [x] Test unsupported/old-API usecase, manual capability/range/key gating and fixed ISO clamp, AE_OFF/manual values, OutputConfiguration usecase before session creation.
- [x] Observe RED, implement public API controls/reporting, observe GREEN.
- [x] Commit tested control changes.

### Task 2: Reporting, comparison and UI
Files: CameraStatistics (new), CameraComparison, MainActivity, CameraProbe; statistics/sequence/UI tests.
Interfaces: annotate(report) adds separate metadata/video/estimated frame-number fields; videoRateReached never proves sensor mapping.
- [x] Test sparse4-step120fps metadata fixture, ordinary30/60, short/error/cancel handling, ninth-step completion and paired factors.
- [x] Observe RED, implement nine-step comparison and two-minute UI with manual brightness notice.
- [x] Run full core/app tests, assemble and lint. Commit.

### Task 3: Review and deliver
- [x] One independent review; fix important findings using failing regressions, then full checks.
- [x] Verify same APK signature/version; copy APK/checksum and update handoff/release docs.
- Delivery gate after upload: fresh fetch; preserve root README/history; fast-forward existing main and verify remote APK bytes before reporting completion to the user.
