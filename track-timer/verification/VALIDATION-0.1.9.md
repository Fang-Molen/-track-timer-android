# 0.1.9 validation

Built2026-11-04. Application cn.tracktimer.app, code10, version0.1.9-field, minSDK26/target35. Same signing certificate as0.1.8; upgrade preserves local reports and videos. Launcher is now HomeActivity, diagnostic MainActivity remains internally accessible.

## Changed behavior

The live finish preview now overlays an image-space reference line. Real camera frames only enable the operator confirmation; READY is published after confirmation and successful journal persistence. The line then locks. Cancellation/background block late readiness. Recording remains the same continuous high-speed session, including alignment in the180s budget. No camera/encoder/clock thresholds or permissions changed.

New role home, unified native styling and fixed action footers reorganize the field flow. History/review retain exact-index decoding and strict timing checks. The line is restored from the trial and remains correct with letterboxing. Short windows hide the header so athlete entry remains reachable alongside the footer.

## Verification

- RED: two new live-overlay tests failed against baseline; targeted tests then passed after implementation.
- RED:360dp-high review viewport left insufficient space for athlete entry; compact-header adjustment and narrow step-button sizing passed the regression.
- Full permanent suite: **157 passed**, core34 + app123, zero failures/errors/skips. Temporary screenshot fixtures are excluded.
- Full test/build/lint: `BUILD SUCCESSFUL in 1m 23s`. Lint0errors/11warnings, covering locked portrait orientation (and the matching discouraged-orientation API advice) and the pre-existing usableSpace advice.
- Tests cover frame availability versus explicit operator confirmation, cancellation, failed persistence, invalid fractions, real RaceCoordinator fire gating, saved reference in live/review screens, gesture/letterbox/lock behavior, launcher navigation and compact-window input space. Existing networking, camera metadata, historical timing, exact frame, permissions and lifecycle regressions ran unchanged.
- Native-graphics Robolectric rendered actual Home/Start/Finish/History/Review activities at360×800 and320×640. Alignment state and review frame pixels in the visual fixture were simulated; these are layout checks, not high-speed camera tests. Reviewed red line, button wrapping, state text and fixed controls. A software-emulator attempt did not complete boot without KVM; no emulator hardware/camera claim is made.
- `apksigner verify --verbose --print-certs`: v2 verified, certificate SHA256 `52e46c1f931b96cca6273a8ceac5f4a38f7e93d8f2238c676a90cf433f83b92a`.

## Field boundary

Previous0.1.8 handset logs recorded two120fps trials at approximately119.716fps with camera/sync checks passing; one saved two training results. That evidence supports retaining the capture/timing pipeline. This delivery's new UI and readiness confirmation still require the user's two-phone outdoor run.180s recording,240fps preview stability, outdoor connectivity, speaker delay and whole-system optical accuracy remain unverified.

Independent whole-branch read-only review found no Critical/Important/Minor change requests and approved the source branch for merge. Scope boundaries and the owner’s decisions are recorded in development-log-0.1.9.md. No user raw logs, videos or private network data are included in this release.

APK:1179786bytes; SHA256 `9b664a335593faa5e8936df06a4aec13bd38841a2cca0fcfbf12b3f5b34c70f2`. Production commit `61e4d062fa277a4740f404206c15fc64cf747f71`.
