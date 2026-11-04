# Field workflow implementation plan

Spec: ../specs/2026-11-04-field-workflow-0110.md. Inline execution, one final independent review; no repeated user approval (standing authorization).

## Review focus
- Preview owns no recorder/file; camera closes before high-speed open, late callbacks/pause cannot start a trial.
- Video cleanup only acts on explicitly selected completed files; batch requires explicit review completion for every referencing race; retain JSON/results and reject active/path-escape cases.
- Dragging never starves visible updates or builds unbounded work; stale previews cannot be saved, release selects exact frame, teardown recycles resources.
- Time navigation uses recorded timestamps while score computation remains strict; keyboard controls/history remain usable.

## Tasks
1. RED/GREEN standalone preview lifecycle gate and no-file behavior; integrate pre-record preview with current high-speed alignment confirmation.
2. RED/GREEN manual storage policy/deletion and retained-history UI; add inventory/single/batch actions and explicit review-completed marker.
3. RED/GREEN latest-frame scheduling, fine-range/time jumps, exact save and UI regressions; integrate into review and compact keyboard layout.
4. Full tests/build/lint, native visual review if available, one independent branch review/fix pass; signed APK, docs, authorized existing-repo push and byte verification.
