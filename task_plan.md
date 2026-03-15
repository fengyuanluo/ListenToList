# 第二波播放链路整改计划

## 目标

在现有第一波修复基础上，完成第二波播放链路优化：

1. Rust 侧提供可解析的播放源 descriptor；
2. Android 侧在保持 `ease://data` 入口不变的前提下，按 descriptor 走：
   - `HttpDataSource`
   - `FileDataSource`
   - WebDAV / 其他 fallback 继续走 FFI stream
3. 同一套路由工厂接入：
   - 主播放
   - next prefetch
   - metadata/duration 队列
   - folder prefetch
4. 提供 debug-only 注入入口与可重复真机 smoke harness；
5. 完成本机构建测试、APK 生成、release 边界检查与真机 adb smoke 验证。

## 阶段状态

- [x] 阶段 1：核查当前实现与接入点，确认第一波改造与第二波边界
- [x] 阶段 2：实现 Rust 播放源解析接口与测试
- [x] 阶段 3：实现 Android 解析型 DataSource wrapper 并接入主播放/预取/metadata
- [x] 阶段 4：补 debug-only 注入入口与 host smoke harness
- [x] 阶段 5：完成本机构建测试、APK 生成、release 边界检查与真机 adb smoke 验证

## 已完成的关键决策

- 第二波主线：
  - `Local / OpenList / OneDrive` 直连
  - `WebDAV` 保留 fallback
- Android 上层 `ease://data?music=<id>` URI 维持不变，分流放在 DataSource `open()`
- 真机验证使用：
  - debug-only 注入入口
  - `adb reverse`
  - host mock harness
  避免依赖手工 UI 配置
- folder prefetch 最终采用：
  - 保持现有 `FolderPrefetcher`
  - 在 `StorageBrowserVM.playFromFolder()` 成功路径补实际调用

## 本轮额外收口项

- 修复 WebDAV mock server 与 `reqwest/hyper` 的 HTTP 兼容性
- 收敛 debug smoke 的 route settle 条件，避免远端场景白等
- 给 debug receiver 增加总超时兜底
- 验证 release merged manifest 不暴露 `DebugSmokeReceiver`

## 风险与关注点

- 当前工作树原本就存在第一波相关改动，本轮始终在其基础上增量收口，未回退用户已有成果
- OneDrive 端到端 smoke 与 folder-prefetch 专项 smoke 仍可作为后续增强项继续追加，但不影响本轮第二波主目标闭环完成
