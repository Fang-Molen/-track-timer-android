# High-speed clock calibration 0.1.5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans inline with one independent final review.

**Goal:** Deliver a120/240fps timestamp-calibration APK with direct encoder/sensor clock evidence.
**Architecture:** New modes use owned MediaCodec/MediaMuxer recorder inside existing CameraProbe lifecycle; pure bounded sample accounting and report analysis enforce evidence gates. Existing comparison runner gains a two-step variant.
**Tech Stack:** Kotlin, Camera2 constrained high-speed, MediaCodec surface, MediaMuxer, JUnit/Robolectric.
**Spec:** ../specs/2026-11-03-high-speed-clock-015.md

## Global Constraints
- min26/target35; version6/0.1.5-probe; same signature; existing GitHub repo only.
- No silent240 fallback; no fixed offset fitting, no physical precision claim, no user media published.
- One-button10s+10s foreground test, logs-only export; preserve existing audio/network and normal modes.

## Review Focus
- Codec resource partial construction and cancellation/error cleanup, EOS timeout, buffer release on mux failure.
- Sparse metadata/direct clock match must not pass normalizedPTS, wrong clock, short/error/cancelled data.
- File PTS normalization/count mismatch must invalidate any reported file-to-clock mapping.
- Unsupported240 must remain explicit and retain120 result; no old result or camera substitution.
- Background/permissions must not restart hardware or advance a cancelled sequence.

### Task1: Sample accounting and calibration evidence
Files: app EncodedSamples.kt, HighSpeedCalibration.kt; corresponding tests.
Interfaces: EncodedSamples.add(ptsUs,observedNs):Long returns relative muxPTS; snapshot report arrays. HighSpeedCalibration.annotate(JSONObject) adds nested clockCalibration evidence without physical verification.
- [ ] Tests: sparse step4/step8 sensor matches, wrong epoch/unknown source, insufficient/cancel/error, duplicate/missing frames, MP4 retiming/count mismatch, bounded sample collector and rawPTS preservation.
- [ ] Observe RED, implement gates/accounting, run core/app suite GREEN, commit.

### Task2: Encoder capture and capability modes
Files: SurfaceEncoder.kt, CameraProbe.kt, CameraModes.kt, DeviceReport.kt; mode/probe/boundary tests.
Interfaces: SurfaceEncoder.prepare(config,file), surface, poll(), finish():JSONObject, close(); owns all resources. New HD120_CLOCK/HD240_CLOCK modes with clockCalibration flag.
- [ ] Tests: strict240 capability and no fallback, new consumer identity on selection failure/cancel; encoder output handling releases buffers on sink error and excludes codec config from sample count.
- [ ] Observe RED, implement bounded polling/EOS and muxing, wire CameraProbe new reports and inspect file; run tests GREEN, commit.

### Task3: Sequence/UI/delivery
Files: CameraComparison, MainActivity, app/build.gradle.kts, UI/sequence tests, docs and deliverables.
- [ ] Tests: two-step failed240 retains120, cancellation/duplicates/wrong camera; new UI button permission denial/background recovery.
- [ ] Observe RED, implement one-button sequence and honest result copy; full test/assemble/lint GREEN, commit.
- [ ] Independent final review, fix important findings using failing regressions then full checks; verify signature/version, APK/checksum, docs.
- Delivery gate: fresh-fetch, fast-forward existing main, verify remote APK bytes and preserved root README before final response.
