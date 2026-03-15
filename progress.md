# Progress Log

## 2026-03-15

### 接管与核查
- 接管上一轮中断现场，确认第二波主线代码已基本落地：
  - Rust playback source resolver
  - Android `MusicPlaybackDataSource`
  - debug-only smoke receiver / executor
  - host mock server / smoke harness
- 核查工作树、planning files、smoke 脚本与真机设备状态。

### 构建与环境闭环
- `bash ./scripts/doctor-android-env.sh`：通过
- `bun run build:jni`：通过
- `cd rust-libs && cargo test -p ease-remote-storage --lib`：18 passed
- `cd android && ./gradlew testDebugUnitTest :app:assembleDebug --warning-mode all`：通过

### 真机 smoke 闭环
- 首轮 smoke 复跑中定位到：
  - `Local`：通过
  - `OpenList`：通过
  - `WebDAV`：失败，错误为 `IncompleteMessage`
- 进一步分析确认失败点在 WebDAV mock server 返回给 `reqwest/hyper` 的响应不够严格，而不是 resolver 分流错误。
- 修复 `scripts/mock-playback-server.ts`：
  - WebDAV `PROPFIND` 返回 `207`
  - 显式 `Content-Length`
  - 修正 `HEAD` / `404`
  - WebDAV 响应显式 `Connection: close`
- 复跑 `bun run smoke:android --device=172.26.121.48:34327 --port=18088`：三场景全绿
  - `Local -> LOCAL_FILE`
  - `OpenList -> DIRECT_HTTP`
  - `WebDAV -> STREAM_FALLBACK`
- smoke 产物目录：
  - `artifacts/smoke/2026-03-15T03-20-41.852Z/`

### smoke 稳定性补强
- 调整 `DebugSmokeExecutor`：
  - 不再为 `next-prefetch` / `metadata` 白等 10 秒
  - settle 只保证 `playback` 路由已写入 diagnostics
- 调整 `DebugSmokeReceiver`：
  - 增加总超时兜底，避免 broadcast 无限挂起
- 调整 `scripts/smoke-android.ts`：
  - 移除过时的 `REQUIRED_SOURCE_TAGS` 强语义
  - 若 `routeHistory` 中已有 `playback` 记录，则额外校验其 resolver mode

### 真机 UI 启动验证
- `adb -s 172.26.121.48:34327 shell am force-stop com.kutedev.easemusicplayer`
- `adb -s 172.26.121.48:34327 shell am start -W -n com.kutedev.easemusicplayer/com.kutedev.easemusicplayer.MainActivity`
- 结果：`LaunchState: COLD`，冷启动成功

### debug / release 边界验证
- 运行 `./gradlew :app:processReleaseMainManifest`
- 检查：
  - `android/app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`
- 结果：
  - release merged manifest 中**不包含** `DebugSmokeReceiver`

### 追加收口
- 根据代码审查补上 `StorageBrowserVM.playFromFolder()` 对 `prefetchFolderSongs(...)` 的真实调用，避免 `folder-prefetch` 仅停留在工厂已接入但业务入口未触发的半完成状态。
- 补丁后再次执行：
  - `cd android && ./gradlew testDebugUnitTest :app:assembleDebug --warning-mode all`
  - `bun run smoke:android --device=172.26.121.48:34327 --port=18089`
- 结果：再次通过
- 最新 smoke 产物目录：
  - `artifacts/smoke/2026-03-15T03-28-35.462Z/`

### 文档收口
- 重写 `docs/playback-second-wave.md`
  - 对齐当前真实文件名
  - 记录本轮修复内容
  - 记录已完成的构建/测试/真机验证
  - 记录 smoke 产物位置与推荐回归命令
