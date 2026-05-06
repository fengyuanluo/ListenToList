# Findings & Decisions

## Requirements
- Read and understand every file under `docs/BUGs/SaltUI`.
- Migrate the Android UI to SaltUI with emphasis on visual polish.
- Keep explanations concise.
- Perform comprehensive testing, acceptance, and checks.
- Make incremental commits along the way.

## Research Findings
- `docs/BUGs/SaltUI` is organized as a 10-step migration plan: compatibility, theme/tokens, foundation components, navigation shell, core pages, player, settings, empty/error asset policy, visual smoke, and release hardening.
- The current app still uses `EaseMusicPlayerTheme`, `EaseTheme`, and Material3-based custom wrappers in the Android UI layer.
- SaltUI upstream currently documents compatibility for `2.9.0-beta02+` with `Compose Multiplatform 1.11.0-beta02`.
- Maven Central metadata confirms `io.github.moriafly:salt-ui` latest/release is `2.9.0-beta02`.
- SaltUI README explicitly calls out Google Play `dependenciesInfo` exclusion for hidden API-related publishing concerns.
- Maven Central metadata for KSP shows the Kotlin 2.3 line has reached `2.3.7`; the current Android build still pins KSP to `2.0.21-1.0.27`.
- Current repository has no SaltUI dependency wired into the Android app yet.
- Existing staged work in `scripts/smoke-android.ts`, `scripts/mock-playback-server.ts`, and debug smoke models is unrelated to SaltUI migration and must be preserved unless it conflicts with this task.
- SaltUI provides direct equivalents for much of the current app's UI substrate: `Button`, `Text`, `Item`, `Switcher`, `PopupMenu`, `BottomSheetScaffold`, `BasicScreen`, `ScreenTopBarCollapsed`, and dialog helpers such as `YesDialog`, `YesNoDialog`, `InputDialog`, and `BasicDialog`.
- The existing project wrappers most likely to convert into thin SaltUI-backed adapters are `EaseTextButton`, `ConfirmDialog`, `EaseContextMenu`, `Form*`, `EaseFlatSwitch`, and `EaseSearchField`.
- The existing image-oriented wrappers are not drop-in replacements: `EaseImage`, `MusicCover`, `ImportCover`, and `ThemeBackgroundImage` keep project-specific bitmap loading, fallback, and backdrop behavior that must be preserved during migration.
- The app's current root theme entry is still `EaseMusicPlayerTheme`, which layers a custom `MaterialTheme` plus `EaseSurfaces` over the app content.
- The current navigation shell is still custom `Scaffold` plus `HomePage` pager plus bottom bar; SaltUI has dedicated `BottomBar`/`TitleBar` and screen helpers that can be adopted incrementally.
- SaltUI theme defaults are intentionally close to the existing project's current shape: `SaltTheme.colors`, `SaltTheme.textStyles`, `SaltTheme.dimens`, and `SaltTheme.shapes` all accept custom overrides, so the app can keep its current primary color and background-image behavior while switching the theme entry point.
- The SaltUI Android artifact is `io.github.moriafly:salt-ui-android:2.9.0-beta02`, which depends on `salt-core-android:2.9.0-beta02`, Kotlin `2.3.20`, and Compose `1.11.0-beta02` runtime/foundation/ui/resources artifacts.
- The repository's Gradle wrapper is already `8.13`, which is compatible with the SaltUI build metadata and does not force a wrapper migration before theme/dep work.
- JetBrains Compose 1.11.0-beta02 runtime/foundation/ui artifacts resolve to AndroidX Compose 1.11.0-beta02 dependencies in their POMs, so the app cannot keep the current older AndroidX Compose BOM while adding SaltUI.
- Google Maven exposes `androidx.compose:compose-bom:2026.04.01`, which manages the `1.11.0` AndroidX Compose line that matches SaltUI's `1.11.0-beta02` ecosystem.
- KSP metadata confirms the latest 2.3 line is `2.3.7`; there is no newer KSP line exposed for Kotlin `2.3.20`, so the Kotlin upgrade should pair with `com.google.devtools.ksp` `2.3.7`.
- The most direct first migration slice is the thin wrapper layer, because SaltUI already provides `ItemEditImpl`, `PopupMenuItem`, `ItemOuter*`, `Button`, `Text`, `Switcher`, and dialog helpers that can replace project wrappers without changing business flow.
## Compatibility Snapshot
| Item | Current repo | SaltUI upstream / target |
|------|--------------|--------------------------|
| SaltUI artifact | none | `io.github.moriafly:salt-ui:2.9.0-beta02` |
| Kotlin | `2.0.21` | `2.3.20` on SaltUI upstream |
| KSP | `2.0.21-1.0.27` | `2.3.7` line exists for Kotlin 2.3 |
| Compose MPP / SaltUI compat | old Compose BOM | `1.11.0-beta02` compat line |
| Android artifact to use | none | `io.github.moriafly:salt-ui-android:2.9.0-beta02` |

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| Start with compatibility and theme bridging before page replacement | Later page migration depends on stable versions and a single theme entry |
| Treat current `Ease*` code as an adapter layer until SaltUI replacement is proven | Avoids breaking the app before verification is in place |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| Planning files were missing from the workspace | Recreated them so the task can be tracked across sessions |
| `task_plan.md` had been removed from the working tree while the index still carried changes | Recreated the file in the working tree so future edits can proceed normally |

## Resources
- `docs/BUGs/SaltUI/README.md`
- `docs/BUGs/SaltUI/00-compatibility-spike.md`
- `docs/BUGs/SaltUI/01-theme-and-tokens.md`
- `docs/BUGs/SaltUI/02-foundation-components.md`
- `docs/BUGs/SaltUI/03-navigation-shell-and-scaffolds.md`
- `docs/BUGs/SaltUI/04-core-pages-home-library-browse.md`
- `docs/BUGs/SaltUI/05-player-mini-player-media-controls.md`
- `docs/BUGs/SaltUI/06-settings-management-dialogs.md`
- `docs/BUGs/SaltUI/07-empty-error-image-asset-policy.md`
- `docs/BUGs/SaltUI/08-visual-regression-and-device-smoke.md`
- `docs/BUGs/SaltUI/09-release-hardening-and-rollout.md`
- `/tmp/SaltUI-inspect/README.md`
- `/tmp/SaltUI-inspect/gradle/libs.versions.toml`
- `/tmp/SaltUI-inspect/ui2/build.gradle.kts`
- `/tmp/SaltUI-inspect/ui2/src/commonMain/kotlin/com/moriafly/salt/ui/SaltTheme.kt`
- `/tmp/SaltUI-inspect/ui2/src/commonMain/kotlin/com/moriafly/salt/ui/SaltConfigs.kt`

## Visual/Browser Findings
- None yet.
