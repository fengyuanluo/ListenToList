# 第二波播放链路修复说明

## 背景

本轮修复的目标，不是再改一层播放器 UI，而是把播放链路从“统一走 FFI 字节流”升级成“**可解析时优先直连，不可解析时再 fallback**”：

1. Rust 侧先解析播放源描述符；
2. Android 侧继续维持 `ease://data?music=<id>` 这一统一入口；
3. `DataSource.open()` 阶段按描述符分流到：
   - `DefaultHttpDataSource`
   - `FileDataSource`
   - 原有 `MusicPlayerDataSource` FFI stream fallback
4. 主播放、next prefetch、metadata、folder prefetch 统一复用同一套路由工厂；
5. 通过 debug-only receiver + host smoke harness 建立可重复真机验证闭环。

---

## 当前实现覆盖范围

### 1. Rust 播放源解析

#### `ease-remote-storage`

- `Local` -> `LocalFile`
- `OpenList` -> `DirectHttp`
- `OneDrive` -> `DirectHttp`
- `WebDAV` -> `StreamFallback`

关键文件：

- `rust-libs/ease-remote-storage/src/backend.rs`
- `rust-libs/ease-remote-storage/src/impls/local.rs`
- `rust-libs/ease-remote-storage/src/impls/openlist.rs`
- `rust-libs/ease-remote-storage/src/impls/onedrive.rs`
- `rust-libs/ease-remote-storage/src/impls/webdav.rs`

#### `ease-client-backend`

- 新增并导出 `ctResolveMusicPlaybackSource(...)`
- Rust 侧 descriptor 通过 UniFFI 暴露给 Kotlin

关键文件：

- `rust-libs/ease-client-backend/src/services/storage/mod.rs`
- `rust-libs/ease-client-backend/src/controllers/asset.rs`
- `rust-libs/ease-client-backend/src/objects/player.rs`

### 2. Android 解析型播放 DataSource

> 当前真实主类是 `MusicPlaybackDataSource.kt`，不是旧设计稿里的 `ResolvingPlaybackDataSource.kt`。

核心文件：

- `android/app/src/main/java/com/kutedev/easemusicplayer/core/MusicPlaybackDataSource.kt`
- `android/app/src/main/java/com/kutedev/easemusicplayer/core/MusicPlayerDataSource.kt`
- `android/app/src/main/java/com/kutedev/easemusicplayer/core/PlaybackDataSourceFactory.kt`
- `android/app/src/main/java/com/kutedev/easemusicplayer/core/PlaybackDataUri.kt`
- `android/app/src/main/java/com/kutedev/easemusicplayer/core/PlaybackDiagnostics.kt`

当前行为：

- 顶层 URI 仍然统一为 `ease://data?music=<id>`
- `MusicPlaybackDataSource.open()` 内部调用 `ctResolveMusicPlaybackSource(...)`
- 根据 descriptor 分流：
  - `DirectHttp` -> `DefaultHttpDataSource`
  - `LocalFile` -> `FileDataSource`
  - `StreamFallback` -> `MusicPlayerDataSource`
- 记录 route diagnostics：
  - `musicId`
  - `route`
  - `resolvedUri`
  - `sourceTag`

### 3. 已接入第二波路由工厂的链路

- **主播放**
  - `android/app/src/main/java/com/kutedev/easemusicplayer/core/MusicPlayer.kt`
- **next prefetch**
  - `MusicPlayer.kt` 内 `PlaybackService` 使用独立 `sourceTag`
- **metadata**
  - `android/app/src/main/java/com/kutedev/easemusicplayer/singleton/PlaylistRepository.kt`
- **folder prefetch**
  - `android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/StorageBrowserVM.kt`

### 4. debug-only 注入与真机 smoke

debug-only 文件：

- `android/app/src/debug/AndroidManifest.xml`
- `android/app/src/debug/java/com/kutedev/easemusicplayer/debug/DebugSmokeReceiver.kt`
- `android/app/src/debug/java/com/kutedev/easemusicplayer/debug/DebugSmokeExecutor.kt`
- `android/app/src/debug/java/com/kutedev/easemusicplayer/debug/DebugSmokeModels.kt`
- `android/app/src/debug/java/com/kutedev/easemusicplayer/debug/DebugMediaControllerHelper.kt`

host 侧文件：

- `scripts/mock-playback-server.ts`
- `scripts/smoke-android.ts`

当前 smoke harness 特性：

- 安装 debug APK
- 自动授权
- 启动 MainActivity
- 启动 mock playback server
- `adb reverse`
- 本地写入 Local 测试音频
- 通过 broadcast 注入 storage / playlist / play 请求
- 优先解析广播返回值；必要时 fallback 读取 logcat `DEBUG_SMOKE_RESULT`

---

## 本轮补充修复

除第二波主实现外，本轮还补了三类高价值收口：

### 1. WebDAV smoke mock 协议兼容性修复

文件：

- `scripts/mock-playback-server.ts`

问题：

- WebDAV fallback 链路在真机 smoke 中通过 `reqwest/hyper` 访问 mock server 时，曾出现 `IncompleteMessage`

本轮修复：

- `PROPFIND` 改为显式返回 `207`
- 显式补齐 `Content-Length`
- `HEAD` / `404` 路径改成更严格的 HTTP 响应
- WebDAV 响应显式加 `Connection: close`

