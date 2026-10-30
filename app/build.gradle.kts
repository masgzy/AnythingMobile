import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名信息（keystore.properties 不入库，由 CI 从 Secrets 生成）：
//   storeFile / storePassword / keyAlias / keyPassword
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.masgzy.anything"
    // 37 = Android 17：compose 1.12.0 与 material-kolor 5.0.1 的
    // AAR 元数据均要求 ≥37（与 ImageToolbox 一致）
    compileSdk = 37

    defaultConfig {
        applicationId = "com.masgzy.anything"
        minSdk = 24
        targetSdk = 36
        versionCode = 27
        versionName = "1.0.0-alpha27"
        // 仪器化测试跑在 Android 虚拟机上（.github/workflows/emulator.yml）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 检查更新总开关：standard 渠道默认开；offline 渠道置 false 且
        // 连 INTERNET 权限一并移除（app/src/offline/AndroidManifest.xml），
        // 给不愿让应用具备联网能力的用户一个零网络请求的发行包（alpha26）
        buildConfigField("boolean", "UPDATE_CHECK", "true")
    }

    // 发行渠道（alpha26）：同一份代码构建两个变体 ——
    //   standard：功能完整（应用内检查更新，Release 附件名无后缀）
    //   offline  ：不检查更新 + Manifest 移除 INTERNET 权限（附件名 _offline）
    // applicationId 保持一致：offline 包可直接覆盖安装 standard 版，
    // 索引与设置无缝延续。
    flavorDimensions += "channel"
    productFlavors {
        create("standard") {
            dimension = "channel"
        }
        create("offline") {
            dimension = "channel"
            buildConfigField("boolean", "UPDATE_CHECK", "false")
        }
    }

    // 按 ABI 拆分产物：三种单架构 APK + 一个 universal 通吃包。
    // 注意：universal 会包含 AAR 中全部 .so（arm64-v8a/armeabi-v7a/x86/x86_64）。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // 签名配置必须先于 buildTypes 声明：
    // buildTypes.release 里 getByName("release") 是立即求值。
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 深度收缩。alpha25 起图标已迁 Material Symbols drawable，
            // 不再引入 material-icons-extended（历史上它是体积大头：仅用
            // 39 个图标却引入上万个类，未开 R8 时 ~28MB）；R8 继续负责
            // Compose 栈与引擎 JNI 契约的按引用裁剪。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // CI 从 Secrets 解码证书并生成 keystore.properties 后自动签名；
            // 本地无该文件时退回未签名 release（不影响 debug 开发）。
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9 内置 Kotlin：android.kotlinOptions{} 已移除，
    // 按官方迁移指南改用 kotlin.compilerOptions{}（jvmTarget 需与 Java 一致）。
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        // 生成 BuildConfig：设置页版本号用 BuildConfig.VERSION_NAME，不再硬编码
        buildConfig = true
        // AIDL：Shizuku/Stellar 用户服务接口（AGP 8+ 默认关闭，需显式开启）
        aidl = true
    }

    // JVM 单测：LegacyOfficeParser 失败路径调 android.util.Log，
    // 无 Robolectric 时让 Log 方法返回默认值而非抛 "not mocked"
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 引擎 = Rust 编译的 libanything.so，经 app/src/main/jniLibs/<abi>/ 打包
    // （./engine/build-so.sh 或 CI 生成；无需任何外部依赖声明）

    // JVM 单测：LegacyOfficeParser / CfbReader 二进制解析回归网（P0-1）
    testImplementation("junit:junit:4.13.2")

    // 仪器化测试（emulator.yml 在模拟器上跑 connectedDebugAndroidTest）：
    // ActivityScenario 启动链路冒烟 —— 覆盖 JNI 加载、ViewModel 构造、
    // 崩溃监听安装，这三样 JVM 单测永远碰不到
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    // Compose（BOM 2026.08.00：ui/foundation 1.12.0、material3 1.4.0；
    // 图标已迁 Material Symbols drawable（alpha25），不再依赖 material-icons-*）
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // material-kolor：ImageToolbox 同款取色引擎（HCT/DynamicScheme/PaletteStyle）。
    // 5.0.1 = ImageToolbox 所用 5.0.0 的最新补丁版。
    // 注意：其 Android 变体不传递任何依赖（KMP module 元数据为空），
    // Hct/TonalPalette/DynamicScheme 所在的 material-color-utilities 必须显式引入。
    implementation("com.materialkolor:material-kolor:5.0.1")
    implementation("com.materialkolor:material-color-utilities:5.0.1")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // 液态玻璃底栏（NPatch 同款实现的外部依赖）：backdrop 采样/折射 API
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")

    // Shizuku / Stellar 特权通道：官方 API + Provider（扫描 Android/data 用）。
    // Stellar 是 Shizuku 的深度定制分支且内置 Shizuku 兼容层，
    // 接入官方 API 一套即可同时被 Shizuku 与 Stellar 管理器支持。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
