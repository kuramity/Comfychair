pluginManagement {
    repositories {
        // 国内镜像优先（2026-11-13）：校园网/Clash fake-ip 下 dl.google.com 读超时，
        // 官方仓库保留在后面兜底；镜像里没有的包会自动回落到 google()/mavenCentral()。
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 同上：镜像优先，官方源兜底
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ComfyChair"
include(":app")
// 小工具（底栏第 6 个 tab）独立成 library 模块：UI 先在模块内做，app 只做依赖与导航挂载
include(":feature-tools")
 