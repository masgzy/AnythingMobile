#!/usr/bin/env bash
# ============================================================
# AnythingMobile —— Rust 引擎 .so 一键构建脚本（alpha20 迁移）
#
# 产物: <输出目录>/<abi>/libanything.so
#       （arm64-v8a / armeabi-v7a / x86_64）
# 默认输出到 app/src/main/jniLibs/（AGP 自动打包）；
# CI 冒烟传入独立目录，避免污染 app 打包（阶段④接入前 .so 尚未被宿主加载）。
#
# 用法: ./build-so.sh [输出目录]     （默认 ../app/src/main/jniLibs）
#
# 依赖:
#   1. Rust (rustup)         https://rustup.rs/
#   2. Android NDK           环境变量 ANDROID_NDK_HOME 指向 NDK 根目录
#   3. cargo-ndk             缺失时本脚本会自动 cargo install
#
# 关键点:
#   - 16KB 页对齐: Android 15+ 的 16KB 页设备要求 .so 按
#     max-page-size=16384 链接，否则加载即崩溃。
#     链接参数已写入 engine/.cargo/config.toml 的各 android target rustflags。
#   - 构建的是 cdylib（默认 feature），panic 策略为 unwind，
#     FFI 边界 catch_unwind 才能生效（禁改 panic=abort）。
# ============================================================
set -euo pipefail

cd "$(dirname "$0")"

OUTPUT_DIR="${1:-../app/src/main/jniLibs}"

# ---- 环境检查 ----
command -v cargo >/dev/null 2>&1 || { echo "错误: 未安装 Rust (cargo 不在 PATH)"; exit 1; }

if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_NDK_LATEST_HOME:-}" ]; then
  export ANDROID_NDK_HOME="$ANDROID_NDK_LATEST_HOME"
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  # 尝试常见默认路径（GitHub Actions ubuntu runner / 本地常见布局）
  for d in "$ANDROID_HOME/ndk/"* /usr/local/lib/android/sdk/ndk/* "$HOME/Android/Sdk/ndk/"* "$HOME/Library/Android/sdk/ndk/"*; do
    [ -d "$d" ] && export ANDROID_NDK_HOME="$d" && break
  done
fi
[ -n "${ANDROID_NDK_HOME:-}" ] || { echo "错误: 未找到 Android NDK，请设置 ANDROID_NDK_HOME"; exit 1; }
echo "NDK: $ANDROID_NDK_HOME"
echo "输出: $OUTPUT_DIR"

command -v cargo-ndk >/dev/null 2>&1 || {
  echo "安装 cargo-ndk..."
  cargo install cargo-ndk --locked
}

# rustup 目标（幂等）
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

# ---- 编译（release + 16KB 对齐）----
# 注意：必须显式 -t 列全三 ABI。实测 cargo-ndk 不带 -t 时按环境自动取舍，
# 在 CI 上可能只构建部分 ABI，导致产物校验失败。
ABIS=(arm64-v8a armeabi-v7a x86_64)
mkdir -p "$OUTPUT_DIR"

cargo ndk -o "$OUTPUT_DIR" \
  -t arm64-v8a -t armeabi-v7a -t x86_64 \
  --platform 24 build --release

echo
echo "✅ 生成完成:"
for abi in "${ABIS[@]}"; do
  so="$OUTPUT_DIR/$abi/libanything.so"
  [ -f "$so" ] || { echo "   缺失: $so"; exit 1; }
  SIZE=$(du -h "$so" | cut -f1)
  echo "   $so ($SIZE)"
done

echo
echo "校验 16KB 对齐（期望 LOAD 段对齐 0x4000）:"
READER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
[ -x "$READER" ] || READER=readelf
FAIL=0
for abi in "${ABIS[@]}"; do
  so="$OUTPUT_DIR/$abi/libanything.so"
  echo "  [$abi]"
  # 取 LOAD 行的 align 列（最后一列），必须全部 >= 0x4000
  # 注：bash 算术上下文直接识别 0x 前缀十六进制，无需 16# 基数语法
  ALIGNS=$("$READER" -l "$so" | grep -E 'LOAD' | awk '{print $NF}')
  for a in $ALIGNS; do
    echo "    align=$a"
    if (( a < 0x4000 )); then
      echo "    ❌ 对齐不足 16KB"
      FAIL=1
    fi
  done
done
[ "$FAIL" -eq 0 ] || { echo "16KB 对齐校验失败"; exit 1; }
echo "✅ 16KB 对齐校验通过"
