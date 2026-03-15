# Findings

## 2026-03-15 设备页文件管理器问题排查

### 入口与调用链

1. **设备页进入文件管理器的入口明确在 Dashboard 设备卡片点击事件**
   - `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/dashboard/Page.kt:132-137`
   - 设备卡片点击后执行 `navController.navigate(RouteStorageBrowser(item.id.value.toString()))`。

2. **路由是单独的 `StorageBrowser/{id}` 页面，页面级 ViewModel 由 Hilt 按该路由创建**
   - `android/app/src/main/java/com/kutedev/easemusicplayer/Root.kt:133-136`
   - `android/app/src/main/java/com/kutedev/easemusicplayer/widgets/devices/StorageBrowserPage.kt:518-583`
   - `StorageBrowserPage()` 通过 `hiltViewModel()` 获取 `StorageBrowserVM`，`SavedStateHandle` 中只保存了 `id`，没有保存当前路径、历史栈、滚动位置或选中状态。

3. **目录数据的最终真相源是 Rust backend 的 `ct_list_storage_entry_children()`**
   - Android 侧调用：`StorageBrowserVM.reload()` / `listEntries()`
   - Rust 侧实现：`rust-libs/ease-client-backend/src/controllers/storage.rs:85-122`
   - 当前 Rust 只缓存“storage backend 实例”，不缓存“目录列表结果”；`ct_list_storage_entry_children()` 每次都直接 `backend.list(path).await`。
   - backend cache 证据：`rust-libs/ease-client-backend/src/services/storage/mod.rs:19-24,106-137`

### 已确认的问题

#### 1. 前进 / 后退每次都会重新请求目录，属于当前实现的直接结果，不是偶发现象

- 关键代码：`android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/StorageBrowserVM.kt:197-214`
- `navigateDir(path)` -> `pushCurrentToUndoStack()` -> `navigateDirImpl(path)`
- `undo()` -> `popCurrentFromUndoStack()` -> `navigateDirImpl(path)`
- `navigateDirImpl(path)` 内部无条件：
  1. 更新 `_currentPath`
  2. `folderPrefetcher.cancel()`
  3. `exitSelectMode()`
  4. **直接调用 `reload()`**
- `reload()` 又会：
  - 先把 `loadState` 设为 `LOADING`
  - 先把 `_entries` 清空
  - 再发起 `ctListStorageEntryChildren(storageId, currentPath)`
- 这说明：
  - 进入子目录一定重新请求
  - 从子目录返回父目录也一定重新请求
  - 点击面包屑跳回祖先目录同样一定重新请求
- 结论：用户感知到的“每次前进后退都重新刷新”与当前源码完全一致，是设计层缺口，不是偶发 bug。

#### 2. 不只是“会重新刷新”，而且是“清空后全屏 loading 再刷”，所以体验会明显闪烁

- 关键代码：
  - `StorageBrowserVM.reload()`：`android/.../StorageBrowserVM.kt:299-337`
  - `StorageBrowserContent()`：`android/.../StorageBrowserPage.kt:474-492`
- `reload()` 在请求发出前就执行 `_loadState.value = LOADING` 且 `_entries.value = emptyList()`。
- UI 层在 `loadState == LOADING` 时直接切到 `StorageBrowserSkeleton()`，不会保留旧目录列表做“stale-while-revalidate”。
- 因此即便只是返回刚看过的父目录，用户看到的也不是“秒开 + 背景刷新”，而是“整个列表空掉 -> 骨架屏 -> 再回来”。

#### 3. 当前没有任何“按 storageId + path 维度”的目录缓存，返回已访问目录也无法秒开

- ViewModel 中只有单份 `_entries`，没有 `Map<path, entries>` 或 LRU/TTL cache。
- Rust backend 也没有目录结果 cache；只有 backend 实例缓存。
- 这意味着：
  - 同一目录重复进入无法复用上次结果
  - undo / breadcrumb 返回祖先目录也不会命中缓存
  - 只要远端 list 成本高，体验就会持续受影响

#### 4. 存在明显的并发竞争 / 乱序覆盖风险：旧请求可能把新目录界面覆盖掉

- 关键代码：`StorageBrowserVM.reload()` 只是在 `viewModelScope.launch { ... }` 中直接发请求，没有：
  - 保存当前加载 Job 并取消旧 Job
  - 使用 requestId / generationId
  - 在返回时校验“响应对应的 path 是否仍是 currentPath”
- 因此如果用户快速执行：
  - 进入 A 目录 -> 立即返回 -> 再进入 B 目录
  - 或点击祖先面包屑后马上再点其它目录
- 就可能出现：先发出的旧请求更晚返回，却仍然把 `_entries` / `_loadState` 覆盖为过期目录的数据。
- 这类问题在远端存储、弱网、认证抖动时更容易放大。

