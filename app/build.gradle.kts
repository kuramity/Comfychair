import java.io.File
import java.util.Properties
import org.gradle.api.GradleException

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

// release 签名：从 local.properties 读（该文件已被 .gitignore 忽略，不会进仓库）。
// 四项齐全才创建正式签名配置；缺任何一项时 release 产物保持未签名，
// 且 assembleRelease / bundleRelease 直接失败（P3-15，杜绝静默套用 debug 签名）。
// keystore 与口令位置见 E:\Zcode\ZcodeProject\tools\comfychair\keys\README.md
val releaseStoreFile = localProperties.getProperty("comfychair.storeFile").orEmpty().trim()
val releaseKeyReady = releaseStoreFile.isNotEmpty() &&
    File(releaseStoreFile).exists() &&
    !localProperties.getProperty("comfychair.storePassword").isNullOrBlank() &&
    !localProperties.getProperty("comfychair.keyAlias").isNullOrBlank()

android {
    namespace = "sh.hnet.comfychair"
    compileSdk = 37
    // Compose 1.13（BOM 2026.11.01）要求至少 android-37.1；本机装的是 37.2，显式点小版本
    compileSdkMinor = 2

    signingConfigs {
        if (releaseKeyReady) {
            create("release") {
                storeFile = File(releaseStoreFile)
                storePassword = localProperties.getProperty("comfychair.storePassword")
                keyAlias = localProperties.getProperty("comfychair.keyAlias")
                keyPassword = localProperties.getProperty("comfychair.keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "sh.hnet.comfychair.plus"
        minSdk = 33
        targetSdk = 37
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
        versionCode = 158
        versionName = "v4.23.0"

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
            // P3-15：不再静默回退 debug 签名。debug keystore 人人都有，debug 签名的 release
            // 包可被任意重签伪造（也无法上架/升级覆盖正式包）。没有正式 keystore 时保持未签名
            // —— 产物名会带 -unsigned，且 assembleRelease / bundleRelease 会在任务启动时直接失败。
            if (releaseKeyReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Kotlin 2.4 起 `kotlinOptions { jvmTarget = ... }` 由警告升级为编译错误，
    // 必须改用 compilerOptions DSL（见 https://kotl.in/u1r8ln）。
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
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

// P3-15：缺正式签名配置时，release 打包必须失败，而不是产出 debug 签名的包。
// 只挂在真正打 release 包的任务上：clone 源码的人照样能 assembleDebug / 跑单测，
// 但 assembleRelease / bundleRelease 会在开始执行时立刻报错并给出配置指引。
if (!releaseKeyReady) {
    tasks.configureEach {
        if (name == "assembleRelease" || name == "bundleRelease") {
            doFirst {
                throw GradleException(
                    "缺少 release 签名配置，已拒绝产出 debug 签名的 release 包。" +
                        "请在 local.properties 写入 comfychair.storeFile / " +
                        "comfychair.storePassword / comfychair.keyAlias / " +
                        "comfychair.keyPassword（keystore 与口令位置见 " +
                        "E:\\Zcode\\ZcodeProject\\tools\\comfychair\\keys\\README.md）。"
                )
            }
        }
    }
}

// P0（2026-11-23 全量单测实测）：ProcessJavaResTask 的空目录竞态会让单测任务整体失败。
// ProcessJavaResTask 继承 Sync/AbstractCopyTask，默认 includeEmptyDirs = true：它会把源树里的
// 每个目录都在输出目录重建一遍并 chmod。Kotlin 插件把 build/tmp/kotlin-classes/debugUnitTest
// （内含 sh/** 编译输出）注册为 java resources 源根，于是 java_res/.../out/ 下会生成一棵只有目录、
// 没有 .class 的空骨架。Gradle 9.4.1 在 Windows 上没有原生 POSIX 文件模式
// （GenericFileSystem.getUnixMode → FallbackStat），一旦某个目录在「枚举完目录树、再去取模式」
// 的间隙里短暂不可见（Kotlin daemon 补写增量缓存 / 并发构建互相清理 build/），就抛
// "Could not get file mode for ...\kotlin-classes\debugUnitTest\sh"，整个
// :app:processDebugUnitTestJavaRes 失败、全量单测直接中断（2026-11-23 第 2 次复现）。
// Java resources 本身无空目录语义（.class 早已被过滤掉），关闭空目录复制既不丢任何真实资源文件，
// 也不再 stat 那些易变的编译输出目录。
tasks.matching { it.name.startsWith("process") && it.name.endsWith("JavaRes") }.configureEach {
    (this as? org.gradle.api.tasks.AbstractCopyTask)?.includeEmptyDirs = false
}

// Disable baseline profile generation for F-Droid reproducible builds
// https://f-droid.org/docs/Reproducible_Builds/
tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

dependencies {
    // 小工具（底栏第 6 个 tab）：独立 library 模块，UI 在模块内维护
    implementation(project(":feature-tools"))

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
    // 进程级前后台判定（ProcessLifecycleOwner 的 ON_START/ON_STOP）—— 后台降级的基础信号之一
    implementation(libs.lifecycle.process)

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