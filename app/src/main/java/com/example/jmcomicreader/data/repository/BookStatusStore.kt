package com.example.jmreader.data.repository

import com.example.jmreader.data.local.BlacklistDao
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.BookDao
import com.example.jmreader.data.local.TagLibraryDao
import com.example.jmreader.data.local.normalizeAuthorName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 漫画账本（v1.12.0 单表重构）：`comics` 一行 = 一本「看过」的漫画，行永不因删本地而删除。
 *
 * 由 BookStatusStore（comic_status 位掩码登记表）重写而来——位掩码退役，状态全部列化，
 * 本类是**唯一写入点**：看完/收藏/拉黑/登记看过都必须走这里（或其转发的 BookDao 定向 UPDATE），
 * 禁止再出现「books 副本 + 账本副本」双写（v1.11.x 数据漂移的根因）。
 *
 * 状态语义（用户心智模型 2026-10-08 定稿）：
 *  - 行存在 = 看过（打开过详情/阅读器/下载过/收藏过/拉黑过，含云同步拉回的号）；
 *  - favorite/finished/blocked = 看过范围内的三个独立标记；「没看完」= finished 的补集，无列；
 *  - localDir（Kotlin 字段 savedDir）= 物理下载状态，空 = 不在本地；「下载过」不再作为分类维度；
 *  - lastChapterId/lastPageIndex/lastReadAt = 阅读记忆（原 read_progress 表并入）；
 *  - recycleAt/trashed = 回收站宽限期标记（原 comic_status.ext JSON 列化）。
 *
 * @param bookDao       comics 表 dao（账本本体）
 * @param blacklistDao  blacklists 表 dao（作品级拉黑兜底判定）
 * @param tagLibraryDao tag_library 表 dao（保留注入：标签录入补偿的扩展点）
 */
class ComicLedger(
    private val bookDao: BookDao,
    private val blacklistDao: BlacklistDao,
    private val tagLibraryDao: TagLibraryDao
) {

    // ── 读 ──────────────────────────────────────────────────

    /** 账本全量流（等价书架 observeAll：行即状态，无独立状态表）。 */
    fun observeAll(): Flow<List<Book>> = bookDao.observeAll()

    /** 某本账本行（null = 从未看过/未同步）。 */
    suspend fun get(bookId: Int): Book? = bookDao.get(bookId)

    /** 作品/作者是否被拉黑：行 blocked 列 + blacklists 作品条目兜底（作者级在 blacklists）。 */
    suspend fun isBookBlocked(bookId: Int): Boolean {
        if (bookDao.get(bookId)?.blocked == 1) return true
        val blacklists = blacklistDao.observeAll().first()
        return blacklists.any { it.type == "work" && it.value.toIntOrNull() == bookId }
    }

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

    /** 拉黑标记（作品级；用户改 blacklists 后由门面调这里同步列）。 */
    suspend fun setBlocked(bookId: Int, blocked: Boolean) {
        ensureRow(bookId)
        bookDao.setBlocked(bookId, if (blocked) 1 else 0)
    }

    /** 一键拉黑作者：该作者全部账本行 blocked 列置/清（归一化匹配，兼容 raw author）。 */
    suspend fun syncBlockedForAuthor(author: String, blocked: Boolean) {
        val norm = normalizeAuthorName(author)
        bookDao.getAll().forEach { b ->
            if (normalizeAuthorName(b.author) == norm) {
                bookDao.setBlocked(b.id, if (blocked) 1 else 0)
            }
        }
    }

    // ── 删除 / 隐私 ─────────────────────────────────────────

    /** 彻底删除某本账本行（隐私入口；真删——书架行同样消失，调用方须二次确认）。 */
    suspend fun deleteRow(bookId: Int) {
        bookDao.get(bookId)?.let { bookDao.delete(it) }
    }

    /**
     * 清空阅读画像（设置页隐私入口）：
     *  - 不在本地且未收藏的纯账本行 → 整行删（浏览痕迹清除）；
     *  - 在架/收藏行 → 保留行与 finished/favorite/blocked，只清阅读记忆（viewCount/进度/lastReadAt）。
     */
    suspend fun clearReadingProfile() {
        bookDao.deleteLedgerOnlyRows()
        bookDao.clearReadingMemory()
    }

    // ── 标签库标记（v5.8.0 TAGS_RECORDED 位的单表等价实现）────────

    /**
     * 标签是否已录入标签库：位掩码退役后以「行存在且 tags 非空」判定——
     * 有真实标签才有东西可录；调用方（BookTagEngine）先查 tags 本身，本判定只做兜底去重。
     */
    suspend fun hasTagsRecorded(bookId: Int): Boolean =
        bookDao.get(bookId)?.tags?.isNotBlank() == true
}
