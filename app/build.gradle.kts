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
        versionCode = 15
        versionName = "1.0.0-alpha15"
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
            // R8 深度收缩。大头是 material-icons-extended：仅用 39 个图标，
            // 未开启时上万个未引用图标类原样进包（~28MB），整个 Compose 栈
            // 无裁剪进包（10 个 dex 共 ~62MB）；开启后按实际引用裁剪。
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
}

dependencies {
    // Go 核心引擎 AAR —— 由 build-aar.sh 或 CI 生成到 app/libs/
    // 本地开发请先执行仓库根目录的 ./build-aar.sh
    implementation(files("libs/engine.aar"))

    // Compose（BOM 2026.08.00：ui/foundation 1.12.0、material3 1.4.0、icons 1.7.8）
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // 扩展图标集：筛选按钮（AllInclusive/VideoLibrary/MusicNote 等）需要；
    // release 构建经 R8 裁剪后仅保留实际引用的图标。
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // material-kolor：ImageToolbox 同款取色引擎（HCT/DynamicScheme/PaletteStyle）。
    // 5.0.1 = ImageToolbox 所用 5.0.0 的最新补丁版。
    // 注意：其 Android 变体不传递任何依赖（KMP module 元数据为空），
    // Hct/TonalPalette/DynamicScheme 所在的 material-color-utilities 必须显式引入。
    implementation("com.materialkolor:material-kolor:5.0.1")
    implementation("com.materialkolor:material-color-utilities:5.0.1")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")

    // Shizuku / Stellar 特权通道：官方 API + Provider（扫描 Android/data 用）。
    // Stellar 是 Shizuku 的深度定制分支且内置 Shizuku 兼容层，
    // 接入官方 API 一套即可同时被 Shizuku 与 Stellar 管理器支持。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
