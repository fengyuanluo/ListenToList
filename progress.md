# Progress Log

## 2026-03-16 当前任务接管
- 用户要求：只基于 Android 官方一手资料，调研音乐播放器中 playlist / queue / media item / user playlist 的最佳实践，重点看 Media3 / ExoPlayer 官方文档。
- 已读取根级 AGENTS 与 planning-with-files skill。
- 已执行轻量 memory quick pass，用于恢复 ListenToList 的上下文背景，但不会把 memory 当成官方证据。
- 已将 planning files 重写为“仅官方资料调研”模式。

## 2026-03-16 官方资料采集完成第一轮
- 已限定并核实 10 个官方一手页面/参考入口，均来自 developer.android.com。
- 已记录与概念建模最相关的直接证据：Player 负责 playlist/queue，MediaSession 负责外部控制路由，MediaLibrarySession 负责 browse tree，RequestMetadata 负责“待解析播放请求”，resumption 由 app 持久化。

## 2026-03-16 原则提炼完成
- 已形成 9 条概念建模原则草案。
- 其中 P3 / P7 的支撑最接近“官方直接表述”；P1 / P4 / P5 / P8 / P9 明确属于跨页推论，最终答复中会显式标记。

## 2026-03-16 交付整理完成
- 已完成最终答复整理，保留“官方直接表述 / 多页资料推论”的边界标记。
- 最终答复仅引用 developer.android.com 官方页面。

## 2026-03-16 官方资料对照阶段新增结论
- 已从 Android 官方 Media3 文档确认：Player 管理 runtime playlist/queue；MediaLibraryService 管理可浏览内容树；二者不是一个概念。
- 已确认 `onSetMediaItems()` 官方就支持“单个请求解析成整条 playlist”，这与本项目当前‘目录点击歌曲 -> 先创建真实 playlist 再播放’形成了强烈对照。
- 已确认 `MediaItem.mediaId` 是 playlist item identity；当前项目虽然使用稳定 `MusicId`，但尚未建立独立 queue item / play context 身份。
- 已确认官方推荐用显式 add/move/remove/replace 操作维护 runtime queue；本项目目前仍主要从 `Playlist` 对象整体重建 queue。

## 2026-03-16 交付前收口
- 已完成本地源码与 Android 官方 Media3 / MediaLibrary 最佳实践的对照。
- 最终判断：当前仓库在 playlist / queue / folder play / membership order 这几个核心概念上仍有明显耦合，尤其是“文件夹播放落成真实歌单”和“曲目顺序挂在全局 MusicModel 上”两点风险最高。
- 本轮未修改产品源码，仅完成研究、证据归档与交付准备。

## 2026-03-16 实施修复第一轮
- 按已确认方案完成 Rust schema / backend 主线改造：
  - `ease-client-schema` 新增 v4；
  - playlist membership relation 新增 `order`；
  - `upgrade_v3_to_v4(...)` 已实现；
  - backend schema version 升到 4；
  - playlist reorder 改走 `set_playlist_music_order(...)`；
  - 新增 `ct_ensure_musics(...)` 供 folder queue 使用。
- Android 侧完成 queue-first 主线接线：
  - 新增 `PlaybackQueueModels.kt`、`PlaybackSessionStore.kt`；
  - `PlayerRepository` / `PlayerControllerRepository` / `MusicPlayerUtil` 改为 runtime queue 驱动；
  - `StorageBrowserVM.playFromFolder()` 改为临时 queue；
  - 播放页删除文案与动作改为按上下文分流。

## 2026-03-16 测试失败修复
- 修复 `rust-libs/ease-client-schema/src/v4/upgrader.rs` 新增测试：
  - 改为 `super::upgrade_v3_to_v4(...)`；
  - 缩小 table 借用作用域后再 commit；
  - 读取 v4 membership 时按 `relation.order` 排序后断言。
- 修复 `android/app/src/test/java/com/kutedev/easemusicplayer/singleton/PlaybackSessionStoreTest.kt`：
  - 去掉 `ApplicationProvider` 依赖；
  - 改用 `RuntimeEnvironment.getApplication()`；
  - prefs 清理改用 `apply()`。

## 2026-03-16 运行态边角收口
- 根据静态代码审查结果，继续修复：
  - `PlayerRepository` 中 `_queue.currentQueueEntryId` 与 `_currentQueueEntryId` 状态分裂；
  - `PlayerControllerRepository` / `PlaybackService` 在首尾边界 next/previous 时回播当前项；
  - 新播放请求失败时旧 player 未停但 repo/session 已清空；
  - 相邻切歌后立即持久化 session 时可能写入旧 queueEntryId。
- 补充 Android 单测 `PlayerRepositoryTest.kt`，覆盖 queue 当前项与 snapshot currentQueueEntryId 同步行为。
- 补充 backend 单测 `reorder_music_only_changes_membership_order_inside_target_playlist`，验证 playlist 重排不会污染别的共享歌曲所在 playlist。

## 2026-03-16 最终验证
- 已运行并通过：
  - `cd rust-libs && cargo test -p ease-client-schema`
  - `cd rust-libs && cargo test -p ease-client-backend --lib`
  - `cd android && ./gradlew testDebugUnitTest`
  - `cd android && ./gradlew :app:assembleDebug --warning-mode all`
- 已运行真机 smoke 并通过：
  - `bun run smoke:android --device=172.26.121.48:34327 --apk=android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
  - 产物目录：`artifacts/smoke/2026-03-16T01-25-08.349Z`
  - 三个场景均成功：
    - local -> `LOCAL_FILE`
    - openlist -> `DIRECT_HTTP`
    - webdav -> `DIRECT_HTTP`
  - current / next metadata duration sync 均为 true。
