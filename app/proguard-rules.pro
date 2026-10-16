# 保留 gomobile 生成的 JNI 绑定层：
# Go 运行时按原始类名 FindClass/RegisterNatives（go/Seq 等），
# 混淆或删除 = 首次调用引擎即 NoSuchMethodError/崩溃。
-keep class go.** { *; }
-keep class com.masgzy.anything.core.** { *; }
-dontwarn go.seq.**

# 任何 JNI native 方法名与签名不可混淆
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 崩溃堆栈保留行号（体积代价极小，配合 mapping.txt 可还原）
-keepattributes SourceFile,LineNumberTable

# kotlinx-coroutines 可选传递依赖不在类路径，静默警告
-dontwarn org.reactivestreams.**
