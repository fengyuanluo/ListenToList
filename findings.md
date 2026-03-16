# Findings

## 本轮研究范围

只收集以下官方一手来源：
- `developer.android.com`
- `developer.android.google.cn`
- `github.com/androidx/media`（官方仓库中的 README / docs，如确实相关）

不使用：
- 第三方博客
- Stack Overflow
- Reddit
- Medium / 掘金 / CSDN 等二手总结

## Memory quick pass

### 命中信息
- `MEMORY.md` 中 ListenToList 最近已有“queue-aware playback”推进记录，说明本仓库语境里 queue 不是纯 UI 概念。
- 这些记忆仅用于帮助理解为什么本轮研究聚焦 queue / playlist 概念，不作为官方依据。

### 当前结论边界
- 最终输出里的原则必须全部回到官方页面落锚。
- memory 只做任务上下文，不作为论据来源。

## 官方资料采集（待补充）

### 候选主题
- Media3 ExoPlayer playlists
- MediaItem / MediaMetadata / RequestMetadata
- MediaSession / MediaController / service lifecycle
- MediaLibraryService / browse tree / children
- Media item identity, queue mutation, move/remove/replace semantics

## 官方资料采集（已核实）

### 1) Playlists | Android media | Android Developers
- 链接: https://developer.android.com/media/media3/exoplayer/playlists
- 可直接支持的点:
  - Playlist API 定义在 `Player` 接口上；Player 负责顺序播放多个 `MediaItem`。
  - 支持 `add/move/remove/replace/set/clear` 等列表变更语义。
  - 正在播放的 item 被 move/remove/replace 时，播放器有明确的不中断/自动接续语义。
  - `MediaItem.mediaId` 用于标识 playlist item；若未显式设置，则使用 URI 字符串。
  - 可用 custom tag 关联 app data；官方示例用于 playlist transition 时更新 UI。
  - `onMediaItemTransition` 适合更新“当前播放项 UI”；`onTimelineChanged(...PLAYLIST_CHANGED)` 适合更新“整个队列 UI”。
  - shuffle 打开时播放顺序可与原始索引不同；`getCurrentMediaItemIndex()` 仍指向原始未打乱顺序。

### 2) Media items | Android media | Android Developers
- 链接: https://developer.android.com/media/media3/exoplayer/media-items
- 可直接支持的点:
  - Playlist API 基于 `MediaItem`。
  - `MediaItem.Builder` 可同时设置 `mediaId`、`tag`、`uri`。
  - 附加 metadata/tag 的典型用途是 playlist transition 时更新 app UI。

### 3) The Player Interface | Android media | Android Developers
- 链接: https://developer.android.com/media/media3/session/player
- 可直接支持的点:
  - `Player` 的职责包括 managing a playlist/queue of media items、shuffle/repeat/speed 等播放属性。
  - `MediaController` 通过 `MediaSession` 把 playback / playlist 调用转发给 `Player`。
  - `MediaBrowser` 在 `MediaController` 能力之上，额外与 `MediaLibrarySession` 交互以浏览内容。

### 4) Control and advertise playback using a MediaSession | Android media | Android Developers
- 链接: https://developer.android.com/media/media3/session/control-playback
- 可直接支持的点:
  - MediaSession 的职责是把外部来源命令路由到真正播放媒体的 player。
  - session 可直接修改其 player 的 playlist；controller 在拥有相应 command 时也可改 playlist。
  - 若 controller 添加的 `MediaItem` 没有可直接播放的 URI，应通过 `onAddMediaItems()` 做解析。
  - 这类“待解析请求”可由 `MediaItem.id`、`RequestMetadata.mediaUri`、`RequestMetadata.searchQuery`、`MediaMetadata` 来描述。
  - `onSetMediaItems()` 可把“单个请求项”扩展为“整条 playlist”，并指定起播 index/position。

