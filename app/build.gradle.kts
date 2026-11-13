import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 内置更新模块的清单地址（开源版默认留空）。
// 开源仓库不指向任何个人服务器：空值 = 更新检查判定「未配置」，不发请求、不弹错。
// 自己分发 APK 时在 local.properties 里写一行即可（该文件已被 .gitignore 忽略，不会进仓库）：
//   comfychair.updateManifestUrl=https://你的域名/apk/update/update.json
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val updateManifestUrl: String =
    localProperties.getProperty("comfychair.updateManifestUrl")?.trim().orEmpty()

android {
    namespace = "sh.hnet.comfychair"
    compileSdk = 36

    defaultConfig {
        applicationId = "sh.hnet.comfychair.plus"
        minSdk = 33
        targetSdk = 36
        // 版本规则（2026-09-25 修订）：三段式 major.minor.patch，**按改动性质**决定升哪一段。
        //   · 小 bug 修复                            → patch +1（v1.0.0 → v1.0.1）
        //   · 功能修改（改了既有行为，不是新功能）    → minor +1、patch 归零（v1.0.3 → v1.1.0）
        //   · 全面大修 / 新 UI / 新交互按钮 / 新功能   → major +1、其余归零（v1.1.3 → v2.0.0）
        // **进位规则**：低位只在**主动升段**时归零，**本身没有进位上限** —— 旧规则
        // "patch 满 32 自动进位到 minor"已废止，patch 可以一路加到 999 也不动 minor 和 major。
        // 即：数字大小只反映"人为判定的改动量级"，不反映发布次数。
        // versionCode 与版本名**解耦**：每次发版 +1，只保证单调递增（Android 的硬性要求）。
        // ⚠️ 发版前务必同时改这两行：只 cp 改文件名不改这里，会得到一个 versionCode
        //    与上一版完全相同的包（装不上也分不清，v0.9.33 踩过）。
        versionCode = 110
        versionName = "v4.7.0"

        // 更新清单地址注入 BuildConfig（空字符串 = 未配置更新源）
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Disable baseline profile generation for F-Droid reproducible builds
// https://f-droid.org/docs/Reproducible_Builds/
tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)

    // OkHttp for HTTP/HTTPS requests and WebSocket communication
    implementation(libs.okhttp)

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.text.google.fonts)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle and ViewModel for Compose
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Navigation Compose
    implementation(libs.navigation.compose)

    // Media3 for video playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)

    // Markdown parsing
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.strikethrough)

    // Security (encrypted SharedPreferences)
    implementation(libs.androidx.security.crypto)

    // WorkManager（内置更新模块：后台下载/断点续传，任务生命周期独立于 UI）
    implementation(libs.androidx.work.runtime)

    // 3D 模型查看器（图生3D 的结果预览）：Filament 内核，直接渲染 .glb
    implementation(libs.sceneview)

    testImplementation(libs.junit)
    // 仅测试域：JVM 单元测试要真实的 org.json（不进 APK）
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // 3D 手势的 instrumented 验证：需要 setContent + captureToImage（模拟器无法用 adb 注入双指，
    // 只能在设备内合成 MotionEvent）。版本由 compose BOM 统一管理。
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}