package com.example.jmreader.data.repository

import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.BookDao
import kotlinx.coroutines.flow.Flow

/**
 * 作者名归一化（v1.17.0 起随屏蔽功能下线，从 data/local/ComicStatus.kt 迁入此处）。
 * 去首尾空白、转小写、合并内部空白。
 */
internal fun normalizeAuthorName(a: String): String =
    a.trim().lowercase().replace(Regex("\\s+"), "")

/**
 * 漫画账本（v1.12.0 单表重构）：`comics` 一行 = 一本「看过」的漫画，行永不因删本地而删除。
 *
 * 本类是**唯一写入点**：看完/收藏/登记看过都必须走这里（或其转发的 BookDao 定向 UPDATE），
 * 禁止再出现「books 副本 + 账本副本」双写（v1.11.x 数据漂移的根因）。
 *
 * 状态语义（用户心智模型 2026-10-08 定稿）：
 *  - 行存在 = 看过（打开过详情/阅读器/下载过/收藏过，含云同步拉回的号）；
 *  - favorite/finished = 看过范围内的两个独立标记；「没看完」= finished 的补集，无列；
 *  - localDir（Kotlin 字段 savedDir）= 物理下载状态，空 = 不在本地；
 *  - lastChapterId/lastPageIndex/lastReadAt = 阅读记忆（原 read_progress 表并入）。
 *
 * v1.17.0 轻量化：拉黑（blocked 列 + blacklists 表）与标签库依赖一并下线，
 * 构造函数不再需要 BlacklistDao / TagLibraryDao。
 *
 * @param bookDao comics 表 dao（账本本体）
 */
class ComicLedger(
    private val bookDao: BookDao
) {

    // ── 读 ──────────────────────────────────────────────────

    /** 账本全量流（等价书架 observeAll：行即状态，无独立状态表）。 */
    fun observeAll(): Flow<List<Book>> = bookDao.observeAll()

    /** 某本账本行（null = 从未看过/未同步）。 */
    suspend fun get(bookId: Int): Book? = bookDao.get(bookId)

    // ── 写（唯一写入点）──────────────────────────────────────

    /**
     * 确保账本行存在（「看过」登记入口）。行已存在则无操作（状态列由各自定向 UPDATE 维护，
     * 绝不在 ensure 时整体覆盖——这是旧 seedStatus 整行 upsert 覆盖位掩码的老坑，勿回退）。
     * @param seed 行不存在时用于落行的元数据（详情页/下载路径带上，避免空行）。
     */
    suspend fun ensureRow(bookId: Int, seed: Book? = null) {
        if (bookDao.get(bookId) != null) return
        val now = System.currentTimeMillis()
        bookDao.upsert(
            seed?.copy(id = bookId, addedAt = if (seed.addedAt > 0) seed.addedAt else now)
                ?: Book(id = bookId, title = "", author = "", addedAt = now)
        )
    }

    /** 看完标记（没看完 = finished 补集，无独立列）。 */
    suspend fun setFinished(bookId: Int, finished: Boolean) {
        ensureRow(bookId)
        bookDao.setFinished(bookId, if (finished) 1 else 0)
    }

    /** 收藏标记。 */
    suspend fun setFavorite(bookId: Int, favorite: Boolean) {
        ensureRow(bookId)
        bookDao.setFavorite(bookId, if (favorite) 1 else 0)
    }

    // ── 删除 / 隐私 ─────────────────────────────────────────

    /** 彻底删除某本账本行（隐私入口；真删——书架行同样消失，调用方须二次确认）。 */
    suspend fun deleteRow(bookId: Int) {
        bookDao.get(bookId)?.let { bookDao.delete(it) }
    }

    /**
     * 清空阅读画像（设置页隐私入口）：
     *  - 不在本地且未收藏的纯账本行 → 整行删（浏览痕迹清除）；
     *  - 在架/收藏行 → 保留行与 finished/favorite，只清阅读记忆（viewCount/进度/lastReadAt）。
     */
    suspend fun clearReadingProfile() {
        bookDao.deleteLedgerOnlyRows()
        bookDao.clearReadingMemory()
    }
}