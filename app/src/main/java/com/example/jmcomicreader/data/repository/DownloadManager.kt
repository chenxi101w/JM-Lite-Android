package com.example.jmreader.data.repository

import android.util.Log
import com.example.jmreader.data.api.JmApiClient
import com.example.jmreader.data.image.NativeAlbumDownloader
import com.example.jmreader.data.local.AppDatabase
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.Chapter
import com.example.jmreader.data.local.Download
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
// v2.0：`kotlinx.coroutines.sync.Semaphore` 已随 Semaphore 排队方案一起移除 ——
// 它的许可泄漏缺陷（acquire 后、try 前被取消 → finally 不存在）是「排队永远不动」的根因，
// 历史教训完整保留在本类头部的注释里。
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import com.example.jmreader.data.api.JmApiHost

/** 实时下载进度（来自 Python get_download_progress，图片级）。 */
data class DownloadProgress(
    val status: String,   // DOWNLOADING | DONE | FAILED | NOT_FOUND
    val done: Int,         // 已完成图片数
    val total: Int,        // 总图片数
    val bytes: Long,       // 累计已下载字节数
    val speed: Float,      // 瞬时速度（字节/秒，指数平滑）
    val mtime: Long = 0L   // 进度文件最后修改时间(ms)，用于判定下载是否已中断（长时间无更新=死任务）
)

/**
 * 下载域：整本下载、进度轮询、取消/删除、下载后行为、状态纠正。
 * 从 JmRepository 拆出。
 *
 * @param saveDir 下载落盘目录
 * @param configProvider 读取配置（并发数等），由门面注入避免循环依赖
 * @param onDetailInvalidated 详情内存缓存失效回调（下载完成后 savedDir 变更需失效）
 */