### 5) Background playback with a MediaSessionService | Android media | Android Developers
- 链接: https://developer.android.com/media/media3/session/background-playback
- 可直接支持的点:
  - 只要 `Player` 的 playlist 中存在 `MediaItem`，通知就会创建。
  - 更新现有 item 的 metadata 可用 `replaceMediaItem`，且无需中断播放（与 playlists 页面相互印证）。
  - 播放恢复由 app 自己负责存储 playlist、当前项 metadata、起播位置；也可恢复 playback speed / repeat mode / shuffle mode。
  - 为冷启动/重启后的系统恢复，建议使用本地可用 metadata（title / artwork）。

### 6) Serve content with a MediaLibraryService | Android media | Android Developers
- 链接: https://developer.android.com/media/media3/session/serve-content
- 可直接支持的点:
  - media library 是树结构：单 root + children；节点可 playable 或 browsable。
  - `MediaLibrarySession` 扩展 `MediaSession`，增加 `onGetLibraryRoot/onGetChildren/onGetSearchResult`。
  - media item 可声明 item-scoped command buttons，例如 “add to playlist”。
  - `MediaMetadata.supportedCommands` 可声明某个 `MediaItem` 支持哪些 item command。
  - item 级自定义命令通过 media item ID 进入 session callback 处理。

### 7) Create a basic media player app using Media3 ExoPlayer | Android media | Android Developers
- 链接: https://developer.android.com/media/implement/playback-app
- 可直接支持的点:
  - `MediaSession` 自动与 `Player` 状态同步。
  - Playback / playlist commands 经由 session 自动下发给 player。
  - `MediaLibrarySession` 以树结构服务内容库，并且可按客户端请求返回不同 root / 不同 tree。
  - 同时指出：若允许 controller 增加 media items，应实现 `onAddMediaItems()`。

### 8) Media apps for cars overview | Android for Cars | Android Developers
- 链接: https://developer.android.com/training/cars/media/
- 可直接支持的点:
  - 浏览器看到的是 `MediaItem` 树。
  - `FLAG_PLAYABLE` 表示叶子单条可播放内容；`FLAG_BROWSABLE` 表示有后代的内容节点。
  - 既可 browsable 又可 playable 的 item “operates like a playlist”：既可浏览其后代，也可选择播放其全部后代。

### 9) MediaItem.RequestMetadata | API reference | Android Developers
- 链接: https://developer.android.com/reference/androidx/media3/common/MediaItem.RequestMetadata
- 可直接支持的点（来自搜索结果摘要）:
  - RequestMetadata 是“帮助 player 理解由 MediaItem 表示的 playback request 的元数据”。
  - 尤其适用于请求被转发到其他 player 实例、且发起方并不知道实际播放所需 `LocalConfiguration` 的场景。
  - 公开字段包括 `mediaUri`、`searchQuery`、`extras`。

### 10) MediaSession | API reference | Android Developers
- 链接: https://developer.android.com/reference/androidx/media3/session/MediaSession
- 可直接支持的点（来自搜索结果摘要）:
  - MediaSession 向其他进程/系统暴露 player 功能、playlist 信息和当前播放 item。
  - 一般一个 app 对其全部 playback 只需要一个 session；若要更细粒度控制才需要多个 session。

## 原则提炼草案

### P1. user playlist 与 playback queue 应分离（多页资料推论）
- 依据组合：`Player` 管理 runtime playlist/queue；`MediaLibrarySession` 暴露 browse tree；item 级命令可做 “add to playlist”。
- 建模含义：用户保存的歌单应是库中的持久实体；真正送给 Player 的是一次播放会话的运行时队列。

### P2. Player / MediaSession / MediaLibrarySession 三层职责不要混用（多页资料归纳）
- Player：播放状态与 playlist/queue。
- MediaSession：把外部控制与 player 绑定并对外暴露。
- MediaLibrarySession：额外提供内容浏览/搜索树。

