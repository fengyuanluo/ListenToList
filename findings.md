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

## Playback Chain P1-1 Findings
- `StorageRepository` is the Android aggregation point for storage configuration and auth mutations: `updateRefreshToken()`, `upsertStorage()`, and `remove()`.
- `PlaybackSourceResolverCache` already had `invalidateAll()`, but before P1-1 only download/delete and playback error branches used resolver invalidation. Storage account edits could therefore leave old direct URLs or auth headers alive until TTL expiry.
- The safe invalidation boundary is after successful backend calls. Failed `ctGetRefreshToken`, `ctUpsertStorage`, or `ctRemoveStorage` calls should not clear cache and mask the real persistence/auth failure.

## Playback Chain P1-2/P1-3 Findings
- P1-2 and P1-3 share the same root cause: prefetch prechecks used the unresolved `ease://data?music=<id>` key while resolved playback routes used route-specific keys such as backend direct key, absolute file path, content URI, or fallback URI.
- The stable Android-side cache identity for a track is now `music:<id>`. Backend direct `cacheKey` is no longer used as the ExoPlayer cache key; it can still remain part of resolver metadata without splitting playback cache space.
- `MediaItem.customCacheKey`, `MusicPlaybackDataSource` delegate specs, next prefetch, and folder prefetch now all use the same `music:<id>` key, so prefetch precheck and actual writes observe the same cache metadata.

## Playback Chain P1-4 Findings
- Current queue planning treats `SINGLE_LOOP` as a one-item Media3 timeline with `REPEAT_MODE_ONE`; `PlaybackService` and `PlaybackRuntimeKernel` do not expose adjacent seek/wrap semantics for that mode.
- Therefore UI-facing `previousMusic` / `nextMusic` must match `SINGLE`, not `LIST_LOOP`. The only automatic next candidate in `SINGLE_LOOP` is `onCompleteMusic`, which should remain the current track for repeat completion.

## Playback Chain P1-5 Findings
- `syncQueueForPlayMode()` used to rebuild the player queue even when only the repeat mode changed or when the current item could be preserved in place. That needlessly stopped playback, cleared media items, and reopened sources in weak-network scenarios.
- The safe optimization boundary is to keep the current item and only mutate the surrounding Media3 timeline when the desired plan still contains the current `mediaId` exactly once. If that cannot be guaranteed, the existing rebuild path remains the fallback.

## Playback Chain P2-1 Findings
- Completed offline playback sources were previously chosen on readability alone. A file or content URI that exists but is truncated could still win over the online fallback.
- The safe completion gate is to compare the resolved file/content length against the recorded `totalBytes` or, when that is missing, the recorded `bytesDownloaded`. If the length mismatches, the record should be marked failed and playback should fall back online.

## Playback Chain P2-2 Findings
- `DownloadWorker` resumes from the current temp output length by calling `ctGetAssetStream(..., byteOffset = existingBytes)` and appending subsequent chunks.
- Rust `AssetStream.size()` reflects remaining bytes after the requested offset, because `StreamFile.size()` returns `total - byte_offset` and the Rust providers pass through range-aware `byte_offset`.
- The Android-side lowest reliable source-identity gate is therefore `existingBytes + stream.size() == recorded totalBytes`. If that check fails, the temp output must be discarded and the worker must reopen the stream at offset 0 rather than append mixed content.
- This P2-2 fix intentionally does not claim etag/last-modified/provider-revision validation, because those identities are not currently exposed through the existing Rust/UniFFI download stream API.
