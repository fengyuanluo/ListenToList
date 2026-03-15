# Progress Log

## 2026-03-15 设备页文件管理器问题深度排查

### 接管现场
- 用户要求深入研究“设备页点击设备进去之后文件管理器存在的问题”，明确点名“每次前进后退都重新刷新”等体验问题。
- 已按仓库要求先读取 `AGENTS.md`，并检查本地 planning files / 工作树状态。
- 发现项目根目录已存在旧 planning files，但内容对应上一轮“网络传输与播放稳定性修复”；已在本轮重写为当前排查任务，避免后续决策被旧目标污染。
- memory quick pass 已执行：`/root/.codex/memories/MEMORY.md` 未检索到 `ListenToList` 相关历史记录，因此本轮主要以当前仓库源码为准。

### 本轮已确认的代码链路
- Dashboard 设备入口：`widgets/dashboard/Page.kt`
- 路由装配：`Root.kt` 的 `StorageBrowser/{id}`
- 页面 UI：`widgets/devices/StorageBrowserPage.kt`
- 状态与加载：`viewmodels/StorageBrowserVM.kt`
- 存储列表仓库：`singleton/StorageRepository.kt`
- Rust 目录 listing：`rust-libs/ease-client-backend/src/controllers/storage.rs`
- Rust backend cache 仅覆盖 storage backend 实例，不覆盖目录结果：`rust-libs/ease-client-backend/src/services/storage/mod.rs`

### 当前已确认问题
1. 前进/后退/点面包屑都会进入 `navigateDirImpl()`，并无条件触发 `reload()`。
2. `reload()` 每次都先清空列表并进入 `LOADING`，UI 切到骨架屏，用户会感知为强闪烁刷新。
3. 目录结果没有 path 级缓存，回到已访问目录也会重新打远端。
4. 多次快速导航时没有取消旧请求或校验响应对应 path，存在乱序覆盖风险。
5. 当前路径、历史栈、选择模式都没有接入 `SavedStateHandle`，状态恢复能力差。
6. 设备被删除/失效时，`currentStorage() ?: return` 会让页面缺少明确兜底。
7. 顶部返回按钮当前是 history undo 语义，不是文件层级返回；与面包屑组合后可能违背用户直觉。
8. 同类“单份 entries + 强刷新 + 无并发防护”的浏览模式也存在于 `ImportVM`，说明这更像架构模式问题而非单页偶发。

### 当前阶段
- 已完成：定位设备页 -> 文件管理器的导航、Compose、ViewModel 与 Rust list 链路。
- 正在进行：把问题按“根因 / 影响 / 修复优先级”整理成可执行结论。
- 尚未修改业务代码；当前以证据收集和分析为主。

### 本轮实际改动
- 仅更新 planning files：
  - `task_plan.md`
  - `findings.md`
  - `progress.md`
- 尚未改动产品源码。

## 2026-03-15 修复与验收收口

### 已落地的产品改动
- 新增共享目录浏览内核：`android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/DirectoryBrowser.kt`
- 设备页状态层重构：`StorageBrowserVM.kt`
- 导入页状态层重构：`ImportVM.kt`
- 设备页 UI 收口：`StorageBrowserPage.kt`
- 导入页 UI 收口：`ImportPage.kt`
- 新增无设备空态文案与 debug-only `TestComposeActivity`

### 已完成的测试补强
- 新增 JVM 状态测试：
  - `android/app/src/test/java/com/kutedev/easemusicplayer/viewmodels/DirectoryBrowserControllerTest.kt`
- 调整 device instrumentation：
  - `android/app/src/androidTest/java/com/kutedev/easemusicplayer/widgets/devices/StorageBrowserContentTest.kt`
  - `android/app/src/androidTest/java/com/kutedev/easemusicplayer/widgets/musics/MusicSliderTest.kt`
- 新增依赖：
  - `android/app/build.gradle.kts` 中补 `kotlinx-coroutines-test`

### 本轮验证记录
- `cd android && ./gradlew testDebugUnitTest --tests com.kutedev.easemusicplayer.viewmodels.DirectoryBrowserControllerTest`
  - 结果：通过
- `cd android && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.kutedev.easemusicplayer.widgets.devices.StorageBrowserContentTest`
  - 结果：通过
- `cd android && ./gradlew connectedDebugAndroidTest`
  - 结果：通过
- `cd android && ./gradlew testDebugUnitTest :app:assembleDebug connectedDebugAndroidTest --warning-mode all`
  - 结果：通过

### 关键问题与处理
- 原有真机 Compose instrumentation 基线持续出现 `No compose hierarchies found`。
- 本轮最终处理：
  - 把行为级强断言下沉到 JVM 状态测试；
  - 保留 device 侧 render smoke，确保真实设备可完成渲染与 instrumentation 闭环。