### P3. “一个播放请求”可以解析成“多个队列项”（官方直接支持）
- `onAddMediaItems()` 支持把不可直接播放的请求项解析成可播放项。
- `onSetMediaItems()` 可把单个请求扩展成整条 playlist 并指定起播位置。

### P4. 应显式设置稳定 `mediaId`，不要默认依赖 URI（多页资料推论）
- 官方直接说明：`mediaId` 标识 item；未设置则用 URI 字符串。
- 建模含义：音乐 App 往往需要稳定 ID 去承接 browse、命令、持久化、恢复、URI 轮换等场景。

### P5. playback-facing metadata 放在 `MediaItem`，持久化/恢复状态放在 app storage（多页资料推论）
- 官方直接说明：UI 可随 media-item transition 使用 metadata/tag；恢复需 app 自己存 playlist、当前项 metadata、position 等。
- 建模含义：`MediaItem` 是“当前播放会话投影”，不是唯一持久真相源。

### P6. browse tree 与 queue 是两套模型；browsable/playable 语义要保留（官方直接支持 + 建模推论）
- library 是树；节点可 browsable/playable；有些容器两者皆可并表现得像 playlist。
- 建模含义：目录、专辑、歌单、搜索结果容器不应简单扁平化成同一种 queue item。

### P7. 队列变更优先用 add/replace/move/remove 语义，而不是粗暴重建（官方直接支持）
- Playlist API 明确提供 mutation 操作。
- 当前播放项被 move/remove/replace 时，官方定义了连续播放行为；metadata 更新也可用 `replaceMediaItem`。

### P8. canonical order 与 shuffle/repeat runtime state 应分离（官方事实 + 推论）
- shuffle 打开后实际播放顺序独立于原始索引；当前索引仍映射原始未打乱顺序。
- 建模含义：用户歌单顺序/专辑顺序是持久顺序，shuffle 是会话层覆盖状态。

### P9. “add to playlist” 这类库动作更适合 item-scoped command，而不是 transport control（多页资料推论）
- 官方直接说明 media item command buttons / supportedCommands 可承载 item 级动作。
- 建模含义：用户歌单编辑、收藏、加入队列等动作应区分 item 级库操作与 session/player 级播放控制。

## 本地源码审计：已确认的概念层信号

### 1. 当前播放上下文直接持有 `Playlist`，队列语义由 `Playlist` 派生
- `PlayerRepository` 直接持有 `_playlist: MutableStateFlow<Playlist?>` 与 `_music`，并据此推导 `previousMusic` / `nextMusic` / `onCompleteMusic`。
- `PlayerControllerRepository.play()` 与 `PlaybackService.play()` 都是先加载 `Playlist`，再 `playerRepository.setCurrent(target, playlist)`，最后通过 `playQueueUtil(playlist, targetId, playMode, player)` 把队列灌给 Media3。
- 这说明当前系统没有独立的“播放队列模型”；所谓 queue 只是从 `Playlist` 现算出来的运行态投影。

### 2. 文件夹播放不是临时队列，而是“创建一个真实播放列表后再播放”
- `StorageBrowserVM.clickEntry()` 点击音乐文件时，会走 `playFromFolder()`。
- `playFromFolder()` 会：
  1. 读取父目录歌曲；
  2. 生成 `playlistName = 文件夹播放 - <folder>`；
  3. 若同名列表已存在则先删掉；
  4. 调 `ctCreatePlaylist(...)` 创建真实 playlist；
  5. 再用 `playerControllerRepository.play(musicId, created.id)` 播放。
- 这意味着“浏览目录并播放一首歌”会污染用户歌单空间，而且同名碰撞时会删除原有列表。

