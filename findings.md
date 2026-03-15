# Findings

## 2026-03-15

### 1. 第二波播放链路的真实主类是 `MusicPlaybackDataSource`
- 当前 Android 侧不是新增一个叫 `ResolvingPlaybackDataSource` 的文件，而是通过：
  - `android/app/src/main/java/com/kutedev/easemusicplayer/core/MusicPlaybackDataSource.kt`
  完成统一 resolver wrapper。
- 它在 `open()` 阶段执行：
  - `ease://data?music=<id>` -> `MusicId`
  - `ctResolveMusicPlaybackSource(...)`
  - `DirectHttp / LocalFile / StreamFallback` 三分流

### 2. 第二波已真正接入四类链路
- 主播放：`MusicPlayer.kt / PlaybackService`
- next prefetch：`PlaybackService` 内独立 `sourceTag`
- metadata：`PlaylistRepository.getMetadataPlayer()`
- folder prefetch：`StorageBrowserVM`

### 3. `folder-prefetch` 曾经处于“工厂接好、业务入口没触发”的半完成状态
- `StorageBrowserVM` 里原本已经有：
  - `folderPrefetcher`
  - `prefetchFolderSongs(...)`
- 但 `playFromFolder(...)` 没有实际调用 `prefetchFolderSongs(...)`
- 本轮已补上该调用，确保 folder-prefetch 不再是死代码入口

### 4. 真机 smoke 首轮失败的根因不在 resolver，而在 WebDAV mock server 的 HTTP 兼容性
- 首轮 smoke 现象：
  - `Local` 通过
  - `OpenList` 通过
  - `WebDAV` 失败
- 真机 logcat 明确显示：
  - `actualResolverMode = STREAM_FALLBACK`
  - 失败异常为 `reqwest::Error ... hyper::Error(IncompleteMessage)`
- 说明 WebDAV fallback 路由本身选对了，真正失败点是 mock server 返回给 `reqwest/hyper` 的响应不够严格

### 5. WebDAV smoke 已通过协议层收口修复恢复稳定
- 本轮对 `scripts/mock-playback-server.ts` 做了以下修复：
  - `PROPFIND` 返回 `207`
  - 显式 `Content-Length`
  - 修正 `HEAD` / `404`
  - WebDAV 响应显式 `Connection: close`
- 修复后真机 smoke 已稳定通过：
  - `Local -> LOCAL_FILE`
  - `OpenList -> DIRECT_HTTP`
  - `WebDAV -> STREAM_FALLBACK`

### 6. debug smoke 原有的“可选 route 白等 10 秒”会放大挂起风险
- 旧逻辑会在 ready 后继续等待：
  - `playback`
  - `next-prefetch`
  - `metadata`
  三个 tag 全出现
- 这不会直接导致错误结果，但会让 `am broadcast` 在远端场景下白等
- 本轮已收敛为：
  - settle 只保证 `playback` 路由已写入 diagnostics
  - `DebugSmokeReceiver` 增加总超时兜底

### 7. debug-only 边界已做源码与构建产物双重确认
- receiver 仅注册在：
  - `android/app/src/debug/AndroidManifest.xml`
- 额外验证了 release merged manifest：
  - `android/app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`
- 结果：
  - release manifest 中**不包含** `DebugSmokeReceiver`

### 8. 本轮最终闭环证据
- 环境：
  - `bash ./scripts/doctor-android-env.sh`
- 生成：
  - `bun run build:jni`
- Rust：
  - `cd rust-libs && cargo test -p ease-remote-storage --lib`
- Android：
  - `cd android && ./gradlew testDebugUnitTest :app:assembleDebug --warning-mode all`
- 真机：
  - `bun run smoke:android --device=172.26.121.48:34327 --port=18089`
  - `adb -s 172.26.121.48:34327 shell am start -W -n com.kutedev.easemusicplayer/com.kutedev.easemusicplayer.MainActivity`

### 9. 最终 smoke 产物位置
- `artifacts/smoke/2026-03-15T03-28-35.462Z/`
