package com.example.jmreader.data.repository

import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.BookDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 回收站域（v7.1.3 宽限期模型；v1.12.0 标记从 comic_status.ext JSON 列化为 comics.recycleAt/trashed）。
 *
 * 正确语义：看完/手动删除 ≠ 立即删本地！
 * - 看完整本 → 只写宽限期到期时间 `recycleAt`（now + 7 天），**本地内容保留**，书架「看完」区可直接点进去看；
 * - 宽限期内再打开阅读器（该书仍 finished）→ 续期 `recycleAt` = now + 7 天；
 * - 7 天扫描（App 启动 + 回收站页进入）：`recycleAt` 过期 → 删除本地内容（保留账本行/封面/记录），标 `trashed=1`；
 *   想再看需重新下载（下载完成自动清标记拾回）；
 * - 手动删除（书架卡片删除按钮）→ 立即删本地 + `trashed=1`；
 * - 彻底删除（回收站页用户确认）→ removeBookCompletely 清本地/章节/下载记录/阅读记忆。
 */
class RecycleManager(
    private val bookDao: BookDao,
    private val deleteLocalKeepBookFn: suspend (Int) -> Unit,
    private val removeBookCompletelyFn: suspend (Int) -> Unit
) {
    /** 宽限期天数：看完后 7 天未再阅读则删除本地内容。 */
    val RETENTION_DAYS: Int = 7

    /** 回收站条目（v1.12.0：book 账本行即快照，不再有 books/状态行两份）。 */
    data class TrashItem(
        val bookId: Int,
        val title: String,
        val author: String,
        val coverUrl: String,
        /** 宽限期到期时间戳（ms）；trashed 后为 0。 */
        val recycleAt: Long,
        /** 本地内容是否已被删（需重新下载）。 */
        val trashed: Boolean,
        /** 剩余天（宽限中 = 还剩几天；已删 = 0）。 */
        val remainDays: Int,
        val book: Book?
    )

    /**
     * 看完标号（退出阅读器且 finished=1）：写入宽限期 `recycleAt`（now + 7 天），**不删本地**。
     * 若之前已 trashed（旧数据），一并清掉。
     */
    suspend fun markFinishedRecycle(bookId: Int) {
        val book = bookDao.get(bookId) ?: return   // 刚看完的书必有账本行，行缺则忽略（防御）
        bookDao.upsert(
            book.copy(
                recycleAt = System.currentTimeMillis() + RETENTION_DAYS * 86_400_000L,
                trashed = 0
            )
        )
    }

    /** 宽限期内再次阅读：续期 `recycleAt` = now + 7 天（仅当该书 still finished 时由阅读器调用）。 */
    suspend fun renewRecycle(bookId: Int) {
        val book = bookDao.get(bookId) ?: return
        if (book.trashed == 1) return   // 已删本地的不续期（重新下载才会捡回）
        bookDao.upsert(book.copy(recycleAt = System.currentTimeMillis() + RETENTION_DAYS * 86_400_000L))
    }

    /** 手动删除：立即删本地内容 + 标记 trashed=1（保留账本行/封面/记录，可重新下载）。 */
    suspend fun trashBook(bookId: Int) {
        deleteLocalKeepBookFn(bookId)
        bookDao.get(bookId)?.let { bookDao.upsert(it.copy(trashed = 1)) }
    }

    /** 拾回（重新下载完成）：清 trashed 与 recycleAt，本地内容已由下载重建。 */
    suspend fun restoreBook(bookId: Int) {
        bookDao.get(bookId)?.let { bookDao.upsert(it.copy(trashed = 0, recycleAt = 0)) }
    }

    /** 从回收站彻底删除（用户显式点「彻底删除」）：清本地/章节/下载记录/阅读记忆。 */
    suspend fun deleteFromRecycle(bookId: Int) = removeBookCompletelyFn(bookId)

    /** 回收站列表响应式流：SQL 直推「宽限中 或 已删本地」的行（原 ext JSON 全表遍历退役）。 */
    fun observeTrashed(): Flow<List<TrashItem>> = bookDao.observeTrashedRows().map { books ->
        books.map { b ->
            val trashed = b.trashed == 1
            TrashItem(
                bookId = b.id,
                title = b.title.ifBlank { "JM-${b.id}" },
                author = b.author,
                coverUrl = b.coverUrl,
                recycleAt = b.recycleAt,
                trashed = trashed,
                remainDays = remainDaysOf(b.recycleAt, trashed),
                book = b
            )
        }.sortedWith(compareByDescending<TrashItem> { !it.trashed }.thenByDescending { it.recycleAt })
    }

    /** 剩余天数：宽限中 = 距到期还剩几天；已删 = 0。 */
    fun remainDaysOf(recycleAt: Long, trashed: Boolean): Int {
        if (trashed) return 0
        val remain = ((recycleAt - System.currentTimeMillis()) / 86_400_000L).toInt()
        return remain.coerceAtLeast(0)
    }

    /**
     * 7 天宽限扫描（App 启动 + 回收站页进入时调用）：
     * `recycleAt` 已过期的 → 删除本地内容（保留账本行/封面/记录）+ 标记 trashed=1。
     * 注意：**不再自动 removeBookCompletely**（用户要求"留下封面，以后想看了再下载"）。
     */
    suspend fun checkRecycleExpiry() {
        val now = System.currentTimeMillis()
        bookDao.getAll().forEach { b ->
            if (b.recycleAt > 0 && b.trashed == 0 && b.recycleAt < now) {
                deleteLocalKeepBookFn(b.id)
                bookDao.get(b.id)?.let { fresh ->
                    bookDao.upsert(fresh.copy(trashed = 1, recycleAt = 0))
                }
            }
        }
    }
}
