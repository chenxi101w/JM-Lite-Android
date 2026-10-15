plugins {
    id("com.android.application")
    // AGP 9.0 内置 Kotlin 支持，不再应用 org.jetbrains.kotlin.android
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.jmreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.jmreader"
        minSdk = 24
        targetSdk = 34
        versionCode = 83
        versionName = "1.19.2"

        // v1.4.0：仅 arm64-v8a（真机为 arm64）。Chaquopy 移除后不再有"多架构重复装 Python 包"的开销。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // v1.2.7：开启 R8 代码裁剪 + 资源压缩。
            // 此前 release 也是 isMinifyEnabled = false —— 等于出的是「没优化的包」，
            // 与 debug 一样臃肿（实测 debug APK 42.4MB，其中 classes.dex 解压后就有 42MB）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 测试用：release 复用 debug 签名，便于 CLI 直接装机验证。
            // 正式发布应换成自己的 keystore。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        // v1.2.7：关闭 release 构建时的 lint 检查。
        // 原因：当前 AGP + Kotlin 版本组合下 lintVitalAnalyzeRelease 自身会崩
        // （`findFirCompiledSymbol only works on compiled declarations`，
        // 是 lint 分析 build.gradle.kts 脚本时的 bug），与业务代码无关。
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 旧版纯 Kotlin 源码（com.jmcomic.android）已整体备份到项目外的 _legacy_backup/，
    // 并从此工程中删除，故不再需要 exclude。
}

// AGP 9.0 内置 Kotlin：原 android.kotlinOptions{} 迁移为 kotlin.compilerOptions{}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }
}

dependencies {
    // v7.0.0 依赖升级：以下版本均在本机 Gradle 缓存中已有，可离线编译验证
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Material 图标：core 只含少量常用图标（Home/Search/ArrowBack/Close/Menu 等），
    // Download/Bookmark/CheckBox/GridView/ChevronRight/ArrowDropUp 等大量图标在 extended 里，
    // 故两者都引入（extended 已含 core，仅多若干 MB 矢量资源，本 App 体量下可忽略）。
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    // v7.0.0：2.7.7 → 2.8.3（修若干导航返回栈问题，API 向后兼容）
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // 图片加载（阅读器从 Python 解码后的字节加载）
    implementation("io.coil-kt:coil-compose:2.6.0")

    // 原生 HTTP 客户端：同源 JSON 接口（v1.1.0 搜索提速，绕开 Python 链路）。
    // Coil 亦传递依赖 okhttp，此处显式声明以免上游变更时断链。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 本地数据库（≥2.7：KSP2 兼容，2.6.x 会崩 "unexpected jvm signature V"）
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ============================================================================
// AGP 8.x 兼容性补丁：旧版（AGP 7.x 及更早）的 Java 生命周期任务 `testClasses`
// 在 AGP 8.x 中已被移除。部分 IDE / 构建入口仍会拼 `:app:testClasses` 一起执行，
// 缺失该任务会导致整个构建在任务解析阶段直接失败（clean/assemble 都跑不到）。
// 这里注册一个空任务满足任务名匹配，使旧命令 `clean assemble testClasses` 可正常编过。
// 本工程当前无 unit test 源码需编译，故为空实现；若日后加测试，可改为
// dependsOn(tasks.named("compileDebugUnitTestSources"))。
// ============================================================================
tasks.register("testClasses") {
    group = "verification"
    description = "No-op placeholder for legacy AGP testClasses task (removed in AGP 8.x)."
}

// ============================================================================
// v1.0.0：编译产物自动拷贝到共享 APK 目录（WiFi 传输工具下发用）。
// v1.1.2 修正：输出目录原为 `D:/code/Android/AI-Android/apk`（旧路径，非本机常用位置），
// 且 `.apply { mkdirs() }` 写在配置阶段 —— 于是**每次 Gradle 配置都会重建那个目录**，
// 让 `D:\code\Android` 反复凭空出现。现改为项目内共享 apk 目录，并去掉配置期副作用
// （Copy 任务会在执行时按需创建目标目录）。
// 输出：D:\code\CODE\AI-Android\apk\JM_vX.Y.Z_debug.apk
// ============================================================================
val apkOutDir = File("D:/code/CODE/AI-Android/apk")
val apkOutName = "JM_v${android.defaultConfig.versionName}_debug.apk"
val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
tasks.register<Copy>("copyApkToD") {
    dependsOn("assembleDebug")
    from(debugApk)
    into(apkOutDir)
    rename { apkOutName }
    doLast { println("APK copied to " + apkOutDir.absolutePath + "/" + apkOutName) }
}

// assembleDebug/assembleRelease 完成后自动出包到共享 apk 目录
// （全局约定：分享 APK 统一放 D:\code\CODE\AI-Android\apk\；用惰性 matching——
//   AGP 延迟注册任务，配置期 tasks.named 会报 not found）
listOf("assembleDebug", "assembleRelease").forEach { n ->
    tasks.matching { it.name == n }.configureEach { finalizedBy("copyApkToD") }
}
