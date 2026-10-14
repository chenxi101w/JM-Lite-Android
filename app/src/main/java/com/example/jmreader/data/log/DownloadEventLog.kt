package com.example.jmreader.data.log

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **统一下载日志**（v1.9.2）——所有下载的记录集中在这**一个文件**里。
 *
 * 用户需求原文（第三次澄清）：
 * 「一个日志记录所有下载，1s 一采集进度，一整本记录放一起，
 *   所有记录放一个地方方便你（AI）查阅」
 *
 * 设计：
 *  - **单文件**：`{saveDir}/.jm_progress/download_log.txt`
 *    —— adb 一条 `cat` / `pull` 就能拿到全部历史，随时可查；
 *  - **1 秒采集**：下载中每秒一条进度行（含 MB/s、张数、已传量、用时）；
 *  - **按书分组**：每行都带 `[#{albumId}]` 标记，同一本书的行自然连续，
 *    `readFor(albumId)` 可按书过滤出完整记录；
 *  - **触发型事件混排**：开始 / 章节清单 / 完成（平均+峰值速度）/ 失败原因 /
 *    停滞 / 强制重下，发生即写；
 *  - **轮转**：超过 10MB 改名为 `.old.txt` 重开，防止无限膨胀；
 *  - 行格式：`[MM-dd HH:mm:ss] [#albumId] 内容`
 *
 * App 内查看：下载管理页每本书的「日志」按钮 → [readFor] 过滤该书最近记录。
 */
object DownloadEventLog {

    private const val TAG = "DownloadLog"
    private const val MAX_BYTES = 10L * 1024 * 1024
    private val TS = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    fun fileFor(saveDir: File): File = File(File(saveDir, ".jm_progress"), "download_log.txt")

    /** 统一写入口：自动加时间戳与 `[#albumId]` 书标记。 */
    fun event(saveDir: File, albumId: Int, message: String) {
        runCatching {
            val f = fileFor(saveDir)
            rotateIfNeeded(f)
            if (!f.parentFile!!.exists()) f.parentFile!!.mkdirs()
            val ts = TS.format(Date())
            synchronized(this) {
                f.appendText("[$ts] [#$albumId] $message\n")
            }
        }.onFailure { Log.w(TAG, "写下载日志失败 album=$albumId", it) }
    }

    /** 每秒进度采集行（v1.9.2：采样协程每秒调用一次）。 */
    fun speed(
        saveDir: File,
        albumId: Int,
        mbPerSec: Double,
        done: Int,
        total: Int,
        gotMb: Double,
        sec: Long,
    ) = event(
        saveDir, albumId,
        "%.1fMB/s | %d/%d张 | %.0fMB | 用时%ds".format(mbPerSec, done, total, gotMb, sec)
    )

    /**
     * 读取某本书的日志（从统一文件按 `[#id]` 过滤，返回**最近** [limit] 条）。
     * 返回 null = 统一日志不存在或该书没有记录。
     */
    fun readFor(saveDir: File, albumId: Int, limit: Int = 400): String? {
        val f = fileFor(saveDir)
        if (!f.isFile) return null
        val tag = "[#$albumId] "
        val lines = synchronized(this) {
            f.readLines().filter { it.contains(tag) }
        }
        if (lines.isEmpty()) return null
        return if (lines.size <= limit) lines.joinToString("\n")
        else "…（更早的 ${lines.size - limit} 条已省略）\n" + lines.takeLast(limit).joinToString("\n")
    }

    /** 超过上限就轮转：当前 → `.old.txt`（覆盖旧的），新日志从头开始。 */
    private fun rotateIfNeeded(f: File) {
        if (f.isFile && f.length() > MAX_BYTES) {
            val old = File(f.parentFile, f.nameWithoutExtension + ".old.txt")
            old.delete()
            f.renameTo(old)
        }
    }
}
