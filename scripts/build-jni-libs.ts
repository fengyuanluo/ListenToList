import { execSync } from "node:child_process";
import { ROOT, RUST_LIBS_ROOTS, TARGETS } from "./base";
import path from "node:path";
import { existsSync } from "node:fs";

const HOST_LIB_NAME = (() => {
  switch (process.platform) {
    case "darwin":
      return "libease_client_backend.dylib";
    case "win32":
      return "ease_client_backend.dll";
    default:
      return "libease_client_backend.so";
  }
})();
const hostLibPath = path.resolve(RUST_LIBS_ROOTS, "target/debug", HOST_LIB_NAME);

console.log("Build ease-client in debug mode");
execSync(`cargo build -p ease-client-backend --lib`, {
  stdio: "inherit",
  cwd: RUST_LIBS_ROOTS,
});
if (!existsSync(hostLibPath)) {
  throw new Error(`未找到生成的宿主库文件: ${hostLibPath}`);
}

for (const buildTarget of TARGETS) {
  console.log(`Generate kotlin file of ${buildTarget}`);
  execSync(
    `cargo run -p ease-client-android-ffi-builder generate --no-format --library ${hostLibPath} --language kotlin --out-dir ${path.resolve(ROOT, "android/app/src/main/java/")}`,
    {
      stdio: "inherit",
      cwd: RUST_LIBS_ROOTS,
      env: {
        ...process.env,
        RUST_BACKTRACE: "1",
        CARGO_NDK_ANDROID_PLATFORM: "34",
      },
    },
  );

  console.log(`Generate jniLibs of ${buildTarget}`);
  execSync(
    `cargo ndk --target ${buildTarget} -o ${path.resolve(ROOT, "android/app/src/main/jniLibs")} build --release --lib -p ease-client-backend`,
    {
      stdio: "inherit",
      cwd: RUST_LIBS_ROOTS,
      env: {
        ...process.env,
        RUST_BACKTRACE: "1",
        CARGO_NDK_ANDROID_PLATFORM: "34",
      },
    },
  );
}
