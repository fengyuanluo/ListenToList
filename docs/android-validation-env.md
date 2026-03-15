# Android 本地验证环境

本文档记录 `ListenToList` 当前已验证可用的 Android 本地验证基线，以及在新机器上复现所需的最小检查项。

## 当前基线

- JDK: `21`
- `JAVA_HOME`: `/opt/jdk-21`
- Android SDK: `/root/Android/Sdk`
- Android NDK: `27.2.12479018`
- Rust target: `aarch64-linux-android`
- `cargo-ndk`: 已安装
- Bun: 已安装，可直接运行 `bun run ...`

## 快速检查

在仓库根目录执行：

```bash
bash ./scripts/doctor-android-env.sh
```

若输出 `[doctor:android-env] OK`，说明本机已具备：

- Java 21
- Bun
- Cargo / rustup
- Android SDK + NDK
- `aarch64-linux-android`
- `cargo-ndk`

## 可重复生成 UniFFI 绑定与 JNI 库

首次或切换环境后建议先安装脚本依赖：

```bash
bun install
```

然后在仓库根目录执行：

```bash
bun run build:jni
```

该命令会：

1. 构建 `ease-client-backend` 宿主库
2. 生成 Kotlin UniFFI 绑定到 `android/app/src/main/java/uniffi/`
3. 生成 JNI 库到 `android/app/src/main/jniLibs/`

上述生成目录已加入 `.gitignore`，因此重复运行不会污染工作树。

## Android 编译验证

在生成 UniFFI 绑定后，可运行：

```bash
cd android
./gradlew :app:compileDebugKotlin
```

如果还要继续跑 JVM 单测，请确保启动 Gradle 的 JDK 也是 21；否则会在 `compileDebugJavaWithJavac` 阶段遇到 `invalid source release: 21`。
