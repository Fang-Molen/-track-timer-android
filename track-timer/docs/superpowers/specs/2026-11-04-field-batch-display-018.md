# 0.1.8: larger field images and high-speed batch calibration

The user confirmed 0.1.7 fixed aspect, rotation and black review, but the image is still small. The preceding log investigation also identified the remaining timing blocker: Android 15 expands a preview+encoder high-speed batch into four (120 fps) or eight (240 fps) completed callbacks using the same frame number and derived intermediate sensor timestamps. The app currently rejects their repeated numbers.

## Approach and constraints

- Make finish preview and review fill the available page width at the upright video's native aspect ratio. Retain scrolling for controls; no activity rotation, surface reparenting, cropping, zoom, or fullscreen lifecycle change. Compared with a fullscreen activity, this is a smaller change that preserves the now-working camera surface. This is the implementer's choice within the user's request to enlarge the image.
- Recognize only complete, internally consistent batches on constrained-high-speed preview+encoder reports at 120/240 fps. Verify batch size fps/30, nonnegative and increasing batch frame numbers, increasing sensor timestamps, all callback clock checks, and the exact Android integer timestamp interpolation. Use only the last metadata record per batch as a sensor-clock anchor. Do not pick records by proximity to encoder timestamps.
- Preserve 2,000 ns matching tolerance, >=30 matching anchors, >=5 s matched span, >=80% span coverage, REALTIME clock, healthy file, strict codec timestamps, exact frame-count and index checks. Unexpected/partial/reversed/inconsistent batches fail closed. Never shift timestamps to pass.
- Preserve original frames and explicitly record callback count, anchor count, derived count, selection method and batch validity in calibration diagnostics. Existing sparse no-preview calibration remains supported.
- Readable legacy field reports with this recognizable batch shape can be rechecked in memory during review; do not rewrite originals or change raw time arrays. Existing rejected clocks still fail.
- Version 0.1.8-field / code 9, same application ID and signing key, no added permissions or dependencies. Existing authorized GitHub repository only; root README and older artifacts stay intact. Never commit uploaded private logs, recordings or identifiers.

## Evidence and limits

On the user's 0.1.7 record, 391 complete groups of four exactly reproduce Android's integer interpolation; every batch endpoint directly matches a unique encoder sample within 30 ns, spanning 13.03081 s and 99.55% coverage. AOSP android-15.0.0_r1 CameraDeviceImpl.java lines 2377-2395 explains the expansion and shared resultExtras. Four supplied field recordings share the pattern. These observations do not establish optical exposure accuracy, audio latency or total timing error.

## Acceptance

Preserve the user-confirmed image direction/proportions and frame navigation. Display portrait images at full content width, with scroll-accessible controls. Replay the latest log through actual Kotlin calibration and timeline; confirm valid batches align while malformed data and the old short interrupted trial remain rejected. Run unit suites, APK build, lint and one independent code review before delivery.
