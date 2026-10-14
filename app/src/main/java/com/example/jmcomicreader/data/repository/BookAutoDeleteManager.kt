package com.example.jmreader.data.repository

import com.example.jmreader.data.local.BookDao
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 章节读完自动删除（4.2.4 起；v7.1.0 整本「看完自动删除」已由回收站机制取代，仅保留本章功能）。
 *
 * v7.0.0 从 BookLibrary 拆出，只负责「退出阅读器后延迟删已读完章节本地图」类后台任务，
 * 不碰书籍正文数据。需要 config（延迟秒数）、book 表、取消下载回调、详情失效回调，
 * 以及「删已读完章节图片」能力（为避免反向依赖 BookLibrary 造成循环引用，
 * 以回调 [deleteFinishedChaptersFn] 注入）。
 *
 * @param configProvider 读取配置（reader_auto_delete_chapter_delay）
 * @param saveDir        下载落盘目录（删本地图片/进度文件用）
 * @param bookDao        comics 表 dao（deleteLocalKeepBook 清 localDir；v1.12.0 单表，账本行保留）
 * @param canceller      取消下载回调（删本地前先停掉在跑的下载）
 * @param onDetailInvalidated 详情内存缓存失效回调
 * @param deleteFinishedChaptersFn 删某本所有已读完章节的本地图（原 BookLibrary.deleteFinishedChapters）
 */
class BookAutoDeleteManager(
    private val configProvider: suspend (String) -> String?,
    private val saveDir: String,
    private val bookDao: BookDao,
    private val canceller: suspend (Int) -> Unit,
    private val onDetailInvalidated: (Int) -> Unit,
    private val deleteFinishedChaptersFn: suspend (Int) -> Unit
) {

    // 常驻后台作用域（不随任何页面 VM 销毁而取消）：章节读完自动删除等后台任务用
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 退出阅读器且开启「读完自动删本章」时，延迟 N 秒后删除该本所有「已读完」章节的本地图片。
    // 与整本删除同理：重入同本（loadChapter）会取消待执行的删除（cancelPendingChapterDelete），
    // 任务挂在 scope（常驻），退出页面也不会被取消。宽限期由 reader_auto_delete_chapter_delay 控制（默认 30s）。
    // 同样采用「取消标记 + 延迟复核」竞态防护。
    private val pendingChapterDelete = ConcurrentHashMap<Int, Job>()
    private val cancelledChapterDelete = ConcurrentHashMap<Int, Boolean>()

    /** 退出阅读器时调用：若开启「读完自动删本章」，延迟后删除该本所有已读完章节的本地图片；重入会先取消旧的。 */
    fun scheduleChapterDelete(bookId: Int) {
        cancelledChapterDelete.remove(bookId)
        pendingChapterDelete[bookId]?.cancel()
        pendingChapterDelete.remove(bookId)
        val job = scope.launch {
            val delaySec =
                configProvider("reader_auto_delete_chapter_delay")?.toIntOrNull()?.coerceAtLeast(0) ?: 30
            if (delaySec <= 0) { deleteFinishedChaptersFn(bookId); return@launch }
            delay(delaySec * 1000L)
            pendingChapterDelete.remove(bookId)
            if (cancelledChapterDelete[bookId] != true) {
                deleteFinishedChaptersFn(bookId)
            } else {
            }
            cancelledChapterDelete.remove(bookId)
        }
        pendingChapterDelete[bookId] = job
    }

    /** 重新打开某本漫画时调用：取消其待执行的章节自动删除（重入即取消）。 */
    fun cancelPendingChapterDelete(bookId: Int) {
        pendingChapterDelete[bookId]?.cancel()
        pendingChapterDelete.remove(bookId)
        cancelledChapterDelete[bookId] = true
    }

    /**
     * 删除某本漫画的本地图片，但**保留 books 行与下载记录**（savedDir 清空、cached 归零、finished 保留），
     * 供「回收站进站」（v7.1.0）与旧「看完自动删除」显式清理。
     * 4.2.0 起不再删 downloadDao（下载记录保留，供「下载管理/重新下载」查阅）。
     */
    suspend fun deleteLocalKeepBook(bookId: Int) {
        runCatching { canceller(bookId) }
        withContext(Dispatchers.IO) {
            val albumDir = File(saveDir, bookId.toString())
            if (albumDir.exists()) albumDir.deleteRecursively()
            val progFile = File(saveDir, ".jm_progress" + File.separator + "$bookId.json")
            if (progFile.exists()) progFile.delete()
        }
        // 4.2.0：保留 downloadDao（下载记录）与阅读进度（v1.12.0 起在 comics 行列里），重新下载后恢复续读。
        // v1.12.0 单表：清 localDir 即「本地已删」，账本行保留（可重下/喂算法），无需再清位。
        bookDao.get(bookId)?.let { bookDao.upsert(it.copy(savedDir = "", cached = 0)) }
        onDetailInvalidated(bookId)
    }

    /** 删除某本漫画的本地图片目录与进度文件（防止「已下载删除」后残留占用存储）。internal 供同模块 BookLibrary 在 removeBook/removeBookCompletely 中调用。 */
    internal suspend fun deleteBookFiles(bookId: Int) {
        withContext(Dispatchers.IO) {
            val albumDir = File(saveDir, bookId.toString())
            if (albumDir.exists()) albumDir.deleteRecursively()
            val progFile = File(saveDir, ".jm_progress" + File.separator + "$bookId.json")
            if (progFile.exists()) progFile.delete()
        }
    }
}
