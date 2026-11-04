# SDD ledger — plan: docs/superpowers/plans/2026-11-04-field-batch-display-018.md

Base: 1445a358f4d57265af32a08d62bc81cf6d402d76. Existing linked worktree verified; new branch fix/field-batch-display-0.1.8. Prior baseline: 139 passing tests, successful APK and lint, no intervening production edits.

Execution: native inline, one fresh reviewer at end. Skill scripts are cloud resources, so briefs and ledger maintained with local tools. User's standing authorization covers repair and delivery to existing GitHub repository; no new approval flow.

Pre-flight: Task 1 preserves existing calibration/timeline interfaces consumed by Task 2's review UI. Task 2 changes dimensions only, not surfaces or frame selection.

Task 1: in progress. Break to reproduce: complete Android expanded batches are rejected by strictly increasing frame-number assumption. Negative fixtures protect against accepting arbitrary repeated numbers or discarding bad callbacks.

Task 1: complete. RED 5 tests, 2 expected failures (valid batches rejected; historical timeline blocked). After implementation, targeted calibration/evidence tests GREEN. One initial compile run caught a List/step typo, corrected to IntProgression; next run successful. Real-log Kotlin replay GREEN: 5 aligned reports, 1 short interrupted report still rejected; newest 391 anchors/30ns maximum residual/99.55% coverage. Original sidecars unchanged. Replay source and data kept outside git.

Task 2: in progress. Layout implementation uses VideoFrameSizer instead of a FieldActivity method: one listener per image with updated upright dimensions, avoiding repeated listener registration. Same width-fitting behavior and unchanged camera surface. Simple size changes use existing preview/review regressions per developer instruction.

Task 2: code complete. Full command :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug successful (1m30s). Core34 + app110 =144 tests, no failures/errors/skips. Existing preview and review regressions included. Awaiting final independent review and packaging.

Final review: fresh reviewer field_018_review, read-only, reviewed1445a35..09c2ac2 plus callers/tests/AOSP. Verdict with fixes.
Final: Important — explicit two-output high-speed preview with only unique endpoint records could fall back to direct_samples; requires full-batch enforcement before sparse fallback. RED regression added for120/240 endpoint-only partial groups.
Final: Ruling: reviewer graded enlarged-image gesture interception Minor; regraded Important because most of the viewport now consumes vertical swipes and users should not need to discover narrow margins to reach controls. Implement axis-aware dragging and real ScrollView touch regressions; cost if wrong is obstructed controls or accidental finish-line movement.
Final: declined handset layout/camera continuity — software regressions cover behavior, actual0.1.8 device check remains required; preserve original surface, transforms and capture session.
Final: declined other vendor variants — unrecognized preview batches remain rejected; compatibility may be narrower until new evidence arrives.
Final: declined independent private-log/full-suite rerun — author observed Kotlin replay and144test/build/lint outputs; originals remain local.
Final: declined packaging/signing/remote delivery — signature already verified; final artifact and remote bytes will be verified after the single fix pass.
Final: declined optical/audio/whole-system accuracy — unchanged, explicitly unverified in release guide.

Final: fixed endpoint-only preview fallback and vertical-scroll interception. Three added regressions RED (8tests/3failures) → GREEN in full147test suite (core34/app113). Build+lint successful3m18s,0failures/errors/skips;9existing lint warnings. One review, one fix pass; no deferred findings.

Task 2: complete. Final APK signed/verified, same certificate. Production a694968. Packaging and authorized remote delivery verification tracked by the root task; user data not committed.
