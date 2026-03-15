# Findings

## 2026-03-15 修复落地与复核

### 已落地修复

1. **OneDrive refresh token rotation 已修复**
   - `rust-libs/ease-remote-storage/src/impls/onedrive.rs`
   - `OneDriveBackend` 不再只依赖构造时注入的旧 refresh token；新增“当前 refresh token”读取与 `store_auth()` 回写逻辑，刷新成功后会同步更新 refresh token 与 access token。
   - 为避免并发请求同时拿旧 refresh token 再去刷新，额外增加了 `refresh_lock` 串行化保护。
   - 新增测试：`test_current_refresh_token_prefers_rotated_auth_token`

2. **Rust fallback 下载链路已补“响应首包超时 + chunk stall timeout”保护**
   - `rust-libs/ease-remote-storage/src/backend.rs`
   - `rust-libs/ease-remote-storage/src/impls/openlist.rs`
   - `rust-libs/ease-remote-storage/src/impls/onedrive.rs`
   - `rust-libs/ease-remote-storage/src/impls/webdav.rs`
   - 方案不是简单给整条请求套一个会误伤长音频的全局超时，而是分两层：
     - `send()` 阶段：限制响应首包等待时间
     - `StreamFile.into_rx()` 阶段：对每次 `response.chunk()` 加 stall timeout
   - OpenList 的新 `Timeout` 错误已重新纳入 `should_retry_error()`，不会出现“加了超时却不重试”的半截修复。
   - 新增测试：`backend::test::stream_file_chunk_timeout_surfaces_timeout_error`

3. **WebDAV auth challenge 不再 `unwrap()` panic**
   - `rust-libs/ease-remote-storage/src/impls/webdav.rs`
   - `build_authorization_header_value()` 现在会把不兼容 challenge 转成 `StorageBackendError::UnsupportedAuthChallenge`，不会再直接 panic。
   - 新增测试：`test_build_authorization_header_value_returns_error_for_invalid_challenge`

4. **WebDAV 已补首批 direct playback 能力**
   - `rust-libs/ease-remote-storage/src/impls/webdav.rs`
   - 现在支持：
     - 匿名 WebDAV 直接返回 `DirectHttp`
     - 已知 `Basic` challenge 的场景，返回携带 `Authorization: Basic ...` 的 `DirectHttp`
   - 仍保守 fallback：Digest / 未知 challenge / 尚未学习到 challenge 的场景
   - 新增测试：
     - `test_resolve_playback_source_returns_direct_http_for_anonymous_webdav`
     - `test_resolve_playback_source_returns_basic_auth_header_when_challenge_is_basic`
   - 真机 smoke 复核：
     - 匿名 WebDAV 场景已通过 `DIRECT_HTTP` 路由与 `next-prefetch` / metadata 回填的强断言
     - 最新产物：`artifacts/smoke/2026-03-15T11-48-59.791Z/webdav.result.json`

5. **OpenList 同源判断已改成真正的 origin 比较**
   - `rust-libs/ease-remote-storage/src/impls/openlist.rs`
   - 不再用 `domain()` 粗比，而是按 `scheme + host + port_or_known_default` 判断。
   - 新增测试：`test_resolve_playback_source_does_not_forward_auth_to_cross_origin_raw_url`

6. **默认测试链已去掉对真实第三方 OpenList 站点的强依赖**
   - `rust-libs/ease-remote-storage/tests/openlist_integration.rs`
   - live integration 现在默认 `#[ignore]`，不会再被 `cargo test` / `cargo nextest run` 默认纳入。
   - 仍可手动执行：`cargo test -p ease-remote-storage --test openlist_integration -- --ignored`

7. **Android 侧已加播放 descriptor TTL cache，减少主播放 / metadata / prefetch 重复 resolve**
   - `android/app/src/main/java/com/kutedev/easemusicplayer/core/MusicPlaybackDataSource.kt`
   - 新增全局 `PlaybackSourceResolverCache`：
     - Direct HTTP：60s TTL
     - StreamFallback / miss：15s TTL
     - Local file：5min TTL
   - 对 `401/403` 会主动失效缓存并重新 resolve，避免继续复用过期直连 descriptor。
   - 对直连 `404` 也会主动失效缓存并再 resolve 一次，避免签名 URL 过期后被旧 descriptor 卡住整个 TTL 窗口。
   - 新增测试：
     - `resolverCache_reusesDescriptorAcrossDataSourceInstances`
     - `directHttp404_invalidatesCacheAndRetriesResolve`

