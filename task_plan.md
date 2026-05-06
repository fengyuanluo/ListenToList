# Task Plan: ListenToList SaltUI Migration

## Goal
将 ListenToList 的 Android UI 按 `docs/BUGs/SaltUI` 的顺序完整迁移到 SaltUI，先完成版本兼容、主题桥接、基础组件和页面壳，再逐页替换核心页面，最后完成视觉回归、device smoke、release 验收与逐步提交。

## Current Phase
Phase 4

## Phases

### Phase 1: Requirements & Discovery
- [x] Read all `docs/BUGs/SaltUI` task files
- [x] Inspect current Android UI entry points and theme layer
- [x] Inspect current SaltUI upstream compatibility notes
- [x] Build prompt-to-artifact checklist
- **Status:** complete

### Phase 2: Compatibility & Theme Bridge
- [x] Align Gradle / Kotlin / Compose versions required by SaltUI
- [x] Add SaltUI dependency and publication constraints
- [x] Replace root theme entry with SaltUI theme bridge
- [x] Preserve project-specific backdrop / token behavior where needed
- **Status:** complete

### Phase 3: Foundation Components
- [ ] Migrate high-reuse controls to SaltUI-backed wrappers
- [ ] Retain project-specific semantics and accessibility
- [ ] Verify default / disabled / destructive / error states
- **Status:** in_progress

### Phase 4: Navigation Shell & Core Pages
- [ ] Migrate app shell, bars, scaffolds, and page containers
- [ ] Migrate home, playlists, storage, search, player, settings surfaces
- [ ] Unify empty / error / image asset policy
- **Status:** in_progress

### Phase 5: Visual Regression, Smoke, and Release
- [ ] Run and fix unit, debug, instrumentation, smoke, and release verification
- [ ] Capture evidence and update progress/findings
- [ ] Make staged commits for each verified milestone
- **Status:** in_progress

## Key Questions
1. What exact SaltUI version and Compose/Kotlin baseline are required by the current upstream release?
2. Which existing `Ease*` tokens and wrappers should remain as thin project-specific adapters instead of being deleted outright?
3. Which pages can be migrated safely without breaking the existing playback and storage smoke flows?

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Use a phased migration instead of one-shot replacement | The UI surface is too large to verify safely in a single edit pass |
| Preserve short-lived project wrappers while the theme/compatibility bridge settles | Reduces breakage while replacing the underlying visual system |
| Upgrade AndroidX Compose BOM to the SaltUI-compatible 1.11.0 line before adding SaltUI | SaltUI Android metadata resolves to JetBrains Compose 1.11.0-beta02, which maps to AndroidX Compose 1.11.0 |
| Keep KSP on the latest 2.3.7 line | Maven metadata shows 2.3.7 is the newest KSP line available for the Kotlin 2.3 series |
| Keep existing `Ease*` APIs as compatibility wrappers while replacing their visual implementation with SaltUI | Lets the app adopt SaltUI without forcing simultaneous call-site rewrites across every page |
| Use sequential `--no-daemon` verification for heavy Kotlin/Android tasks | Avoids the incremental cache conflicts seen when debug verification tasks were run in parallel |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| `task_plan.md` / `findings.md` / `progress.md` were missing in the working tree | 1 | Recreated planning files from the project template and current SaltUI objective |

## Notes
- Update phase status as work progresses.
- Keep findings and progress synchronized with concrete file / command evidence.
- Use real test results as the completion gate, not intent.
