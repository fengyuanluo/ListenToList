# UI 逐页审查报告

## 说明
- 范围：基于代码静态审查，未做真机/自动化实测。
- 输出格式：每条包含问题类型、表现/影响、建议方向，并标注代码定位。

## Root（全局容器/导航）
- [国际化/可用性] 强制使用 LayoutDirection.Ltr 计算左右内边距，RTL 语言会布局错位。建议改用 LocalLayoutDirection。android/app/src/main/java/com/kutedev/easemusicplayer/Root.kt:75
- [交互体验] 前进与返回动画方向一致，返回时仍从右滑入/向左滑出，方向感不直观。建议 pop 动画反向。android/app/src/main/java/com/kutedev/easemusicplayer/Root.kt:89

## HomePage（首页容器）
- [易用性] HorizontalPager 状态未保存，旋转或进程重建会回到首页第一页。建议 rememberSaveable 保存页索引。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/home/Page.kt:28

## BottomBar（底部栏）
- [适配/交互] 底部栏与迷你播放器高度固定（60/124dp），未结合系统手势导航/安全区，可能遮挡内容。建议使用 WindowInsets。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/appbar/BottomBar.kt:67
- [可访问性] 底栏图标无 contentDescription，屏幕阅读器无法识别。建议补充语义标签。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/appbar/BottomBar.kt:159
- [可读性] 未选中态颜色使用 surfaceVariant，在浅色主题下对比度偏低。建议使用 onSurfaceVariant 或提高对比度。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/appbar/BottomBar.kt:141

## MiniPlayer（迷你播放器）
- [交互] 整行可点击跳转播放页，按钮区域也位于该行内，易误触进入播放页。建议将点击区域限制为封面/标题区或增加手势区分。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/MiniPlayer.kt:61
- [样式/可读性] 进度条未设置背景轨道颜色，低对比主题下可视性弱。建议设置 trackColor。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/MiniPlayer.kt:130

## Dashboard（仪表盘）
- [性能/交互] 外层 Column 与设备列表内部 Column 同时 verticalScroll，嵌套滚动易导致手势冲突且影响性能。建议内部改 LazyColumn 或由外层统一滚动。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/dashboard/Page.kt:88
- [交互] 设备行整体可点击进入浏览，同时右侧齿轮按钮进入编辑，点击范围相邻易误触。建议分离点击区域或增加按钮间距。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/dashboard/Page.kt:122

## TimeToPause（睡眠定时弹窗）
- [易用性/可访问性] 仅提供拖拽选择时间，无输入框/加减按钮，精细调整困难且读屏不可用。建议增加可编辑文本或步进按钮。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/dashboard/TimeToPause.kt:140

## Playlists（播放列表页）
- [易用性] 空状态卡片整体可点击但缺少明确按钮/文案提示“创建”，易错过入口。建议增加显式按钮。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/playlists/PlaylistsPage.kt:66
- [适配] 网格列固定宽度 172dp，小屏可能出现单列且间距过大/列表拥挤。建议使用自适应列数。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/playlists/PlaylistsPage.kt:154
- [交互] 拖拽排序时未利用 isDragging 给出视觉反馈，用户难以确认拖拽状态。建议加阴影/缩放/透明度变化。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/playlists/PlaylistsPage.kt:159

## PlaylistPage（播放列表详情）
- [易用性] 空列表区域设置可点击但无任何行为，属于“死点击”。建议移除点击或提供导入/添加入口。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/playlists/PlaylistPage.kt:246
- [交互] 行内点击播放与横向滑动删除共用区域，滑动易误触播放。建议区分手势或增设删除入口。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/playlists/PlaylistPage.kt:337
- [易用性] 删除按钮面板宽度 48dp，触控目标偏小，误触率高。建议扩大面板或增加确认。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/playlists/PlaylistPage.kt:399
- [适配] 播放按钮通过固定偏移定位，窄屏/大字体可能与标题区重叠。建议用布局约束替代固定偏移。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/playlists/PlaylistPage.kt:499

## PlaylistDialog（创建/编辑播放列表弹窗）
- [适配] 弹窗内容未滚动，内容较多时小屏易被遮挡。建议增加 verticalScroll。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/playlists/PlaylistDialog.kt:211
- [样式] Tab 非激活态使用 Color.Unspecified，在深色主题下可能对比不足。建议明确颜色。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/playlists/PlaylistDialog.kt:69

## StorageBrowser（存储浏览）
- [易用性] 错误态整体卡片点击重试/授权，缺少明确按钮和动作文案，易误触。建议提供按钮。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/devices/StorageBrowserPage.kt:101
- [可用性] 面包屑使用横向滚动 + 小字号，无当前路径强调，定位较费力。建议高亮当前路径并提升字号/留白。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/devices/StorageBrowserPage.kt:297
- [交互] 选择模式切换仅有图标，无文字提示与状态说明，学习成本高。建议加提示或状态条。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/devices/StorageBrowserPage.kt:420

## EditStorages（设备编辑）
- [易用性] 存储类型卡片尺寸固定且选中态弱，初次使用难以分辨当前类型。建议强化选中态与说明文案。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/devices/EditStorage.kt:131

## ImportPage（导入页）
- [可读性] 面包屑字体多处 10sp，弱视与小屏阅读压力大。建议统一提升字号或允许缩放。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/ImportPage.kt:215
- [交互] 行点击与复选框同一触控区域，易误选。建议分离点击区域或增加复选框点击优先。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/ImportPage.kt:146

## MusicPlayer（播放页）
- [稳定性] 进度条计算使用 sliderWidth 作为除数，初始为 0 时可能出现 NaN/Infinity。建议在宽度为 0 时短路处理。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/PlayerPage.kt:199
- [交互] 全屏点击切换歌词/封面，与横向滑动切歌、歌词纵向滚动手势冲突，易误切。建议限定点击区域或增加按钮。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/musics/PlayerPage.kt:487

## SettingSubpage（设置页）
- [易用性] 版本项保留点击态但无任何动作，用户易困惑。建议移除点击或显示复制版本功能。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/settings/Page.kt:159
- [可访问性] 列表图标 contentDescription 为空，读屏无法识别。建议补充语义。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/settings/Page.kt:85

## LogPage（日志页）
- [易用性] 点击日志直接外部打开，无预览/复制/分享入口，排查成本高。建议提供本页预览与分享按钮。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/settings/LogPage.kt:69

## DebugMorePage（调试页）
- [安全/易用性] 触发崩溃/异常操作无二次确认，易误触导致崩溃。建议加确认弹窗。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/settings/DebugMorePage.kt:60

## ThemeSettings（主题设置）
- [易用性] HSV 三个滑条缺少数值显示，难以精确调色与复现。建议显示当前数值或提供输入框。android/app/src/main/java/com/kutedev/easemusicplayer/widgets/settings/ThemeSection.kt:224