### 3. Rust 数据层把曲目顺序挂在 `MusicModel.order` 上，而不是 playlist membership 上
- `ease-client-schema/src/v3/models/music.rs` 中 `MusicModel` 包含 `order`；`PlaylistMusicModel` 只有 `playlist_id` / `music_id`，没有 membership 级别顺序字段。
- `DatabaseServer.load_musics_by_playlist_id()` 读取某个 playlist 的曲目后，按 `music.order` 排序。
- `cts_reorder_music_in_playlist()` 最终调用 `set_music_order(from.meta.id, order)`，修改的也是全局 music 实体。
- 由于 `add_music_impl()` 会按 `StorageEntryLoc` 复用已有 `MusicId`，同一首歌可被多个 playlist 共享同一个 `MusicModel`。因此一个 playlist 中调序，本质上会改这首歌的全局顺序属性，而非该 playlist 中的 membership 顺序。

### 4. 当前实现没有独立的 queue item / play context 身份
- `buildMediaItemInternal()` 直接把 `MediaItem.mediaId` 设为 `music.meta.id`。
- `PlayerRepository` 用 `playlist.musics.indexOfFirst { id == currentId }` 反推出当前 index。
- 这使播放层只能识别“哪首歌”，不能识别“这首歌在当前会话中的哪一个队列项 / 来自哪个上下文”。
- 该问题在“同一首歌出现在多个来源”“未来支持重复添加同一首歌到队列”“想对当前队列项做 remove/move”时会放大。

### 5. `PlaylistRepository` 承担了过多非 playlist 语义责任
- 除了增删改查 playlist，它还负责：metadata probe / duration 回填、播放期 prime、storage 删除联动 reload、pre-remove 事件广播。
- 这不是单纯的‘代码多’，而是表明当前“歌单仓库”同时承担了媒体元数据后台任务与播放链路协同职责，playlist 概念边界偏宽。

### 6. 外部 browse tree 与 app 内 storage/browser 仍是两套世界
- AndroidManifest 当前只声明 `androidx.media3.session.MediaSessionService`，没有 `android.media.browse.MediaBrowserService` action，也没有 `MediaLibraryService`。
- 结合 app 内已有的 storage browser / playlist / dashboard，可见产品已经有“内容树”概念，但系统对外只暴露 playback session，不暴露 library tree。
- 若未来目标包含 Android Auto / Assistant / 系统媒体浏览，当前概念层会出现“app 内可浏览，系统外不可浏览”的断层。

## 官方对照：Android / Media3 一手资料抽出的概念边界

> 检索日期：2026-03-16；来源限定为 `developer.android.com` 官方文档。

### 官方直接可确认的点
1. `Player` 负责管理 `MediaItem` playlist / queue，并提供 `add / move / remove / replace / set / clear` 等显式队列变更语义。
2. `MediaSession.Callback.onSetMediaItems()` 可以把“一个请求项”扩展成整条 playlist，并指定起播 index / position。
3. `MediaItem.mediaId` 是 playlist item 的标识；若不显式设置，默认取 URI。
4. `MediaLibraryService` / `MediaLibrarySession` 暴露的是可浏览内容树（root / children / playable / browsable item），与纯 playback session 是不同能力。
5. 官方示例明确支持 media-item 级 command button，例如 `add_to_playlist`，说明“加入歌单”更接近 library action，而非 transport control。
6. shuffle 后的实际播放顺序可以变化，但 `getCurrentMediaItemIndex()` 仍指向原始 playlist 索引，说明 canonical order 与 runtime order 是两个层次。

### 基于多页官方资料的稳妥推论
1. user playlist（持久化歌单）与 playback queue（本次会话运行态队列）应分离建模。
2. browse item / request item / resolved queue item 不应天然等价；目录、专辑、歌单、搜索结果都可以先是可浏览/可解析对象，再展开成真正队列。
3. 若 App 既有内容浏览又有播放控制，Player / MediaSession / MediaLibrarySession 的职责边界最好显式保留，不要让 Repository 额外长出平行的“当前队列真相源”。

## 2026-03-16 实施阶段新增发现

