# Repository Guidelines

## 仓库定位与真相源
- 本仓库不是“纯 Android App”，而是 **Android 前端 + Rust backend + UniFFI/JNI/JNA** 的混合工程。Android 端负责 UI、播放控制与系统集成，核心业务与存储能力大量由 Rust 提供。
- 处理复杂改动时，优先按以下顺序找真相源：
  1. `rust-libs/Cargo.toml` 与各 crate `Cargo.toml`
  2. `android/app/build.gradle.kts`、`android/settings.gradle.kts`
  3. `package.json` 与 `scripts/`
  4. `.github/workflows/release.yml`
  5. `docs/android-validation-env.md`、`docs/playback-second-wave.md`
- `report.md` 是静态 UI 审查记录，不等于当前实机验证结果；`task_plan.md`、`findings.md`、`progress.md` 是历史任务产物，不是产品规范。
- 如果文档、脚本、CI 与当前源码冲突，以当前源码和可执行脚本为准，并在同轮补齐/修正文档。

## 项目结构与模块组织

### 根目录
- `android/`：单模块 Android 应用工程，只有 `:app`。
- `rust-libs/`：Rust workspace，包含 backend、schema、storage、FFI builder 等 crate。
- `scripts/`：Bun + TypeScript 自动化脚本，负责 JNI 生成、APK 打包、Android smoke、mock server、环境检查。
- `docs/`：环境与方案说明文档。
- `artifacts/`：构建与 smoke 产物目录，属于生成结果，不是源码。

### Android 目录
- `android/app/src/main/java/com/kutedev/easemusicplayer/`
  - `core/`：播放、DataSource、缓存、Prefetch、Service、路由等核心运行层
  - `singleton/`：`Bridge` 与各类 repository/application-scope 状态
  - `viewmodels/`：Hilt ViewModel
  - `widgets/`：页面级 Compose UI
  - `components/`：可复用 Compose 组件
  - `ui/theme/`：主题系统
  - `utils/`：工具函数
- `android/app/src/main/java/uniffi/`：**生成的 Kotlin 绑定**，不要手改。
- `android/app/src/main/jniLibs/arm64-v8a/`：**生成的 JNI so**，不要手改。
- `android/app/src/debug/`：debug-only receiver 与 smoke 注入逻辑。
- `android/app/src/test/`：JVM/Robolectric 单测。
- `android/app/src/androidTest/`：设备/模拟器 instrumentation 与 Compose UI 测试。

### Rust workspace
- `ease-client-backend`：主 backend crate，输出 `cdylib` + `rlib`，是 Android FFI 的核心来源。
- `ease-client-android-ffi-builder`：UniFFI Kotlin 绑定生成器。
- `ease-client-schema`：schema、升级与持久化模型。
- `ease-remote-storage`：Local / OpenList / OneDrive / WebDAV 等远端/本地存储实现。
- `ease-client-tokio`：tokio runtime 支撑。
- `ease-order-key`：独立排序键 crate。
- **不要假设存在 `rust-libs/ease-client/` 这种总目录**；Rust workspace 的真实成员以 `rust-libs/Cargo.toml` 为准。

### Scripts / Docs
- `scripts/doctor-android-env.sh`：检查 JDK 21、Android SDK/NDK、Rust target、cargo-ndk、Bun 等前置环境。
- `scripts/build-jni-libs.ts`：构建 Rust 宿主库、生成 UniFFI Kotlin 绑定、生成 `jniLibs`。
- `scripts/build-apk.ts`：生成临时签名文件并构建/收集 release APK。
- `scripts/mock-playback-server.ts`：本地 mock playback server，服务于 Android smoke。
- `scripts/smoke-android.ts`：通过 `adb` + debug broadcast 跑端到端播放路由 smoke。
- `docs/android-validation-env.md`：Android 本地验证环境说明。
- `docs/playback-second-wave.md`：当前播放路由、debug smoke、真机验证闭环说明。

## 架构与跨层契约

### Android 关键入口
- `MainActivity.kt`：不仅是 UI 容器，还负责：
  - 启动 `KeepBackendService`
  - 初始化 `Bridge`
  - 建立 `MediaController`
  - 做 repository 初始 reload
  - 处理 OAuth 回调 `easem://oauth2redirect`
- `Root.kt`：Compose 根节点与导航入口。
- `core/MusicPlayer.kt`：定义 `PlaybackService : MediaSessionService`，承接真正的播放服务。
- `singleton/Bridge.kt`：Android ↔ Rust backend 的关键边界。

