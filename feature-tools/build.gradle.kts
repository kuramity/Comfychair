plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "sh.hnet.comfychair.feature.tools"
    // 与 app 模块同口径：compileSdk 37 + 小版本 2（Compose 1.13 要求至少 android-37.1）
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Kotlin 2.4 起 `kotlinOptions { jvmTarget = ... }` 由警告升级为编译错误，
    // 统一用 compilerOptions DSL（与 app 模块同一写法）
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // 依赖一律走 libs.versions.toml 别名；Compose 版本由 BOM 统一管理
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.runtime)
    // 列表卡片图标（AutoFixHigh 不在 material-icons-core 里）
    implementation(libs.compose.material.icons.extended)
    // ToolsNavGraph 内部的 NavHost（模块单独成容器时使用）
    implementation(libs.navigation.compose)
}