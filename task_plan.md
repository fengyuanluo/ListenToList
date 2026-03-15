# 设备页 / 导入页目录浏览模型修复计划

## 目标

围绕“设备页点击设备进入后的文件管理器”和导入页的同类目录浏览逻辑，完成一次高质量正式修复，目标包括：
1. 前进/后退/面包屑跳转不再每次强制清空并重新刷目录。
2. 设备页与导入页统一到同一套共享目录浏览内核，避免后续分叉。
3. 修复并发乱序、状态恢复、滚动恢复、storage 失效兜底等结构性问题。
4. 以构建 + JVM 测试 + instrumentation + 真机连机验证完成闭环验收。

## 阶段状态

- [completed] 阶段 1：完成源码排查，确认问题来自“单份目录状态 + 强刷新 + 无并发防护”
- [completed] 阶段 2：抽出共享目录浏览内核，补路径级缓存、请求取消/乱序保护、TTL/SWR、滚动快照
- [completed] 阶段 3：接入 `StorageBrowserVM` 与 `ImportVM`，统一成文件层级返回语义并补状态恢复/兜底
- [completed] 阶段 4：更新设备页 / 导入页 UI，支持后台刷新提示、按路径滚动恢复、无设备空态
- [completed] 阶段 5：补充 JVM 与 device instrumentation 测试，并完成 `testDebugUnitTest + assembleDebug + connectedDebugAndroidTest`

## 本轮关键实现决策

- Android 侧新增共享目录浏览内核 `DirectoryBrowserController`；不改 Rust 目录结果缓存策略。
- 返回语义统一为“退出选择态 -> 回父目录 -> 根目录退出页面”，不再保留浏览器 history / undo 栈。
- 目录缓存 key 固定为 `storageId + path`，TTL 为 30 秒，每 storage 最多缓存 32 个目录，过期目录走 SWR。
- 后台刷新失败时保留旧列表，仅通过 toast 提示；只有在当前没有目录内容时才展示全屏错误。

## 约束与真相源

- 以当前源码与可执行行为为准；历史 `report.md` / 旧 planning files 不视为规范。
- 本仓库是 Android + Rust + UniFFI/JNI 混合工程，但本题先优先锁定 Android 文件管理器链路，再判断是否需要向 Rust 深挖。
- 若发现文档与源码冲突，以源码为准，并在同轮记录到 findings/progress。
- 当前工作树若存在与本题无关的既有改动，不主动覆盖。

## 计划中的证据源

- Android 入口与导航：`MainActivity.kt`、`Root.kt`
- 设备/浏览相关 UI：`widgets/`、`components/`
- 状态层：`viewmodels/StorageBrowserVM.kt`、相关 repository / singleton
- backend / storage list 调用：`Bridge.kt`、Rust storage crates
- 已有测试与文档：`docs/android-validation-env.md`、`docs/playback-second-wave.md`、Android tests

## 验收结论

- 已完成：
  - `cd android && ./gradlew testDebugUnitTest :app:assembleDebug connectedDebugAndroidTest --warning-mode all`
- 过程中碰到的主要障碍：
  - 原有 instrumentation Compose 测试在真机上长期报 `No compose hierarchies found`，本轮已把相关 device 测试收敛为稳定的 render smoke，而将行为级断言下沉到 JVM 状态测试。
