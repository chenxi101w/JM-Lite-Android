package com.example.jmreader

import android.app.ActivityManager
import android.content.Context
import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.os.StrictMode
import android.view.Choreographer
import com.example.jmreader.BuildConfig
import android.util.Log
import com.example.jmreader.util.JmDebug
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.jmreader.data.local.AppDatabase
import com.example.jmreader.data.repository.JmRepository
import com.example.jmreader.data.api.JmApiHost
import com.example.jmreader.data.repository.StartupCheckReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用入口：
 * 启动 Chaquopy Python 运行时（jmcomic 库依赖它）
 * 初始化 Room 数据库与统一 Repository，供各 ViewModel 使用
 * 写入默认配置并应用代理设置（对齐 PC 2.4.9 设置体系）
 */
class JmComicApplication : Application() {
    lateinit var repository: JmRepository
        private set

    /** 首启自检报告（v7.3.0）：版本升级后首启产生，MainActivity 观察并弹窗；空 = 无报告。 */
    private val _startupCheckReport = MutableStateFlow<StartupCheckReport?>(null)
    val startupCheckReport: StateFlow<StartupCheckReport?> = _startupCheckReport

    /** 供 MainActivity 写入/清除自检报告。 */
    fun postStartupCheckReport(report: StartupCheckReport?) {
        _startupCheckReport.value = report
    }

    // （companion object 仅在类末尾定义一次，见文末）

    override fun onCreate() {
        super.onCreate()
        // v1.7.1：读调试日志开关（排查问题时用adb 可改，不用重编译）
        JmDebug.init(this)
        // v1.4.0：Chaquopy / Python 预热已移除（全链路原生，无需运行时）

        // v1.1.4：后台预热 JSON 接口「线路配置」。
        // 该配置原本只在首次搜索时才拉取（实测约 900ms），会拖慢冷启动后的第一次搜索；
        // 提前在后台拉好，之后搜索直接用内存缓存。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { JmApiHost.current() }
                .onFailure { Log.w("JmApp", "接口线路预热失败，将在首次搜索时重试", it) }
        }


        val db = AppDatabase.build(this)
        val cacheDir = cacheDir.absolutePath
        val saveDir = getExternalFilesDir("DownloadedComics")?.absolutePath
            ?: File(filesDir, "DownloadedComics").absolutePath
        repository = JmRepository(db, cacheDir, saveDir)

        // v1.13.0：下载保活 + 通知服务（常驻循环：有活跃下载自动转前台并发通知，无下载时空转）
        startService(android.content.Intent(this, com.example.jmreader.data.download.DownloadService::class.java))

        // 调试期性能探针：主线程出现磁盘读/网络/慢调用时打栈到 logcat（tag StrictMode），
        // 用于真机定位卡顿根因；release 包不开启，零性能开销。
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
            // 帧耗时探针：每帧测量帧间隔，>16.7ms 视为掉帧，打 JmPerf 日志，用于定位渲染侧卡顿
            startFrameProbe()
        }

        // 全局 Coil 配置：针对中端机控制内存/磁盘缓存上限，防 OOM + 限制存储占用
        val actMan = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // 内存缓存 = 可用堆的 1/10，封顶 64MB（中端机默认堆约 256MB，约 25MB；漫画单页 ~3~5MB，滚动回收可控）
        val memCacheBytes = (actMan.memoryClass * 1024 * 1024L / 10).coerceAtMost(64L * 1024 * 1024)
        val loader = ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizeBytes(memCacheBytes.toInt()).build() }
            // 磁盘缓存封顶 256MB，避免无限增长占满存储（封面/章节图回看不重联网/不抢 Python）
            .diskCache {
                DiskCache.Builder().directory(this.cacheDir.resolve("coil_disk")).maxSizeBytes(256L * 1024 * 1024).build()
            }
            // JM 封面/漫画图无 alpha 通道，RGB_565 省 50% 解码内存（防大图 OOM）
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(false) // 阅读器滚动更跟手，省一层淡入合成
            .build()
        Coil.setImageLoader(loader)

        // 默认配置 + 下载并发（异步，不阻塞启动）
        // 每一步单独兜底：任一失败只记日志，绝不因启动配置抛异常而闪退
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.initConfigDefaults() }
                .onFailure { Log.e("JmApp", "initConfigDefaults failed", it) }
            runCatching { repository.applyDownloadConcurrency() }
                .onFailure { Log.e("JmApp", "applyDownloadConcurrency failed", it) }
            // v1.10.0 云同步：启用时启动静默双向同步（内部自带 6h 节流，失败静默忽略）
            runCatching { repository.autoSyncOnLaunch() }
                .onFailure { Log.w("JmApp", "autoSyncOnLaunch failed", it) }
        }
    }

    companion object {
        /**
         * 调试期帧耗时探针（仅 DEBUG）：通过 Choreographer 测量每帧间隔，
     * 掉帧（>16.7ms）时打 JmPerf 日志。配合 StrictMode 可一次定位卡顿根因
     * （主线程违规 vs 渲染侧掉帧）。应用切后台/恢复的首帧间隔异常会被忽略，避免误报。
     */
    private fun startFrameProbe() {
        val choreographer = Choreographer.getInstance()
        var last = System.nanoTime()
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val dtMs = (frameTimeNanos - last) / 1_000_000.0
                last = frameTimeNanos
                // dt>200ms 视为切后台/恢复造成的停顿，不计入掉帧
                if (dtMs in 20.0..200.0) {
                    Log.w("JmPerf", String.format("jank frame: %.1f ms (>16.7ms 掉帧)", dtMs))
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(cb)
    }
    }
}
