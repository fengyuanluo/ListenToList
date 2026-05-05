# Task Plan

## Objective
Continue the ListenToList quality sweep requested by the user:

1. Archive the previous `docs/BUGs` BUG research and fix ledger.
2. Run a new, deeper review with real-device checks allowed, organized by functional domain and covering performance, appearance, code conflicts, dead code, robustness, UI/UX quality, and validation gaps.
3. Build a task center from the review, then fix, test, accept, and commit issues one by one.

## Success Criteria
- Previous BUG batch is moved out of the active `docs/BUGs` root into a clearly named archive directory, with an active README explaining the archive boundary.
- A new review batch exists under `docs/BUGs/` with domain-scoped evidence and actionable issue IDs.
- A task-center ledger maps every actionable new issue to status, fix evidence, validation commands, and commit references when available.
- At least one new issue from the new review is implemented, tested, and committed before calling the larger goal complete.
- Final completion audit maps every explicit objective requirement to real file, command, device, test, and git evidence.

## Current Phase
Phase 8: continue the next domain pass, starting with real-device playback smoke baseline.

## Phases
1. Archive previous BUG batch and update active workspace docs.
2. Establish new review batch structure and scope matrix.
3. Audit the next functional domain from source, tests, scripts, and device-access readiness.
4. Write new review findings and seed task-center rows.
5. Pick the highest-confidence actionable issue, implement a focused fix, and add/adjust tests.
6. Run targeted validation, then broad validation appropriate to the touched layer.
7. Commit the completed repair batch with Conventional Commit message.
8. Repeat domains until the full goal is complete, then perform the mandatory completion audit.

## Constraints
- Follow root `AGENTS.md`; this is Android + Rust + UniFFI/JNI, not a pure Android app.
- Do not hand-edit generated UniFFI Kotlin or generated JNI `.so` artifacts.
- Real-device checks are allowed, but device smoke results must be explicitly run and recorded.
- Treat `report.md`, old planning files, and archived BUG docs as historical context, not current product truth.
- If docs/scripts/CI/source disagree, use current source and executable scripts as truth, then update docs or ledgers in the same round.

## Status
- Phase 1 complete: previous BUG batch archived and active README created.
- Phase 2 complete: new review batch and task center created.
- Phase 3 in progress: app shell/navigation/permissions/bootstrap review started.
- Phase 4 in progress: AS1 documented in the new review and task center.
- Phase 5 complete: AS1 code fix and targeted test passed.
- Phase 6 complete: broad Android validation passed.
- Phase 7 complete: committed `ec4eb4b fix: handle cold oauth redirects`.
- Phase 8 complete: fixed SG1 smoke runner hang and passed real-device playback/download smoke on `172.20.65.10:45749`.
- Phase 9 complete: theme/UI layout review documented UI1, validated it, and committed `7def8d0 fix: align bottom chrome spacing`.
- Phase 10 in progress: import/metadata/lyrics/LrcApi domain review documented ML1 and code fix is pending validation.

## Completion Audit
- Not yet complete. The previous batch is archived, but the new deep review, task center, fixes, tests, commits, and final audit are still pending.

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| `MainActivityIntentTest` failed with Android runtime stubs for `Intent`/`Uri` in plain JVM test | 1 | Switched the test to Robolectric, matching existing Android URI tests in the repo. |
| `bun run smoke:android --device=172.20.65.10:45749` hung after "启动 mock playback server" and before the first scenario print | 1 | Terminated stale smoke/mock processes; device stayed online, but new artifacts were not produced. Will retry with explicit preflight and port. |
| Retried smoke with `--port=18091`; it hung at the same point | 2 | Verified manual `adb reverse`, mkdir, push, start, and get-state all work; documented SG1 as a smoke runner timeout/progress-evidence issue. |
| `bunx tsc --noEmit` failed on existing Bun globals/top-level await/module target issues | 1 | Treat as non-applicable script gate for this repo; validate smoke script by executing it with Bun. |
