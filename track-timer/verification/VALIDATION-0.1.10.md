# 0.1.10 validation

Built2026-11-04. Application cn.tracktimer.app, versionCode11/versionName0.1.10-field, minSDK26/target35. Same certificate as0.1.9 for in-place upgrade and retained local data.

## Behavior and boundaries

- Independent ordinary preview owns no recorder/encoder/media file and has no180s recording timer. It uses the selected recording camera/size/orientation; ordinary and high-speed framing may differ. Actual camera release precedes starting the existing high-speed path, and its frames plus operator confirmation still gate READY. Permission/pause/late callbacks cannot silently start a trial.
- Manual storage inventory and single deletion; batch snapshots only explicitly marked reviewed videos and rechecks all references. Saved results alone are insufficient. JSON/results remain after media cleanup, and history shows retained results without opening a missing player.
- Process-local media ownership protects the pending name, renamed MP4, inspection, calibration/report and onDone publication. Deletion checks the same lock; normal diagnostic recordings are protected too. Explicitly previous-process nonterminal MP4s may be removed manually; current/unknown sessions remain protected and batch completion stays strict. Pending files/audio/outside paths/symlinks are not eligible.
- Scrubbing uses one in-flight exact-index decoder and the latest requested destination. During dragging intermediate frames may render, but cannot be saved. Release waits for the exact final index. Whole/local range, recorded-timestamp search and electronic-start seconds jump do not replace any saved-score validation. Short windows hide navigation/header to retain athlete input/save space.

CameraProbe changes only video ownership bookkeeping. Camera recording/encoder settings, actual frame/timestamp handling, networking and sound/timing checks retain their previous behavior.

## Evidence

- RED live-preview entry and storage-discoverability tests failed before implementation.
- RED continuous-drag regression reproduced the old80ms debounce/generation starvation: no image update before finger release. GREEN test verifies visible intermediate frames, disabled saving while dragging, exact final image and saved frame index.
- Storage tests cover manual versus explicit-review batch, exact confirmed names, shared unreviewed references, active state, retained JSON/results, path escape, symlink, pending/audio exclusion, and missing-video history UI.
- Preview tests cover separate permission intent, no-media failure cleanup, close-before-start and cancellation/failed/duplicate callbacks. No test pretends to exercise physical camera capture.
- Nonuniform raw timestamp boundary tests verify navigation does not substitute nominalFPS or file playback time. Native rendering of actual activities at320×640 and360×800 checked the layout; preview/video pixels were simulated. Compact-window regression passes.
- Independent read-only review found two Important cleanup defects: premature deletion during MP4 finalization, and permanent old-session cleanup lockout. Both were fixed in one pass. Unchanged cleanup policy produced2expected failures among8tests; final regressions plus a real CameraProbe done-callback ownership test pass. No second review was dispatched; no minor findings deferred.
- Final permanent suite **173passed**, core34/app139, zero failures/errors/skips; `BUILD SUCCESSFUL in 3m 51s`. Lint0errors/14warnings ({'LockedOrientationActivity': 6, 'DiscouragedApi': 6, 'UsableSpace': 2}). Temporary native screenshot fixture excluded from the shipped suite.
- APK v2 signature verified; certificate SHA256 `52e46c1f931b96cca6273a8ceac5f4a38f7e93d8f2238c676a90cf433f83b92a`.

## Handset validation still needed

Actual standalone-preview transition, final framing, keyboard behavior and long-video seek latency depend on the handset. Prior0.1.8 logs support the retained120fps timing pipeline; this new workflow has not been run on the user's phone here. Speaker latency and whole-system optical precision remain uncalibrated. No original user recordings/logs were deleted or included in the repository.

APK:1235809bytes. SHA256 `3b962c6d719ffb9cea78fa19f7166512a50453a0dcfa16e76217f406de7a5b89`. Production commit `50f77d5289d477f6e2ffe4c066537785e709d42e`. Decisions and review record: development-log-0.1.10.md.
