# Progress Log

## 2026-05-05
- Continued from active thread goal.
- Checked git status: clean `master`, synchronized with `origin/master`.
- Confirmed only root `AGENTS.md` governs this workspace.
- Read existing `docs/BUGs/issue_status.md`, `docs/BUGs/completion_audit.md`, `task_plan.md`, `findings.md`, and `progress.md`.
- Confirmed previous BUG batch was already committed and closed by its own audit.
- Archived previous BUG batch into `docs/BUGs/archive/2026-05-05-fix-ledger/`.
- Created active `docs/BUGs/README.md`.
- Replaced stale non-device-only plan files with the current objective's task center.
- Created `docs/BUGs/2026-05-05-deep-review/` with `task_center.md` and the first app-shell review document.
- Reviewed `MainActivity`, `AndroidManifest.xml`, `Root`, `PermissionRepository`, `StorageRepository`, `EditStorageVM`, and OneDrive OAuth URL generation.
- Documented AS1: cold-created OAuth callback Activity dropped OneDrive auth code.
- Patched AS1 by sharing validated OAuth intent handling between `onCreate()` and `onNewIntent()`.
- Added `MainActivityIntentTest` for OAuth redirect code extraction.
- First targeted test run failed because plain JVM tests cannot execute Android `Intent`/`Uri` methods; changed `MainActivityIntentTest` to Robolectric.
- Targeted AS1 test passed: `./gradlew testDebugUnitTest --tests 'com.kutedev.easemusicplayer.MainActivityIntentTest' --warning-mode all`.
- Whitespace check passed: `git diff --check`.
- Broad Android gate passed: `./gradlew testDebugUnitTest :app:assembleDebug --warning-mode all`.
