# Findings

## Audit Summary
- The repo is a mixed Android + Rust + UniFFI/JNI workspace, so the audit had to follow cross-layer code paths rather than stay in the Compose UI.
- `docs/BUGs/` now exists and contains a functional-domain framework plus five domain documents.
- The current document set separates confirmed issues, risks, and validation gaps instead of collapsing everything into one generic notes file.

## Functional Domains Identified
- App shell, navigation, permissions, and bootstrap.
- Playback, MediaSession, notification ownership, queue, and session restore.
- Playlist and queue editing.
- Storage configuration, directory browser, and remote/local listing.
- Storage search and search-driven actions.
- Downloads and offline playback resolution.
- Import, metadata, lyrics, cover, and LrcApi supplementation.
- Theme, visual tokens, settings, logs, and debug pages.
- Rust backend, schema, FFI, and generated artifacts.
- Build, CI, debug smoke, and validation harness.

## Confirmed Code-Path Findings
- Recent-task removal in `PlaybackService` can leave persisted playback session state behind because `onTaskRemoved()` only stops the service while `onDestroy()` persists the current session instead of clearing it.
- `MainActivity` creates a new asynchronous MediaController on each `onStart()` without retaining or cancelling the previous future, which is a lifecycle drift risk.
- Playback source resolution prefers completed downloads first, but if the local file/URI disappears the code can silently fall back to the remote resolver.
- Search deduplication helpers are keyed only by path, which is acceptable for current single-storage call sites but fragile if reused for multi-storage aggregation.
- Global search retry/load-more paths use shared versioning that can accept stale results if requests race.
- Download manager semantics do not clearly distinguish app-private default storage from user-visible downloads.
- Rust `StreamFile` header parsing still uses `unwrap()` and can panic on malformed provider responses.
- The Rust backend exports `deinit()`, but Android `Bridge.destroy()` only disposes the UniFFI wrapper unless `deinit()` is called separately.

## Validation Gap Summary
- No real-device validation was performed, by request.
- Existing unit and instrumentation tests cover many helper and UI-state paths, but they do not prove recent-task removal semantics, the search request races, SAF directory revocation, missing completed downloads, or malformed HTTP header handling.

## Deliverables Produced
- `docs/BUGs/00-functional-domain-framework.md`
- `docs/BUGs/01-playback-session-and-notification.md`
- `docs/BUGs/02-storage-search-and-browser.md`
- `docs/BUGs/03-downloads-and-offline-playback.md`
- `docs/BUGs/04-rust-ffi-backend-stability.md`
- `docs/BUGs/05-test-and-validation-gaps.md`

## Remaining Risk
- Several findings are source-derived and should be reproduced before they are treated as user-confirmed defects.
- The audit intentionally stops short of device-level acceptance and provider-live checks.
