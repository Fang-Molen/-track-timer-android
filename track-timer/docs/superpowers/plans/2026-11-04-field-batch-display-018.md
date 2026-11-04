# Field batch calibration and larger images implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement task-by-task.

**Goal:** Enlarge the working field views and correctly validate Android high-speed preview batches.
**Architecture:** An isolated metadata selector validates batch structure before existing calibration. Aspect-aware frame sizing changes layout only. Historical recalibration is in memory and retains evidence and identity gates.
**Tech Stack:** Kotlin, Android Camera2, Robolectric/JUnit, Gradle.
**Spec:** ../specs/2026-11-04-field-batch-display-018.md

## Global Constraints

Preserve 2,000 ns direct-match tolerance, >=30 anchors, >=5 s span and >=80% coverage. No added permissions/dependencies. 0.1.8-field / code 9. No uploaded user data in git.

## Review Focus

- Partial/duplicated/reordered groups must not become accepted anchors (Task 1).
- Derived timestamp or callback corruption must not be discarded silently (Task 1).
- Missing provenance / ordinary sessions must not use batch normalization (Task 1).
- Historical report recheck must not bypass identity, raw-frame count or clock checks (Task 1).
- Image sizing must retain direction, full frame, and reachable controls without recreating the camera (Task 2).

### Task 1: validate high-speed metadata batches

Files: new `HighSpeedMetadata.kt`; modify `HighSpeedCalibration.kt`, `FieldEvidence.kt`; tests `HighSpeedCalibrationTest.kt`, `FieldEvidenceTest.kt` as needed.
Interface: `HighSpeedMetadata.select(JSONObject): Selection` carries anchor indices, batch size, validity and whether grouped; `HighSpeedCalibration.recheckForReview(JSONObject): JSONObject` returns a rechecked copy only for eligible historical batches.

- [ ] Write RED tests for valid 120/240 batches, unchanged codec timeline, in-memory historical recheck, malformed grouping, unsupported provenance, derived/callback corruption, insufficient anchors/span/coverage and shifted codec clocks.
- [ ] Observe targeted tests fail for existing repeated-frame rejection.
- [ ] Implement structural selection, diagnostic fields and conservative in-memory historical recheck.
- [ ] Run target suite GREEN and offline Kotlin replay of supplied logs, keeping raw data outside git.
- [ ] Commit code and tests.

### Task 2: enlarge the existing image areas

Files: `FieldUi.kt`, `FinishActivity.kt`, `RaceReviewActivity.kt`, version config. Interface: `VideoFrameSizer(View).setImageSize(Int, Int)` updates layout height from measured width and upright image dimensions with one listener per image.

- [ ] Replace fixed 230/280dp areas with content-width aspect sizing; preview config and decoded bitmap supply real upright dimensions. Preserve existing transform and ImageView FIT_CENTER.
- [ ] Run existing preview geometry / review navigation tests. Simple sizing values do not warrant implementation-mirroring tests. Final review adds real ScrollView gesture tests for enlarged-image control access.
- [ ] Bump 0.1.8-field / code 9; run full core/app tests, assembleDebug, lintDebug.
- [ ] Commit and obtain one fresh whole-branch review; address important findings with regression tests.
- [ ] Package signed APK, verification notes and release guide, update handoff docs; preserve existing artifacts and root README.
- [ ] Push authorized existing repository and verify remote APK bytes/hash and main commit.
