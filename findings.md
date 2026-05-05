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

## Playback Chain P2-3 Findings
- Direct HTTP playback should not rely on the default Media3 timeout behavior alone, because the Rust fallback providers already use explicit response/chunk timeout constants and limited retries.
- The current Android-side direct HTTP path can safely add its own connect/read timeouts without changing the remote route contract.
- Open-time retries are the right place to handle transient 408/429/5xx and network causes for direct HTTP, while 401/403/404 still belong to cache invalidation / resolver refresh semantics.
- Read-stage network errors should still be left to the existing playback recovery path rather than retried inside `MusicPlaybackDataSource.open()`.

## Playback Chain P2-4 Findings
- Media3 `CacheDataSource.Factory` supports an `EventListener` with `onCacheIgnored(reason)`, which is exactly the missing signal for `FLAG_IGNORE_CACHE_ON_ERROR`.
- The safe minimum fix is observability first: record cache bypass count and last reason in `PlaybackDiagnostics` and carry those fields through debug smoke route history.
- Automatic cache deletion is intentionally not part of this fix because active `SimpleCache` spans may be in use by playback, metadata, or prefetch; clearing requires a separate lifecycle-safe maintenance path.

## Playback Chain P2-5 Findings
- `PlaylistRepository.waitForPlaybackToSettle()` used to log loading timeout and then continue into metadata probing anyway, so a main playback buffering stall could still be followed by extra metadata network work.
- The safe minimum fix is to make loading-settle timeout a skip boundary for that metadata task, while recording the reason in diagnostics.
- Metadata player `onPlayerError` and timeout are non-fatal for playback but still need route-level observability, so they now increment metadata failure counters in `PlaybackDiagnostics` and are exposed through debug smoke route history.
- This fix does not introduce a global network-state policy or a shared prefetch/metadata concurrency budget; those remain larger product/architecture enhancements.

## Playback Chain P3-1 Findings
- Folder prefetch in `StorageBrowserVM.playFromFolder()` was unconditional once the folder song list was assembled, so even `SINGLE` and `SINGLE_LOOP` modes would still launch extra prefetch traffic for tracks that the current queue model does not need.
- The lowest-risk correction is a pure play-mode gate: `LIST` and `LIST_LOOP` may prefetch the folder tail, while `SINGLE` and `SINGLE_LOOP` should skip it entirely.
- This does not solve network-aware throttling. Weak-network, buffering, or user-intent gating still need a separate policy layer if the product wants to avoid folder prefetch during contention.

## Playback Chain P3-2 Findings
- The first actionable P3-2 gap overlaps P3-1: even after play-mode gating, folder prefetch should not start while main playback is already loading/buffering, because `PlayerRepository.loading` is the app-level signal set from Media3 `STATE_BUFFERING`.
- A startup-only loading gate is safe and low-risk: if playback is loading at folder-play time, skip new folder prefetch; list-mode playback itself still proceeds through `PlayerControllerRepository.playFolder()`.
- This still does not cancel an already-running folder prefetch if playback enters buffering later. That requires collecting the loading flow and coordinating cancellation inside or near `FolderPrefetcher`, which is broader than the current startup gate.
- The next actionable P3-2 gap is network type: `ACCESS_NETWORK_STATE` plus `ConnectivityManager.isActiveNetworkMetered` is enough for a conservative startup gate. Unknown connectivity is treated as metered by the context helper so folder prefetch fails closed instead of consuming mobile data.

## Playback Chain Smoke Stability Findings
- The new play-mode-preservation smoke is sensitive to fixture length and on-device leftovers. A short 1.2-second WAV can advance through multiple tracks before assertions complete, which makes `next-prefetch` and position checks look flaky even when the playback code is correct.
- The safest smoke fixture shape is to recreate `/sdcard/Music/ListenToListSmoke` on every run, push only the current test WAVs, and use longer mock WAVs so the queue stays on the intended track long enough to observe route history and metadata backfill.
- For the route-preservation assertion, the target item must be a known non-tail entry after the backend's actual listing/order-key normalization. In the current mock datasets, `test-openlist-next.wav` and `test-webdav-next.wav` are the stable first entries in the Android-created playlist, so they keep next-metadata and next-prefetch observations deterministic.
- The successful real-device smoke at `artifacts/smoke/2026-05-05T11-30-00.417Z/playmode-switch-preserve.json` proves play-mode cycling preserved `currentMusicId`, `currentQueueEntryId`, position progression, and `DIRECT_HTTP` playback diagnostics.
- A truncated WAV is not a valid read-failure smoke fixture: Media3 can treat the short body as a short playable item and naturally advance without emitting a recoverable player error.
- A stalled direct HTTP response is a better weak-read fixture because it returns headers and keeps the body open until `DefaultHttpDataSource` hits its read timeout.
- Real-device stalled-read smoke exposed a real second-order gap: Media3's default progressive load error policy can retry a recoverable direct HTTP load repeatedly before `onPlayerError()` fires, so weak-network recovery is delayed even when `isRecoverablePlaybackError()` is correct.
- Main playback now uses a custom `PlaybackLoadErrorPolicy` to fail fast on recoverable IO/HTTP load errors and hand control to `onPlayerError()`; real-device smoke at `artifacts/smoke/2026-05-05T11-53-33.530Z/openlist-read-failure-recovery.result.json` proves `routeRefreshCount=1`, `recoverySkipCount=1`, and recovery to the next `DIRECT_HTTP` track after a stalled OpenList read.

## Playback Chain Residual Review Findings
- Static follow-up review found that the P2-2 download resume fix was only size-identity reliable when a persisted `totalBytes` / `sizeHint` existed. The follow-up repair now makes unknown-size partial downloads restart from zero instead of appending unverifiable `.part` prefixes.
- Static follow-up review found that weak-network recovery was skip-oriented. The follow-up repair now allows one descriptor-refresh same-track retry when the runtime queue has no adjacent recovery candidate and the current queue entry is still valid, covering single-entry and single-mode playback without introducing infinite retry.
