# ListenToList playlist / queue 语义修复计划（官方最佳实践对照 + 实施）

## 目标

先基于 Android 官方一手资料确认 Media3 / ExoPlayer 关于 playlist、queue、media item、MediaSession、MediaLibrary 的最佳实践，
再把结论落实到当前仓库，实现并验证以下修复：
- 文件夹播放改为临时 runtime queue，不再创建真实歌单
- playlist 内顺序从全局 `MusicModel.order` 迁到 membership relation
- 播放页删除动作按上下文分流（歌单成员 vs 当前临时队列项）
- 临时 queue 跨重启恢复
- 播放运行态以 queue-first 模型驱动，并补齐关键验证

## 当前阶段

Phase 6

## 阶段

### Phase 1: 边界确认与上下文准备
- [x] 读取 AGENTS.md 与用户约束
- [x] 读取 planning-with-files skill 说明
- [x] 执行轻量 memory quick pass，确认 ListenToList 最近已有 queue 语义背景
- [x] 将本轮目标重写到 planning files
- **Status:** complete

### Phase 2: 官方资料收集
- [x] 仅检索 Android 官方一手资料
- [x] 收集 Media3 / ExoPlayer 关于 Player playlist API、MediaItem、MediaSession、MediaLibraryService、browse tree 的原始页面
- [x] 记录每页能直接支持的概念结论
- **Status:** complete

### Phase 3: 原则提炼
- [x] 提炼 6-10 条概念建模原则
- [x] 区分“官方直接表述”与“跨文档推论”
- [x] 为每条原则整理页面标题与链接
- **Status:** complete

### Phase 4: 交付整理
- [x] 复核只引用官方来源
- [x] 输出中文结论，明确证据边界与推论边界
- **Status:** complete

### Phase 5: 概念对照后的实现设计冻结
- [x] 与用户确认文件夹播放=临时 queue、membership order 正式迁移、删除按上下文、临时 queue 要恢复
- [x] 将实现切到 queue-first 方案，而不是继续扩展“playlist 即播放上下文”的旧模型
- **Status:** complete

### Phase 6: 逐项修复与验证
- [x] Rust schema 升级到 v4，并把 playlist membership order 迁移到关系层
- [x] backend 改为按 membership order 读取/重排 playlist，并新增 ensure musics 能力供 folder queue 使用
- [x] Android 引入 runtime queue / session persistence / queue-first controller
- [x] 文件夹播放改为临时 queue，不再创建“文件夹播放-xxx”真实歌单
- [x] 播放页删除改为按上下文分流
- [x] 修复 queue 当前项状态分裂、边界 next/previous 回播当前项、失败播放遗留旧 player 等运行态问题
- [x] 跑 Rust 单测、Android unit test、assembleDebug、JNI 构建、真机 smoke
- **Status:** complete

## 关键问题
1. 如何把“用户歌单”和“播放运行时队列”彻底拆开，不再用 `Playlist` 直接充当当前播放上下文？
2. 如何在保留现有数据的前提下，把 playlist 顺序迁到 membership relation？
3. 文件夹播放怎样改成临时 queue，同时不破坏 metadata probe / prefetch / smoke 路径？
4. 当前项删除、边界切歌、恢复与 playlist 刷新，怎样避免 queue 状态分裂？

## 已做决策
| Decision | Rationale |
|----------|-----------|
| 官方资料只用一手来源 | 用户明确要求对照官方最佳实践 |
| 文件夹播放改为临时 runtime queue，不创建真实歌单 | 用户已明确锁定产品语义 |
| playlist 内顺序迁到 membership relation，并走正式迁移 | 避免全局 `MusicModel.order` 污染多个歌单 |
| 删除当前项按上下文分流 | 用户已明确锁定产品语义 |
| 临时 queue 需要跨重启恢复 | 用户已明确锁定产品语义 |

## 错误记录
| Error | Attempt | Resolution |
|-------|---------|------------|
| 既有 planning files 仍偏向“仓库概念审计”而非“官方资料调研” | 1 | 重写为本轮仅官方一手资料研究计划 |
| Rust v4 upgrader 新增测试初次失败（作用域/借用/断言顺序） | 1 | 修正 `super::upgrade_v3_to_v4(...)` 调用、缩小表借用作用域、按 membership order 排序后断言 |
| `PlaybackSessionStoreTest` 初次失败（`ApplicationProvider` / `commit()` 未解析） | 1 | 改为 Robolectric `RuntimeEnvironment.getApplication()`，使用 `apply()` 清空 prefs |
| queue-first Android 改造后存在当前项状态分裂与边界切歌回播当前项风险 | 1 | 同步 `_queue.currentQueueEntryId` 与 `_currentQueueEntryId`，并修正 next/previous fallback 逻辑 |

## 备注
- 外部网页内容一律写入 `findings.md`，不把网页原文塞进 `task_plan.md`。
- 本轮已从“只研究”推进到“按已确认方案实施修复并验证”；后续若继续扩展，应以当前源码与验证结果为准。
