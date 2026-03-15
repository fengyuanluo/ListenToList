# 网络传输与播放稳定性修复计划

## 目标

围绕 2026-03-15 的仓库级审计结论，按“先功能风险、再性能大头、最后验收闭环”的顺序，逐项修复 `ListenToList` 当前最关键的网络传输与播放稳定性问题，并给出可复现的验证结果。

当前已完成的重点包括：
1. OneDrive refresh token 轮换丢失
2. Rust fallback 下载链路缺少超时保护
3. WebDAV `WWW-Authenticate` 解析 panic 风险
4. WebDAV 直连播放（匿名 / Basic 第一批场景）
5. OpenList 同源判断过松导致 token 误带
6. Android 播放 descriptor 重复 resolve
7. metadata 可写 cache 放大远端 I/O
8. StorageBrowser 首开重复 reload
9. 默认测试链依赖真实第三方站点
10. one-item 播放模型导致 next-prefetch 无法真正兑现为 queue 级切歌准备
11. metadata / next-prefetch 验证盲区过大

## 阶段状态

- [completed] 阶段 1：按真相源复核审计结论，确认实际可修批次
- [completed] 阶段 2：落地 Rust P0/P1 修复（OneDrive / fallback timeout / OpenList / WebDAV / CI live test gating）
- [completed] 阶段 3：落地 Android 首批修复（descriptor cache / metadata read-only cache / 首开 reload）
- [completed] 阶段 4：落地架构级续修（queue 化播放、metadata 轻量 probe、smoke 强断言扩展）
- [completed] 阶段 5：执行分层验收（Rust 单测、workspace 测试、nextest、Android JVM、assembleDebug、真机 smoke）
- [pending] 阶段 6：继续处理剩余 backlog（OneDrive E2E、folder-prefetch 专项验证、WebDAV Digest 直连、弱网 fallback 行为测）

## 当前结论

- 已完成并验收的修复：
  - OneDrive token rotation
  - Rust 首包/卡流超时保护
  - OpenList origin 边界
  - WebDAV invalid challenge panic
  - WebDAV 匿名/Basic 直连
  - Android descriptor TTL cache + 404 失效重查
  - metadata read-only cache + direct probe 轻量化
  - 播放器从 one-item 升级到按 `PlayMode` 规划 queue
  - StorageBrowser 首开重复 reload
  - 默认 CI 去 live OpenList 依赖
  - smoke 对 `next-prefetch` / metadata duration 回填的强断言
- 已完成风险兜底但仍有余量的修复：
  - WebDAV 直连目前支持匿名和“已知 Basic challenge”场景；Digest / 未知 challenge 仍保守 fallback。
  - metadata 对 Local / DirectHttp 已优先走 `MediaMetadataRetriever` 轻量 probe；`StreamFallback` 仍走 metadata player fallback。
- 仍待后续的项：
  - OneDrive E2E smoke
  - folder-prefetch 独立验证
  - 弱网 / 卡流 / 断流专项行为测试

## 已知约束

- 以当前源码为准，`task_plan.md/findings.md/progress.md` 仅作为工作记录，不当作产品规范。
- 本仓库是 Android + Rust + UniFFI/JNI 混合工程，不能只看 Android。
- `android/app/src/main/java/uniffi/**` 与 `android/app/src/main/jniLibs/**` 为生成物，不手改。
- 当前工作树存在用户侧改动：`AGENTS.md` 已修改；本轮不覆盖该文件。

## 验证矩阵

- Rust 目标 crate：`cargo test -p ease-remote-storage`
- Rust 反向依赖回归：`cargo test --workspace --lib`
- CI 同构入口：`bun run test`
- Android JVM：`cd android && ./gradlew testDebugUnitTest`
- Android 组装：`cd android && ./gradlew :app:assembleDebug --warning-mode all`
- 真机 smoke：`bun run smoke:android --device=172.26.121.48:34327 --apk=android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

## 错误记录

| 错误 | 阶段 | 处理 |
|---|---|---|
| `cargo nextest` 本地不存在 | 验收 | 按 `.github/workflows/release.yml` 的同构方法安装后再跑 `bun run test` |
| OneDrive/WebDAV 新增 `tokio::time::timeout` 后把 `resp` 保留成 `Result<Response, reqwest::Error>`，导致 `headers()/error_for_status()` 编译失败 | Rust 修复 | 调整 `match` 分支为 `Ok(resp) => resp?`，显式解包 reqwest 结果 |
| 把 `assembleDebug` 与 `smoke:android` 并行执行，导致 smoke 可能安装旧 APK | Android 验收 | 改回严格串行：先 assemble 完成，再单独重跑 smoke |
| OpenList/WebDAV smoke 最初选择了排序后位于尾部的目标曲目，导致“下一首 metadata / next-prefetch”断言天然无对象 | smoke 扩展 | 将目标曲目切到排序后的首项（`*-next.wav`），让下一首断言真正有意义 |
