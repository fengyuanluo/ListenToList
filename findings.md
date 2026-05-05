# Findings

## Current State
- Repository is clean at start of this continuation, on `master` tracking `origin/master`.
- Latest commits already include the previous BUG audit and fixes: `docs: record bug audit and verification ledger`, plus playback, storage, downloads, and Rust hardening commits.
- Previous active BUG files have been archived under `docs/BUGs/archive/2026-05-05-fix-ledger/`.
- New active BUG workspace now starts at `docs/BUGs/README.md`.

## Previous Batch Boundary
- The archived batch was a non-device audit/fix ledger dated 2026-05-05.
- Its `completion_audit.md` states all previous issue IDs P1-P4, S1-S5, D1-D5, R1-R4, and G1-G6 are either verified or accepted as a boundary.
- Its G6 boundary explicitly did not claim real-device smoke coverage.
- New work must not reopen that archive unless current source evidence shows regression or incomplete behavior.

## Next Review Direction
- The next sweep should start from a high-value domain not fully covered by the previous source-only pass.
- Candidate domains: app shell/navigation/permissions/bootstrap, theme/UI visual consistency, playlist/import/metadata/lyrics, release/CI/signing, or real-device playback smoke.
- Because real-device checks are now allowed, playback-route smoke and UI screenshot inspection should be treated as first-class evidence when the touched domain needs it.

## App Shell Review Findings
- AS1 confirmed: OneDrive OAuth callback handling was only in `MainActivity.onNewIntent()`. A cold browser redirect into a newly created activity would not call that method, so the authorization code would be dropped before `StorageRepository.updateRefreshToken()` could update `oauthRefreshToken`.
- AS1 also had an input-validation weakness: the old handler accepted any intent data URI with a `code` parameter, rather than checking the manifest-declared `easem://oauth2redirect` callback.

## Playback Chain P0-2 Findings
- `PlaybackService.onPlayerError()` was the decisive branch for whether playback recovery is attempted or the current session is stopped and cleared.
- Existing `recoverFromPlaybackError()` already advances through the runtime queue using the seeded direction and avoids retrying attempted queue entries, so the safest P0-2 repair is to broaden recoverable IO classification rather than rewrite queue recovery.
- Recoverable weak-network coverage now includes Media3 network failure, network timeout, bad HTTP status, read-position-out-of-range, file-not-found, plus common transient causes such as socket timeout, connection failure, interrupted IO, socket reset, unknown host, and early EOF.
- `PlaybackDiagnostics` previously only recorded route snapshots; P0-2 needs route-refresh, skip-count, and last error signals so debug smoke can distinguish weak-network recovery from a generic route record.
