# Findings

## 本轮任务范围
- 仓库：`/root/Coding/General/ListenToList`
- 目标页面：
  - 聚合搜索页 `widgets/search/StorageSearchPage.kt`
  - 目录页 `widgets/devices/StorageBrowserPage.kt`
  - 播放页 `widgets/musics/PlayerPage.kt`
  - 设置页 `widgets/settings/Page.kt`
- 真相源优先级遵循根级 `AGENTS.md`

## Memory quick pass
- `MEMORY.md` 中与本轮最相关的是 ListenToList 的目录浏览共享内核、播放队列语义、以及根级 AGENTS 真相源路线。
- 本轮 memory 只用于恢复仓库上下文，不作为产品需求或官方依据。

## 官方最佳实践（仅官方来源）

### 1) 长按与上下文操作
- 来源：Android Developers - Tap and press
- 链接：https://developer.android.com/develop/ui/compose/touch-input/pointer-input/tap-and-press
- 关键点：
  - `combinedClickable` 是 Compose 中同时承载 click / long-click 的推荐入口。
  - 长按适合触发上下文菜单或 selection mode。
  - 官方示例明确把长按与上下文菜单关联在列表项上。
  - 官方示例还建议在长按触发时加入 `HapticFeedbackType.LongPress` 之类的触感反馈，帮助用户确认状态切换。

### 2) 选择态 dynamic top app bar
- 来源：Android Developers - Create a dynamic top app bar on scroll
- 链接：https://developer.android.com/develop/ui/compose/components/app-bars-dynamic
- 关键点：
  - 官方示例直接展示了“selectedItems + top app bar actions”的模式。
  - 选择态下，top app bar 应切换为上下文工具栏，而不是维持普通浏览态操作。

### 3) Search bar / app bar 搜索入口
- 来源：Android Developers - Search bar
- 链接：https://developer.android.com/develop/ui/compose/components/search-bar
- 关键点：
  - 搜索入口通常承载在 app bar / top app bar 行为中。
  - 结果项示例使用 `ListItem` 的 `leadingContent` + 主/副文本组织结果，不依赖额外“类型标签”按钮。

### 4) Menus / bottom sheets 用于扩展动作
- 来源：Android Developers - Bottom sheets
- 链接：
  - https://developer.android.com/develop/ui/compose/components/bottom-sheets
- 关键点：
  - 与单个内容项强相关的一组操作，适合用 modal bottom sheet 承载。
  - 扩展动作适合收纳在 menu / overflow 中，避免列表行堆叠过多显式按钮。

### 5) 后台下载与进度观察
- 来源：Android Developers - WorkManager overview；Observe intermediate worker progress
- 链接：
  - https://developer.android.com/topic/libraries/architecture/workmanager
  - https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/observe
- 关键点：
  - 需要跨进程/跨重启持续执行的下载任务，优先放进 WorkManager 这类持久后台工作框架。
  - UI 层不要自造“假进度”；应以 Worker 的真实状态与进度为真相源，再把元数据补齐给页面。

## 本地代码勘察结论

### 1) 搜索结果页现状
- 关键文件：
  - `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/search/StorageSearchPage.kt`
  - `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/search/SearchWidgets.kt`
  - `android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/StorageSearchVM.kt`
  - `android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/StorageSearchModels.kt`
- 现状：
  - `StorageSearchResultRow(...)` 目前同时显示左侧图标、类型 badge、右侧“定位”按钮。
  - 聚合搜索页与目录页内部搜索共用同一个 `StorageSearchResultRow(...)`。
  - 聚合搜索页短按目录会打开目录；短按音乐会调用 `StorageSearchVM.playEntry(entry)` 从所在目录播放。

### 2) 目录页现状
- 关键文件：
  - `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/devices/StorageBrowserPage.kt`
  - `android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/StorageBrowserVM.kt`
- 现状：
  - 顶栏常态右上角有搜索按钮 + 选择按钮。
  - 选择态由 `toggleSelectMode()` 手动进入。
  - 选择态当前唯一显式动作是右下角 `FloatingActionButton(icon_download)`，但其真实代码路径是 `CreatePlaylistVM.importFromEntries(selectedEntries)`，不是下载。
  - `collectSelectedMusicEntries()` 已经能把“选中的目录 + 音乐文件”解析成真实音乐条目列表，可直接复用于批量加入歌单 / 加入队列 / 下载列表。

### 3) 播放页现状
- 关键文件：
  - `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/PlayerPage.kt`
  - `android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/PlayerVM.kt`
  - `android/app/src/main/java/com/kutedev/easemusicplayer/singleton/PlayerControllerRepository.kt`
  - `android/app/src/main/java/com/kutedev/easemusicplayer/singleton/PlaybackQueueModels.kt`
- 现状：
  - 封面/歌词区域在 `MusicPlayerBody(...)` 中整体居中。
  - 下方 `MusicPanel(...)` 把睡眠、歌词、上一首、播放暂停、下一首、队列、播放模式全部塞在同一行。
  - 当前 runtime queue 的上下文类型只有 `USER_PLAYLIST` 和 `FOLDER`，不适合承接“外部追加到当前播放队列”的新动作。

### 4) 设置页现状
- 关键文件：
  - `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/settings/Page.kt`
  - `android/app/src/main/java/com/kutedev/easemusicplayer/core/Routes.kt`
  - `android/app/src/main/java/com/kutedev/easemusicplayer/Root.kt`
- 现状：
  - 设置页已有主题、日志、更多、关于等入口。
  - 尚无下载管理路由或页面。

### 5) 下载基础设施真实状态（编译阶段纠偏）
- 关键文件：
  - `android/app/src/main/java/com/kutedev/easemusicplayer/singleton/DownloadRepository.kt`
  - `android/app/src/main/java/com/kutedev/easemusicplayer/core/DownloadWorker.kt`
  - `android/app/build.gradle.kts`
  - `android/gradle/libs.versions.toml`
- 结论：
  - 仓库并非“没有下载基础设施”，而是已有 `WorkManager + DownloadWorker + DownloadRepository` 的真实雏形。
  - 该仓库已经支持：
    - 从 `StorageEntry` 批量入队下载；
    - 从当前播放音乐入队下载；
    - 通过 `WorkInfo` 追踪状态与进度；
    - 取消/重试已存在的下载任务。
  - 本轮需要做的不是新造下载模型，而是：
    1. 修正下载仓库里对 `WorkInfo` 元数据读取的实现；
    2. 把搜索/目录/播放/设置页入口接到现有下载仓库。

## 方案冻结结论
1. 搜索结果行改为“图标 + 标题 + 副标题”，去掉 badge 与定位按钮；长按后通过 bottom sheet 打开定位 / 加入播放队列 / 加入歌单等动作。
2. 搜索结果长按除了定位 / 加队列 / 加歌单，也补上下载入口；对应 ViewModel 直接接现有下载仓库。
3. 目录页改为长按进入多选；常态顶栏只保留搜索；选择态切换为上下文 top bar，并把下载 / 加入歌单 / 加入播放队列收纳进 overflow menu。
4. 播放页将控制区拆成两层：主传输控制（上一首/播放暂停/下一首）与次级动作（睡眠/歌词/下载/队列/播放模式）。
5. 下载管理页直接接入现有 `DownloadRepository + DownloadWorker`，并补齐任务元数据持久化与页面入口。
6. 新增 `TEMPORARY` 播放上下文，用于承接“从搜索页/目录页加入到当前播放队列”的动作，避免污染用户歌单语义。
