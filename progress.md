# Progress Log

## 2026-03-15 网络传输与播放稳定性修复

### 接管现场
- 用户要求“开始完整高质量逐个修复验收”，不再停留在审计阶段。
- 先按仓库要求复核真相源与 planning files，再从审计问题中收敛出本轮可安全落地的修复批次。
- 发现当前工作树存在用户改动：`AGENTS.md` 已修改；本轮不覆盖该文件。

### 本轮实际代码改动
- Rust：
  - `ease-remote-storage/src/backend.rs`
  - `ease-remote-storage/src/impls/onedrive.rs`
  - `ease-remote-storage/src/impls/openlist.rs`
  - `ease-remote-storage/src/impls/webdav.rs`
  - `ease-remote-storage/tests/openlist_integration.rs`
  - `ease-remote-storage/Cargo.toml`
- Android：
  - `core/MusicPlaybackDataSource.kt`
  - `core/MusicPlayerUtil.kt`
  - `core/MusicPlayer.kt`
  - `singleton/PlayerControllerRepository.kt`
  - `singleton/PlaylistRepository.kt`
  - `viewmodels/StorageBrowserVM.kt`
  - `core/MusicPlaybackDataSourceTest.kt`
  - `core/MusicPlayerUtilTest.kt`
  - `src/debug/.../DebugSmokeModels.kt`
  - `src/debug/.../DebugSmokeExecutor.kt`
- 脚本 / 文档：
  - `scripts/smoke-android.ts`
  - `docs/playback-second-wave.md`

### 处理中遇到的问题与处理
1. `cargo nextest` 本机缺失
   - 先用 `cargo test` / `cargo test --workspace --lib` 做局部与反向依赖回归
   - 再按 CI 的 `curl | tar` 方式安装 `cargo-nextest`
   - 最终补跑 `bun run test`

2. OneDrive / WebDAV 响应超时补丁首次编译失败
   - 失败原因：`tokio::time::timeout(...).await` 之后仍保留 `Result<Response, reqwest::Error>`，直接调用 `headers()` / `error_for_status()` 编译报错
   - 修复方式：改成 `Ok(resp) => resp?`，先解包 reqwest 层错误，再继续后续逻辑

3. focused code review 补出 3 个收尾风险
   - OneDrive refresh 仍有并发竞争窗口
   - OpenList 新增 `Timeout` 错误未纳入重试判断
   - Android 直连 descriptor cache 只在 `401/403` 失效，`404` 仍会吃满 TTL
   - 已在本轮继续修完：OneDrive 增加 refresh mutex、OpenList `should_retry_error()` 接入 timeout、Android 直连 `404` 触发失效与重试

4. Android smoke 扩展阶段踩到两个执行/场景问题
   - 问题 A：把 `assembleDebug` 与 `smoke:android` 并行执行，导致 smoke 安装旧 APK
   - 处理：改回严格串行，先 assemble 完成再单独重跑 smoke
   - 问题 B：OpenList / WebDAV 最初选中了排序后位于尾部的目标曲目，导致“下一首 metadata / next-prefetch”断言天然无对象
   - 处理：把目标曲目改成 `*-next.wav` 这一排序后首项，使下一首断言真实可测

### 已执行验收命令与结果
- Rust 侧（上一批修复完成时已跑通，且本轮未再改 Rust 业务逻辑）：
  - `cd rust-libs && cargo fmt` -> 成功
  - `cd rust-libs && cargo test -p ease-remote-storage` -> 24 passed, 1 ignored
  - `cd rust-libs && cargo test --workspace --lib` -> 通过
  - `cd rust-libs && cargo test -p ease-remote-storage --test openlist_integration -- --ignored` -> 1 passed
  - `cd /root/Coding/General/ListenToList && bun run test` -> 46 passed, 1 skipped
- Android / smoke 扩展这轮新增验收：
  - `cd android && ./gradlew testDebugUnitTest --tests com.kutedev.easemusicplayer.core.MusicPlayerUtilTest --tests com.kutedev.easemusicplayer.core.MusicPlaybackDataSourceTest` -> BUILD SUCCESSFUL
  - `cd android && ./gradlew testDebugUnitTest` -> BUILD SUCCESSFUL
  - `cd android && ./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac :app:compileDebugAndroidTestKotlin` -> BUILD SUCCESSFUL
  - `cd android && ./gradlew :app:assembleDebug --warning-mode all` -> BUILD SUCCESSFUL
  - `cd /root/Coding/General/ListenToList && bun run smoke:android --device=172.26.121.48:34327 --apk=android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` -> 成功，产物目录 `artifacts/smoke/2026-03-15T11-48-59.791Z`

### 当前结论
- 这一批已经把最危险的“会线上随机炸 / 会卡死播放线程 / 会默认污染 CI / 会错误携带 token / 会 panic”的点压下来了。
- 在上一轮基础上，本轮又完成了 3 件真正影响体验与验证闭环的大项：
  1. **播放 queue 化**：LIST/LIST_LOOP 不再停留在 one-item 模型，自动切歌与 next/prev 已优先走队列能力。
  2. **metadata 轻量 probe**：Local / DirectHttp 不再一律走独立 ExoPlayer，metadata 成本明显下降。
  3. **smoke 强断言扩展**：不再只看 `playback` 主路由，已真实覆盖 `next-prefetch` 与当前/下一首 metadata duration 回填。
- 当前仍未完全覆盖的剩余项：
  - OneDrive E2E smoke
  - folder-prefetch 独立验证
  - 弱网 / 卡流 / 断流专项行为测试
  - WebDAV Digest 直连
