import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.masgzy.anything"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.masgzy.anything"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.0-alpha4"
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

    // 统一产物命名: AnythingMobile-<版本>-<abi|universal>.apk
    // （配合 CI 的 upload-artifact v7 单文件直传，下载即是可安装的 APK 本体）
    applicationVariants.all {
        val vName = versionName
        outputs.all {
            val output = this as BaseVariantOutputImpl
            val abi = output.getFilter("ABI") ?: "universal"
            output.outputFileName = "AnythingMobile-$vName-$abi.apk"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Go 核心引擎 AAR —— 由 build-aar.sh 或 CI 生成到 app/libs/
    // 本地开发请先执行仓库根目录的 ./build-aar.sh
    implementation(files("libs/engine.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // 扩展图标集：筛选按钮（AllInclusive/VideoLibrary/MusicNote 等）需要；
    // release 构建经 R8 裁剪后仅保留实际引用的图标。
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
}
