#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "[doctor:android-env] ERROR: $*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少命令: $1"
}

need_cmd java
need_cmd javac
need_cmd cargo
need_cmd rustup
need_cmd bun

JAVA_BIN="$(command -v java)"
JAVAC_BIN="$(command -v javac)"
BUN_BIN="$(command -v bun)"
CARGO_BIN="$(command -v cargo)"

JAVA_MAJOR="$(java -version 2>&1 | awk -F '\"' 'NR==1{print $2}' | cut -d. -f1)"
[ "${JAVA_MAJOR:-0}" -ge 21 ] || fail "当前 java 版本不是 21+: $(java -version 2>&1 | head -n 1)"

[ -n "${JAVA_HOME:-}" ] || fail "JAVA_HOME 未设置"
[ -x "${JAVA_HOME}/bin/java" ] || fail "JAVA_HOME 无效: ${JAVA_HOME}"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[ -n "${ANDROID_SDK:-}" ] || fail "ANDROID_HOME / ANDROID_SDK_ROOT 未设置"
[ -d "${ANDROID_SDK}" ] || fail "Android SDK 不存在: ${ANDROID_SDK}"
[ -x "${ANDROID_SDK}/cmdline-tools/latest/bin/sdkmanager" ] || fail "缺少 sdkmanager: ${ANDROID_SDK}/cmdline-tools/latest/bin/sdkmanager"
[ -d "${ANDROID_SDK}/ndk" ] || fail "缺少 Android NDK 目录: ${ANDROID_SDK}/ndk"
find "${ANDROID_SDK}/ndk" -maxdepth 2 -name source.properties | grep -q . || fail "Android NDK 未安装完整"

rustup target list --installed | grep -q '^aarch64-linux-android$' || fail "缺少 Rust target: aarch64-linux-android"

if ! cargo ndk --version >/dev/null 2>&1; then
  fail "cargo-ndk 不可用，请先安装 cargo-ndk"
fi

echo "[doctor:android-env] OK"
echo "  JAVA_HOME=${JAVA_HOME}"
echo "  java=${JAVA_BIN}"
echo "  javac=${JAVAC_BIN}"
echo "  bun=${BUN_BIN}"
echo "  cargo=${CARGO_BIN}"
echo "  ANDROID_SDK=${ANDROID_SDK}"
echo "  NDK=$(find "${ANDROID_SDK}/ndk" -maxdepth 1 -mindepth 1 -type d | sort | tail -n 1)"