### FFI / JNI 契约
- Rust backend 改动如果影响导出函数、对象、类型、错误、播放描述符、ABI 输出，必须同步运行：
  - `bun run build:jni`
- **禁止手改**：
  - `android/app/src/main/java/uniffi/**`
  - `android/app/src/main/jniLibs/**`
- 这些目录虽然位于源码树中，但本质上是生成物；正确做法是改 Rust / FFI builder / 生成脚本，然后重新生成。

### 播放路由契约
- Android 上层统一播放入口仍是：
  - `ease://data?music=<id>`
- 当前播放路由由 `MusicPlaybackDataSource.kt` 在 `open()` 阶段解析，路由语义为：
  - `DIRECT_HTTP`
  - `LOCAL_FILE`
  - `STREAM_FALLBACK`
- `PlaybackDiagnostics` 会记录路由结果，debug smoke 会用它断言，因此：
  - 不能只验证“能播出声音”
  - 改 resolver、DataSource、cache、prefetch、fallback、播放 URI、source tag 时必须做路由级验证

### 调试广播与脚本耦合契约
- 当前硬编码耦合项包括：
  - 包名：`com.kutedev.easemusicplayer`
  - 启动 Activity：`com.kutedev.easemusicplayer.MainActivity`
  - debug receiver：`com.kutedev.easemusicplayer.debug.DebugSmokeReceiver`
  - debug action：`com.kutedev.easemusicplayer.debug.SMOKE`
  - OAuth scheme/host：`easem://oauth2redirect`
- 如果修改以上任一项，必须同步更新：
  - `AndroidManifest.xml`
  - `android/app/src/debug/**`
  - `scripts/smoke-android.ts`

### ABI / 设备支持契约
- 当前只支持 `arm64-v8a`：
  - `scripts/base.ts` 中 `TARGETS = ["arm64-v8a"]`
  - `android/app/build.gradle.kts` 的 ABI split 也只包含 `arm64-v8a`
- 默认 debug APK 为：
  - `android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- **不要默认把 x86/x86_64 模拟器当成支持目标**；若要扩 ABI，需同时改脚本、Gradle 配置与相关安装/验证预期。

## 构建、测试与开发命令

### 前置与环境检查
- `bun install`：安装脚本依赖。
- `bun run doctor:android-env`：检查 Java 21、Android SDK/NDK、Rust target、cargo-ndk、Bun。
- 如果 Android / JNI 命令异常，先检查环境而不是先怪业务代码；本仓库对 JDK、NDK、Rust target 有明确前置要求。

### JNI / FFI 生成
- `bun run build:jni`
  - 先 `cargo build -p ease-client-backend --lib`
  - 再通过 `ease-client-android-ffi-builder` 生成 Kotlin UniFFI 绑定
  - 再通过 `cargo ndk` 生成 `jniLibs`
- 适用场景：
  - 新机器/新环境首次构建
  - 改 Rust FFI
  - 改 backend 对 Android 暴露的数据结构/对象/接口
  - 清理工作树后重新验证 JNI 产物

### Android 构建与测试
- `cd android && ./gradlew testDebugUnitTest`
  - 跑 JVM / Robolectric 单测
- `cd android && ./gradlew :app:assembleDebug --warning-mode all`
  - 生成 debug APK
- `cd android && ./gradlew connectedDebugAndroidTest`
  - 跑设备/模拟器 instrumentation 测试
- `cd android && ./gradlew :app:processReleaseMainManifest`
  - 适用于验证 release merged manifest 边界，例如 debug-only receiver 是否泄漏进 release

### Rust 测试
- 首选：`bun run test`
  - 实际执行 `cd ./rust-libs && cargo nextest run`
  - **前提：本机已安装 `cargo-nextest`；否则该命令会直接失败**
- 单 crate 调试：
  - `cd rust-libs && cargo test -p ease-remote-storage`
  - `cd rust-libs && cargo test -p ease-client-schema`
- 当机器上尚未安装 `cargo-nextest` 时，优先用单 crate `cargo test` 做最小回归，并明确说明缺少 nextest 是环境问题而不是仓库问题。

### Android 端到端 smoke
- `bun run smoke:android --device=<adb-serial> --apk=android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- 测试真机使用 adb 无线调试串号：`172.26.121.48:34327`
- 该脚本会：
  - 安装 debug APK
  - 清应用数据
  - 授权音频/通知权限
  - 启动 `MainActivity`
  - 启动 mock playback server
  - 通过 debug broadcast 注入场景
  - 校验至少三类主播放路由：
    - Local -> `LOCAL_FILE`
    - OpenList -> `DIRECT_HTTP`
    - WebDAV -> `DIRECT_HTTP`（当前匿名 smoke 场景）
  - 并对扩展信号做强断言：
    - `next-prefetch` source tag 已出现
    - 当前曲目 metadata duration 已回填
    - 下一首曲目 metadata duration 已回填

