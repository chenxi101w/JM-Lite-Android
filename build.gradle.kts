// 顶层构建文件：只声明插件版本，不在顶层直接 apply。
plugins {
    // Android Studio 2026.1.3 (Quail 3) 推荐配置：AGP 9.0 + Gradle 9.7.1 + 内置 Kotlin
    id("com.android.application") version "9.3.0" apply false
    // AGP 9.0 起 Kotlin 编译支持内建于 AGP（内置 KGP 2.2.10），无需再单独应用 org.jetbrains.kotlin.android
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    // Chaquopy 17.0.0 起支持 AGP 9.0~9.2
    id("com.chaquo.python") version "17.0.0" apply false
}