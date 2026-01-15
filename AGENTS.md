# Repository Guidelines

## 项目结构与模块组织
- `android/`：Android 应用（Jetpack Compose），`android/app/src/main/java` 为 Kotlin 源码，`android/app/src/main/jniLibs` 为 JNI 产物，资源在 `android/app/src/main/res`。
- `rust-libs/`：Rust workspace，`ease-client-*` 为核心库与 FFI 相关代码；测试位于各 crate 的 `tests/` 或 `#[cfg(test)]` 模块中。
- `scripts/`：Bun + TypeScript 构建脚本；`docs/` 为文档；`artifacts/` 为构建输出目录（由脚本生成）。

## 构建、测试与开发命令
- `bun install`：安装脚本依赖。
- `bun run build:jni`：构建 Rust 后端并生成 Kotlin 绑定与 JNI 库（需要 `cargo-ndk` 与 Android NDK；目标为 `arm64-v8a`）。
- `bun run build:apk`：打包 release APK，依赖 `ANDROID_SIGN_JKS`（Base64+Brotli 的 jks 内容）与 `ANDROID_SIGN_PASSWORD`。
- `bun run test`：在 `rust-libs/` 内运行 `cargo nextest`。

## 编码风格与命名约定
- Rust 代码遵循 `rustfmt` 默认风格，建议在 `rust-libs/` 下运行 `cargo fmt`；`clippy` 配置见 `rust-libs/clippy.toml`。
- Kotlin/Gradle 使用 4 空格缩进，类型用 PascalCase、变量用 camelCase；包名保持 `com.kutedev.easemusicplayer`。
- TypeScript 脚本采用 2 空格缩进，文件名以 `build-*.ts` 为主。

## 测试指南
- Rust 测试分布在各 crate 的 `tests/` 目录或 `#[cfg(test)]` 模块中。
- 推荐优先跑 `bun run test`；单 crate 调试可在 `rust-libs/` 下运行 `cargo test -p <crate>`。

## 提交与 PR 规范
- 提交信息采用 Conventional Commits：`feat: ...`、`fix: ...`、`refactor: ...`、`doc: ...`，可附 `[#issue]`。
- PR 需包含变更说明、测试结果；涉及 UI 请附截图/录屏；如影响构建或签名流程需注明。

## 配置与安全提示
- 不要提交签名文件、`key.properties` 或任何密钥；本地构建 APK 前先准备环境变量与 NDK。
- Rust 与 Android 通过 JNI 交互，修改 FFI 接口后请同步更新生成的 Kotlin 代码。
