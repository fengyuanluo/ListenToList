# Findings

## 本轮任务范围
- 仓库：`/root/Coding/General/ListenToList`
- 审阅主题：播放列表 / 运行时播放队列 / 拖动排序 / 切歌语义 / session restore / Android-Rust-FFI 契约
- 本轮以源码为主，memory 仅用于恢复上下文与识别可能漂移点

## Memory quick pass
- `MEMORY.md` 已记录 2026-03-16 的 queue-first 语义重构：playlist 与 runtime playback queue 已被显式拆开，顺序真相源迁移到 membership-order，session restore 围绕 `queueEntryIds`。
- 本轮需重点验证这些结论在当前 HEAD 是否仍成立，以及 Android UI / ViewModel / Service 是否完全跟上。

## 待补充的审阅维度
1. Rust schema / migration / playlist membership / queue snapshot
2. Backend playlist / queue / folder play / next-prev / remove current
3. Android playlist UI / drag reorder / play-from-playlist / queue sheet
4. PlayerRepository / PlayerControllerRepository / PlaybackService / Media3 session restore
5. FFI 生成接口中与 playlist / queue 相关的桥接对象与调用入口

## 初步代码地图（Phase 1）

### Android 侧高相关文件
- UI / 页面：
  - `widgets/playlists/PlaylistsPage.kt`
  - `widgets/playlists/PlaylistPage.kt`
  - `widgets/playlists/PlaylistDialog.kt`
  - `widgets/musics/PlayerPage.kt`
  - `widgets/musics/MiniPlayer.kt`
- ViewModel：
  - `viewmodels/PlaylistsVM.kt`
  - `viewmodels/PlaylistVM.kt`
  - `viewmodels/CreatePlaylistVM.kt`
  - `viewmodels/EditPlaylistVM.kt`
  - `viewmodels/PlayerVM.kt`
  - `viewmodels/StorageBrowserVM.kt`
  - `viewmodels/StorageSearchVM.kt`
- Repository / runtime state：
  - `singleton/PlaylistRepository.kt`
  - `singleton/PlayerRepository.kt`
  - `singleton/PlayerControllerRepository.kt`
  - `singleton/PlaybackQueueModels.kt`
  - `singleton/PlaybackSessionStore.kt`
  - `singleton/Bridge.kt`
- Playback / Service：
  - `core/MusicPlayer.kt`
  - `core/MusicPlayerUtil.kt`
  - `core/PlaybackDataUri.kt`
  - `core/MusicPlaybackDataSource.kt`
  - `core/PlaybackDiagnostics.kt`

### Rust 侧高相关文件
- Schema / migration：
  - `ease-client-schema/src/v4/models/playlist.rs`
  - `ease-client-schema/src/v4/repositories/defs.rs`
  - `ease-client-schema/src/v4/upgrader.rs`
- Repository / object / controller / service：
  - `ease-client-backend/src/repositories/playlist.rs`
  - `ease-client-backend/src/repositories/music.rs`
  - `ease-client-backend/src/controllers/playlist.rs`
  - `ease-client-backend/src/controllers/music.rs`
  - `ease-client-backend/src/services/music/mod.rs`
  - `ease-client-backend/src/objects/playlist.rs`

### 初步判断
- playlist 不是纯 Android UI 概念，顺序与成员关系的真相源明显在 Rust schema/backend。
- queue 与 playlist 的交汇点主要在 Android 的 `PlaybackQueueModels` / `PlayerRepository` / `PlaybackSessionStore`，以及 Rust 的“从 playlist 或 folder 构造播放描述符”逻辑。
- “拖动排序”需要同时看 UI 手势/局部列表重排 + ViewModel 调用 + Repository 持久化 order 写回，不能只看 Compose 页面。