效果：

- `STREAM_FALLBACK` 的 WebDAV 真机 smoke 已恢复稳定通过

### 2. debug smoke 不再为可选 route 白等 10 秒

文件：

- `android/app/src/debug/java/com/kutedev/easemusicplayer/debug/DebugSmokeExecutor.kt`
- `android/app/src/debug/java/com/kutedev/easemusicplayer/debug/DebugSmokeReceiver.kt`

问题：

- 旧逻辑会在播放 ready 后继续等待：
  - `playback`
  - `next-prefetch`
  - `metadata`
  三个 tag 全出现
- 这会让 `am broadcast` 在 openlist / webdav 场景下额外白等，增加假挂起风险

本轮修复：

- smoke settle 只保证 `playback` route 已写入 diagnostics
- receiver 增加总超时兜底，避免 broadcast 无限挂起

### 3. smoke 脚本断言语义收敛

文件：

- `scripts/smoke-android.ts`

本轮修复：

- 去掉过时的 `REQUIRED_SOURCE_TAGS` 强要求语义
- 结果断言聚焦在：
  - `status == ok`
  - `actualResolverMode == expectedRoute`
  - `resolvedUri` 非空
  - 若 `routeHistory` 中已有 `playback` 记录，则其 `resolverMode` 也必须匹配

---

## 验收标准

### A. 构建与生成

1. `bun run build:jni` 成功
2. UniFFI Kotlin 绑定与 JNI 库可重复生成
3. Android debug 单测通过
4. Debug APK 成功 assemble

### B. 行为验收

#### Local

- `actualResolverMode = LOCAL_FILE`
- `resolvedUri = file://...`

#### OpenList

- `actualResolverMode = DIRECT_HTTP`
- `resolvedUri = http://127.0.0.1:<port>/media/openlist/...`

#### WebDAV

- `actualResolverMode = STREAM_FALLBACK`
- `resolvedUri = ease://data?music=<id>`

### C. debug-only 边界

- receiver 仅存在于 `src/debug/`
- host smoke 必须通过 broadcast result 或 logcat 至少一种方式回收结果

---

## 已完成验证

### 本机环境与构建

```bash
bash ./scripts/doctor-android-env.sh
bun run build:jni
cd rust-libs && cargo test -p ease-remote-storage --lib
cd android && ./gradlew testDebugUnitTest :app:assembleDebug --warning-mode all
```

已验证通过：

- Android 环境 doctor：OK
- JDK 21 + Bun + Cargo + Android SDK/NDK：OK
- UniFFI / JNI 可重复生成：OK
- `ease-remote-storage` 单测：18 passed
- Android `testDebugUnitTest`：通过
- `app-arm64-v8a-debug.apk`：已生成

### 真机验证

设备：

- `172.26.121.48:34327`

执行命令：

```bash
bun run smoke:android --device=172.26.121.48:34327 --port=18089
adb -s 172.26.121.48:34327 shell am force-stop com.kutedev.easemusicplayer
adb -s 172.26.121.48:34327 shell am start -W -n \
  com.kutedev.easemusicplayer/com.kutedev.easemusicplayer.MainActivity
```

验证结果：

- `Local`：通过
- `OpenList`：通过
- `WebDAV`：通过
- `MainActivity` 冷启动：通过

本次 smoke 产物目录：

- `artifacts/smoke/2026-03-15T03-28-35.462Z/`

其中：

- `local.result.json`
- `openlist.result.json`
- `webdav.result.json`
- `summary.json`

都已落盘，可直接复查。

### release 边界验证

执行命令：

```bash
cd android && ./gradlew :app:processReleaseMainManifest
```

检查文件：

- `android/app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`

验证结果：

- release merged manifest 中**不包含** `DebugSmokeReceiver`

---

## 当前已验证到的边界

### 已验证

- Rust resolver 四类后端分流主线可编译
- Android 统一 `ease://data` 入口未破坏
- `MusicPlaybackDataSource` 三分流模型可工作
- 真机上：
  - Local -> `LOCAL_FILE`
  - OpenList -> `DIRECT_HTTP`
  - WebDAV -> `STREAM_FALLBACK`

### 尚未纳入本轮真机 smoke 的扩展项

这些不影响本轮第二波主线闭环完成，但属于下一阶段可继续补强的扩展验收：

- OneDrive 端到端 smoke
- folder-prefetch 独立场景 smoke
- 对 `metadata` / `next-prefetch` 的单独强断言 smoke

---

## 推荐回归命令

```bash
bash ./scripts/doctor-android-env.sh
bun run build:jni
cd rust-libs && cargo test -p ease-remote-storage --lib
cd android && ./gradlew testDebugUnitTest :app:assembleDebug --warning-mode all
cd ..
bun run smoke:android --device=172.26.121.48:34327
```

---

## 结论

第二波播放链路已经从“实现完成”推进到“**本机构建闭环 + 真机 smoke 闭环**”：

- 解析型播放 descriptor 已打通
- Android 路由工厂已统一接入主播放/预取/metadata
- debug-only 注入与 host smoke harness 已可重复运行
- `Local / OpenList / WebDAV` 三条核心路径已在真机验证通过

当前可以把第二波视为：

> **主目标完成，且具备可复跑、可验收、可定位问题的工程化闭环。**
