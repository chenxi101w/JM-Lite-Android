package com.example.jmreader.data.download

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.jmreader.JmComicApplication
import com.example.jmreader.data.repository.JmRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 下载保活 + 通知服务（v1.10.0，对齐 YOMII 的前台服务体验）。
 *
 * **只负责保活与通知，不承载下载逻辑**——下载队列 v2.0 仍归 DownloadManager
 * （Application 协程），本服务职责：
 *  1. 有活跃下载时进入前台（`dataSync` 类型）提升进程优先级——
 *     切桌面 / 其它 App 时系统不轻易杀进程，下载不中断；
 *  2. 每秒轮询进度文件（`.jm_progress/{albumId}.json`，与下载管理页同一真源），
 *     发布每本进度通知 + 汇总通知；
 *  3. 任务终态（进度文件 DONE/FAILED，或记录离开下载中）发布结果通知一次。
 *
 * 常驻循环：Application 启动即拉起（START_STICKY），无活跃下载时不进前台、
 * 只空转等待（1s 一拍，开销可忽略）；有活跃下载即转前台保活。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loopStarted = false

    @Volatile
    private var inForeground = false

    /** 已发过终态通知的专辑（防重复发；专辑回到下载中时清除）。 */
    private val finalNotified = mutableSetOf<Int>()

    /** 上一轮的下载中集合（检测「刚离开下载中」的专辑，补发终态通知）。 */
    @Volatile
    private var lastActive: Set<Int> = emptySet()

    /** 标题缓存（书名几乎不变，避免每秒查库）。 */
    private val titleCache = HashMap<Int, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadNotifier.ensureChannels(this)
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startLoop()
        return START_STICKY   // 系统回收后重建，循环自愈
    }

    private fun startLoop() {
        if (loopStarted) return
        loopStarted = true
        scope.launch {
            while (true) {
                runCatching { tick() }
                delay(1000)
            }
        }
    }

    private suspend fun tick() {
        val repo = (application as JmComicApplication).repository
        val rows = repo.getDownloads()
        val actives = rows.filter { it.status == "DOWNLOADING" }
        val activeIds = actives.map { it.bookId }.toSet()

        // 终态补发：上一轮在下载、这一轮离开的（正常完成 / 失败 / 记录被删）
        for (id in lastActive - activeIds) {
            if (id in finalNotified) continue
            val row = rows.find { it.bookId == id }
            when (row?.status) {
                "DONE" -> DownloadNotifier.finished(this, id, titleFor(repo, id), ok = true, error = null)
                "FAILED" -> DownloadNotifier.finished(this, id, titleFor(repo, id), ok = false, error = "下载失败，可在下载管理重试")
                else -> DownloadNotifier.cancelProgress(this, id)   // 记录已删（取消 / 清空）→ 只撤进度通知
            }
        }
        lastActive = activeIds

        if (actives.isEmpty()) {
            if (inForeground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                inForeground = false
            }
            titleCache.clear()
            return
        }

        enterOrUpdateForeground(actives.size)

        for (dl in actives) {
            val prog = runCatching { repo.getDownloadProgress(dl.bookId) }.getOrNull() ?: continue
            val title = titleFor(repo, dl.bookId)
            when (prog.status) {
                "DOWNLOADING" -> {
                    finalNotified.remove(dl.bookId)
                    DownloadNotifier.progress(this, dl.bookId, title, prog.done, prog.total)
                }
                "DONE" -> if (finalNotified.add(dl.bookId)) {
                    DownloadNotifier.finished(this, dl.bookId, title, ok = true, error = null)
                }
                "FAILED" -> if (finalNotified.add(dl.bookId)) {
                    DownloadNotifier.finished(this, dl.bookId, title, ok = false, error = "下载失败，可在下载管理重试")
                }
                else -> { /* NOT_FOUND：预取阶段，进度通知显示「正在准备…」 */ }
            }
        }
        finalNotified.removeAll { it !in activeIds }
    }

    private fun enterOrUpdateForeground(activeCount: Int) {
        val n = DownloadNotifier.summary(this, activeCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DownloadNotifier.SUMMARY_NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(DownloadNotifier.SUMMARY_NOTIFICATION_ID, n)
        }
        inForeground = true
    }

    private suspend fun titleFor(repo: JmRepository, albumId: Int): String =
        titleCache.getOrPut(albumId) {
            runCatching { repo.getBook(albumId)?.title }.getOrNull() ?: "JM-$albumId"
        }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
