# ListenToList 搜索/目录/播放/设置页交互重构计划（2026-03-16）

## 目标
在 `/root/Coding/General/ListenToList` 中完成 4 组 UI/交互修改，并基于 Android / Material 官方最佳实践完成实现、验证与交付：
1. 搜索结果页去掉类型文字与定位按钮，仅保留左侧图标区分类型；长按后提供定位、加入播放队列、加入歌单等操作。
2. 目录页移除右上角“选择”入口，仅保留搜索；改为长按进入选择态；选择态新增批量加入歌单、加入播放队列、下载等动作。
3. 播放页重排层级：封面轻微上移，上一首/播放暂停/下一首上移至进度条下方，底部次级动作重组为 5 个按钮并新增下载。
4. 设置页新增“下载管理”页面与入口。

## 当前阶段
Phase 4

## 阶段
### Phase 1: 代码与最佳实践调研
- [x] 读取根级 AGENTS.md 与仓库结构真相源
- [x] 快速检索 memory，恢复 ListenToList 最近的播放/目录上下文
- [x] 定位搜索页、目录页、播放页、设置页相关 Kotlin/Compose/ViewModel/Route 文件
- [x] 检索 Android / Material 官方最佳实践（长按选择、动态 top app bar、search bar、menus / bottom sheets）
- **Status:** complete

### Phase 2: 方案冻结
- [x] 明确搜索结果长按动作与目录页选择态动作的最小实现路径
- [x] 明确“加入播放队列”需要的 runtime queue 扩展点
- [x] 确认下载基础设施真实状态，并决定接入现有下载仓库而非自建平行实现
- **Status:** complete

### Phase 3: 实现交互与页面
- [x] 搜索结果页：去 badge / 去定位按钮 / 加长按动作
- [x] 目录页：去选择按钮 / 长按进入选择态 / 选择态动作重构
- [x] 播放页：重排封面与控制区层级 / 新增下载按钮
- [x] 设置页：新增下载管理入口与页面
- [x] 必要时补充播放队列 / 下载任务 supporting repository / VM / route
- **Status:** complete

### Phase 4: 测试与验收
- [x] 更新受影响的 unit/androidTest 渲染 smoke
- [x] 运行 `cd android && ./gradlew testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --warning-mode all`
- [x] 运行 `cd android && ./gradlew connectedDebugAndroidTest`
- [x] 运行 `bun run smoke:android --device=172.26.121.48:34327 --apk=android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- **Status:** complete

## 已确认决策
| Decision | Rationale |
|----------|-----------|
| 搜索结果项只保留图标区分类型，不再显示“目录/音频/文件”badge | 与 Material 列表的 leading icon 模式一致，也符合用户明确要求 |
| 搜索结果项的扩展动作改为长按触发 | Android 官方 tap-and-press + contextual actions 模式更贴近移动端列表交互 |
| 目录页常态右上角只保留搜索 | 用户明确要求，选择态改为长按进入 |
| 目录页选择态采用“动态 top app bar + overflow actions” | Android 官方 dynamic top app bar with selection 更适合作为多选上下文工具栏 |
| 新增“临时/手动播放队列”上下文承接外部加入队列动作 | 不能把外部追加动作继续绑定到用户歌单上下文，否则 remove/refresh 语义会错乱 |
| 下载管理优先接入现有 `DownloadRepository + DownloadWorker` | 编译阶段已确认仓库真实存在下载任务仓库与 Worker，只是此前未接入搜索/目录/播放/设置页入口 |

## 风险与注意事项
1. 目录页当前“下载”FAB 实际代码路径是 `CreatePlaylistVM.importFromEntries(...)`，与用户感知语义不一致，必须纠正。
2. 搜索页与目录页内部搜索共用 `StorageSearchResultRow`，去 badge / 去按钮 / 加长按时要同步兼顾两个入口。
3. “加入播放队列”若直接追加到 `USER_PLAYLIST` 上下文，会把运行时队列和持久歌单重新耦合，需新建临时上下文。
4. 播放页视觉调整不仅是按钮位置变化，还要避免破坏现有 queue sheet、歌词切换与 slider 交互。
5. 长按交互需要尽量贴近 Android 官方 tap-and-press 习惯，最好补上触感反馈而不是只弹菜单。
6. 下载页并非从零开始；真正的风险是把 UI 再做一套平行任务模型，导致与现有 WorkManager 下载状态脱节。

## 错误记录
| Error | Attempt | Resolution |
|-------|---------|------------|
| 目录页页面文件初看误以为在 `widgets/musics/` 下 | 1 | 复查 Root 导航后确认实际页面为 `widgets/devices/StorageBrowserPage.kt` |
| 当前 planning files 仍是旧任务内容 | 1 | 已重写为本轮 UI/交互改造计划 |