class DownloadManager(
    db: AppDatabase,
    private val saveDir: String,
    private val configProvider: suspend (String) -> String?,
    private val onDetailInvalidated: (Int) -> Unit,
    // v1.16.1：根 Job 必须用 SupervisorJob。原来是裸 `CoroutineScope(Dispatchers.IO)`
    // —— 任一子协程的未捕获异常会**连带取消整个 scope**：watchdog、启动恢复、
    // 之后所有 dispatch 的 launch 全部变成「已取消态，body 不执行」，
    // 而 enqueue 还在照常往 pending/active 里放死任务 → 新点的下载全部静默卡住，
    // 只有重启（新进程新 scope）或……重下也不行。单本书翻车不得拖垮整个调度器。
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val downloadDao = db.downloadDao()
    private val bookDao = db.bookDao()

    /**
     * 原生下载器（v1.2.8）：整本下载的纯 Kotlin 实现，用于「去掉 Python」。
     *
     * 默认**开启**，但失败时自动回退 Python（[useNativeDownloader] 的兜底）——
     * 原生版尚未在真机全链路验证过，有 Python 兜底才敢默认启用。
     * 验证稳定后可以把回退分支删掉，彻底摘掉 Chaquopy。
     */
    private var useNativeDownloader = true

    private val nativeDownloader by lazy {
        NativeAlbumDownloader(
            api = JmApiClient,
            // ── v1.8.4：显式配置调度器与连接池 ──────────────────────────
            // OkHttp 默认 `maxRequestsPerHost = 5`、`connectionPool` 只保留 5 条空闲连接。
            // 图片并发开到 12 时，实际只有 5 条能真正并发，其余在 Dispatcher 里排队；
            // 而超出的连接用完即关 → 下一批又要重做 TCP + TLS 握手（每个 2~3 个 RTT），
            // 吞吐因此**周期性塌一下**再恢复，界面上就是"速度时好时坏"。
            // 这里把每 host 并发与空闲连接数都抬到 ≥ 图片并发上限。
            //
            // readTimeout 保持 60s。v1.8.4 曾缩到 30s 想"更早回收并发位"，
            // 但那对**弱网**是负优化：CDN 抖动时单张图读到 30 秒以上很常见，
            // 一超时就得换域名重下整张，反而更慢。v1.8.5 改回 60s。
            // ── v1.8.7：并发对齐官方客户端后，连接池/调度器也要跟上 ──────────
            // 官方 jmcomic 的 `image_concurrency = 30`，其 HTTP 客户端连接池就是 30。
            // 我们把图片并发提到 16（× 2 本 = 32）后，连接池若还停在 16，
            // 超出部分用完即关 → 反复 TLS 握手，等于又把并发压回去。
            //
            // connectTimeout 20s → **8s**：域名改为「健康域里随机挑」之后，
            // 万一批到真正不可用的域名，必须**尽快失败**才能快速回退到下一个；
            // 20 秒的连接超时会让一次误选白白吃掉 20 秒（与 JmApiHost 的 8s 对齐）。
            http = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .dispatcher(
                    okhttp3.Dispatcher().apply {
                        maxRequests = MAX_BOOK_CONCURRENCY * NativeAlbumDownloader.MAX_IMAGE_CONCURRENCY + 8
                        maxRequestsPerHost = NativeAlbumDownloader.MAX_IMAGE_CONCURRENCY + 4
                    }
                )
                .connectionPool(
                    // 空闲连接保留 5 分钟（OkHttp 默认值）。v1.8.4 曾设 1 分钟，
                    // 章节之间/重试间隙稍长就会把连接全丢掉 → 重新 TLS 握手。
                    // 容量按「书本并发 × 图片并发」的最大值给足，避免高并发下反复建连。
                    okhttp3.ConnectionPool(
                        NativeAlbumDownloader.MAX_IMAGE_CONCURRENCY + 16,
                        5,
                        TimeUnit.MINUTES
                    )
                )
                .build(),
            saveDir = File(saveDir),
            imgConcurrency = { imgConcurrency },
        )
    }

    /** v1.1.1：下载完成时把章节列表落库（此前只写 books，导致本地无章节 → 离线读不了）。 */
    private val chapterDao = db.chapterDao()

    // ════════════════════════════════════════════════════════════════
    // v2.0 下载队列：显式 FIFO + 单调度器（**取代 v14 的 Semaphore 方案**）
    //
    // ── 为什么要重构 ────────────────────────────────────────────────
    // 旧方案：每本书一个协程挂在 `Semaphore(MAX_BOOK_CONCURRENCY)` 上排队，
    // 它有一个**结构性缺陷**：
    //
    //     acquireBookSlot()          // ← 在 try 之外
    //     try { download() } finally { releaseBookSlot() }
    //
    // `acquireBookSlot` 内部是「sem.acquire() 成功 → 轮询 bookConcurrency（delay 250ms）」。
    // 如果协程恰好在轮询的 delay 处被取消（用户取消 / 作用域关闭），
    // CancellationException 会把 acquire 函数直接炸出去 —— **try 还没进，finally 不存在**，
    // 刚被 sem.acquire() 吃掉的许可就永远还不回去了。
    //
    // MAX_BOOK_CONCURRENCY = 2 → 泄漏 2 次后 Semaphore 永久枯竭：
    // 之后**所有**下载（包括手动点「重新下载」）都挂在 acquire() 上永远不动。
    // 表现就是用户报告的「**排队的漫画永远排队，只有重启 App 才能真的重下**」
    // —— 重启后 Semaphore 是新对象，名额"恢复"了。旧方案同时还有：
    //   · `cancelDownload()` 是**空函数**（没有 Job 引用，根本取消不了协程）
    //   · 一本下完，下一本要靠 delay(250) 轮询 + watchdog 5s 兜底才被推进
    //
    // ── 新方案 ─────────────────────────────────────────────────────
    //   pending : ConcurrentLinkedQueue<Int>    FIFO 等待队列（排队顺序的权威来源）
    //   active  : ConcurrentHashMap<Int, Job>   正在下载的任务（**持有 Job，可真取消**）
    //   dispatch(): 只要 active.size < bookConcurrency 就从 pending 头部取任务启动。
    //
    //   · **没有信号量** → 结构上不可能泄漏名额
    //   · 一本结束（成功/失败/取消）→ finally 里 active.remove + **立即 dispatch**，
    //     下一本马上开跑，不等 watchdog
    //   · 全部入口（导入批量 / 手动重下 / 启动恢复 / 老图自愈 / watchdog 续传）
    //     收口到 [enqueue]，同一本书在 pending+active 里最多一份
    // ════════════════════════════════════════════════════════════════

    /** FIFO 等待队列。排队顺序的权威来源。 */
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Int>()

    /** 正在下载的任务（albumId → Job）。持有 Job 才能**真正取消**（旧版 cancelDownload 是空函数）。 */
    private val active = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.Job>()

    /** dispatch 的互斥锁：防止「完成腾位」和「新入队」两个 dispatch 并发超发名额。 */
    private val dispatchLock = Any()

    // ── v1.7.9：并发数改为设置页可调 ────────────────────────────────
    /** 同时在下载的本数（1~3，默认 2）。 */
    @Volatile
    private var bookConcurrency: Int = DEFAULT_BOOK_CONCURRENCY

    /** 图片级并发上限来源（设置页），转推给下载器的全局闸门。 */
    @Volatile
    private var imgConcurrency: Int = NativeAlbumDownloader.DEFAULT_IMAGE_CONCURRENCY

    /**
     * 从配置刷新并发设置（v1.7.9）。
     *
     * 由 `JmRepository.applyDownloadConcurrency()` 在启动与设置页改动时调用。
     * 范围：本数 1~[MAX_BOOK_CONCURRENCY]，图片 1~[NativeAlbumDownloader.MAX_IMAGE_CONCURRENCY]。
     */
    suspend fun refreshConcurrency() {
        val b = runCatching {
            configProvider("book_download_concurrency")?.toIntOrNull()
        }.getOrNull()?.coerceIn(1, MAX_BOOK_CONCURRENCY) ?: DEFAULT_BOOK_CONCURRENCY
        val raw = runCatching {
            configProvider("max_concurrent_downloads")?.toIntOrNull()
        }.getOrNull()
        val i = when {
            raw == null -> NativeAlbumDownloader.DEFAULT_IMAGE_CONCURRENCY
            // v1.8.7：老用户 DB 里存着旧默认值 6（v1.7.9 起的默认值），
            // 而 6 已不在新档位（4/8/16/24/32）中 —— 读到它就说明是"旧默认残留"
            // 而不是用户主动选的，直接提到新的默认值 16。
            raw == NativeAlbumDownloader.LEGACY_DEFAULT_IMAGE_CONCURRENCY ->
                NativeAlbumDownloader.DEFAULT_IMAGE_CONCURRENCY
            else -> raw.coerceIn(1, NativeAlbumDownloader.MAX_IMAGE_CONCURRENCY)
        }
        bookConcurrency = b
        imgConcurrency = i
        // v1.8.9：把并发上限推给下载器的**全局共享闸门**，立即生效（不必等下一本）。
        // 同时打一条诊断日志，便于真机确认「全局上限」与「当前在飞数」。
        runCatching {
            nativeDownloader.updateImageConcurrency(i)
            Log.i(
                TAG,
                "并发设置：全局图片 $i 个请求同时发出" +
                    "（不限单本；同时下 $b 本共享这 $i 个名额）" +
                    " 当前在飞 ${nativeDownloader.inFlightImages}"
            )
        }
    }

    /** v1.7.9：启动时按配置初始化一次并发设置。 */
    init {
        scope.launch { runCatching { refreshConcurrency() } }
    }

    /**
     * 把一组漫画加入下载队列（fire-and-forget，**立即返回**不阻塞调用方）。
     * 内部走 [enqueue] 统一收口：入队即写 QUEUED 记录（下载管理立刻可见），
     * 由调度器按 `bookConcurrency` 逐本启动，完成后落 DONE/书架。
     */
    fun enqueueAlbums(ids: List<Int>) {
        if (ids.isEmpty()) return
        ids.forEach { enqueue(it) }
    }

    /**
     * 手动重下（下载管理「重新下载」/ JM 号直达）。`force = true`：
     * 若该书正在下载，**先取消当前任务再重新入队**（旧实现会被去重挡住 = 点了没反应）。
     */
    suspend fun redownload(albumId: Int) = enqueue(albumId, force = true)

    /**
     * 整本下载到本地书架目录。
     *
     * v2.0 起本函数**只负责入队并立即返回**（真正的下载由调度器启动）——
     * 保持 suspend 签名是为了兼容现有调用方（`JmRepository` 透传、各 ViewModel
     * 的 `scope.launch { repo.downloadAlbum(id) }`），它们全是 fire-and-forget，
     * 不依赖本函数挂起到下载完成。
     */
    suspend fun downloadAlbum(albumId: Int) {
        enqueue(albumId)
    }

    /**
     * 统一入队收口。
     *
     * @param force true = 用户手动重下：正在下载的先取消再重新入队；
     *              false = 一般入队：已在下载/已排队则忽略（去重）。
     */
    private fun enqueue(albumId: Int, force: Boolean = false) {
        // v1.16.1：队列变更整体纳入 dispatchLock —— 与 runDownload.finally 的
        // 「摘 entry + dispatch」互斥。否则强制重下的 `active.remove → pending.add`
        // 会和旧任务的迟到 finally 交错，dispatch 的 `active.containsKey → continue`
        // 把已 poll 的同一本书**直接丢弃** → 书落入「QUEUED 记录但不在任何队列」的
        // limbo（界面永远显示「排队中（队列第 N 位）」）。
        synchronized(dispatchLock) {
            if (active.containsKey(albumId)) {
                if (!force) {
                    Log.i(TAG, "入队跳过：$albumId 正在下载中")
                    return
                }
                // 强制重下：取消当前任务。runDownload 的 finally 会腾位并重新 dispatch。
                // 这里只是把 Job 从 active 摘掉并 cancel，队列位置由下面重新排。
                active.remove(albumId)?.cancel()
                Log.i(TAG, "强制重下：$albumId 取消当前任务，重新入队")
            }
            if (pending.contains(albumId)) {
                if (!force) return
                pending.remove(albumId)     // 强制时挪到队尾重新排
            }
            pending.add(albumId)
        }
        // 入队即写 QUEUED 记录（v1.2.4 行为保持：下载管理里立刻可见，而不是毫无痕迹）。
        // ⚠️ v1.16.1：dispatch() 必须等这条落库之后 —— 原来是两条路各跑各的，
        // 迟到的 QUEUED 写会把已经 DOWNLOADING、甚至已 DONE 的记录覆盖回「排队中」
        // （幽灵排队：界面显示排队中，实际在下或已下完）。放进同一个 launch 里保序。
        scope.launch {
            runCatching {
                val cur = downloadDao.get(albumId)
                if (cur == null || cur.status == "DONE" || cur.status == "FAILED") {
                    downloadDao.upsert(
                        Download(
                            bookId = albumId,
                            status = "QUEUED",
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            dispatch()
        }
    }

    /**
     * 调度：只要 active 未满就把 pending 头部的任务启动。
     *
     * synchronized 保护「检查 active.size → poll → put」这个复合操作 ——
     * 「一本完成腾位」和「新任务入队」可能同时触发 dispatch，不加锁会超发名额。
     * 锁内只做 launch（非阻塞），不会有性能问题。
     */
    private fun dispatch() {
        synchronized(dispatchLock) {
            while (active.size < bookConcurrency) {
                val id = pending.poll() ?: break
                if (active.containsKey(id)) {
                    // v1.16.1：入队/摘除都已在同一把锁内，此状态理论不可达。
                    // 真出现了至少留个现场，别静默丢任务。
                    Log.w(TAG, "调度异常：$id 同时在 active 与 pending，跳过本轮")
                    continue
                }
                val job = scope.launch { runDownload(id) }
                active[id] = job
                Log.i(
                    TAG,
                    "调度：$id 开始下载（active=${active.size}/$bookConcurrency，" +
                        "排队 ${pending.size} 本）"
                )
            }
        }
    }

    /**
     * 单本书的下载执行体（由调度器启动）。
     *
     * finally 里的 `active.remove + dispatch()` 是新架构的**心脏**：
     * 无论成功、失败还是被取消，都立即腾出名额并拉起下一本 ——
     * 旧架构这一步要靠 watchdog 每 5 秒轮询兜底。
     */
    private suspend fun runDownload(albumId: Int) {
        // v1.16.1：本协程自己的 Job —— finally 摘 active 时做身份校验。
        // cancelDownload / 强制重下是「先摘 entry 再 cancel」，旧任务的迟到 finally
        // 若无条件 remove，会把同书**新任务**的 entry 一起摘掉 → 新任务脱离监控 →
        // watchdog 之后取消它全是空转 → 同书重复并发下载不断累积。
        val self = coroutineContext[Job]!!
        try {
            downloadAlbumInternal(albumId)
        } catch (e: CancellationException) {
            throw e                      // 取消是正常路径，照旧抛出让协程收尾
        } catch (e: Throwable) {
            // v1.16.1：异常必须在这里收官。downloadAlbumInternal 会显式 throw e，
            // 一路逃出本协程就会连带取消整个 scope（见 scope 处注释）。
            // 在这里落 FAILED、腾位，调度器与其它书不受影响。
            Log.e(TAG, "下载任务异常收官：$albumId", e)
            runCatching {
                downloadDao.upsert(
                    Download(bookId = albumId, status = "FAILED", createdAt = System.currentTimeMillis())
                )
            }
        } finally {
            // 摘 entry + 腾位 dispatch 在同一把锁内完成 —— 与 enqueue 的队列变更
            // 互斥，否则 dispatch 可能 poll 到「刚取消又入队」的同一本书并丢弃它。
            synchronized(dispatchLock) {
                active.remove(albumId, self)
                dispatch()
            }
        }
    }

    private suspend fun downloadAlbumInternal(albumId: Int) {
        downloadDao.upsert(
            Download(bookId = albumId, status = "DOWNLOADING", createdAt = System.currentTimeMillis())
        )
        try {
            // v1.4.1：下载前先取一次详情（书名/作者/封面/章节）。
            // 详情拿不到就别白跑几百个请求了，直接标失败。
            val detail = runCatching { JmApiClient.albumDetail(albumId) }.getOrNull()
            if (detail == null) {
                Log.w("DownloadManager", "下载中止：详情获取失败（$albumId），接口可能不可用")
                runCatching {
                    downloadDao.upsert(Download(bookId = albumId, status = "FAILED", createdAt = System.currentTimeMillis()))
                }
                return
            }
            when (val r = nativeDownloader.download(albumId, File(saveDir), detail.second, detail.first)) {
                is NativeAlbumDownloader.Outcome.Success -> {
                    Log.i("DownloadManager", "原生下载成功：$albumId，共 ${r.images} 张 / ${r.chapters} 章")
                    // ⚠️ savedDir 必须是 `{root}/{albumId}`（与 Python 版一致）——
                    // 传根目录会让书里记录的路径少一层，表现为"下载完但打不开"。
                    finishDownload(albumId, File(saveDir, albumId.toString()).absolutePath, r.chapters, detail.first, detail.second)
                    return
                }
                is NativeAlbumDownloader.Outcome.Failed -> {
                    // 不再回退：如实标失败，让用户在下载管理里看到并可重试
                    Log.w("DownloadManager", "下载失败：${r.reason}")
                    runCatching { downloadDao.upsert(Download(bookId = albumId, status = "FAILED", createdAt = System.currentTimeMillis())) }
                    return
                }
            }
        } catch (e: Exception) {
            // 落库异常（协程被取消、bookDao NPE 等）。
            // 不可把状态永远卡在 DOWNLOADING：按进度文件纠正一次。
            val p = runCatching { getDownloadProgress(albumId) }.getOrNull()
            when (p?.status) {
                "DONE" -> runCatching { reconcileDownloadStatus(albumId, "DONE") }
                "FAILED", "CANCELLED", "NOT_FOUND" ->
                    runCatching { reconcileDownloadStatus(albumId, "FAILED") }
            }
            throw e
        }
    }

    /**
     * 下载完成后的统一收尾（v1.2.8 提取）。
     *
     * 原生下载器与 Python 两条路径**共用这一套落库逻辑**，避免行为漂移：
     *  downloads 表 → books 行（必要时用 comic_status 快照重建）→ 标签库 → chapters 表
     *  → 详情缓存失效 → comic_status 位 → 回收站拾回 → 按设置处理下载记录。
     *
     * @param meta Python 响应的 data 段（原生路径传 null —— 它不返回元数据，
     *             此时书名/作者/封面全部回退到已有记录或 comic_status 快照）。
     */
    private suspend fun finishDownload(
        albumId: Int,
        savedDir: String,
        chapterCount: Int,
        metaBook: Book,
        metaChapters: List<Chapter>,
    ) {
        downloadDao.upsert(
            Download(
                bookId = albumId,
                status = "DONE",
                total = chapterCount,
                progress = chapterCount,
                savedDir = savedDir
            )
        )
        // 4.2.1 / 4.13.0：账本行可能不存在（JM 号直达下载）或已存在（看过/已删本地的号）——
        // 下载响应的专辑元数据（title/author/cover/tags）优先，账本行补缺
        // （v1.4.1：元数据直接来自原生 `albumDetail`，不再经过 Python 的 JSON 响应）。
        val existing = bookDao.get(albumId)
        val title = metaBook.title.ifBlank { existing?.title ?: "JM-$albumId" }
        val author = metaBook.author.ifBlank { existing?.author ?: "" }
        val cover = metaBook.coverUrl.ifBlank { existing?.coverUrl ?: "" }
        val tags = metaBook.tags.ifBlank { existing?.tags ?: "" }
            if (existing != null) {
                // v6.0.3：下载完成时更新 addedAt = 完成时间，书架「下载时间」排序 = 下载完成顺序（最新在前）
                bookDao.upsert(
                    existing.copy(
                        savedDir = savedDir, title = title, author = author,
                        coverUrl = cover, tags = tags,
                        addedAt = System.currentTimeMillis()
                    )
                )
            } else {
                bookDao.upsert(
                    Book(
                        id = albumId,
                        title = title,
                        author = author,
                        coverUrl = cover,
                        tags = tags,
                        savedDir = savedDir,
                        addedAt = System.currentTimeMillis()
                    )
                )
            }
            // ── v1.1.1：章节列表落库 ──────────────────────────────
            // 修复：此前下载只写 books（savedDir）不写 chapters，于是「JM 号直达下载」等
            // 没访问过详情页的路径下 chapters 表为空 → 阅读器找不到章节（报「本章未下载」）、
            // 书架点卡片只能跳联网详情页 → 离线完全看不了已下载的漫画。
            // 另外把 chapters 表里已有的 finished 标记保留（重下不该清掉已读进度）。
            // v1.2.8：原生路径没有 Python 的 chapter_list，改为从本地目录重建
            // （每章一个 `%03d` 子目录，目录名即 sort，章节 id 从详情页补）。
            // v1.4.1：章节直接用下载前 `albumDetail` 取到的列表（不再解析 Python 的 chapter_list）。
            // 只登记**确实有内容**的章（目录存在）—— 没下成功的章不该出现在章节列表里。
            val albumDir = File(File(savedDir).parentFile ?: File(savedDir), albumId.toString())
            val finishedMap = runCatching { chapterDao.get(albumId).associate { it.id to it.finished } }
                .getOrDefault(emptyMap())
            val chList = metaChapters.mapNotNull { ch ->
                if (!File(albumDir, "%03d".format(ch.sort)).isDirectory) return@mapNotNull null
                ch.copy(finished = finishedMap[ch.id] ?: 0)
            }
            if (chList.isNotEmpty()) {
                // v1.12.0：单章漫画（唯一章且章 id == bookId）不入章节表（省行 + 少一态）；
                // 兼容旧数据：顺手清掉历史单章行
                val isSingleChapter = metaChapters.size == 1 && metaChapters[0].id == albumId
                if (isSingleChapter) chapterDao.delete(albumId)
                else runCatching { chapterDao.upsertAll(chList) }
            }
            else Log.w("DownloadManager", "章节落库跳过：无任何章节目录（$albumId）")
            onDetailInvalidated(albumId)   // 详情内存缓存里的 savedDir 已过期（否则详情页仍显示「未下载」）
    }

    /**
     * 按进度文件纠正卡死的下载状态：Python 已实际完成/失败（落盘 saved_dir / 进度文件定稿），
     * 但 Kotlin 侧落库被协程取消/异常跳过，导致 DB 仍停在 DOWNLOADING → 下载管理误显示「下载中」。
     * 由下载管理轮询循环与 downloadAlbum 异常分支调用，让已完成/失败的下载自动纠正为正确状态。
     */
    suspend fun reconcileDownloadStatus(albumId: Int, status: String) {
        val dl = downloadDao.get(albumId) ?: return
        if (dl.status == status) return
        val savedDir = File(saveDir, albumId.toString()).absolutePath
        val updated = if (status == "DONE") dl.copy(status = "DONE", savedDir = savedDir)
                      else dl.copy(status = status)
        downloadDao.upsert(updated)
        if (status == "DONE") {
            // v6.0.3：纠正路径同样刷新 addedAt，保证「下载时间」排序一致
            runCatching { bookDao.get(albumId)?.let { bookDao.upsert(it.copy(savedDir = savedDir, addedAt = System.currentTimeMillis())) } }
            onDetailInvalidated(albumId)
        }
    }

    // ── v1.12.0 单表：原 onDownloaded（落 comic_status 快照 + 置 LOCAL|DOWNLOADED 位）退役——
    // 下载完成时账本行已由上面的 upsert 写好 localDir（行即状态），无独立登记表可写。

    /**
     * 取消某本漫画的整本下载（v1.4.0：原生链路）。
     *
     * 原生下载器是**同步串行**的协程，取消 = 取消该协程本身
     * （`download()` 在 `withContext(Dispatchers.IO)` 里，协程取消会自然中断）。
     * 调用方（下载管理的「取消并删除」）已经负责取消 job 与清理 DB/本地内容，
     * 这里保留空实现只为不改动调用点。
     */
    /**
     * 取消下载（v2.0 起**真正取消**）。
     *
     * 旧实现是空函数（只有一行注释「靠协程取消」——但根本没有 Job 引用，
     * 协程取消不了，进度文件照写、网络请求照发）。现在：
     *   · 在下载中 → 摘出 Job 并 cancel()（downloadAlbumInternal 的 catch 会把
     *     状态按进度文件纠正，finally 腾位并调度下一本）；
     *   · 在排队中 → 直接从队列移除（QUEUED 记录留在 DB，由「清空记录」清理）。
     */
    suspend fun cancelDownload(albumId: Int) {
        val job = active.remove(albumId)
        if (job != null) {
            Log.i(TAG, "取消下载 $albumId：终止协程")
            job.cancel()
        } else if (pending.remove(albumId)) {
            Log.i(TAG, "取消下载 $albumId：从排队队列移除")
        } else {
            Log.i(TAG, "取消下载 $albumId：任务不在队列中（可能已完成）")
        }
    }

    /**
     * 取消下载并删除内容：用于下载管理清理「下载一半出问题」的卡死任务。
     * 1) 通知 Python 停止同 id 的飞行中下载（最佳努力）；
     * 2) 删除本地内容目录（saveDir/{albumId}）与进度文件；
     * 3) 删除 DB 记录（级联 chapters / downloads / read_progress / book）。
     */
    suspend fun cancelAndDeleteDownload(albumId: Int, cascadeRemove: suspend (Int) -> Unit) {
        // 1) 取消下载任务并**等它真正退出**再删文件 ——
        //    旧实现 cancelDownload 是空的，删文件时下载协程还在跑，
        //    经常出现「删了又被写出半个文件」。现在 join 保证退出后再动手。
        val job = active.remove(albumId)
        if (job != null) {
            job.cancel()
            runCatching { job.join() }
        }
        pending.remove(albumId)
        // 2) 删除本地内容（部分或完整）+ 进度文件
        withContext(Dispatchers.IO) {
            val albumDir = File(saveDir, albumId.toString())
            if (albumDir.exists()) albumDir.deleteRecursively()
            val progFile = File(saveDir, ".jm_progress" + File.separator + "$albumId.json")
            if (progFile.exists()) progFile.delete()
            // v1.9.2：统一下载日志是**全局共享**的，不随单本书删除（保留完整历史供排查）
        }
        // 3) 删除 DB 记录（级联清理，避免孤儿数据）
        cascadeRemove(albumId)
    }

    /** 当前下载任务快照（供进度轮询判断哪些 album 还在下载中）。 */
    suspend fun getDownloads(): List<Download> = downloadDao.observeAll().first()

    fun observeDownloads(): Flow<List<Download>> = downloadDao.observeAll()
    suspend fun getDownload(bookId: Int): Download? = downloadDao.get(bookId)

    /** 清空全部下载记录（下载管理「清空历史记录」；仅删 downloads 表，不影响书架与已下载内容）。 */
    suspend fun clearAllDownloadRecords() = downloadDao.clearAll()

    /** 删除单条下载记录（隐藏记录页「删除」；仅删记录，不影响书架与已下载内容）。 */
    suspend fun deleteDownloadRecord(bookId: Int) = downloadDao.delete(bookId)

    /**
     * 读取某本漫画的实时下载进度（图片级）。
     * 进度由 Python 每完成一张图落盘到 saveDir/.jm_progress/{albumId}.json，
     * 此处直接读文件，避免与「阻塞式整本下载」并发调用 Python 抢 GIL 导致读不到实时数据。
     * speed 由调用方（ViewModel 轮询）按两次轮询间的字节增量估算。
     */
    suspend fun getDownloadProgress(albumId: Int): DownloadProgress = withContext(Dispatchers.IO) {
        val file = File(saveDir, ".jm_progress" + File.separator + "$albumId.json")
        if (!file.exists()) return@withContext DownloadProgress("NOT_FOUND", 0, 0, 0, 0f, 0L)
        runCatching {
            val root = JSONObject(file.readText())
            DownloadProgress(
                status = root.optString("status", "NOT_FOUND"),
                done = root.optInt("done", 0),
                total = root.optInt("total", 0),
                bytes = root.optLong("bytes", 0),
                speed = 0f,
                mtime = file.lastModified()
            )
        }.getOrDefault(DownloadProgress("NOT_FOUND", 0, 0, 0, 0f, 0L))
    }

    // ── v1.18.0：运行时看门狗已移除 ────────────────────────────────
    // 原「卡死检测 + 自动续传重下」（v6.0.8 起，5s 一轮扫全部 DOWNLOADING、
    // 60s 无进展就取消重下、最多自动重试 8 次）整块删除，约 250 行。
    // 删的理由：① 它误杀过真下载（代码注释自认「重试 4 次后标 FAILED，
    // 但其实它一直在正常下」）；② 每 5 秒读进度文件 + 查库，对轻量版是纯负担；
    // ③ 用户要的是「轻」，卡死手动重下即可（下载页有「⟳ 继续下载」）。
    // **启动续传 [resumePendingDownloads] 保留** —— 那是可感知的能力：
    // 退出 App 后未完成的下载下次启动会自动接着下。

    /**
     * App 启动时恢复未完成的下载（v6.0.19，v1.6.7 加首帧延迟）。
     *
     * 扫描 downloads 表 status=DOWNLOADING/QUEUED 的任务逐本重新下载——
     * 断点续传靠「目标文件已存在且非空则跳过」，不重复下已完成的图片。
     *
     * ── v1.6.7 修「关闭 App 后进度不能继续」─────────────────────
     * 原实现 `scope.launch { ... }` **立刻**开始恢复，但那时：
     *   ① App 刚 onCreate，接口线路（`JmApiHost.current()`）还在后台预热，
     *      `albumDetail` 大概率拿不到 → 直接标 FAILED → 用户看到"续传失败"；
     *   ② `JmRepository` 里 `downloads` 的`also{}` 块早于 `library` 初始化，
     *      恢复流程用到 library 时可能尚未就绪。
     * 现在：**延迟 1.5s + 拉取前先等线路就绪（最多 10s）**，
     * 让启动噪声过去再恢复，显著提高续传成功率。
     */
    fun resumePendingDownloads() {
        scope.launch {
            // ① 等启动阶段过去 + 线路就绪（best-effort，超时也继续）
            delay(1_500)
            runCatching {
                repeat(20) {                      // 最多 20 × 500ms = 10s
                    if (JmApiHost.current() != null) return@runCatching
                    delay(500)
                }
            }
            // ② 再留一点余量给 DB / 协程池就绪
            delay(300)

            // ── v1.7.0 改为「扫进度文件」而非只查 DB ──────────────────────
            // 旧实现只查 downloads 表的 DOWNLOADING/QUEUED，漏掉两种真实情况：
            //   ① watchdog 重试超限后把记录标成 FAILED → 不在恢复列表 → 永不续传；
            //   ② 用户手动「清空下载历史」后记录就没了。
            // 而**进度文件 `.jm_progress/{albumId}.json` 才是"下到哪了"的真源**，
            // 它一定在磁盘上。改为：扫目录里所有 `status != DONE` 且 `done < total` 的 id。
            val byProgress = scanUnfinishedFromProgressFiles()
            val byDb = runCatching { downloadDao.observeAll().first() }
                .getOrDefault(emptyList())
                .filter { it.status == "DOWNLOADING" || it.status == "QUEUED" }
                .map { it.bookId }
            val pending = (byProgress + byDb).distinct().filter { it > 0 }
            if (pending.isEmpty()) return@launch
            Log.i("DownloadManager", "恢复未完成下载：${pending.size} 本（进度文件 ${byProgress.size} + DB ${byDb.size}）")
            enqueueAlbums(pending)
        }
    }


    /**
     * 扫 `.jm_progress` 目录里的进度文件，返回**未下完**的专辑 id 列表（v1.7.0）。
     *
     * 判据：status不是 DONE/NOT_FOUND，且 done < total。
     * 进度文件才是"下到哪了"的真源 —— DB 记录可能被用户清理掉，
     * 或被 watchdog 标成 FAILED 而不在恢复列表里，但进度文件一定还在磁盘上。
     */
    private fun scanUnfinishedFromProgressFiles(): List<Int> {
        val dir = File(saveDir, ".jm_progress")
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return emptyList()
        val out = ArrayList<Int>()
        for (f in files) {
            val id = f.nameWithoutExtension.toIntOrNull() ?: continue
            val ok = runCatching {
                val o = org.json.JSONObject(f.readText())
                val st = o.optString("status")
                val done = o.optInt("done", 0)
                val total = o.optInt("total", 0)
                st != "DONE" && st != "NOT_FOUND" && total > 0 && done < total
            }.getOrDefault(false)
            if (ok) out.add(id)
        }
        return out
    }

    private companion object {
        const val TAG = "DownloadManager"

        /** 书本级并发默认值。 */
        const val DEFAULT_BOOK_CONCURRENCY = 2

        /**
         * 书本级并发**硬上限**。
         *
         * 总并发 = 本数 × 图片并发。取 3 是因为默认图片并发 6：
         * 3 × 6 = 18 个请求，源站还能接受；再高（12 图 × 3 本 = 36）容易被风控。
         */
        const val MAX_BOOK_CONCURRENCY = 3
    }
}