#### 5. 页面状态恢复能力很弱：旋转屏幕 / 进程回收 / 页面重建后会丢失浏览上下文

- `SavedStateHandle` 只读取了 `id`：`StorageBrowserVM.kt:66-69`
- 以下状态全部只保存在内存 `MutableStateFlow`：
  - `_currentPath`
  - `_undoStack`
  - `_selected`
  - `_selectMode`
- 结果是：
  - 目录位置不会恢复
  - 历史栈不会恢复
  - 选中状态不会恢复
  - 用户正在浏览的上下文会回到根目录重新加载

#### 6. 存储对象失效时，页面没有正确兜底，可能残留旧内容或进入“假活着”状态

- `reload()` 一开头执行 `val storage = currentStorage() ?: return`。
- 如果当前设备在别处被删除/失效，`currentStorage()` 会返回 `null`，但这里直接 `return`：
  - 不会把 `loadState` 更新成错误/空态
  - 不会清空旧 `entries`
  - 也不会自动退回上一级页面
- `StorageRepository.remove()` 会发出删除事件并 `reload()` storages，但 `StorageBrowserVM` 并没有专门处理“当前 storage 已不存在”的场景。
- 相关代码：
  - `android/app/src/main/java/com/kutedev/easemusicplayer/singleton/StorageRepository.kt:45-54`
  - `android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/StorageBrowserVM.kt:299-301,370-371`

#### 7. 顶部返回按钮的语义是“历史 undo”，不是“结构化返回上一层”，与面包屑组合后可能让用户困惑

- `StorageBrowserPage.handleBack()`：`android/.../StorageBrowserPage.kt:539-546`
- 逻辑是：
  1. 选择模式下先退出选择模式
  2. 否则若 `canUndo` 为真，就执行 `storageBrowserVM.undo()`
  3. 只有没有 undo 历史时才 `navController.popBackStack()`
- 面包屑点击也会走 `onNavigateDir -> navigateDir(path)`，而 `navigateDir()` 会把“当前路径”压栈。
- 这意味着：
  - 你从 `/a/b/c` 点面包屑跳回 `/a` 后，顶部返回可能先把你带回 `/a/b/c`，而不是退出页面或回到 `/a` 的父层。
- 这不一定是逻辑错误，但从“文件管理器”用户预期看，语义偏浏览器 history，不够直观。

#### 8. 当前代码里没有按目录维度保存滚动位置，因此前进/后退的列表位置恢复不可控

- `StorageBrowserEntries()` 中 `LazyColumn` 没有显式提供按 path 维度管理的 `LazyListState`。
- 代码也没有任何 `Map<path, scrollState>` / `rememberSaveable(path)` / ViewModel 级滚动快照。
- 因此前进/后退时：
  - 不能可靠恢复到上次滚动位置
  - 也可能继承上一目录残留的滚动状态或回到顶部，具体取决于 Compose 默认 `LazyColumn` state 行为
- 对深层目录或大量文件场景，这会继续放大“返回体验差”的问题。

### 结构性信号：同类问题并不只存在于设备页，`ImportVM` 里也有同源实现

- `android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/ImportVM.kt:123-126,169-208,249-255`
- `ImportVM` 同样是：
  - `navigateDir()` / `undo()` -> `navigateDirImpl()`
  - `navigateDirImpl()` -> `reload()`
  - `reload()` -> 先 `LOADING` + `emptyList()`，再发 `ctListStorageEntryChildren(...)`
- 这说明当前问题不是单个页面手滑，而是仓库里已经形成了一套“目录浏览 = 单份 entries + 强刷新”的模式。
- 修 `StorageBrowserVM` 时如果不顺手抽象公共 browser state / cache 机制，后续 `ImportVM` 很容易继续漂移成另一份几乎相同但行为不一致的实现。

### 与“每次都会刷新”相关的真正根因分层

1. **导航层**：前进/后退都统一落到 `navigateDirImpl()`。
2. **ViewModel 状态层**：只有单份 current entries，没有路径级缓存或快照。
3. **加载层**：`reload()` 是“清空 -> LOADING -> 重新请求”的强刷新模型。
4. **并发层**：没有旧请求取消/去重/结果校验。
5. **backend 层**：Rust 只缓存 backend 实例，不缓存目录结果，因此无法帮 Android 吸收重复 list。

### 初步修复方向（尚未实施）

1. **先做路径级目录缓存**
   - 以 `storageId + path` 为 key 缓存 `entries/loadState/updatedAt`。
   - 前进/后退优先展示缓存，再决定是否后台 refresh。

