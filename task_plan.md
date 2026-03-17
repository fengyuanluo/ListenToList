# ListenToList 播放列表全链路代码审阅计划（2026-03-17）

## 目标
对 `/root/Coding/General/ListenToList` 中**所有播放列表相关代码**做一次系统级审阅与分析，覆盖：
1. 持久播放列表（创建、删除、重命名、增删曲目、排序）
2. 运行时播放队列（queue）与持久歌单的边界
3. 拖动排序 / reorder 的 UI、ViewModel、Repository、Rust schema 与持久化路径
4. 切歌语义（播放指定项、下一首、上一首、跳转到队列项、删除当前项后的行为）
5. folder play / search add-to-queue / playlist play 等不同入口如何落到统一播放语义
6. Android / Rust / FFI / playback service / session restore / diagnostics 的契约一致性
7. 找出当前实现中的关键设计点、风险点、潜在 bug 与后续排查入口

## 当前阶段
Phase 5

## 阶段
### Phase 1: 范围界定与代码地图
- [x] 读取相关 memory 与当前 planning 上下文
- [x] 全仓搜索 playlist / queue / reorder / drag / next / previous / remove-from-queue 等关键词
- [x] 建立 Android / Rust / scripts 侧的相关文件清单
- **Status:** complete

### Phase 2: Rust 与数据模型审阅
- [x] 审阅 schema、playlist membership、migration、queue snapshot、session store
- [x] 审阅 backend 中 playlist / queue / folder playback / seek 邻接切歌实现
- [x] 记录 playlist 与 queue 的真实边界、顺序真相源与恢复语义
- **Status:** complete

### Phase 3: Android 与交互链路审阅
- [x] 审阅 playlist 页面、拖动排序、播放入口、队列页面/组件、PlayerVM/Repository
- [x] 审阅 Media3 / PlaybackService / controller 与 queue snapshot 对接
- [x] 审阅从 UI 到 Rust FFI 的调用路径
- **Status:** complete

### Phase 4: 交叉验证与风险评审
- [x] 对照 memory 与当前源码，确认哪些旧结论已落地、哪些可能漂移
- [x] 形成“功能链路图 + 风险清单 + 排查建议”
- [x] 已核对本轮使用的 memory 结论与当前源码一致，暂未发现需要修正的 stale memory
- **Status:** complete

### Phase 5: 输出结论
- [ ] 产出面向用户的系统级中文审阅报告
- [ ] 附关键文件路径、核心机制、潜在问题、建议阅读顺序
- **Status:** in_progress

## 已确认决策
| Decision | Rationale |
|----------|-----------|
| 本轮以“代码审阅/架构分析”为主，不默认改业务代码 | 用户要求是深入研究分析与审阅，而非直接修复 |
| playlist 与 runtime queue 必须分开审视 | 仓库近期明显做过 queue 语义重构，若混为一谈会误判 |
| Android / Rust / FFI / Service 要一起审 | 播放列表问题经常不是单层 bug，而是跨层契约漂移 |
| 除功能行为外，重点看 reorder 与 next/previous 边界 | 用户明确点名“拖动，切歌等全部内容” |

## 风险与注意事项
1. 仓库近期经历过 queue-first 重构，旧 mental model 很可能已失效。
2. `playlist`、`queue`、`folder playback` 在 UI 文案上可能相近，但实现语义并不等价。
3. 不能只看 Android 页面；顺序真相源和恢复语义很可能在 Rust schema/backend。
4. 若 memory 与当前源码冲突，必须以当前源码为准并同步修正 memory。

## 错误记录
| Error | Attempt | Resolution |
|-------|---------|------------|
| 当前 planning files 仍是上一轮 UI 改造内容 | 1 | 已重写为本轮播放列表全链路审阅计划 |
