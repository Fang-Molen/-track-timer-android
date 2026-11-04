# 0.1.8 validation

Built 2026-11-04. Application cn.tracktimer.app, code9, version0.1.8-field, minSDK26/target35. Same signing certificate as0.1.7; existing installations can upgrade in place.

## Reproduced fault and fix

The user confirmed0.1.7 fixed preview aspect, direction and black frame review, while asking for a larger image. Its fixed230/280dp viewport now expands to the actual upright image's aspect at full page width. The original TextureView, transform, frame-index retrieval and scrolling controls are retained.

The supplied0.1.7 camera evidence had1564 callbacks in391 groups of4; group frame numbers repeated while SENSOR_TIMESTAMP increased. All derived timestamps exactly match the Android15 integer interpolation. The old strictly-increasing-frame-number gate rejected this before any encoder matching.

Reference: [AOSP Android15 CameraDeviceImpl](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/hardware/camera2/impl/CameraDeviceImpl.java#L2377), expanded onCaptureCompleted, shared resultExtras and synthesized intermediate sensor timestamps.

HighSpeedMetadata selects endpoints by structure before reading encoder timestamps. It requires the constrained-high-speed session, preview enabled, two outputs,120/240fps, complete4/8-entry batches, identical within-batch numbers, expected between-batch increment and exact interpolation. Every original callback still undergoes clock validation. Tolerance2,000ns,30matching anchors,5s matched span,80% coverage and existing health/REALTIME/index checks stay unchanged. Unsupported or malformed repeated-number reports fail closed.

## Automated verification

- RED: five new regression methods ran against0.1.7; two failed for the reproduced valid-batch/historical-review paths, three rejection tests already passed.
- A first implementation build caught a Kotlin List/step typo; corrected to IntProgression before successful verification.
- GREEN: calibration and evidence regression suites passed, then all147 shipped tests passed (core34/app113), zero failures/errors/skips.
- `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug -Pkotlin.compiler.execution.strategy=in-process`: BUILD SUCCESSFUL in3m18s.
- Lint0errors,9existing warnings: portrait-orientation recommendations and usableSpace advice.
- Synthetic tests exercise120/240 batches, direct original encoder timestamps, in-memory historical recheck, count mismatch, missing provenance, partial/corrupted batches, corrupt derived callbacks, shifted codec clocks, short span, low coverage and health rejections. Existing preview geometry and actual frame-navigation/save tests also ran.
- `apksigner verify --verbose --print-certs`: v2 verified. Certificate SHA256 `52e46c1f931b96cca6273a8ceac5f4a38f7e93d8f2238c676a90cf433f83b92a`.

## Actual log replay (local only)

A temporary Robolectric test exercised production Kotlin calibration and FrameTimeline against six supplied camera reports. It was removed from the shipped test tree; original user files remain outside git.

|Recorded version / mode|Anchors|Recomputed alignment|
|---|---:|---|
|0.1.7 preview120|391|pass|
|0.1.6 preview120, longer trial|1097|pass|
|0.1.6 preview120, short interrupted trial|117|rejected|
|0.1.6 preview120, other longer trial|830|pass|
|0.1.5 no-preview240|289|pass retained|
|0.1.5 no-preview120|285|pass retained|

Newest report:1568 codec/file samples,391unique endpoint matches,30ns max recorded residual,13.03081s matched span,99.5533% coverage. Historical timeline recheck leaves original JSON and timestamp values unchanged. Replayed frame counts come from the report; no new handset decoding or optical measurement was performed here.

## Remaining physical checks

Install0.1.8 and open the saved11:41 trial, check enlarged view, move after the start and save a training result. Then record a new120fps trial.240fps with preview has synthetic regression coverage but no new handset report in this delivery. Long180s recordings, outdoor network coverage, acoustic latency and total timing error remain unverified;30ns timestamp agreement is not30ns system accuracy.

Independent review identified an incomplete-preview sparse fallback and enlarged-image vertical gesture interception. Both were addressed in one fix pass with three additional RED→GREEN regressions, followed by the147test full suite. No findings deferred. See development-log-0.1.8.md for the ledger and review boundaries.

Final APK:1,157,977bytes; SHA256 `7bc1e000d3658847a8dc4da29b68f50575c44c66ddfa800177f083367fe03e30`. Production commit `a6949680e6673edb6c4f15829a21949d38f4c667`.