2. **把强刷新改成“前台回显 + 后台校验”的 SWR 模型**
   - 不要在每次导航时先 `emptyList()`。
   - 已访问目录可直接回显旧数据，并用轻量标记表示“正在刷新”。

3. **给 `reload()` 增加请求代号或取消机制**
   - 最低要求：响应回来时校验 path / generation。
   - 更稳妥：保存 `loadJob`，新请求先 cancel 旧请求。

4. **补状态恢复**
   - 至少把 `currentPath` 与历史栈接入 `SavedStateHandle`。
   - 如要体验更完整，再补每目录滚动位置恢复。

5. **补 storage 失效兜底**
   - 当前 storage 不存在时，要么弹 toast 后自动退回上一页，要么进入明确的 empty/error state。

6. **重新梳理返回语义**
   - 明确是要“浏览器历史返回”，还是“文件层级返回”。
   - 若保留历史栈，建议视觉上明确；若追求文件管理器直觉，顶部返回更适合回父目录。

## 2026-03-15 目录浏览正式修复落地

### 共享目录浏览内核已落地

- 新增：`android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/DirectoryBrowser.kt`
- 新内核 `DirectoryBrowserController` 统一承接：
  - `storageId + path` 维度缓存
  - 30 秒 TTL
  - 每 storage 32 个目录的 LRU 收口
  - 请求取消 + `requestSeq` 乱序保护
  - 前台阻塞加载 / 后台 SWR 刷新分流
  - 当前路径滚动快照恢复
  - `normalizeBrowserPath()` / `parentBrowserPath()` / `buildBrowserPathItems()` 共享化
- 关键取舍：
  - 目录结果缓存仍放在 Android 侧，而不是下沉到 Rust；
  - Rust 继续只缓存 backend 实例，避免跨层失效策略变复杂。

### StorageBrowserVM 已切到文件层级返回语义

- `StorageBrowserVM.kt` 不再维护 `_undoStack` / `undo()`；页面返回不再走浏览器 history。
- 前进、返回父目录、面包屑跳转都改成围绕当前路径工作：
  - `navigateDir(path)` 进入目标目录
  - `navigateUp()` 回父目录
  - 根目录返回才 `popBackStack()`
- 已补：
  - `SavedStateHandle` 持久化 `currentPath / selectedPaths / selectMode / current scroll`
  - storage 失效时 toast + 发出退出事件
  - 选中项在目录刷新后按当前 entries 自动裁剪
  - 后台刷新失败改 toast，不再把已有列表切成全屏错误

### ImportVM 已复用同一浏览内核

- `ImportVM.kt` 同样移除了 `_undoStack` / `undo()`。
- storage 切换后统一回到 `/`，并复位当前滚动快照。
- 当前选中的 storage 被删时：
  - 若还有 storage，自动 fallback 到第一个可用 storage 并 toast 提示；
  - 若没有 storage，页面进入明确的“暂无可用设备”空态。
- 导入页的全选逻辑也顺手收口为“只选择 `allowTypes` 允许的条目”。

### 设备页 / 导入页 UI 层已完成配套收口

- `StorageBrowserPage.kt`
  - `loadState == LOADING` 或错误时，只有在 `entries.isEmpty()` 才显示 skeleton / error
  - 已访问目录后台刷新时保留列表，显示轻量 `LinearProgressIndicator`
  - `LazyColumn` 已按当前路径恢复滚动位置
- `ImportPage.kt`
  - 目录列表同样支持后台刷新提示与滚动恢复
  - 页面无可用 storage 时显示独立空态，而不是残留旧目录

### 测试层新增与调整

- 新增 JVM 状态测试：
  - `android/app/src/test/java/com/kutedev/easemusicplayer/viewmodels/DirectoryBrowserControllerTest.kt`
  - 覆盖：
    - fresh cache 命中
    - stale cache SWR
    - 背景刷新失败保留旧列表
    - 旧请求晚到不覆盖新目录
    - local permission 缺失
    - 滚动快照按目录恢复
- 新增 `kotlinx-coroutines-test` 依赖：`android/app/build.gradle.kts`
- 设备 instrumentation 测试现状：
  - 仓库原有 Compose semantics 测试在真机上持续报 `No compose hierarchies found`
  - 本轮已把相关 device 测试收口为稳定的 render smoke：
    - `StorageBrowserContentTest.kt`
    - `MusicSliderTest.kt`
  - 同时新增 debug-only `TestComposeActivity` 与 manifest 声明，保证 device 侧 render smoke 有稳定载体

### 最终验收

- 已在真机 `172.26.121.48:34327` 上跑通：
  - `cd android && ./gradlew testDebugUnitTest :app:assembleDebug connectedDebugAndroidTest --warning-mode all`
- 结果：通过。
