package com.example.jmreader.data.repository

import android.util.Log
import com.example.jmreader.data.api.JmApiClient
import com.example.jmreader.data.local.AppDatabase
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.Chapter
import com.example.jmreader.data.local.SystemConfig
import com.example.jmreader.data.mapper.toBook
import com.example.jmreader.data.mapper.toChapter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 书架域：书籍/章节/进度读写、在线抓取、查看次数与智能缓存、书架排序。
 * 从 JmRepository 拆出。
 *
 * @param cacheDir 在线阅读图片缓存目录
 * @param saveDir  下载落盘目录（智能缓存/淘汰复用）
 * @param configProvider 读取配置（auto_cache 等），由门面注入
 * @param onDetailInvalidated 详情内存缓存失效回调
 */
class BookLibrary(
    private val db: AppDatabase,
    private val cacheDir: String,
    private val saveDir: String,
    private val configProvider: suspend (String) -> String?,
    private val canceller: suspend (Int) -> Unit,
    private val onDetailInvalidated: (Int) -> Unit
) {
    private val bookDao = db.bookDao()
    private val chapterDao = db.chapterDao()
    private val downloadDao = db.downloadDao()
    private val configDao = db.systemConfigDao()

    // v1.12.0：漫画账本（原 BookStatusStore 位掩码逻辑重写为 ComicLedger，唯一写入点）。
    private val ledger by lazy {
        ComicLedger(bookDao)
    }

    // ── 在线抓取 ──────────────────────────────────────────────

    /**
     * v1.28.0：允许透传给接口的排序枚举白名单。
     * mr 最新 / mv 最多观看（≈最多喜欢） / mp 最多图片 / tr 标题
     * md 总排名 / mv_m 月排名 / mv_w 周排名 —— 后三个取值**待真机验证**，
     * 验证方式是搜一次看日志：若出现「order=xx 无结果，回落 mr 重试」即说明接口不认。
     */
    private val SUPPORTED_ORDERS = setOf("mr", "tf", "mv", "mp", "tr")
    /** v1.28.1：time 白名单（a 全部 / w 周 / m 月）。与 mv 组合成周/月排名。 */
    private val SUPPORTED_TIMES = setOf("a", "w", "m")

    /**
     * 搜索漫画。
     * mode: keyword | author | tags | combined | jmid
     * orderBy 取值：mr 最新 / mv 浏览 / mp 收藏 / tr 标题；time 取值：a 全部 / t 今天 / w 本周 / m 本月
     * tagsLogic: or | and；tagsJson: 标签数组 JSON。
     * v1.17.0：aliasesJson / blocksJson 两个入参随别名、屏蔽功能下线删除（本就没被函数体用到）。
     */
    suspend fun search(
        keyword: String,
        mode: String,
        page: Int = 1,
        orderBy: String = "mr",
        time: String = "a",
        tagsLogic: String = "or",
        tagsJson: String = "",
    ): List<Book> {
        // v1.4.0：Chaquopy 已移除 → 纯原生。
        // 只支持「纯关键词」这一档：时间 time 与标签 tagsJson 的接口参数枚举未逐一验证。
        // v1.28.0：**放开 orderBy**。首页新增排序按钮（最新/最多喜欢/总排名/月排名/周排名），
        // 不再只放行 mr；取值合法性交给下面的「回落」兜底，而不是在这里一刀切拒掉。
        if (mode != "keyword" || tagsJson.isNotBlank() || time !in SUPPORTED_TIMES || orderBy !in SUPPORTED_ORDERS) {
            Log.w(
                "BookLibrary",
                "搜索条件暂不支持（mode=$mode orderBy=$orderBy time=$time 有标签=${tagsJson.isNotBlank()}）"
            )
            return emptyList()
        }
        val books = JmApiClient.search(keyword, page, orderBy, time)
        if (books.isNullOrEmpty()) {
            // v1.28.0：非默认排序**回落**。order 的取值没有在真机逐个验证过，
            // 若接口不认这个枚举 → 返回空 → 用 mr 再试一次。
            // 这样最坏情况只是"排序没生效"，绝不会变成"搜不出东西"。
            if (orderBy != "mr") {
                Log.w("BookLibrary", "order=$orderBy 无结果，回落 mr 重试：$keyword")
                val fallback = JmApiClient.search(keyword, page, "mr", "a")
                if (!fallback.isNullOrEmpty()) return fallback
            }
            Log.w("BookLibrary", "原生搜索无结果：$keyword")
            return emptyList()
        }
        return books
    }

    /** 获取详情并写入书架（books 与 chapters 表）。返回 Book 与章节列表。 */
    suspend fun getDetail(albumId: Int): Pair<Book, List<Chapter>> {
        // ── v1.3.4：优先走原生 API（Kotlin → OkHttp → JSON），绕开 Python ──
        // 与搜索同一套思路：Chaquopy 缺 pip 依赖时 Python 链路整体不可用，
        // 详情页是最依赖它的页面，必须能走原生兜住。
        // 失败（离线 / 线路全挂 / 协议变更）→ 静默回退 Python。
        val native = runCatching { JmApiClient.albumDetail(albumId) }
            .onFailure { Log.w("BookLibrary", "原生详情失败，回退 Python：${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        if (native != null) {
            val (nBook, nChapters) = native
            // v1.12.0 热修：以**本地行为基底**，只刷新网络元数据——
            // 单表后 finished/favorite/blocked/viewCount/进度/回收站标记全在行里，
            // 原写法（nBook.copy 保留 4 个字段）联网打开详情一次就把它们全清零（新数据漂移源）。
            val ex = bookDao.get(albumId)
            val book = (ex ?: nBook).copy(
                title = nBook.title,
                author = nBook.author,
                coverUrl = nBook.coverUrl,
                tags = nBook.tags,
                description = nBook.description,
                pageCount = nBook.pageCount,
                // 新行用网络的 addedAt；已有行保留本地「加入时间」（书架排序依据）
                addedAt = ex?.addedAt ?: nBook.addedAt,
                savedDir = ex?.savedDir ?: "",
                cached = ex?.cached ?: 0
            )
            bookDao.upsert(book)
            // 合并已读标记：网络章节 finished=0，不能覆盖本地「已读完」
            val finishedMap = runCatching { chapterDao.get(albumId).associate { it.id to it.finished } }
                .getOrDefault(emptyMap())
            // v1.12.0：单章漫画（JM 单行本 photoId≡bookId）不入章节表——省行、少一态，
            // 阅读路由直接用真实 chapterId（= bookId），本地目录扫描兜底（rebuildChaptersFromLocal）
            val isSingleChapter = nChapters.size == 1 && nChapters[0].id == albumId
            if (nChapters.isNotEmpty() && !isSingleChapter) {
                val merged = nChapters.map { if (finishedMap[it.id] == 1) it.copy(finished = 1) else it }
                runCatching { chapterDao.upsertAll(merged) }
            }
            return book to (if (nChapters.isEmpty()) chapterDao.get(albumId) else nChapters)
        }

        // v1.4.0：Chaquopy 已移除，原生失败直接抛出（由调用方/ViewModel 显示错误）
        error("获取详情失败：接口不可用（$albumId）")
    }

    /**
     * 本地详情：仅当该书已下载（savedDir 非空且章节已入库）时从 DB 返回，
     * 供「阅读」页离线秒开；否则返回 null 由调用方回退联网详情。
     */
    suspend fun getLocalDetail(bookId: Int): Pair<Book, List<Chapter>>? {
        val book = bookDao.get(bookId) ?: return null
        if (book.savedDir.isBlank()) return null
        var chapters = chapterDao.get(bookId)
        // v1.1.1：老数据兜底 —— 只写过 books 没写过 chapters 的下载，
        // 本地图片其实都在，直接读表会返回 null 导致「离线进不了详情页」。
        if (chapters.isEmpty()) chapters = rebuildChaptersFromLocal(book)
        if (chapters.isEmpty()) return null
        return book to chapters
    }

    /**
     * v1.1.1：chapters 表为空但本地已有下载目录时，从目录结构重建章节并落库。
     *
     * 目录约定（见 jm_bridge.download_album）：`savedDir/{sort:03d}/{idx:03d}.jpg`。
     * 重建时以 sort 兼作章节 id —— 阅读器只用 `id` 匹配章节、用 `sort` 定位目录，
     * 自洽即可（早期数据没有真实 photo id，无从还原）。
     * 目的：让历史下载（无章节记录）也能离线打开阅读，无需重新下载。
     */
    private suspend fun rebuildChaptersFromLocal(book: Book): List<Chapter> {
        // v1.1.2：目录扫描是磁盘 IO，切到 IO 线程执行（调用方可能在 Main，
        // 原实现会让主线程做 isDirectory/listFiles/isFile，StrictMode 会报 DiskReadViolation）。
        val rebuilt = withContext(Dispatchers.IO) {
            val dir = runCatching { File(book.savedDir) }.getOrNull() ?: return@withContext emptyList()
            if (!dir.isDirectory) return@withContext emptyList()
            val subDirs = dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return@withContext emptyList()
            // v1.12.0：目录只有一个章节子目录 = 单章漫画（单行本 photoId≡bookId），
            // 章节 id 用 bookId 对齐真实路由（详情页/迁移进度都传真实 chapterId）
            val single = subDirs.size == 1
            subDirs.mapNotNull { sub ->
                val sort = sub.name.trimStart('0').ifBlank { "0" }.toIntOrNull() ?: return@mapNotNull null
                if (sort <= 0) return@mapNotNull null
                // 目录里得有图片才算一章（避免半途失败留下的空目录变成假章节）
                val hasImage = sub.listFiles { f ->
                    f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
                }?.isNotEmpty() == true
                if (!hasImage) return@mapNotNull null
                Chapter(
                    bookId = book.id,
                    id = if (single) book.id else sort,
                    name = if (single) "全一话" else "第 $sort 话",
                    sort = sort
                )
            }.sortedBy { it.sort }
        }
        if (rebuilt.isNotEmpty()) {
            runCatching { chapterDao.upsertAll(rebuilt) }
        }
        return rebuilt
    }

    // ── 本地书架 / 进度 ───────────────────────────────────────

    fun observeBooks(): Flow<List<Book>> = bookDao.observeAll()
    suspend fun getBook(id: Int): Book? = bookDao.get(id)
    fun observeChapters(bookId: Int): Flow<List<Chapter>> = chapterDao.observe(bookId)

    /** 章节列表：表为空但有本地下载目录时从目录重建（v1.1.1，离线可读）。 */
    suspend fun getChapters(bookId: Int): List<Chapter> {
        val list = chapterDao.get(bookId)
        if (list.isNotEmpty()) return list
        val book = bookDao.get(bookId) ?: return emptyList()
        if (book.savedDir.isBlank()) return emptyList()
        return rebuildChaptersFromLocal(book)
    }

    /**
     * v1.12.0（vc71 修正点）：单章漫画旧章节残留清理，幂等，返回删除行数。
     * 旧版单行本按 id=sort=1 落库，与新约定（单章不入表 / 进度 chapterId=bookId）冲突；
     * 删除后离线打开自动 rebuild 为「全一话」，详见 ChapterDao.deleteLegacySingleChapterRows。
     */
    suspend fun purgeSingleChapterLegacyRows(): Int =
        runCatching { chapterDao.deleteLegacySingleChapterRows() }
            .getOrElse { Log.w("BookLibrary", "单章残留清理失败：${it.message}"); 0 }

    /** 写入/更新一本漫画的书架记录（搜索结果直接下载时，先把图书落到 books 表）。
     *  若该书已存在（例如之前已下载到本地书架），保留其 savedDir，避免联网详情覆盖导致本地记录「消失」。 */
    suspend fun saveBook(book: Book) {
        val existing = bookDao.get(book.id)
        // v6.0.3 修复：首次落库（搜索结果直接下载等）时若 addedAt 为 0（搜索结果 JSON 不带时间），
        // 补为当前时间——否则书架「下载时间」排序的 key 全为 0，排序顺序乱
        val merged = if (existing != null) book.copy(
            savedDir = existing.savedDir,
            viewCount = existing.viewCount,
            cached = existing.cached,
            tags = if (existing.tags.isNotBlank()) existing.tags else book.tags
        ) else book.copy(
            addedAt = if (book.addedAt > 0) book.addedAt else System.currentTimeMillis()
        )
        bookDao.upsert(merged)
    }

    /** 首章 id：local=true 走本地章节（已下载），否则尝试联网详情取首章（失败返回 null）。 */
    suspend fun firstChapterId(bookId: Int, local: Boolean): Int? {
        val chapters = if (local) getChapters(bookId)
        else (getLocalDetail(bookId)?.second ?: runCatching { getDetail(bookId).second }.getOrNull().orEmpty())
        return chapters.firstOrNull()?.id
    }

    /**
     * 写入/更新漫画关系状态（位掩码登记表 comic_status）的快照：title/author/coverUrl/tags/viewCount
     * + 由 books 当前状态（本地/读完/下载/收藏）与 blacklists（拉黑）计算的位掩码。
     * 本表不随 books 级联删除：删除本地漫画后行仍保留（清 LOCAL 位），故「已删但封面在/可重下」
     * 与「屏蔽所有下载过的」都能稳定成立。
     * @param touchRead 为 true 时刷新 lastReadAt（发生实际阅读），否则沿用旧值（如下载/仅落库）。
     */
    /**
     * 删除一本漫画的本地内容（用户主动「删除本地」走这里）。
     * **v1.12.0 单表语义**：账本行永不删（行即「看过」历史，元数据/进度/标记全保留），
     * 只删本地文件、清 localDir 与 cached、清章节表。重新下载后自动恢复（onDownloaded 重写 localDir）。
     *
     * **保留记录（4.2.0 起）**：downloads（下载记录）与阅读记忆不删——
     * 下载记录供「下载管理/重新下载」查阅，阅读记忆在重新下载后恢复续读。
     */
    suspend fun removeBook(book: Book) {
        deleteBookFiles(book.id)
        bookDao.get(book.id)?.let { bookDao.upsert(it.copy(savedDir = "", cached = 0)) }
        chapterDao.delete(book.id)
        onDetailInvalidated(book.id)
    }

    /** 按 id 删除本地内容（行不存在则只清文件）。语义同 [removeBook]。 */
    suspend fun removeBook(bookId: Int) {
        deleteBookFiles(bookId)
        bookDao.get(bookId)?.let { bookDao.upsert(it.copy(savedDir = "", cached = 0)) }
        onDetailInvalidated(bookId)
        chapterDao.delete(bookId)
    }

    /**
     * 彻底删除（下载管理「取消并删除」/ 回收站「彻底删除」专用，4.2.0）：与 removeBook 相反，
     * **连下载记录与阅读记忆一起删**——该入口语义是清理「下载一半出问题」的卡死残留，
     * 用户已明确确认删除。账本行与 finished/favorite/blocked 标记仍保留（屏蔽/算法种子需要），
     * 但回收站标记清零（条目从回收站消失）。
     */
    suspend fun removeBookCompletely(bookId: Int) {
        deleteBookFiles(bookId)
        onDetailInvalidated(bookId)
        chapterDao.delete(bookId)
        downloadDao.delete(bookId)
        bookDao.get(bookId)?.let {
            bookDao.upsert(
                it.copy(
                    savedDir = "", cached = 0,
                    viewCount = 0, lastReadAt = 0, lastChapterId = 0, lastPageIndex = 0
                )
            )
        }
    }

    // ── 漫画账本（v1.12.0：Book 即账本行，原 comic_status 位掩码域退役）──

    /** 账本全量流（= comics 全表，含已删本地的行）。 */
    fun observeLedger(): Flow<List<Book>> = bookDao.observeAll()

    /** 账本全量快照。 */
    suspend fun getLedger(): List<Book> = bookDao.getAll()

    /** 某本账本行（null = 从未看过/未同步）。 */
    suspend fun getLedgerEntry(bookId: Int): Book? = bookDao.get(bookId)

    /** 彻底删除某本账本行（隐私/防无限增长；书架行同步消失，调用方须确认）。 */
    suspend fun deleteLedgerEntry(bookId: Int) = ledger.deleteRow(bookId)

    /** 清空阅读画像（纯账本行删除 + 在架/收藏行清阅读记忆，标记保留）。 */
    suspend fun clearReadingProfile() = ledger.clearReadingProfile()

    /**
     * 启动数据体检退役（v1.12.0）：位掩码一致性核对的对象（comic_status）已随单表重构消失，
     * 迁移在 Room MIGRATION_13_14 一次性完成；单表后状态唯一写入点收敛于 ComicLedger，无漂移可补。
     */

    /** 漫画本体占用（设置页「关于」）：所有账本行 localDir 目录大小之和（字节）。
     *  v6.0.18：去重目录（多书同目录只算一次）+ 抛到 IO 线程，避免阻塞主线程。 */
    suspend fun getDownloadedBytes(): Long = withContext(Dispatchers.IO) {
        bookDao.observeAll().first()
            .map { it.savedDir }
            .filter { it.isNotBlank() }
            .distinct()   // 同一目录（异常情况下多书共享）只统计一次，避免重复计算
            .sumOf { dir ->
                runCatching {
                    java.io.File(dir).walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }.getOrDefault(0L)
            }
    }

    suspend fun saveProgress(bookId: Int, chapterId: Int, pageIndex: Int) {
        // v1.12.0：阅读记忆并入账本行（原 read_progress 表退役）。行缺失先兜底落行。
        ledger.ensureRow(bookId)
        bookDao.updateProgress(bookId, chapterId, pageIndex, System.currentTimeMillis())
    }

    /** 阅读记忆（v1.12.0：返回账本行，取 lastChapterId/lastPageIndex；null = 从未看过）。 */
    suspend fun getProgress(bookId: Int): Book? = bookDao.get(bookId)

    /** 进过阅读器（已读）的 bookId 全集——书架「已读/未读」批量勾选判定（v1.11.0 起）。
     *  v1.12.0：原 read_progress 有行 = 已读 → 账本行 viewCount/lastReadAt 非零 = 已读。 */
    suspend fun getReadBookIds(): List<Int> = bookDao.getReadBookIds()

    // ── 已下载集合（搜索隐藏/灰显、书架基础数据集用）─────────────

    /** 是否有下载记录（含已删本地的）——「曾下载过」判定（v1.12.0 替代原 DOWNLOADED 位）。 */
    suspend fun hasDownloadRecord(bookId: Int): Boolean = downloadDao.get(bookId) != null

    /** 当前仍在本地的已下载漫画 id 集合（savedDir 非空；已删除的不算）。
     *  v14 优化：过滤下推 SQL，只取 id 列，不再物化整行。 */
    suspend fun getDownloadedIds(): Set<Int> = bookDao.getDownloadedIds().toSet()

    /** 书架基础数据：仅已下载（savedDir 非空）的漫画，随下载/删除实时更新。
     *  v14 优化：由 `observeAll().map { filter }` 改为 SQL 下推（命中 index_books_savedDir），
     *  省掉「整表物化 + 调用线程过滤」两步开销。 */
    fun observeDownloadedBooks(): Flow<List<Book>> = bookDao.observeDownloaded()

    /** 已下载 id 集合流（搜索页「已下载屏蔽」判断）。只查 id 列，命中 index_books_savedDir。 */
    fun observeDownloadedIds(): Flow<List<Int>> = bookDao.observeDownloadedIds()

    /** 收藏 id 集合流（搜索卡片收藏态高亮）。只查 id 列，命中 index_books_favorite。 */
    fun observeFavoriteIds(): Flow<List<Int>> = bookDao.observeFavoriteIds()

    /** 已看完 id 集合流（书架沉底排序）。只查 id 列，命中 index_books_finished。 */
    fun observeFinishedIds(): Flow<List<Int>> = bookDao.observeFinishedIds()

    // ── 查看次数 / 智能缓存（1.6.0）─────────────────────────────

    /** 阅读/查看次数 +1 并刷新最后阅读时间（智能缓存排序 + 已读判定依据）。 */
    suspend fun bumpViewCount(bookId: Int) {
        ledger.ensureRow(bookId)
        bookDao.bumpViewCount(bookId, System.currentTimeMillis())
    }

    // ── 看完标记（2.0.0）────────────────────────────────────────

    /**
     * 标记已看完。若该书不在账本（open_directly 直达阅读器、从未进详情页的书），
     * 先联网拉详情落库再标记，避免「读完确认」静默失效。
     */
    suspend fun markFinished(bookId: Int) {
        if (bookDao.get(bookId) == null) {
            runCatching { getDetail(bookId) }   // 落库（失败则本次标记跳过，不阻塞）
        }
        ledger.setFinished(bookId, true)
    }
    suspend fun markUnfinished(bookId: Int) {
        ledger.setFinished(bookId, false)
    }
    suspend fun toggleFinished(bookId: Int) {
        if (bookDao.get(bookId) == null) {
            runCatching { getDetail(bookId) }   // 书架长按「标记看完」同样兜底
        }
        val b = bookDao.get(bookId) ?: return
        ledger.setFinished(bookId, b.finished != 1)
    }

    // ── 章节读完标识（v7：读了几章标几 + 读完自动删章）─────────

    suspend fun markChapterFinished(bookId: Int, chapterId: Int) {
        chapterDao.markFinished(bookId, chapterId)
    }

    suspend fun markChapterUnfinished(bookId: Int, chapterId: Int) {
        chapterDao.markUnfinished(bookId, chapterId)
    }

    /** 已读完章节数（详情页显示「已读 X/N」）。 */
    suspend fun countFinishedChapters(bookId: Int): Int = chapterDao.countFinished(bookId)

    /**
     * 删除某本漫画的本地图片目录与进度文件（删除本地/彻底删除共用）。
     *
     * v1.18.0：从 `BookAutoDeleteManager` 并入本类 —— 该类「读完自动删本章」主体已下线，
     * 只剩这一个方法还在用，为一个函数单开一个管理器不值得，故直接内联。
     * **先取消在跑的下载**再删目录：否则下载协程会把刚删掉的目录重新写出来，
     * 表现为「删了又回来」（这条保护原先写在已无调用方的 deleteLocalKeepBook 里，形同失效）。
     */
    private suspend fun deleteBookFiles(bookId: Int) {
        runCatching { canceller(bookId) }
        withContext(Dispatchers.IO) {
            val albumDir = File(saveDir, bookId.toString())
            if (albumDir.exists()) albumDir.deleteRecursively()
            val progFile = File(saveDir, ".jm_progress" + File.separator + "$bookId.json")
            if (progFile.exists()) progFile.delete()
        }
    }

    // ── 收藏（v5：搜索结果卡片左上角 + 书架「收藏」入口）─────────

    suspend fun markFavorite(bookId: Int) {
        ledger.setFavorite(bookId, true)
    }
    suspend fun unmarkFavorite(bookId: Int) {
        ledger.setFavorite(bookId, false)
    }
    suspend fun toggleFavorite(bookId: Int) {
        val b = bookDao.get(bookId) ?: return
        ledger.setFavorite(bookId, b.favorite != 1)
    }

    /** 当前收藏的漫画 id 集合（搜索卡片显示收藏态用）。v14 优化：过滤下推 SQL，命中 index_books_favorite。 */
    suspend fun getFavoriteIds(): Set<Int> = bookDao.getFavoriteIds().toSet()

    // ── 收藏导入解析 / 选择下载（4.7.0：导入前弹窗逐个选择是否下载）──
    /** 导入项（解析自备份 JSON），导入弹窗列表用。 */
    data class ImportItem(
        val id: Int,
        val title: String,
        val author: String,
        val coverUrl: String
    )
    /** 解析结果：可导入项 + 收藏标签 + 错误信息（error 非 null 表示失败）。 */
    data class ImportParseResult(
        val items: List<ImportItem>,
        val tags: List<String>,
        val error: String?
    )
    /** 纯解析备份 JSON，供导入弹窗预览（不写库、不联网）。 */
    fun parseImportFavorites(json: String): ImportParseResult {
        val j = runCatching { org.json.JSONObject(json) }.getOrNull()
            ?: return ImportParseResult(emptyList(), emptyList(), "文件不是有效的收藏备份")
        val items = mutableListOf<ImportItem>()
        j.optJSONArray("favoriteBooks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optInt("id")
                if (id <= 0) continue
                val title = o.optString("title").takeIf { it.isNotBlank() } ?: "JM-$id"
                items.add(
                    ImportItem(
                        id = id,
                        title = title,
                        author = o.optString("author").takeIf { it.isNotBlank() } ?: "",
                        coverUrl = o.optString("coverUrl", "")
                    )
                )
            }
        }
        val tags = j.optJSONArray("favTags")?.let { a ->
            (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() }
        } ?: emptyList()
        if (items.isEmpty() && tags.isEmpty())
            return ImportParseResult(emptyList(), emptyList(), "文件中没有可导入的收藏或标签")
        return ImportParseResult(items, tags, null)
    }

    // ── 收藏导出 / 导入（4.5.1：设置 → 数据 → 收藏备份）─────────

    /**
     * 导出收藏为 JSON 字符串：收藏漫画（id/title/author）+ 收藏标签（fav_tags）。
     * 用途：换机/重装前备份，或分享给另一台设备。
     */
    suspend fun exportFavorites(): String {
        val books = bookDao.observeAll().first()
            .filter { it.favorite == 1 }
            .map { b ->
                org.json.JSONObject()
                    .put("id", b.id)
                    .put("title", b.title)
                    .put("author", b.author)
                    .put("coverUrl", b.coverUrl)   // 4.13.0：补封面，修复导入后封面不加载
            }
        val favTags = (configProvider("fav_tags") ?: "")
            .split("|").map { it.trim() }.filter { it.isNotBlank() }
        return org.json.JSONObject()
            .put("app", "jmcomicreader")
            .put("type", "favorites")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("favoriteBooks", org.json.JSONArray(books))
            .put("favTags", org.json.JSONArray(favTags))
            .toString(2)
    }

    /**
     * 导入收藏并恢复：收藏漫画（books 行缺失时优先用 comic_status 快照重建，否则用导出数据兜底建最小行）
     * + 合并收藏标签（fav_tags，| 分隔，与现有去重合并）。
     * downloadIds 非空时，对其中的漫画额外触发整本下载（网络拉取 + 落库）；下载失败不影响已加入的收藏。
     * 返回人类可读的导入结果摘要。
     */
    /**
     * 导入收藏：全部项写 favorite=1（books + comic_status BIT_FAVORITE），标签合并。
     * 4.9.0 起**不再同步下载**——勾选下载的 id 由调用方交给 [com.example.jmreader.data.repository.DownloadManager.enqueueAlbums]
     * 后台队列执行（并发受 max_concurrent_downloads 控制），导入立即返回不阻塞 UI。
     * downloadIds 仅用于结果摘要统计（「N 本已加入后台下载队列」）。
     */
    suspend fun importFavorites(json: String, downloadIds: Set<Int> = emptySet()): String {
        val j = runCatching { org.json.JSONObject(json) }.getOrNull()
            ?: return "导入失败：文件不是有效的收藏备份"
        var books = 0
        val arr = j.optJSONArray("favoriteBooks")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optInt("id")
                if (id <= 0) continue
                val existing = bookDao.get(id)
                if (existing != null) {
                    bookDao.upsert(existing.copy(favorite = 1))
                } else {
                    bookDao.upsert(
                        Book(
                            id = id,
                            title = o.optString("title").takeIf { it.isNotBlank() } ?: "JM-$id",
                            author = o.optString("author"),
                            coverUrl = o.optString("coverUrl", ""),
                            favorite = 1,
                            addedAt = System.currentTimeMillis()
                        )
                    )
                }
                books++
            }
        }
        var tags = 0
        val tagsArr = j.optJSONArray("favTags")
        if (tagsArr != null) {
            val incoming = (0 until tagsArr.length())
                .map { tagsArr.optString(it).trim() }
                .filter { it.isNotBlank() }
                .toSet()
            if (incoming.isNotEmpty()) {
                val cur = (configProvider("fav_tags") ?: "")
                    .split("|").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                configDao.set(SystemConfig("fav_tags", (cur + incoming).joinToString("|")))
                tags = incoming.size
            }
        }
        val sb = StringBuilder("导入完成：收藏漫画 $books 本")
        if (downloadIds.isNotEmpty()) sb.append("，${downloadIds.size} 本已在后台开始下载（下载管理可看进度）")
        sb.append("，收藏标签 $tags 个")
        return sb.toString()
    }

    // ── 书架排序（2.0.0）────────────────────────────────────────

    /**
     * 书架排序：先按 sortMode 排（download 下载时间↓ / views_desc 阅读次数↓ / views_asc 阅读次数↑ /
     * preference 标签匹配数↓），若 unfinishedFirst 则把已看完(finished=1)稳定排到最后、未看完置顶。
     */
    fun sortLibraryBooks(
        books: List<Book>,
        sortMode: String,
        unfinishedFirst: Boolean,
        preferred: Set<String>
    ): List<Book> {
        val base = when (sortMode) {
            "views_desc" -> books.sortedByDescending { it.viewCount }
            "views_asc"  -> books.sortedBy { it.viewCount }
            "preference" -> books.sortedByDescending { it.addedAt }   // v1.17.0：标签匹配排序随标签库下线，暂回落为按加入时间
            else         -> books.sortedByDescending { it.addedAt }
        }
        return if (unfinishedFirst) base.sortedBy { it.finished } else base
    }
}
