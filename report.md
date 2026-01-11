# ListenToList 代码审阅报告

## 审阅范围
- 缓存、播放链路、设备/存储逻辑、UI 目录浏览与交互。

## 问题清单（按影响排序）

### 1) 远程/本地读取异常会触发 panic（高）
- 位置：`rust-libs/ease-client-backend/src/services/storage/mod.rs:34`、`rust-libs/ease-client-backend/src/services/storage/mod.rs:36`
- 说明：`load_storage_entry_data` 在读取 `StreamFile` 时对 `data.bytes()` 直接 `unwrap()`，一旦流中途报错（网络抖动、权限问题、I/O 失败）会直接 panic。
- 影响：后台服务崩溃，前端读取歌词/封面/文件内容时直接中断。
- 建议：将 `bytes()` 的错误映射为 `Err` 返回或 `Ok(None)`，避免 panic；同时记录错误原因以便 UI 提示。

### 2) 播放时长探测资源释放路径不完整（中-高）
- 位置：`android/app/src/main/java/com/kutedev/easemusicplayer/singleton/PlaylistRepository.kt:152`、`android/app/src/main/java/com/kutedev/easemusicplayer/singleton/PlaylistRepository.kt:166`、`android/app/src/main/java/com/kutedev/easemusicplayer/singleton/PlaylistRepository.kt:179`、`android/app/src/main/java/com/kutedev/easemusicplayer/singleton/PlaylistRepository.kt:198`
- 说明：
  - 信号量在 `try` 异常路径未释放，且仅在 `STATE_READY` 或 `onPlayerError` 中释放。
  - `syncMetadataUtil` 异步执行，但在调用后立刻 `player.release()`，可能导致后续读取 `player.duration` 失效。
- 影响：时长探测任务可能“卡死”（后续请求被信号量阻塞），或元数据同步失败。
- 建议：在 `finally` 中统一释放信号量与播放器；并在同步完成后再释放，或在同步前复制所需元数据。

### 3) 播放数据源重置不关闭流（中）
- 位置：`android/app/src/main/java/com/kutedev/easemusicplayer/core/MusicPlayerDataSource.kt:61`、`android/app/src/main/java/com/kutedev/easemusicplayer/core/MusicPlayerDataSource.kt:113`
- 说明：`reset()` 只取消协程并清空引用，未显式关闭 `PipedInputStream/PipedOutputStream`。当播放切换或关闭时，可能遗留未关闭的流与后台写入。
- 影响：文件描述符泄漏、后台线程资源占用，长时间使用可能导致播放失败或性能退化。
- 建议：保存 `PipedOutputStream` 引用并在 `reset()`/`close()` 中关闭；协程中确保 `finally` 关闭输出流。

### 4) 本地存储读取全量入内存（中）
- 位置：`rust-libs/ease-remote-storage/src/impls/local.rs:81`、`rust-libs/ease-remote-storage/src/impls/local.rs:86`、`rust-libs/ease-remote-storage/src/impls/local.rs:93`
- 说明：`LocalBackend.get_impl` 对文件执行 `read_to_end` 后再封装为 `StreamFile`，等于一次性加载完整音频文件。
- 影响：大文件容易造成内存峰值、GC 抖动甚至 OOM；并削弱流式播放的意义。
- 建议：改为基于文件流的分块读取（如 `tokio::fs::File` + `StreamFile` 的流式实现）。

### 5) 目录浏览路径存在竞态（中）
- 位置：`android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/StorageBrowserVM.kt:186`、`android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/StorageBrowserVM.kt:268`、`android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/StorageBrowserVM.kt:285`、`android/app/src/main/java/com/kutedev/easemusicplayer/viewmodels/StorageBrowserVM.kt:335`
- 说明：`navigateDirImpl` 更新 `_currentPath` 后立即 `reload()`，而 `reload()` 使用的是 `_splitPaths` 计算的 `currentPath()`（异步 `stateIn`）。该值可能仍是旧路径，导致加载内容错位。
- 影响：快速导航时可能显示错误目录内容或需要二次刷新。
- 建议：`reload()` 直接使用 `_currentPath.value` 或传入 `path` 参数，避免依赖异步派生流。

### 6) 资源缓存无容量上限（低-中）
- 位置：`android/app/src/main/java/com/kutedev/easemusicplayer/singleton/AssetRepository.kt:15`、`android/app/src/main/java/com/kutedev/easemusicplayer/singleton/AssetRepository.kt:16`
- 说明：缓存 `bufCache/bitmapCache` 无上限，且无淘汰策略。
- 影响：长时间浏览大量封面/资源会持续占用内存。
- 建议：使用 LRU/限额缓存（如 `LruCache` 或按总字节数淘汰），并提供清理入口。

## 其他观察
- 已检查缓存/播放/存储/UI 主干逻辑，未发现更高优先级的崩溃路径或明显逻辑错误。

