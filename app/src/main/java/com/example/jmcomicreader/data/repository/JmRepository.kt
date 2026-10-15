package com.example.jmreader.data.repository

import com.example.jmreader.data.local.AppDatabase
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.Chapter
import com.example.jmreader.data.local.Download
import com.example.jmreader.data.local.SearchHistory
import com.example.jmreader.data.local.SearchHistoryAgg
import com.example.jmreader.data.local.SystemConfig
import com.example.jmreader.data.api.JmApiClient
import android.util.Log
import com.example.jmreader.JmComicApplication
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 统一数据入口：桥接 Python 抓取层与本地 Room 数据库。
 *
 * 门面（Facade）：保持全部公开方法签名不变（各 ViewModel 零改动），
 * 内部按职责委派给 5 个单一职责类：
 *  - [ConfigManager]   配置/代理/并发
 *  - [ContentFilter]   搜索结果过滤（已下载/已看完屏蔽）
 *  - [DownloadManager] 下载全流程
 *  - [BookLibrary]     书架/进度/智能缓存/在线抓取
 * 进程级内存缓存（封面、详情）保留在本类，供委派对象通过回调失效。
 *
 * cacheDir 用于在线阅读图片缓存；saveDir 用于整本下载到书架。
 */
class JmRepository(
    db: AppDatabase,
    private val cacheDir: String,
    private val saveDir: String
) {
    private val bookDao = db.bookDao()
    private val searchHistoryDao = db.searchHistoryDao()

    // ── 进程级内存缓存 ─────────────────────────────────────────

    /** 封面内存缓存（避免 Lazy 列表滚动回收后回看时的空白闪烁 + 跳过 Python 调用）。 */
    private val coverMemCache = ConcurrentHashMap<String, String>()
    private val COVER_MEM_MAX = 600

    /** 详情/章节内存缓存：避免「从详情点作者/标签搜索后返回」或「从搜索/书架重新点进同一本」时重复联网拉详情。 */
    private val detailCache = ConcurrentHashMap<Int, Pair<Book, List<Chapter>>>()

    private fun coverMemPut(url: String, path: String) {
        coverMemCache[url] = path
        if (coverMemCache.size > COVER_MEM_MAX) {
            val it = coverMemCache.keys.iterator()
            var drop = coverMemCache.size / 2
            while (it.hasNext() && drop-- > 0) it.remove()
        }
    }

    private fun invalidateDetail(id: Int) { detailCache.remove(id) }

    /** 仅当内存缓存命中时返回路径（不触发 IO），供 UI 同步预填，避免回滚列表时的重新加载闪烁。 */
    fun cachedCoverPath(imgUrl: String): String? = coverMemCache[imgUrl]

    // ── 子模块（单一职责）──────────────────────────────────────

    private val config = ConfigManager(db)
    private val filter = ContentFilter()

    /** 云同步（v1.10.0）：看过的漫画 JM 号与用户自有服务器双向合并。 */
    private val sync = JmSyncManager(db)

    private val downloads = DownloadManager(
        db = db,
        saveDir = saveDir,
        configProvider = { config.getConfig(it) },
        onDetailInvalidated = ::invalidateDetail
    ).also {
        // v1.18.0：startStuckWatchdog（运行时看门狗）与 healLegacyImages（旧解扰图一次性重下）
        // 已下线；保留启动续传 —— 退出 App 后未完成的下载下次启动自动接着下。
        it.resumePendingDownloads()
    }
    private val library = BookLibrary(
        db = db,
        cacheDir = cacheDir,
        saveDir = saveDir,
        configProvider = { config.getConfig(it) },
        canceller = { id: Int -> downloads.cancelDownload(id) },
        onDetailInvalidated = ::invalidateDetail
    )

    // ── 配置 ──────────────────────────────────────────────────

    suspend fun initConfigDefaults() = config.initConfigDefaults()
    /** v1.12.0（vc71 修正点）：清理单章漫画旧章节残留，返回删除行数。 */
    suspend fun purgeSingleChapterLegacyRows(): Int = library.purgeSingleChapterLegacyRows()
    suspend fun getConfig(key: String): String? = config.getConfig(key)
    suspend fun setConfig(key: String, value: String) = config.setConfig(key, value)

    // ── 云同步（v1.10.0，委托 JmSyncManager）──────────────────

    /** 立即双向同步（设置页手动触发）。 */
    suspend fun syncNow(): Result<JmSyncManager.SyncResult> = sync.syncNow()

    /** 启动静默自动同步（启用 + 配置完整 + 距上次成功 ≥6h 才真正跑）。 */
    suspend fun autoSyncOnLaunch() = sync.autoSyncIfDue()

    suspend fun isSyncEnabled(): Boolean = sync.isEnabled()
    suspend fun setSyncEnabled(on: Boolean) =
        sync.setConfigValue(JmSyncManager.KEY_ENABLED, if (on) "1" else "0")

    suspend fun setSyncServerUrl(url: String) =
        sync.setConfigValue(JmSyncManager.KEY_URL, url.trim())

    suspend fun setSyncToken(token: String) =
        sync.setConfigValue(JmSyncManager.KEY_TOKEN, token.trim())
    suspend fun applyDownloadConcurrency() {
        config.applyDownloadConcurrency()
        downloads.refreshConcurrency()   // v1.7.9：把设置页的并发值同步给下载器
    }

    /** 批量读取全部配置（一次查询替代 N 次单查，设置页等场景提速）。 */
    suspend fun getAllConfig(): Map<String, String> = config.getAllConfig()

    /** 配置表响应式流（供搜索页派生讨厌/喜欢实时流，避免改设置返回后数据陈旧）。 */
    fun observeConfigAll(): Flow<List<SystemConfig>> = config.observeAllConfig()

    // ── 在线抓取（搜索/详情/图片）──────────────────────────────

    suspend fun search(
        keyword: String,
        mode: String,
        page: Int = 1,
        orderBy: String = "mr",
        time: String = "a",
        tagsLogic: String = "or",
        tagsJson: String = "",
    ): List<Book> {
        // ── v1.1.0 提速：优先走同源 JSON 接口，绕开 Python ──
        // 旧链路 Kotlin → Chaquopy → Python → jmcomic 抓 HTML → 解析 DOM；
        // 新链路 Kotlin → OkHttp → JSON，搜索快一个数量级。
        //
        // 保守启用条件：仅「纯关键词 + 默认排序/时间 + 无标签条件」走接口。
        // 排序(orderBy/时间 time)与标签(tagsJson)的接口参数枚举尚未逐一验证，
        // 故这些场景继续走 Python，避免行为不一致。
        // 需要整体回退时把 USE_API_SEARCH 置 false 即可（或删掉本段）。
        if (mode == "keyword" &&
            tagsJson.isBlank() &&
            orderBy == "mr" &&
            time == "a"
        ) {
            // v1.2.1：耗时采样已移除（原 SearchPerfLog 记录，仅用于提速验证）
            val viaApiRun = runCatching { JmApiClient.search(keyword, page) }
            // v1.3.2：原来用 `.getOrNull()` 把异常静默吞掉，线上只能看到"搜索无反应"，
            // 排查时完全无从下手。这里把异常打出来。
            viaApiRun.exceptionOrNull()?.let {
                Log.w("JmRepository", "JSON 接口搜索异常：${it.javaClass.simpleName}: ${it.message}", it)
            }
            val viaApi = viaApiRun.getOrNull()
            if (!viaApi.isNullOrEmpty()) {
                return viaApi
            }
            // 接口不可用（离线 / 线路全挂 / 协议变更）→ 走 BookLibrary 兜底
            Log.w("JmRepository", "JSON 接口搜索无结果，回退 BookLibrary 链路")
        }
        return library.search(keyword, mode, page, orderBy, time, tagsLogic, tagsJson)
    }

    /** 取详情；forceRefresh=false 且内存已缓存则直接返回，跳过联网。 */
    suspend fun getDetailCached(albumId: Int, forceRefresh: Boolean = false): Pair<Book, List<Chapter>> {
        if (!forceRefresh) detailCache[albumId]?.let { return it }
        val d = library.getDetail(albumId)
        detailCache[albumId] = d
        return d
    }

    suspend fun getDetail(albumId: Int): Pair<Book, List<Chapter>> = library.getDetail(albumId)
    suspend fun getLocalDetail(bookId: Int): Pair<Book, List<Chapter>>? = library.getLocalDetail(bookId)

    /**
     * 下载并缓存单张图片，返回本地文件路径（v1.4.0 改为纯原生 OkHttp）。
     *
     * 原来走 `PythonBridge.imageToFile`（Chaquopy）；现在：
     *  1) 内存缓存命中直接返回；
     *  2) 磁盘缓存命中直接返回（不联网、不重新下载）；
     *  3) 否则 OkHttp 下载到 `{cacheDir}/img/{md5(url)}.{ext}，再落盘返回。
     *
     * 用 MD5 作文件名：URL 稳定 → 文件名稳定 → 天然完成 resume-skip；
     * 同一张封面在列表里出现几十次也只下载一次。
     */
    suspend fun loadImage(imgUrl: String): String {
        if (imgUrl.isBlank()) return ""
        // 1) 内存缓存
        coverMemCache[imgUrl]?.let { if (java.io.File(it).isFile) return it }

        // 2) 磁盘缓存
        val target = imageCacheFile(imgUrl)
        if (target.isFile && target.length() > 0L) {
            coverMemPut(imgUrl, target.absolutePath)
            return target.absolutePath
        }

        // 3) 下载
        withContext(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(imgUrl)
                    .header("User-Agent", IMG_UA)
                    .header("Referer", "https://18comic.vip/")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val bytes = resp.body?.bytes() ?: error("空响应体")
                    if (bytes.isEmpty()) error("内容为空")
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                }
            }.onFailure {
                Log.w("JmRepository", "图片下载失败 $imgUrl：${it.message}")
                // 失败时若已有旧文件（可能被覆盖写坏），删掉避免返回坏图
                runCatching { if (target.isFile) target.delete() }
            }
        }
        // 无论成功失败都返回路径：调用方（Coil/Image）会自行处理加载失败
        val path = if (target.isFile && target.length() > 0L) target.absolutePath else ""
        if (path.isNotEmpty()) coverMemPut(imgUrl, path)
        return path
    }

    /** 图片缓存文件路径：`{cacheDir}/img/{md5(url)}.{ext}`。 */
    private fun imageCacheFile(imgUrl: String): java.io.File {
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(imgUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
        // 从扩展名推断（jpg/webp/png），无扩展名则按 webp（JM 图片多为 webp）
        val ext = imgUrl.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") } ?: "webp"
        return java.io.File(java.io.File(cacheDir, "img"), "$md5.$ext")
    }

    fun filterSearchResults(
        results: List<Book>,
        hideMode: String,
        downloadedIds: Set<Int>,
        hideFinishedMode: String = "off",
        finishedIds: Set<Int> = emptySet()
    ): Pair<List<Book>, Set<Int>> =
        filter.filterSearchResults(results, hideMode, downloadedIds, hideFinishedMode, finishedIds)

    // ── 搜索历史 ──────────────────────────────────────────────

    suspend fun addSearchHistory(type: String, content: String, count: Int = 0) {
        if (content.isBlank()) return
        searchHistoryDao.insert(SearchHistory(type = type, content = content, count = count))
    }

    suspend fun getSearchHistory(limit: Int = 50): List<SearchHistory> =
        searchHistoryDao.getRecent(limit)

    /** 聚合搜索历史（首页推荐兴趣种子）。 */
    suspend fun getSearchHistoryAggregated(limit: Int = 50): List<SearchHistoryAgg> =
        searchHistoryDao.getAggregated(limit)

    /** 聚合搜索历史的响应式流（首页推荐）。 */
    fun observeSearchHistoryAggregated(limit: Int = 50): Flow<List<SearchHistoryAgg>> =
        searchHistoryDao.observeAggregated(limit)

    // ── 下载 ──────────────────────────────────────────────────

    suspend fun downloadAlbum(albumId: Int) = downloads.downloadAlbum(albumId)

    /** 手动重下（force=true）：正在下载的先取消再重新入队。 */
    suspend fun redownload(albumId: Int) = downloads.redownload(albumId)

    suspend fun reconcileDownloadStatus(albumId: Int, status: String) = downloads.reconcileDownloadStatus(albumId, status)
    suspend fun cancelDownload(albumId: Int) = downloads.cancelDownload(albumId)
    suspend fun cancelAndDeleteDownload(albumId: Int) = downloads.cancelAndDeleteDownload(albumId) { library.removeBookCompletely(it) }
    fun observeDownloads(): Flow<List<Download>> = downloads.observeDownloads()
    suspend fun getDownload(bookId: Int): Download? = downloads.getDownload(bookId)
    suspend fun getDownloads(): List<Download> = downloads.getDownloads()
    suspend fun getDownloadProgress(albumId: Int): DownloadProgress = downloads.getDownloadProgress(albumId)
    suspend fun clearAllDownloadRecords() = downloads.clearAllDownloadRecords()
    suspend fun deleteDownloadRecord(bookId: Int) = downloads.deleteDownloadRecord(bookId)
    /** 彻底删除（下载管理「取消并删除」专用）：连下载记录/阅读进度一起删。 */
    suspend fun removeBookCompletely(bookId: Int) = library.removeBookCompletely(bookId)

    // ── 本地书架 / 进度 ───────────────────────────────────────

    fun observeBooks(): Flow<List<Book>> = library.observeBooks()
    suspend fun getBook(id: Int): Book? = library.getBook(id)
    fun observeChapters(bookId: Int): Flow<List<Chapter>> = library.observeChapters(bookId)
    suspend fun getChapters(bookId: Int): List<Chapter> = library.getChapters(bookId)
    suspend fun saveBook(book: Book) = library.saveBook(book)
    suspend fun firstChapterId(bookId: Int, local: Boolean): Int? = library.firstChapterId(bookId, local)
    suspend fun removeBook(book: Book) = library.removeBook(book)
    suspend fun removeBook(bookId: Int) = library.removeBook(bookId)

    // ── 读完自动删本章（4.2.4）：同样的「退出 + 宽限 + 重入取消」模型

    suspend fun saveProgress(bookId: Int, chapterId: Int, pageIndex: Int) = library.saveProgress(bookId, chapterId, pageIndex)
    /** 阅读记忆（v1.12.0：原 read_progress 行并入账本行，返回 Book 取 lastChapterId/lastPageIndex）。 */
    suspend fun getProgress(bookId: Int): Book? = library.getProgress(bookId)

    /** 有阅读进度（打开过阅读器）的 bookId 集合——书架批量删除「已读/未读」勾选用（v1.11.0）。 */
    suspend fun getReadBookIds(): Set<Int> = runCatching { library.getReadBookIds().toSet() }
        .getOrDefault(emptySet())

    // ── 已下载集合 ────────────────────────────────────────────

    /** 是否有下载记录（含已删本地的）——「曾下载过」判定（v1.12.0 替代原 DOWNLOADED 位）。 */
    suspend fun hasDownloadRecord(bookId: Int): Boolean = library.hasDownloadRecord(bookId)

    suspend fun getDownloadedIds(): Set<Int> = library.getDownloadedIds()
    fun observeDownloadedBooks(): Flow<List<Book>> = library.observeDownloadedBooks()
    /** 已下载 id 集合流（v14：SQL 下推，只取 id 列）。 */
    fun observeDownloadedIds(): Flow<List<Int>> = library.observeDownloadedIds()
    /** 收藏 id 集合流（v14：SQL 下推，替代全表物化后再过滤）。 */
    fun observeFavoriteIds(): Flow<List<Int>> = library.observeFavoriteIds()
    /** 已看完 id 集合流（v14：SQL 下推）。 */
    fun observeFinishedIds(): Flow<List<Int>> = library.observeFinishedIds()

    // ── 查看次数 ──────────────────────────────────────────────

    suspend fun bumpViewCount(bookId: Int) = library.bumpViewCount(bookId)

    // ── 看完标记 ──────────────────────────────────────────────

    suspend fun markFinished(bookId: Int) = library.markFinished(bookId)
    suspend fun markUnfinished(bookId: Int) = library.markUnfinished(bookId)
    suspend fun toggleFinished(bookId: Int) = library.toggleFinished(bookId)

    // 章节读完标识 / 读完自动删章（v7）
    suspend fun markChapterFinished(bookId: Int, chapterId: Int) = library.markChapterFinished(bookId, chapterId)
    suspend fun markChapterUnfinished(bookId: Int, chapterId: Int) = library.markChapterUnfinished(bookId, chapterId)
    suspend fun countFinishedChapters(bookId: Int): Int = library.countFinishedChapters(bookId)

    // ── 收藏（v5）─────────────────────────────────────────────

    suspend fun markFavorite(bookId: Int) = library.markFavorite(bookId)
    suspend fun unmarkFavorite(bookId: Int) = library.unmarkFavorite(bookId)
    suspend fun toggleFavorite(bookId: Int) = library.toggleFavorite(bookId)
    suspend fun getFavoriteIds(): Set<Int> = library.getFavoriteIds()
    suspend fun exportFavorites(): String = library.exportFavorites()
    fun parseImportFavorites(json: String): BookLibrary.ImportParseResult = library.parseImportFavorites(json)
    suspend fun importFavorites(json: String, downloadIds: Set<Int> = emptySet()): String =
        library.importFavorites(json, downloadIds)

    /**
     * 导入收藏（4.9.0 后台下载版）：收藏**立即落库并返回**，勾选下载的 id 交给
     * DownloadManager 后台队列（并发 = max_concurrent_downloads，逐本 runCatching 兜底），
     * 不阻塞调用方。进度在「下载管理」实时可见。
     */
    suspend fun importFavoritesBackground(json: String, downloadIds: Set<Int>): String {
        val summary = library.importFavorites(json, downloadIds)
        if (downloadIds.isNotEmpty()) downloads.enqueueAlbums(downloadIds.toList())
        return summary
    }

    // ── 漫画账本（v1.12.0 单表：Book 即账本行，原 comic_status 位掩码退役）──

    /** 账本全量流（= comics 全表）。 */
    fun observeLedger(): Flow<List<Book>> = library.observeLedger()
    /** 账本全量快照。 */
    suspend fun getLedger(): List<Book> = library.getLedger()
    /** 某本账本行（null = 从未看过/未同步）。 */
    suspend fun getLedgerEntry(bookId: Int): Book? = library.getLedgerEntry(bookId)
    /** 彻底删除某本账本行（隐私入口，书架行同步消失）。 */
    suspend fun deleteLedgerEntry(bookId: Int) = library.deleteLedgerEntry(bookId)
    /** 清空阅读画像（纯账本行删除 + 在架/收藏行清阅读记忆）。 */
    suspend fun clearReadingProfile() = library.clearReadingProfile()

    /** 漫画本体占用字节数（设置页「关于」）。 */
    suspend fun getDownloadedBytes(): Long = library.getDownloadedBytes()

    /**
     * 书架排序（v1.17.0：标签匹配排序随标签库下线，library 侧已回落为按加入时间；
     * `preferred` 参数保留只为不改调用方签名，已不再参与计算）。
     */
    fun sortLibraryBooks(
        books: List<Book>,
        sortMode: String,
        unfinishedFirst: Boolean,
        preferred: Set<String>
    ): List<Book> = library.sortLibraryBooks(books, sortMode, unfinishedFirst, preferred)

    private companion object {
        /** 图片请求 UA（v1.4.0：随 Chaquopy 移除后，封面下载改由原生 OkHttp 发起）。 */
        const val IMG_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120 Mobile Safari/537.36"
    }
}
