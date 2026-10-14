package com.example.jmreader.data.repository

import com.example.jmreader.data.local.AppDatabase
import com.example.jmreader.data.local.Alias
import com.example.jmreader.data.local.Blacklist
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.Category
import com.example.jmreader.data.local.Chapter
import com.example.jmreader.data.local.ComicCategory
import com.example.jmreader.data.local.Download
import com.example.jmreader.data.local.SearchHistory
import com.example.jmreader.data.local.SearchHistoryAgg
import com.example.jmreader.data.local.SystemConfig
import com.example.jmreader.data.local.Subscription
import com.example.jmreader.data.local.SubscriptionUpdate
import com.example.jmreader.data.local.TagLibrary
import com.example.jmreader.data.api.JmApiClient
import com.example.jmreader.data.api.JmApiHost
import android.os.StatFs
import android.util.Log
import com.example.jmreader.BuildConfig
import com.example.jmreader.JmComicApplication
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
 *  - [ContentFilter]   拉黑/别名/搜索过滤
 *  - [CategoryManager] 分类管理
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
    private val filter = ContentFilter(db)
    private val categories = CategoryManager(db)

    /** 云同步（v1.10.0）：看过的漫画 JM 号与用户自有服务器双向合并。 */
    private val sync = JmSyncManager(db)

    /** 下载完成 → 从回收站拾回（经方法引用注入下载管理器，避免属性互相引用的类型递归）。 */
    private suspend fun onDownloadRestore(id: Int) = library.restoreFromRecycle(id)

    private val downloads = DownloadManager(
        db = db,
        saveDir = saveDir,
        configProvider = { config.getConfig(it) },
        onDetailInvalidated = ::invalidateDetail,
        restoreFromRecycle = ::onDownloadRestore   // v7.1.0：下载完成即从回收站拾回
    ).also {
        it.startStuckWatchdog()
        it.resumePendingDownloads()   // v6.0.19：启动自动恢复上次未完成的下载（断点续传）
        it.healLegacyImages()         // v1.7.7：自动重下「旧解扰算法」写出的整本图片
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
        aliasesJson: String = "",
        blocksJson: String = "",
        smartAssociation: Boolean = true
    ): List<Book> {
        // ── v1.1.0 提速：优先走同源 JSON 接口，绕开 Python ──
        // 旧链路 Kotlin → Chaquopy → Python → jmcomic 抓 HTML → 解析 DOM；
        // 新链路 Kotlin → OkHttp → JSON，搜索快一个数量级。
        //
        // 保守启用条件：仅「纯关键词 + 默认排序/时间 + 无标签条件」走接口。
        // 排序(orderBy/时间 time)与标签(tagsJson)的接口参数枚举尚未逐一验证，
        // 故这些场景继续走 Python，避免行为不一致。
        // 需要整体回退时把 USE_API_SEARCH 置 false 即可（或删掉本段）。
        if (USE_API_SEARCH &&
            mode == "keyword" &&
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
            // 接口不可用（离线 / 线路全挂 / 协议变更）→ 静默回退 Python 链路
            Log.w("JmRepository", "JSON 接口搜索无结果，回退 Python 链路")
        }
        return library.search(keyword, mode, page, orderBy, time, tagsLogic, tagsJson, aliasesJson, blocksJson, smartAssociation)
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

    // ── 拉黑 / 别名 / 搜索过滤 ────────────────────────────────

    // 拉黑（讨厌标记，不参与搜索过滤；供喜欢/讨厌算法用）
    fun observeBlacklists(): Flow<List<Blacklist>> = filter.observeBlacklists()
    suspend fun addBlacklist(type: String, value: String): Long {
        val id = filter.addBlacklist(type, value)
        // 同步 comic_status 位掩码的 BIT_BLOCKED（作品级即时置位；作者级按归一化名匹配其全部漫画）
        if (type == "work") value.toIntOrNull()?.let { library.syncBlockedBit(it, true) }
        else if (type == "author") library.syncBlockedForAuthor(value, true)
        return id
    }
    suspend fun removeBlacklist(id: Int) {
        // 先取回待删项，按其 type/value 清对应状态行 BIT_BLOCKED，再删 blacklists 行
        filter.getBlacklist(id)?.let { bl ->
            if (bl.type == "work") bl.value.toIntOrNull()?.let { library.syncBlockedBit(it, false) }
            else if (bl.type == "author") library.syncBlockedForAuthor(bl.value, false)
        }
        filter.removeBlacklist(id)
    }

    fun observeAliases(type: String): Flow<List<Alias>> = filter.observeAliases(type)
    /** 同义词分组（canonical → 别名列表），供标签分组显示用。 */
    fun observeAliasGroups(type: String): Flow<Map<String, List<String>>> =
        filter.observeAliases(type).map { list -> list.groupBy({ it.canonical }, { it.alias }) }
    suspend fun addAlias(type: String, alias: String, canonical: String): Long = filter.addAlias(type, alias, canonical)
    suspend fun removeAlias(id: Int) = filter.removeAlias(id)
    suspend fun getAliasesJson(): String = filter.getAliasesJson()

    fun filterSearchResults(
        results: List<Book>,
        hideMode: String,
        downloadedIds: Set<Int>,
        hideFinishedMode: String = "off",
        finishedIds: Set<Int> = emptySet(),
        hatedTags: Set<String> = emptySet()
    ): Pair<List<Book>, Set<Int>> =
        filter.filterSearchResults(results, hideMode, downloadedIds, hideFinishedMode, finishedIds, hatedTags)

    // ── 分类 ──────────────────────────────────────────────────

    fun observeCategories(): Flow<List<Category>> = categories.observeCategories()
    fun observeAllComicCategories(): Flow<List<ComicCategory>> = categories.observeAllComicCategories()
    fun observeComicCategories(comicJmId: Int): Flow<List<Int>> = categories.observeComicCategories(comicJmId)
    suspend fun createCategory(name: String, parentId: Int? = null): Long = categories.createCategory(name, parentId)
    suspend fun renameCategory(id: Int, name: String): Boolean = categories.renameCategory(id, name)
    suspend fun moveCategory(id: Int, parentId: Int?): Boolean = categories.moveCategory(id, parentId)
    suspend fun deleteCategory(id: Int): Boolean = categories.deleteCategory(id)
    suspend fun deleteCategoryWithReassign(catId: Int, targetCatId: Int): Boolean = categories.deleteCategoryWithReassign(catId, targetCatId)
    suspend fun setComicCategories(comicJmId: Int, categoryIds: List<Int>) = categories.setComicCategories(comicJmId, categoryIds)
    suspend fun getComicCategories(comicJmId: Int): List<Int> = categories.getComicCategories(comicJmId)
    suspend fun getDefaultCategoryBookIds(): Set<Int> = categories.getDefaultCategoryBookIds()
    suspend fun getBookIdsInCategory(categoryId: Int): List<Int> = categories.getBookIdsInCategory(categoryId)

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

    // ── 回收站（v7.1.0；v7.1.3 重构为宽限期模型）──
    fun observeAllBooks(): Flow<List<Book>> = library.observeAllBooks()
    /** 看完标号：写宽限期（不删本地，7 天没再看才删）。 */
    suspend fun markFinishedRecycle(bookId: Int) = library.markFinishedRecycle(bookId)
    /** 宽限期内再阅读：续期 7 天。 */
    suspend fun renewRecycle(bookId: Int) = library.renewRecycle(bookId)
    /** 手动删除进回收站：立即删本地 + 标记。 */
    suspend fun trashBook(bookId: Int) = library.trashBook(bookId)
    suspend fun restoreFromRecycle(bookId: Int) = library.restoreFromRecycle(bookId)
    suspend fun deleteFromRecycle(bookId: Int) = library.deleteFromRecycle(bookId)
    fun observeTrashed(): Flow<List<RecycleManager.TrashItem>> = library.observeTrashed()
    suspend fun checkRecycleExpiry() = library.checkRecycleExpiry()
    fun recycleRetentionDays(): Int = library.recycleRetentionDays()

    // ── 读完自动删本章（4.2.4）：同样的「退出 + 宽限 + 重入取消」模型
    fun scheduleChapterDelete(bookId: Int) = library.scheduleChapterDelete(bookId)
    fun cancelPendingChapterDelete(bookId: Int) = library.cancelPendingChapterDelete(bookId)

    // ── 作者订阅（P1-4，3.0.0 / 4.0.0）──
    suspend fun addSubscription(author: String): Boolean = library.addSubscription(author)
    suspend fun removeSubscription(id: Int) = library.removeSubscription(id)
    suspend fun removeSubscriptionAndUpdates(id: Int, author: String) = library.removeSubscriptionAndUpdates(id, author)
    fun observeSubscriptions(): Flow<List<Subscription>> = library.observeSubscriptions()
    suspend fun clearSubscriptionNew(id: Int) = library.clearSubscriptionNew(id)
    /** 检查订阅更新，返回新作数量。 */
    suspend fun checkSubscriptionsForUpdates(): Int = library.checkSubscriptionsForUpdates().size

    /**
     * 检查订阅更新 + 新作自动下载（v7.2.0）：配置 sub_auto_download 开启时，
     * 本次发现的新作自动入书架并加入后台下载队列；关闭则仅记录不下载。返回新作数量。
     */
    suspend fun checkSubscriptionsForUpdatesWithAutoDownload(): Int {
        val newWorks = library.checkSubscriptionsForUpdates()
        if (newWorks.isNotEmpty() && getConfig("sub_auto_download") == "1") {
            for (w in newWorks) { runCatching { library.saveBook(w) } }
            downloads.enqueueAlbums(newWorks.map { it.id })
        }
        return newWorks.size
    }
    fun observeSubscriptionUpdates(): Flow<List<SubscriptionUpdate>> = library.observeSubscriptionUpdates()
    fun observeUnseenUpdates(): Flow<List<SubscriptionUpdate>> = library.observeUnseenUpdates()
    suspend fun markUpdateSeen(bookId: Int) = library.markUpdateSeen(bookId)
    suspend fun markAllUpdatesSeen() = library.markAllUpdatesSeen()

    // ── 智能关联拓展（P2/P3，3.0.0）──
    suspend fun isSmartAssociationEnabled(): Boolean = library.isSmartAssociationEnabled()
    suspend fun getMinHits(): Int = library.getMinHits()
    suspend fun resetPreferenceSample(): Boolean = library.resetPreferenceSample()

    /** 组合算法逻辑报告（喜欢画像 + 权重 + 屏蔽 + 同义词 + 策略），供逻辑查看页可视化整条搜索/推荐流程。 */
    suspend fun getLogicReport(): LogicReport {
        val profile = library.getPreferenceProfile()
        val smart = library.isSmartAssociationEnabled()
        val sampleCount = library.countFavorites()
        val minHits = library.getMinHits()
        val aliases = filter.countAliasesByType()
        val ss = getConfig("search_strategy") ?: "classic"
        val rs = getConfig("recommend_strategy") ?: "classic"
        // 总开关关闭时无活跃权重：喜欢 Top 置空，由逻辑页显示「已关闭」提示
        val primaryTop = if (smart) {
            val weights = library.getPreferredTagWeights()
            profile.primary.map { it to (weights[it] ?: 90) }.sortedByDescending { it.second }.take(12)
        } else emptyList()
        val microTop = if (smart) {
            val weights = library.getPreferredTagWeights()
            profile.micro.map { it to (weights[it] ?: 10) }.sortedByDescending { it.second }.take(12)
        } else emptyList()
        // 标题名称特征（关键字分词）单独计分，与标签喜欢分开展示（4.1.0）
        val keywordTop = if (smart) library.computeNameFeatureTags().take(12) else emptyList()
        return LogicReport(
            smartAssociation = smart,
            sampleCount = sampleCount,
            manualCount = profile.manual.size,
            primaryTop = primaryTop,
            microTop = microTop,
            keywordTop = keywordTop,
            aliasCounts = aliases,
            minHits = minHits,
            searchStrategy = ss,
            searchStrategyLabel = AlgorithmRegistry.searchStrategy(ss).label,
            recommendStrategy = rs,
            recommendStrategyLabel = AlgorithmRegistry.recommendStrategy(rs).label,
        )
    }

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
    suspend fun deleteChapter(bookId: Int, chapterId: Int) = library.deleteChapter(bookId, chapterId)

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

    // ── 喜欢 / 讨厌标签 / 书架排序 ────────────────────────────

    suspend fun getPreferredTags(): Set<String> = library.getPreferredTags()
    suspend fun getHatedTags(): Set<String> = library.getHatedTags()
    suspend fun saveHatedTags(tags: List<String>) = library.saveHatedTags(tags)
    suspend fun computePreferredTags(): List<Pair<String, Int>> = library.computePreferredTags()
    // ── 喜欢画像（v2.4.3：手动/计算拆分 + 主喜欢/微喜欢）──
    suspend fun getManualPreferredTags(): Set<String> = library.getManualPreferredTags()
    suspend fun saveManualPreferredTags(tags: Set<String>) = library.saveManualPreferredTags(tags)
    suspend fun getPreferenceProfile(): BookLibrary.PreferenceProfile = library.getPreferenceProfile()
    suspend fun getPreferredTagWeights(): Map<String, Int> = library.getPreferredTagWeights()
    suspend fun getMaxPreferredTags(): Int = library.getMaxPreferredTags()

    // ── 标签库（v2.7.0：搜索结果累积，按数量排序）─────────────
    suspend fun addSearchTags(tags: List<String>) = library.addSearchTags(tags)
    fun observeTagLibrary(): Flow<List<TagLibrary>> = library.observeTagLibrary()
    fun observeMergedTagLibrary(): Flow<List<Pair<String, Int>>> = library.observeMergedTagLibrary()
    suspend fun getTagLibrary(): List<TagLibrary> = library.getTagLibrary()
    suspend fun clearTagLibrary() = library.clearTagLibrary()
    /** 只读获取某漫画真实标签（不落库），供搜索结果后台补抓标签库。 */
    suspend fun getAlbumTags(albumId: Int): List<String> = library.getAlbumTags(albumId)
    /** 某漫画是否已被拉黑（v5.11.0 阅读器「拉黑/删除处理」用）。 */
    suspend fun isBookBlocked(bookId: Int): Boolean = library.isBookBlocked(bookId)
    /** 漫画本体占用字节数（设置页「关于」）。 */
    suspend fun getDownloadedBytes(): Long = library.getDownloadedBytes()
    /** 搜索结果后台补抓标签（关键词/作者搜索结果往往不带 tags）。 */
    suspend fun enrichTagLibraryFromSearch(books: List<Book>, cap: Int = 15) =
        library.enrichTagLibraryFromSearch(books, cap)

    // ── 无关性标签（v4.14.0 起；5.0.19 用户可决定）──
    suspend fun isIrrelevantTag(tag: String): Boolean = library.isIrrelevantTag(tag)
    suspend fun getDefaultIrrelevantTags(): Set<String> = library.getDefaultIrrelevantTags()
    suspend fun getIgnoredTags(): Set<String> = library.getIgnoredTags()
    suspend fun setIgnoredTags(tags: Set<String>) = library.setIgnoredTags(tags)
    suspend fun toggleIgnoredTag(tag: String) = library.toggleIgnoredTag(tag)
    suspend fun isHideIrrelevantEnabled(): Boolean = library.isHideIrrelevantEnabled()
    suspend fun setHideIrrelevant(on: Boolean) = library.setHideIrrelevant(on)
    suspend fun aggregateComicTags(scope: String, cap: Int = 30): List<Pair<String, Int>> =
        library.aggregateComicTags(scope, cap)

    // ── 标签相关性（4.13.0：同义词智能推荐 / 一键分组）──
    suspend fun computeTagCorrelations(minScore: Int = 2): List<BookLibrary.TagCorrelation> =
        library.computeTagCorrelations(minScore)
    suspend fun groupCorrelatedTags(): List<List<String>> = library.groupCorrelatedTags()
    // ── 一键分类（4.13.0）──
    suspend fun proposeCategories(minBooks: Int = 3, topN: Int = 8): List<BookLibrary.CategoryProposal> =
        library.proposeCategories(minBooks, topN)
    fun sortLibraryBooks(
        books: List<Book>,
        sortMode: String,
        unfinishedFirst: Boolean,
        preferred: Set<String>
    ): List<Book> = library.sortLibraryBooks(books, sortMode, unfinishedFirst, preferred)

    // ── 首启自检（v7.3.0）──────────────────────────────────────
    /**
     * 版本数据修正点登记表：每次发版如有「升级后要自动修改的数据」，往这里加一条（幂等）。
     * 修正点说明见 [VersionFixup]。当前登记：
     * - 146（7.2.0）起：确保配置默认值完整（initConfigDefaults 幂等，防旧版本缺新增配置项）。
     * - 71（1.12.0）起：清理单章漫画旧章节残留（旧版单行本 id=sort=1 行与新约定冲突，见
     *   ChapterDao.deleteLegacySingleChapterRows；删除后离线打开自动 rebuild 为「全一话」）。
     */
    private val versionFixups = listOf(
        VersionFixup(
            appliesFrom = 146,
            summary = "补齐配置默认值",
            run = { repo -> repo.initConfigDefaults() }
        ),
        VersionFixup(
            appliesFrom = 71,
            summary = "清理单章漫画旧章节残留",
            run = { repo -> repo.purgeSingleChapterLegacyRows() }
        )
    )

    /** 关键配置项集合：配置一致性检查用（缺失即自动补齐并登记）。 */
    private val requiredConfigKeys = listOf(
        "max_concurrent_downloads", "image_quality", "library_sort",
        "finished_in_library", "download_after", "sub_auto_download", "bookshelf_columns"
    )

    /**
     * 首启自检（v7.3.0）：版本升级后第一次进入 App 时运行（MainActivity 用 data_audit_version 门控）。
     * ① 执行版本数据修正点（登记式）→ ② 数据体检已退役（v1.12.0 单表，无位一致性可补）
     * → ③ 故障识别：Python 桥接健康 / 数据一致性 / 存储目录可用性。
     * 幂等、纯本地、无网络；返回报告供 UI 弹窗。
     */
    suspend fun runStartupSelfCheck(fromVersion: Int): StartupCheckReport {
        val fixed = mutableListOf<String>()
        val faults = mutableListOf<String>()

        // ① 版本修正点（仅跑比升级前版本新的）
        for (fx in versionFixups.filter { it.appliesFrom > fromVersion }) {
            runCatching { fx.run(this) }
                .onSuccess { fixed += fx.summary }
                .onFailure { faults += "修正「${fx.summary}」失败：${it.message}" }
        }

        // ② 数据体检退役（v1.12.0 单表重构：位掩码一致性核对的对象已不存在；
        //    三表合一迁移在 Room MIGRATION_13_14 里一次性完成）

        // ③ 故障识别
        checkPythonHealth(faults)
        checkConfigHealth(fixed, faults)
        checkBooksHealth(faults)
        checkStorageHealth(faults)

        return StartupCheckReport(
            versionFrom = fromVersion,
            versionTo = BuildConfig.VERSION_CODE,
            autoFixed = fixed,
            faults = faults
        )
    }

    /**
     * v1.4.0：Chaquopy 已移除，原无 Python 健康检查。
     *
     * 原实现是启动时探测 Python 解释器是否就绪（`awaitPythonStart` + `PythonBridge.isReady`），
     * 未就绪就报「搜索/下载/推荐不可用」。现在全链路原生，**没有需要探测的运行时**，
     * 因此改为探测 API 线路是否可用（更有意义：线路全挂时搜索/详情/下载都会失败）。
     */
    private suspend fun checkPythonHealth(faults: MutableList<String>) {
        val ok = runCatching {
            JmApiHost.current() != null
        }.getOrDefault(false)
        if (!ok) faults += "接口线路不可用（搜索 / 详情 / 下载将失败，请检查网络）"
    }

    private suspend fun checkConfigHealth(fixed: MutableList<String>, faults: MutableList<String>) {
        runCatching {
            val all = config.getAllConfig()
            val missing = requiredConfigKeys.filter { all[it].isNullOrBlank() }
            if (missing.isEmpty()) return@runCatching
            initConfigDefaults()
            val after = config.getAllConfig()
            val still = requiredConfigKeys.filter { after[it].isNullOrBlank() }
            if (still.isEmpty()) fixed += "补齐缺失配置：${missing.joinToString("、")}"
            else faults += "配置表仍缺关键项：${still.joinToString("、")}"
        }.onFailure { faults += "配置表读取异常：${it.message}" }
    }

    private suspend fun checkBooksHealth(faults: MutableList<String>) {
        val ok = runCatching { bookDao.observeAll().first() }.isSuccess
        if (!ok) faults += "本地书架数据读取异常（数据库可能损坏）"
    }

    private fun checkStorageHealth(faults: MutableList<String>) {
        if (!dirWritable(saveDir)) faults += "下载目录不可写：$saveDir"
        if (!dirWritable(cacheDir)) faults += "缓存目录不可写：$cacheDir"
        val free = runCatching { StatFs(saveDir).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < MIN_FREE_BYTES) {
            faults += "存储空间不足（剩余 ${free / 1024 / 1024}MB，下载大本可能失败）"
        }
    }

    private fun dirWritable(path: String): Boolean = runCatching {
        val f = File(path)
        if (!f.exists()) f.mkdirs()
        f.isDirectory && f.canWrite()
    }.getOrDefault(false)

    private companion object {
        /** 存储可用空间警戒线：低于 100MB 提示（避免整本下载中途失败）。 */
        const val MIN_FREE_BYTES = 100L * 1024 * 1024

        /** v1.1.0：搜索是否优先走同源 JSON 接口；置 false 即整体回退 Python 旧链路。 */
        const val USE_API_SEARCH = true

        /** 图片请求 UA（v1.4.0：随 Chaquopy 移除后，封面下载改由原生 OkHttp 发起）。 */
        const val IMG_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120 Mobile Safari/537.36"
    }
}
