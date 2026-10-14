pluginManagement {
    repositories {
        // 阿里云镜像：Gradle Plugin Portal（AGP / Kotlin / KSP 等插件分发）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        // 阿里云镜像：Google Maven（AndroidX / AGP 运行时构件）
        maven("https://maven.aliyun.com/repository/google")
        // 阿里云镜像：Maven Central（第三方库，如 Room / Coil / Coroutines / Chaquopy 的纯 JVM 依赖）
        maven("https://maven.aliyun.com/repository/public")
        // Chaquopy 官方源（仅此处提供 Python 运行时与 chaquopy 插件）
        maven("https://maven.chaquo.com")
        // Gradle 官方插件门户：阿里云 gradle-plugin 镜像缺少部分插件
        // （如下方 foojay-resolver-convention，实测该镜像 404、仅官方有）。
        // 放最后作兜底，正常命中阿里云时不影响速度；缺失时避免 Sync 直接失败。
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.chaquo.com")
    }
}

rootProject.name = "JMComicReader"
include(":app")
