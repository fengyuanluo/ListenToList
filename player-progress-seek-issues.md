# Player Progress / Seek Issue Ledger

Last updated: 2026-04-07

This file is the single source of truth for playback seek issues on the player page and MiniPlayer.
Each issue must be closed with both automated evidence and real-device evidence.

## Verification summary
- Automated build/test pass:
  - `cd android && ./gradlew testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest connectedDebugAndroidTest --warning-mode all`
- Real-device target:
  - `172.26.121.48:34327` / `PHP110` / Android 15
- Device-side coverage:
  - full `connectedDebugAndroidTest` suite passed on device, including the new player seek instrumentation tests

## Status legend
- `done`: code landed, automated acceptance passed, real-device acceptance passed
- `auto_pass_device_pending`: code landed, automated acceptance passed, waiting for real-device validation
- `in_progress`: code change not fully validated yet
- `blocked`: cannot proceed because environment or prerequisites are missing

## Issues

### SEEK-001
- Priority: P0
- Status: done
- Symptom: Player page tap seek and drag seek do not use the same mental model.
- Root cause: tap maps finger `x` to an absolute position, but drag starts from the current playback position and only applies relative deltas.
- Scope: `MusicSlider`
- Source evidence: `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/PlayerPage.kt`
- Fix plan: anchor drag preview to the finger-down position, keep drag updates absolute to the track, and preserve tap as an absolute seek.
- Automated acceptance: player slider instrumentation test proves 80%-down + 90%-drag resolves near 90% target.
- Real-device acceptance: start dragging far away from the current playhead and verify the slider follows the finger position instead of offsetting from the old playhead.

### SEEK-002
- Priority: P0
- Status: done
- Symptom: current-time label on the player page does not keep up with the preview position.
- Root cause: the left duration label was driven by an external formatted string instead of the slider’s displayed seek position.
- Scope: `MusicSlider`
- Source evidence: `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/PlayerPage.kt`
- Fix plan: drive thumb, fill, and current-time label from the same displayed milliseconds source.
- Automated acceptance: delayed-feedback test keeps the current-time label at the optimistic seek target before external state catches up.
- Real-device acceptance: drag on the player page and watch the left duration label update continuously with the slider.

### SEEK-003
- Priority: P0
- Status: done
- Symptom: releasing a drag can momentarily snap back to the old position before the seek settles.
- Root cause: drag state exited before the seek result had a local optimistic value to hold the UI steady.
- Scope: `MusicSlider`
- Source evidence: `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/PlayerPage.kt`
- Fix plan: keep an optimistic seek value after release until controller feedback settles or a timeout expires.
- Automated acceptance: delayed-feedback player slider test never reverts to the old `00:00:05` label after release.
- Real-device acceptance: drag to a new position and confirm there is no visible jump back to the old time before playback resumes from the new time.

### SEEK-004
- Priority: P0
- Status: done
- Symptom: normal playback position updates feel too coarse and make seek feedback feel stale.
- Root cause: `PlayerVM` only polled player position every 1000ms.
- Scope: `PlayerVM`
- Source evidence: `android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/PlayerVM.kt`
- Fix plan: poll every 250ms while playing/loading, keep 1000ms while idle, and force one immediate sync after seek.
- Automated acceptance: compile + tests prove no regression; code inspection confirms active polling interval changed to 250ms.
- Real-device acceptance: during playback, observe that the player page and MiniPlayer progress bars feel noticeably smoother than 1-second jumps.

### SEEK-005
- Priority: P1
- Status: done
- Symptom: player page and MiniPlayer seek controls do not feel like the same product.
- Root cause: player page used a custom tap + relative-drag implementation while MiniPlayer used Material Slider with different release semantics.
- Scope: `MusicSlider`, `MiniPlayerSeekBar`
- Source evidence: `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/PlayerPage.kt`, `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/MiniPlayer.kt`
- Fix plan: unify both controls around absolute-position seek math, local optimistic state, clamp rules, and release sequencing.
- Automated acceptance: both player and MiniPlayer instrumentation tests assert absolute seek targets and retained optimistic state.
- Real-device acceptance: compare both controls on the same track and confirm the two seek bars feel consistent.

### SEEK-006
- Priority: P1
- Status: done
- Symptom: tap seek can look slow because the thumb waits for external position feedback.
- Root cause: tap path did not apply any local optimistic state.
- Scope: `MusicSlider`, `MiniPlayerSeekBar`
- Source evidence: `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/PlayerPage.kt`, `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/MiniPlayer.kt`
- Fix plan: store optimistic seek target immediately for tap and keep it until feedback settles.
- Automated acceptance: delayed-feedback tests show player and MiniPlayer stay at the new target before external state updates.
- Real-device acceptance: tap far from the current playhead and verify the UI responds immediately.

### SEEK-007
- Priority: P1
- Status: done
- Symptom: fill and thumb can go out of bounds when runtime and metadata durations drift.
- Root cause: duration and buffer ratios were not uniformly clamped to `[0, 1]`.
- Scope: `MusicSlider`, `MiniPlayerSeekBar`, seek math helpers
- Source evidence: player and MiniPlayer seek rendering logic
- Fix plan: centralize ratio/position conversions and clamp all ratios/positions.
- Automated acceptance: unit tests cover out-of-range offset, ratio, and slider value clamping.
- Real-device acceptance: near-end playback and unusual duration metadata do not push fill or thumb outside the track.

### SEEK-008
- Priority: P1
- Status: done
- Symptom: the player page seek area is hard to hit and drag reliably.
- Root cause: the hit target height was only 16dp.
- Scope: `MusicSlider`
- Source evidence: `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/PlayerPage.kt`
- Fix plan: expand the interactive container height while keeping the visible track slim.
- Automated acceptance: compile + instrumentation tests pass with the larger hit target.
- Real-device acceptance: repeated tap/drag attempts on the player page feel easier and more stable than before.

### SEEK-009
- Priority: P1
- Status: done
- Symptom: seek regressions could easily return because there was almost no behavior-level test coverage.
- Root cause: the old instrumentation test only verified that `MusicSlider` rendered.
- Scope: tests
- Source evidence: `android/app/src/androidTest/java/com/kutedev/easemusicplayer/widgets/musics/MusicSliderTest.kt`
- Fix plan: replace render-only checks with behavior tests for absolute seek, optimistic state retention, and helper math.
- Automated acceptance: new unit tests + new instrumentation tests run green.
- Real-device acceptance: not applicable as a separate manual step; this issue closes when the new automated coverage is in place and device checks for the behavior issues pass.
