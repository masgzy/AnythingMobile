# 保留 gomobile 生成的 JNI 绑定层（alpha19 前的 AAR 通道，现已移除，留作回归保险）：
# Go 运行时按原始类名 FindClass/RegisterNatives（go/Seq 等），
# 混淆或删除 = 首次调用引擎即 NoSuchMethodError/崩溃。
-keep class go.** { *; }
-keep class com.masgzy.anything.core.** { *; }
-dontwarn go.seq.**

# Rust FFI 契约（alpha20 起宿主唯一引擎，com.masgzy.anything.rust 包）：
# Rust 侧对 ProgressListener/ExternalParser 按「方法名+签名」JNI 反射回调
# （call_method("onFinished") 等，见 engine/src/callback.rs）。R8 混淆把
# 接口方法改名后回调查找静默失败 → onFinished 永不到达 → 索引状态永不
# 更新 —— alpha21/22 真机「一直正在索引中」的最终根因（alpha23 修复）。
# native 方法已有上方通用规则保住，这里整体 keep 代价极小（3 个类）。
-keep class com.masgzy.anything.rust.** { *; }

# 任何 JNI native 方法名与签名不可混淆
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 崩溃堆栈保留行号（体积代价极小，配合 mapping.txt 可还原）
-keepattributes SourceFile,LineNumberTable

# kotlinx-coroutines 可选传递依赖不在类路径，静默警告
-dontwarn org.reactivestreams.**
