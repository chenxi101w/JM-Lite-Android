# 保留 Chaquopy/Python 运行时与 jmcomic 动态引用
-keep class com.chaquo.python.** { *; }
-keep class com.example.jmcomicreader.data.python.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# jmcomic 依赖中可能存在的反射/动态导入
-dontwarn org.jsoup.**
-dontwarn bs4.**
-dontwarn requests.**

# ── v1.2.7：为开启 R8 补充的规则 ──────────────────────────────

# 应用自身的数据层与模型：Room 实体、DAO、迁移都靠反射/注解访问，整体保留
-keep class com.example.jmreader.data.local.** { *; }
-keep class com.example.jmreader.data.model.** { *; }

# 保留 Kotlin 元数据等属性 —— 反射、序列化、协程续体都依赖
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions

# 网络栈（OkHttp / Okio）与 Coil 的可选依赖告警
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn coil.**

# 协程：内部有大量内联与反射调用
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
