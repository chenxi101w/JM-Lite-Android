package com.example.jmreader.util

import android.content.Context
import android.util.Log

/**
 * 全局调试开关（v1.7.1）。
 *
 * **目的**：让排查问题时能"不改代码、不重新编译"就打开/关闭全链路日志。
 *
 * **用法**：
 * ```bash
 * # 打开（写 SharedPreferences）
 * adb shell run-as com.example.jmreader \
 *   sh -c 'echo -n 1 > shared_prefs/jm_debug.xml'
 * # 或者更简单：用 App 内设置页的开关（见 SettingsScreen）
 * ```
 * 打开后可在 logcat 里按TAG 过滤：
 * ```bash
 * adb logcat -s JmDebug:* JmApiClient:* NativeAlbumDl:* DownloadManager:* ReaderVm:* -v time
 * ```
 *
 * **默认关闭**——正式使用不打这些日志（避免 logcat 刷屏、拖慢下载）。
 */
object JmDebug {
    private const val PREFS = "jm_debug"
    private const val KEY_VERBOSE = "verbose"

    @Volatile
    @JvmStatic
    var verbose: Boolean = false
        private set

    /** 在 Application 启动时调用，读一次开关。 */
    fun init(context: Context) {
        verbose = runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_VERBOSE, false)
        }.getOrDefault(false)
        Log.i("JmDebug", "调试日志：${if (verbose) "已开启" else "关闭"}")
    }

    /** 设置页的开关用（写回并立即生效）。 */
    fun setVerbose(context: Context, on: Boolean) {
        verbose = on
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_VERBOSE, on).apply()
        }
        Log.i("JmDebug", "调试日志已${if (on) "开启" else "关闭"}")
    }

    /** 条件日志：开启时按 INFO 打。 */
    fun i(tag: String, msg: () -> String) {
        if (verbose) Log.i(tag, msg())
    }

    /** 条件日志：开启时按 WARN 打。 */
    fun w(tag: String, msg: () -> String) {
        if (verbose) Log.w(tag, msg())
    }

    /** 计时器：返回 `{elapsedMs}`，配合 [i] 打印耗时。 */
    inline fun time(): () -> String {
        val t0 = System.currentTimeMillis()
        return { "${System.currentTimeMillis() - t0}ms" }
    }
}