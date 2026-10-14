package com.example.jmreader.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 下载通知发布器（v1.10.0，对齐 YOMII 的通知体验）：
 *  - 每本独立进度通知（章/图片进度条，静默低优先级）
 *  - 完成 / 失败走「结果」通道（默认优先级，可点击清除）
 *  - 前台服务的汇总通知也由这里构造
 *
 * 由 [DownloadService] 每秒轮询进度文件（`.jm_progress/{albumId}.json`，与
 * 下载管理页同一真源）后调用；本对象只管通道与通知构造，不做状态判断。
 */
object DownloadNotifier {

    private const val CHANNEL_PROGRESS = "download_progress"
    private const val CHANNEL_ALERT = "download_alert"
    const val SUMMARY_NOTIFICATION_ID = 10086

    @Volatile
    private var channelsReady = false

    fun ensureChannels(ctx: Context) {
        if (channelsReady) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "下载进度", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "漫画下载进度（静默）" }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "下载结果", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "下载完成 / 失败通知" }
            )
        }
        channelsReady = true
    }

    /** 每本漫画的实时进度通知（notification id = albumId，天然去重）。 */
    fun progress(ctx: Context, albumId: Int, title: String, done: Int, total: Int) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
            .setContentTitle("正在下载：$title")
            .setContentText(if (total > 0) "$done/$total 张图片" else "正在准备…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (total > 0) setProgress(total, done, false) else setProgress(0, 0, true)
            }
            .build()
        notify(ctx, albumId, n)
    }

    /** 终态通知（完成 / 失败），同时撤掉对应进度通知。 */
    fun finished(ctx: Context, albumId: Int, title: String, ok: Boolean, error: String?) {
        ensureChannels(ctx)
        val nm = notificationManager(ctx)
        nm.cancel(albumId)   // 撤进度通知
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setContentTitle(if (ok) "下载完成：$title" else "下载失败：$title")
            .setContentText(error?.takeIf { it.isNotBlank() } ?: if (ok) "已存入书架" else "可在下载管理重试")
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        // 结果通知用独立 id 段（100000+），与进度通知互不覆盖
        notify(ctx, 100000 + (albumId % 100000), n)
    }

    /** 前台服务汇总通知（「正在下载 N 本」）。 */
    fun summary(ctx: Context, activeCount: Int): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
            .setContentTitle("正在下载漫画")
            .setContentText("$activeCount 本进行中")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /** 撤掉某本的进度通知（用户取消 / 记录删除时）。 */
    fun cancelProgress(ctx: Context, albumId: Int) {
        notificationManager(ctx).cancel(albumId)
    }

    private fun notify(ctx: Context, id: Int, n: Notification) {
        notificationManager(ctx).notify(id, n)
    }

    private fun notificationManager(ctx: Context): NotificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