### 1. Rust v4 schema + backend 已完成 membership order 迁移闭环
- `ease-client-schema` 新增 `v4`，`PlaylistMusicModel` 从单纯 `music_id` 升级为 `{ music_id, order }`。
- `upgrade_v3_to_v4(...)` 会读取 v3 `playlist_music + music.order`，按旧顺序为每个 playlist 生成新的 membership order。
- backend `SCHEMA_VERSION` 已升到 `4`，并串起 `upgrade_v1_to_v2 / upgrade_v2_to_v3 / upgrade_v3_to_v4`。
- `DatabaseServer.load_musics_by_playlist_id()` 现在按 relation.order 返回，并把 relation.order 覆写到返回的 `MusicModel.order`，让上层 playlist 视图拿到的是 membership order，而不是全局顺序。

### 2. folder play 已改成 ensure music + runtime queue，不再创建真实 playlist
- `StorageBrowserVM.playFromFolder()` 不再生成 “文件夹播放 - xxx” 歌单。
- 新流程变成：
  1. 列当前文件夹歌曲；
  2. `ctEnsureMusics(...)` 确保这些歌曲在 DB 中有 `MusicId`；
  3. 继续做 duration request 与 folder prefetch；
  4. 调 `PlayerControllerRepository.playFolder(...)` 构造临时 queue 并播放。
- 这已经与官方“browse item / request item 解析成 runtime queue”思路对齐，不再污染用户歌单空间。

### 3. Android 已形成 queue-first 运行态模型
- 新增 `PlaybackContext / PlaybackQueueEntry / PlaybackQueueSnapshot / PlaybackSessionStore`。
- `PlayerRepository` 不再只从 `Playlist` 推导前后曲，而是直接维护 runtime queue、current queue entry 与 remove action。
- `MusicPlayerUtil` 已支持基于 `PlaybackQueueSnapshot` 生成真正的 Media3 queue，并把 `MediaItem.mediaId` 切到 queueEntryId。

### 4. 实施中暴露出的关键运行态问题与已落地修法
- 问题 A：`_queue.currentQueueEntryId` 与 `_currentQueueEntryId` 分裂，导致删除当前项/刷新 playlist 可能命中旧项。
  - 修法：`PlayerRepository.setPlaybackSession / updatePlaybackQueue / updateCurrentQueueEntry` 统一同步 snapshot currentQueueEntryId。
- 问题 B：边界 next/previous fallback 会重播当前项。
  - 修法：在 `PlayerControllerRepository` 与 `PlaybackService` 中显式对首尾边界做 no-op，只有“命令不可用但目标仍存在”时才走 fallback 重建。
- 问题 C：新播放请求失败时会清空 repo/session，但旧 player 可能继续播放。
  - 修法：失败分支统一 stop/clear 当前 player，再重置 repo 与 session store。
- 问题 D：切歌后立即持久化 session 可能把“旧 queueEntryId + 新 playWhenReady/position”写回磁盘。
  - 修法：seek 到相邻项时，persist 接口允许显式传入 target queueEntryId override。

### 5. 额外实现细节
- `PlayerControllerRepository.playFolder()` 现在按 target `musicId` 匹配临时 queue 项，而不是仅靠输入列表索引，避免 ensure/abstract 构造过程中发生过滤时误选目标。
- `StorageBrowserVM.clickEntry()` 已去掉不再需要的 “folder playlist prefix / rootName” 参数链。
- 原先只用于全局 `MusicModel.order` 的 backend 内部 `set_music_order(...)` 已删除，避免旧语义继续滞留。

### 6. 当前仍应注意的一个边界风险
- playlist queue 的 `queueEntryId` 目前对“同一 playlist 中的重复曲目 occurrence”仍不是独立主键；本轮修复已防住核心语义回归，但若产品未来要正式支持“同歌多次出现且按 occurrence 精确操作”，需要引入 membership-level stable identity，而不只是 `playlistId + musicId` 级别标识。