8. **metadata 已从“仅减写盘”升级到“对 Local / DirectHttp 优先轻量 probe”**
   - `android/app/src/main/java/com/kutedev/easemusicplayer/singleton/PlaylistRepository.kt`
   - `android/app/src/main/java/com/kutedev/easemusicplayer/core/MusicPlayerUtil.kt`
   - `getMetadataPlayer()` 保持 read-only cache。
   - 新增 `probeMusicMetadataDirectly(...)`：
     - `LocalFile` / `DirectHttp` 优先走 `MediaMetadataRetriever`
     - 仅 `StreamFallback` 或 probe 不足时再 fallback 到 metadata player
   - 同时修正了“只要 duration 已有就直接返回”的早退条件，现已允许 cover 缺失场景继续补元数据。

9. **播放器已从 one-item 模式升级到按 `PlayMode` 规划 queue**
   - `android/app/src/main/java/com/kutedev/easemusicplayer/core/MusicPlayerUtil.kt`
   - `android/app/src/main/java/com/kutedev/easemusicplayer/core/MusicPlayer.kt`
   - `android/app/src/main/java/com/kutedev/easemusicplayer/singleton/PlayerControllerRepository.kt`
   - 新增 `buildPlaybackQueuePlan(...)` / `playQueueUtil(...)`：
     - `SINGLE` / `SINGLE_LOOP`：仅当前曲目入队
     - `LIST` / `LIST_LOOP`：整条 playlist 入队，并保留起播索引
   - `playNext()` / `playPrevious()` 优先走 `seekToNextMediaItem()` / `seekToPreviousMediaItem()`，不再一律重建 one-item 管线。
   - `PlaybackService` 在 media item transition 时会同步 `PlayerRepository.current` 与 recovery seed。
   - 新增测试：`MusicPlayerUtilTest`

10. **StorageBrowser 首开重复 reload 已收口**
    - `android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/StorageBrowserVM.kt`
    - 由“两个 flow 首次立即发射 + 手动 reload”改成：
      - 先 `reload()` 一次
      - 再 `merge(storages.drop(1), permission.drop(1))` 监听后续变化
    - 目标是避免首屏远端目录重复 list。

11. **smoke 已从“只验 playback 主路由”扩到 next-prefetch 与 metadata 回填**
    - `android/app/src/debug/java/com/kutedev/easemusicplayer/debug/DebugSmokeModels.kt`
    - `android/app/src/debug/java/com/kutedev/easemusicplayer/debug/DebugSmokeExecutor.kt`
    - `scripts/smoke-android.ts`
    - 新增断言：
      - `requiredSourceTags`
      - 当前曲目 metadata duration 已回填
      - 下一首曲目 metadata duration 已回填
    - 最新真机 smoke 产物：
      - `artifacts/smoke/2026-03-15T11-48-59.791Z/local.result.json`
      - `artifacts/smoke/2026-03-15T11-48-59.791Z/openlist.result.json`
      - `artifacts/smoke/2026-03-15T11-48-59.791Z/webdav.result.json`

### 已验证但仍属“部分完成”的项

1. **WebDAV 直连只覆盖了第一批高收益场景**
   - 匿名 / Basic 已支持
   - Digest / 复杂 challenge 仍保守 fallback
   - 这属于有意识的兼容性保守，而不是遗漏

2. **metadata 轻量化目前覆盖 Local / DirectHttp，未覆盖 StreamFallback**
   - 轻量 probe 已能显著减轻 Local / OpenList / OneDrive / 匿名 WebDAV 的 metadata 成本
   - `StreamFallback` 场景仍会回落到 metadata player

### 仍待后续的 backlog

1. **专项 smoke / E2E 盲区仍存在**
   - OneDrive token refresh replay
   - folder-prefetch 强断言
   - fallback 弱网 / 卡流超时行为

2. **WebDAV 更完整直连策略**
   - Digest / 更复杂 challenge 仍未接入 direct playback

### 本轮新增验证证据

- Rust 新增或扩充测试覆盖：
  - stream chunk timeout
  - OneDrive token rotation 读取逻辑
  - WebDAV invalid challenge / anonymous direct / basic direct
  - OpenList cross-origin raw_url header 边界
- Android 新增单测：
  - descriptor cache 跨 data source 复用
  - direct HTTP 404 失效重查
  - queue plan / repeat mode 规划
- 真机 smoke（最新一轮）：
  - Local -> `LOCAL_FILE`
  - OpenList -> `DIRECT_HTTP`
  - WebDAV -> `DIRECT_HTTP`（当前匿名 mock 场景）
  - `next-prefetch` source tag 已强断言通过
  - 当前曲目与下一首曲目的 metadata duration 回填已强断言通过
