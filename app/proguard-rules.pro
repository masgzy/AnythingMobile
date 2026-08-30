# 保留 gomobile 生成的 JNI 绑定层
-keep class com.masgzy.anything.core.** { *; }
-keepclassmembers class go.** { *; }
-dontwarn go.seq.**