### Release 打包
- `bun run build:apk`
  - 依赖环境变量：
    - `ANDROID_SIGN_JKS`
    - `ANDROID_SIGN_PASSWORD`
  - 会先执行 `build:jni`
  - 再执行 `./gradlew assembleRelease --info`
  - 再把 APK 复制/重命名到 `artifacts/apk/`

## 按改动类型选择验证方式
- **Rust 存储/backend/schema 改动**
  - 至少跑相关 crate 测试
  - 若影响 Android 暴露接口，再跑 `bun run build:jni`
- **FFI/UniFFI/JNI 改动**
  - 必跑：`bun run build:jni`
  - 建议再跑：`cd android && ./gradlew testDebugUnitTest :app:assembleDebug --warning-mode all`
- **播放路由 / DataSource / cache / prefetch / manifest / 包名 / debug receiver 改动**
  - 必跑：`testDebugUnitTest`、`assembleDebug`
  - 强烈建议跑：`bun run smoke:android`
- **Compose UI / theme / ViewModel 改动**
  - 至少跑：`testDebugUnitTest`、`assembleDebug`
  - 涉及已有 UI 测试覆盖的页面时，补跑 `connectedDebugAndroidTest`
- **脚本 / release / 签名 / workflow 改动**
  - 除本地脚本验证外，必须联查 `.github/workflows/release.yml`
  - 不要只验证本地，不看 CI

## 编码风格与命名约定
- Rust：
  - 默认 `rustfmt`
  - 建议在 `rust-libs/` 下运行 `cargo fmt`
  - `clippy` 配置见 `rust-libs/clippy.toml`
- Kotlin / Gradle：
  - 4 空格缩进
  - 类型用 PascalCase，变量/函数用 camelCase
  - 包名保持 `com.kutedev.easemusicplayer`
- TypeScript：
  - 2 空格缩进
  - 保持现有脚本风格，优先复用 `scripts/base.ts` 与现有脚本入口，不要重复造轮子
- Markdown / docs：
  - 以准确、可执行、路径清晰为先
  - 涉及命令/路径/构建产物时给出真实相对路径，不写抽象表述

## 测试与调试注意事项
- JVM unit test 默认**不会加载 UniFFI native lib**；可被 `src/test` 覆盖的核心代码不要直接依赖 native logging / native side effect，参考现有 `safeEaseLog()` / `safeEaseError()` 的容错模式。
- `lint` 当前不是硬门禁：`android { lint { abortOnError = false } }`
  - 不要把 “lint 绿了” 当成完整质量证明
- `assembleDebug` 绿也不等于：
  - debug smoke 绿
  - release 边界安全
  - 真机播放路径正确

## 配置、安全与常见坑
- 不要提交：
  - 签名文件
  - `android/key.properties`
  - `android/root.jks`
  - 任何密钥或令牌
- `scripts/build-apk.ts` 会生成临时签名文件；构建成功不代表这些文件应该进入版本控制。
- Manifest 中以下配置都带业务含义，不能随手删改：
  - `android:usesCleartextTraffic="true"`
  - `MainActivity` 的 `launchMode="singleInstance"`
  - OAuth redirect (`easem://oauth2redirect`)
  - 媒体播放前台服务权限
  - `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE(maxSdkVersion=32)`
- 版本升级时不要只改一处：
  - `android/gradle/libs.versions.toml`
  - `android/app/build.gradle.kts`
  两边都可能存在版本钉死项。
- 处理 Rust/Android 联动问题时，优先把“真实失败点”分清楚：
  - 环境缺失
  - FFI 生成物过期
  - playback route 逻辑错误
  - debug smoke / mock server 协议不兼容

## 提交与 PR 规范
- 提交信息采用 Conventional Commits：
  - `feat: ...`
  - `fix: ...`
  - `refactor: ...`
  - `docs: ...`
- PR 需包含：
  - 变更说明
  - 影响范围（Android / Rust / FFI / scripts / CI）
  - 实际跑过的命令与结果
  - 涉及 UI 时附截图/录屏
  - 涉及播放链路时优先附 smoke 结果摘要或产物目录
  - 涉及构建/签名/发布流程时注明环境变量与安全边界是否受影响
