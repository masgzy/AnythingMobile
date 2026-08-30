#!/usr/bin/env bash
# ============================================================
# AnythingMobile —— Go 核心引擎 AAR 一键构建脚本
#
# 产物: app/libs/engine.aar (arm64-v8a / armeabi-v7a / x86_64)
#
# 依赖:
#   1. Go >= 1.23           https://go.dev/dl/
#   2. Android NDK          环境变量 ANDROID_NDK_HOME 指向 NDK 根目录
#                           (Android Studio 用户通常在
#                            ~/Android/Sdk/ndk/<version>)
#   3. JDK 17+              gomobile bind 需要 javac
#   4. gomobile             缺失时本脚本会自动 go install
#
# 关键点:
#   - 16KB 页对齐: Android 15+ 的 16KB 页设备要求 .so 按
#     max-page-size=16384 链接，否则加载即崩溃。与是否上架无关。
#     参考: developer.android.com/guide/practices/page-sizes
#   - 依据 Go Mobile Wiki，bind 前 go.mod 需保留 x/mobile 相关
#     indirect 依赖，脚本内 go get 会自动补齐。
# ============================================================
set -euo pipefail

cd "$(dirname "$0")"

# ---- 环境检查 ----
command -v go >/dev/null 2>&1 || { echo "错误: 未安装 Go (需要 >= 1.23)"; exit 1; }

if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_NDK_LATEST_HOME:-}" ]; then
  export ANDROID_NDK_HOME="$ANDROID_NDK_LATEST_HOME"
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  # 尝试常见默认路径
  for d in "$HOME/Android/Sdk/ndk/"* "$HOME/Library/Android/sdk/ndk/"*; do
    [ -d "$d" ] && export ANDROID_NDK_HOME="$d" && break
  done
fi
[ -n "${ANDROID_NDK_HOME:-}" ] || { echo "错误: 未找到 Android NDK，请设置 ANDROID_NDK_HOME"; exit 1; }
echo "NDK: $ANDROID_NDK_HOME"

JAVA_HOME_SET="${JAVA_HOME:-}"
[ -n "$JAVA_HOME_SET" ] || command -v javac >/dev/null 2>&1 || \
  { echo "错误: 需要 JDK 17+ (javac)"; exit 1; }

# ---- 安装 gomobile 并补齐 go.mod indirect 依赖 ----
# 版本钉死（可复现构建，防上游漂移）：
#   x/mobile 的 go 指令演进：2025-08-13 起要求 Go>=1.24，
#   2026-02-11 起要求 Go>=1.25，2026-08-21 起要求 Go>=1.26。
#   395d808d53cd（2025-08-08，go 指令 1.23.0）是最后一个兼容本项目
#   基线 Go 1.23 的快照。升级 pin 前必须先核对上游 go 指令仍 <= 1.23：
#     curl -s https://raw.githubusercontent.com/golang/mobile/<sha>/go.mod
#   或整体升级项目 Go 基线后再跟进。
GOMOBILE_PIN=395d808d53cd

command -v gomobile >/dev/null 2>&1 || {
  echo "安装 gomobile (pin: $GOMOBILE_PIN)..."
  go install "golang.org/x/mobile/cmd/gomobile@${GOMOBILE_PIN}"
}
# gobind 同样钉死：bind 仅依赖 PATH 中的 gobind（bind.go: exec.LookPath），
# 无需 gomobile init —— init 内部写死 gobind@latest，在 GOTOOLCHAIN=local
# 下会拉到要求 Go>=1.26 的上游快照而失败（2026-08-30 CI 故障根因链）。
command -v gobind >/dev/null 2>&1 || {
  echo "安装 gobind (pin: $GOMOBILE_PIN)..."
  go install "golang.org/x/mobile/cmd/gobind@${GOMOBILE_PIN}"
}
(cd core && go get golang.org/x/mobile/cmd/gomobile@"${GOMOBILE_PIN}")

# ---- 编译 AAR（含 16KB 页对齐链接参数） ----
# 注意：bind 必须在 Go 模块目录（core/）内执行
mkdir -p app/libs
(
  cd core
  gomobile bind \
    -target=android \
    -androidapi 24 \
    -javapkg=com.masgzy.anything \
    -ldflags='-extldflags=-Wl,-z,max-page-size=16384' \
    -o ../app/libs/engine.aar \
    .
)

echo
echo "✅ 生成完成: app/libs/engine.aar"
echo "   校验 16KB 对齐: check-elf-alignment 工具或"
echo "   objdump -p $(find /tmp -name 'libcore.so' 2>/dev/null | head -1) | grep LOAD"