## Rust 侧已确认语义
- schema v4 已把 playlist 内顺序从全局 `MusicModel.order` 拆到 membership 关系 `PlaylistMusicModel { music_id, order }`；读取 playlist 曲目时会把 relation.order 写回到返回的 `MusicModel.order`，所以 playlist 页面看到的顺序是 membership order，不是全局 music order。
- `v3 -> v4` upgrader 会按旧 `MusicModel.order` 排序后重建每个 playlist 的 membership order，测试 `upgrade_v3_to_v4_migrates_playlist_order_to_membership_table` 已覆盖这一点。
- Rust reorder 接口 `cts_reorder_playlist` / `cts_reorder_music_in_playlist` 都不是“按 index 覆盖重排”，而是基于前后邻居 `a/b` 计算新的 `OrderKey::between/greater/less_or_fallback`。
- backend 已有回归测试 `reorder_music_only_changes_membership_order_inside_target_playlist`，明确锁住“同一首歌在两个 playlist 中共享 musicId 时，重排 playlist A 不能污染 playlist B”。
- folder play 不再建真实 playlist，而是先 `ct_ensure_musics` 确保 music 存在，再由 Android 侧构造 `PlaybackQueueSnapshot(context=FOLDER)`。

## Android 侧已确认语义
- runtime queue 的一等真相源是 `PlaybackQueueSnapshot`，条目身份不是 `musicId`，而是 `queueEntryId`；playlist / folder / temporary 三类上下文全部依赖它。
- `PlaybackSessionStore` 持久化的是 queue snapshot 的轻量投影：context + entries + currentQueueEntryId + position/playWhenReady/playMode；恢复时再按 `musicId` 回查 `MusicAbstract` / `Music`。
- `PlayerRepository` 专门维护 `_queue + _currentQueueEntryId` 双状态，并通过 `normalizeQueueSnapshot()` 保证二者 cursor 对齐；`PlayerRepositoryTest` 已覆盖这类对齐语义。
- playlist 页面拖动排序：`PlaylistPage` 用 reorderable list + `PlaylistVM.musicMoveTo()` 本地先重排，再调用 Rust `ctsReorderMusicInPlaylist`，随后 `scheduleReload()+reload()` 做最终收口。
- playlist 列表页拖动排序：`PlaylistsPage` 用 reorderable grid + `PlaylistRepository.playlistMoveTo()` 本地先重排，再调用 Rust `ctsReorderPlaylist`。
- player 队列页拖动排序：`PlaybackQueueSheet` 直接重排 runtime queue；若当前上下文是 `USER_PLAYLIST`，会同步落库 reorder playlist membership；若是 `FOLDER/TEMPORARY`，只改运行时 queue，不写持久 playlist。
- 从搜索页/目录页“加入播放队列”统一走 `PlayerControllerRepository.appendEntriesToQueue()`；当已有活动队列时会把整个 queue 提升/转换为 `TEMPORARY` 上下文，避免再把外部追加动作解释成对原 playlist 的持久编辑。
- 从 playlist 点播、folder play、queue sheet 点播，最终都汇入 `playResolvedQueue()`：先 `playerRepository.setPlaybackSession(...)`，再 `playQueueUtil(...)`，最后持久化 session。
- `playNext()/playPrevious()` 先尝试用 Media3 的 `seekToNext/PreviousMediaItem()` 保持系统 session 行为；若当前 playMode 导致 player 内 mediaItems 不含相邻项（如 SINGLE / SINGLE_LOOP），则 fallback 到应用层 queue 重新播目标项。
- `removeCurrent()` 语义按 context 分叉：`USER_PLAYLIST` 真删除歌单成员并基于刷新后的 playlist 选中下一项；`FOLDER/TEMPORARY` 只删除运行时 queue 项。

## 结构性风险 / 审阅结论
- 最核心的正确设计点：仓库当前确实已经不是“playlist-centric playback”，而是 queue-first；playlist 只是 queue 的一个来源。
- 当前最大维护风险不是单个函数 bug，而是 **逻辑重复**：`PlayerControllerRepository` 与 `PlaybackService(MusicPlayer.kt)` 都各自实现了一套 `playNext/playPrevious`、`seekAdjacent`、`buildPlaylistSnapshot`、`playResolvedQueue`、`persistCurrentSession`。两边目前语义接近，但天然存在未来漂移风险。
- 当前测试覆盖强项在 Rust schema/membership-order 与 Android queue plan/session store；弱项在 `PlayerControllerRepository` / `PlaybackService` 的 remove-current、append-to-queue、restore temporary queue、queue reorder 等复杂交互，主要依赖集成 smoke 而不是单测锁死。
- `refreshPlaylistIfMatch()` 目前只在 `PlaylistVM.reload()` 中触发，意味着“活动 playlist queue 与持久 playlist 的同步”更偏向页面存在时的刷新链，而不是 repository 级全局订阅；这是一个值得持续留意的架构点。
