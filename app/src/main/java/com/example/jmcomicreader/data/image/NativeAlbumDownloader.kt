package com.example.jmreader.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.jmreader.data.api.JmApiClient
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.Chapter
import com.example.jmreader.data.log.DownloadEventLog
import com.example.jmreader.util.JmDebug
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 原生整本下载器（v1.2.8 起，v1.3.1 真机验证通过）—— 「去掉 Python」路线的核心。
 *
 * 此前整本下载完全依赖 Python（`PythonBridge.downloadAlbum` → jmcomic 的 `download_by_image_detail`），
 * 是 Chaquopy 身上最大的一块依赖。本类用**纯 Kotlin + OkHttp + ImageScrambler** 把整条链路重写：
 *
 * ```
 * /album?id=<aid>      → series[]        章节列表（id / sort / name）
 * /chapter?id=<photo>  → images[]         该章所有图片的**文件名**
 *                       → scramble_id      加扰参数（要另调 /chapter_view_template 取）
 *                       → CDN 域名         不返回，用内置列表轮换
 * GET https://{domain}/media/photos/{photoId}/{name}?v={v}
 *     → ImageScrambler.decode() 还原 → 写入 {saveDir}/{sort}/{name}
 * ```
 *
 * 特性：
 *  - **断点续传**：目标文件已存在且非空则跳过（与 Python 侧 `_save_image_decoded` 行为一致）；
 *  - **域名轮换**：逐个 CDN 域名重试（API 不返回域名，jmcomic 是随机挑，我们更稳）；
 *  - **进度文件同格式**：`{saveDir}/.jm_progress/{albumId}.json`，与 Python 侧一致，
 *    使 DownloadManager 的进度轮询 / 卡死检测 / 恢复下载全部复用现有逻辑；
 *  - **失败如实上报**：任一张失败都会记入并最终返回 [Outcome.Failed]，不静默产出残缺文件。
 */
internal class NativeAlbumDownloader(
    private val api: JmApiClient,
    private val http: OkHttpClient,
    private val saveDir: File,
    /** 图片级并发上限的来源（设置页「图片并发数」）。
     *  v1.8.9 起**只用于首次构造闸门的初始值**；运行中改设置请调 [updateImageConcurrency]，
     *  它会立即生效，不必等下一本。 */
    private val imgConcurrency: () -> Int = { DEFAULT_IMAGE_CONCURRENCY },
) {
    init {
        // 用配置里的值初始化令牌上限（设置页改过也能在启动时生效）
        runCatching {
            updateImageConcurrency(imgConcurrency())
        }
    }

    /** 章节引用（统一内部表示：无论来自 preset 的 Chapter 还是自取的 ChapterRef）。 */
    private data class ChapterRefOf(val id: Int, val sort: Int, val name: String)

    private data class ChapterPlan(
        val ref: ChapterRefOf,
        val detail: JmApiClient.ChapterDetail,
    )

    /** 一张待下载图片的完整任务描述（v1.8.4：跨章统一并发池的任务单元）。 */
    private data class ImgJob(
        val target: File,
        val photoId: Int,
        val name: String,
        val domain: String,
        val vParam: String,
        val scrambleId: Int,
    )

    /** 进度快照（图片级）。 */
    data class Progress(val done: Int, val total: Int, val bytes: Long)

    /** 下载结果。 */
    sealed interface Outcome {
        data class Success(val chapters: Int, val images: Int) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * 下载整本到 [saveRoot]。
     *
     * @param albumId 本子 JM 号（参与加扰参数计算，必须传）
     * @param saveRoot 书架根目录（每章一个 `%03d` 子目录）
     * @param onProgress 进度回调（每张图处理完后调用一次，含跳过的）
     */
    suspend fun download(
        albumId: Int,
        saveRoot: File,
        /** 调用方已取到的详情（v1.4.1 传入以复用，省一次 /album 请求；null 则内部自取）。 */
        preset: List<Chapter>? = null,
        /** preset 为空时用作单行本的章节名。 */
        presetBook: Book? = null,
        onProgress: (Progress) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        // v1.7.5：进度计数器改成原子类型 —— 并发下载时每张图完成都要推一次进度
        val done = AtomicInteger(0)
        val bytes = AtomicLong(0L)

        // ── v1.9.2：**统一下载日志**（1 秒采集、按书分组、全部集中一个文件）────
        // 用户需求原文（第三次澄清）：「一个日志记录所有下载，1s 一采集进度，
        // 一整本记录放一起，所有记录放一个地方方便你（AI）查阅」。
        //
        // 实现：`{saveDir}/.jm_progress/download_log.txt` **一个文件**装所有书：
        //   · 采样协程**每秒**写一条进度行（`速度 X.XMB/s | N/M张 | ...`）；
        //   · 触发型事件（开始/清单/完成摘要/失败原因）也写同一文件（见各埋点）；
        //   · 每行带 `[#{albumId}]` 标记 → 同一本书的行自然连续，
        //     `DownloadEventLog.readFor(id)` 可按书过滤；
        //   · 超 10MB 自动轮转为 `.old.txt`。
        // 同时内存里维护峰值，完成时写统计摘要。
        val peakSpeed = java.util.concurrent.atomic.AtomicReference(0.0)
        val totalForLog = AtomicInteger(0)
        val t0ForLog = System.currentTimeMillis()
        val speedSampler = launch(Dispatchers.IO) {
            var lastT = System.currentTimeMillis()
            var lastB = 0L
            while (isActive) {
                delay(1_000)
                val t = System.currentTimeMillis()
                val b = bytes.get()
                val dtMs = (t - lastT).coerceAtLeast(1L)
                val mbPerSec = (b - lastB).coerceAtLeast(0L) / 1048576.0 / (dtMs / 1000.0)
                // 峰值统计
                peakSpeed.updateAndGet { if (mbPerSec > it) mbPerSec else it }
                // ★ 每秒采集：写进统一下载日志（用户要的 1s 粒度）
                DownloadEventLog.speed(
                    saveDir, albumId, mbPerSec,
                    done.get(), totalForLog.get(),
                    b / 1048576.0, (t - t0ForLog) / 1000
                )
                lastT = t
                lastB = b
            }
        }
        try {
            try {
                // 章节列表：优先用调用方给的，否则自己取
                var chapters: List<ChapterRefOf> =
                    preset?.map { ChapterRefOf(it.id, it.sort, it.name) }
                        ?: api.albumChapters(albumId).map { ChapterRefOf(it.id, it.sort, it.name) }
            if (chapters.isEmpty()) {
                // ⚠️ v1.4.1 修复：**单行本**的 /album 的 series 本来就是空的（没有章节概念），
                // 此前一律报「未取到章节列表」→ **所有单行本都下载不了**。
                // 正确做法：把专辑自身当成唯一一章，photo_id = albumId、sort = 1。
                Log.i(TAG, "单行本（series 为空）：按单章处理，photo_id=$albumId")
                chapters = listOf(ChapterRefOf(albumId, 1, presetBook?.title ?: "第 1 话"))
            }

            // ── v1.8.4：章节清单**并发**拉取 + 短时缓存 ────────────────────
            // 原实现是一句串行 `for (ref in chapters) api.chapterDetail(ref.id)`。
            // 每章 1~2 次真实网络往返（scramble_id 不在 /chapter 响应里时还要再打一次
            // /chapter_view_template），30 章就是 30~60 次**串行** RTT ≈ 9~18 秒
            // 纯等待、字节数零增长。这正是「下载速度时好时坏」的头号来源：
            // 界面先静止十几秒，还常被 DownloadManager 的「10 秒零增长」停滞判据
            // 误判成卡死 → 取消 → 重下 → 又静止十几秒，来回抖动。
            // 现在：① 并发拉取；② 结果缓存 10 分钟，watchdog 续下不再重复付预热成本。
            val plans = fetchChapterPlans(albumId, chapters)
            var totalImages = plans.sumOf { it.detail.pageArr.size }
            // 交给每秒速度采样协程显示分母
            totalForLog.set(totalImages)
            if (plans.isEmpty()) {
                return@withContext Outcome.Failed("未取到任何章节的图片列表（/chapter 返回为空）")
            }

            // v1.7.5：进度计数器改成原子类型 —— 并发下载时每张图完成都要推一次进度
            // （done / bytes 已提到 try 外，供上面的每秒速度采样协程读取）
            // v1.8.5：改成线程安全队列 —— 原来是非同步的 ArrayList，
            // 12 路并发写 `+=` 可能丢项甚至抛异常（结构性修改）。
            val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()

            // ── v1.7.3 自愈：老版本写的图可能是「错误解扰」的，需要整本重下 ──
            // `{albumId}/.jm_ver` 记录写出这些图片的「解扰算法版本」。
            // 版本号低于 DESC_VERSION 说明是旧算法的产物（可能花屏）→ 全部重下。
            val verFile = File(File(saveRoot, albumId.toString()), VER_FILE)
            val wroteVer = runCatching { verFile.readText().trim().toInt() }.getOrDefault(0)
            val needRedo = wroteVer < DESC_VERSION
            if (needRedo && plans.isNotEmpty()) {
                Log.i(TAG, "整本重下：$albumId（图片解扰版本 $wroteVer < $DESC_VERSION）")
            }

            // ── v1.7.5：进度改为**单调递增**（基于磁盘上实际存在的图片）────────
            // 原来 `done` 从 0 起算、每跳过一个文件 +1 —— 续传时（watchdog 取消后重下）
            // 计数会**从头再爬一遍**，界面上就出现「170 → 150」这种倒退，
            // 速度也会因为字节数突然变小而显示成 0 B/s。
            // 现在先把磁盘上已存在的有效图片一次性计入基准，之后只累加新下载的，
            // 保证单调不减、且与字节数一致。
            run {
                val albumRoot0 = File(saveRoot, albumId.toString())
                for (pl in plans) {
                    val d0 = File(albumRoot0, "%03d".format(pl.ref.sort))
                    for (n in pl.detail.pageArr) {
                        if (needRedo) break
                        val f = existingImage(d0, n) ?: continue
                        done.incrementAndGet()
                        bytes.addAndGet(f.length())
                    }
                }
                if (done.get() > 0) {
                    Log.i(TAG, "续传基准：$albumId 已有 ${done.get()}/$totalImages 张、${bytes.get()} 字节")
                }
            }

            JmDebug.i(TAG) {
                "开始整本：$albumId，共 ${plans.size} 章 / $totalImages 张" +
                    "，进度文件=${File(saveDir, ".jm_progress/$albumId.json").absolutePath}"
            }
            DownloadEventLog.event(
                saveDir, albumId,
                "开始整本：${plans.size} 章 / $totalImages 张" +
                    if (done.get() > 0) "（续传：已有 ${done.get()} 张）" else ""
            )
            // ── v1.8.4：整本**统一并发池**（原「章内并发、章间串行」）──────────
            // 章间串行有典型的 straggler 问题：每章结束都要等本章**最慢**那张图，
            // 30 章就累计 30 次「等最慢的一张」，吞吐被反复拉回单条连接的水平
            // —— 表现就是「一阵快一阵慢」。
            // 现在先把所有章待下的页拉平成一张任务表，用一个全局并发池跑：
            // 慢图只拖住它自己那一路，其余路继续吃带宽。
            val albumRoot = File(saveRoot, albumId.toString())
            val jobs = ArrayList<ImgJob>(totalImages)
            for (plan in plans) {
                // ⚠️ 目录结构必须与 Python 版完全一致：`{saveDir}/{albumId}/{sort:03d}/`
                // （见 jm_bridge.download_album：`album_dir = os.path.join(save_dir, str(album_id))`）。
                // v1.4.0曾漏掉 `{albumId}` 这一层 → 书架上所有已下载的书都判定为"目录不存在"，
                // 表现为「下载一直失败」。
                val dir = File(albumRoot, "%03d".format(plan.ref.sort))
                if (!dir.isDirectory && !dir.mkdirs()) {
                    // v1.6.5：不 continue —— 整本因"建不出目录"直接失败的话，
                    // 重试多少次都一样的结果。改为记失败并继续下其他章。
                    failures.add("创建目录失败：${albumRoot.name}/${dir.name}")
                    Log.w(TAG, "创建目录失败 ${dir.absolutePath}")
                    continue
                }
                // 2023-07-14 起图片 URL 必须带 v 参数，否则返回空数据；整章共用同一个 v。
                // data_original_0 缺失时（真机确实会缺）用当前时间戳兜底 —— 与 jmcomic
                // 的 get_data_original_query_params 行为一致：data_original_0 为 None → f'v={now}'。
                val vParam = extractV(plan.detail.dataOriginal0).ifEmpty {
                    (System.currentTimeMillis() / 1000).toString()
                }
                for (name in plan.detail.pageArr) {
                    // 断点续传：已下载过（.webp 或 .jpg 都认），不联网、进度已在基准里
                    if (!needRedo && existingImage(dir, name) != null) continue
                    // 清掉可能残留的坏文件，避免和新文件并存
                    runCatching { File(dir, name).takeIf { it.isFile }?.delete() }
                    jobs += ImgJob(
                        target = File(dir, name),
                        photoId = plan.ref.id,
                        name = name,
                        domain = plan.detail.domain,
                        vParam = vParam,
                        scrambleId = plan.detail.scrambleId,
                    )
                }
            }

            val dlT0 = System.currentTimeMillis()
            if (jobs.isNotEmpty()) {
                // ── v1.8.9：图片并发闸门改为**全局共享**（跨书）────────────────
                // 原来这里是 `Semaphore(imgConcurrency())` 建在 download() **内部** ——
                // 也就是**每本书各有一份闸门**，于是：
                //     同时下 1 本 → 总并发 = 一份的量（实测只有 ~210 KB/s）
                //     同时下 2 本 → 总并发 = 两份的量
                // 而官方 jmcomic 是 `asyncio.Semaphore(image_concurrency)` **全局一份、跨章节共享**，
                // 无论下几本，总并发恒定（实测 24 并发 ≈ 681 KB/s，30 并发 ≈ 1 MB/s，
                // 与用户实测的「Python 轻量版稳 1 MB/s」吻合）。
                // 现在闸门是实例级（下载器是 by lazy 单例），所有书共享，
                // `imgConcurrency` 的语义从「每本多少」变成「**全局一共多少**」——
                // 与 jmcomic 的 `image_concurrency` 完全一致。
                //
                // v1.8.1：进度文件**每秒节流写一次**。
                // 原来只在「章末」写 —— 单章本（大量短篇）整本下完前界面上根本没有数字，
                // 只显示「下载中…」，用户以为状态不准/卡住了。
                val lastProgWrite = AtomicLong(0L)
                // v1.8.5：低频心跳。v1.8.4 把「每张图一条日志」关掉（性能上是对的），
                // 但完全静音之后真机上就没法用 logcat 观测吞吐了，只能靠界面截图。
                // 留一条每 30 秒一次的心跳：既能诊断，又不构成瓶颈。
                val lastBeat = AtomicLong(0L)
                coroutineScope {
                    jobs.map { job ->
                        async(Dispatchers.IO) {
                            // ── 阶段①：网络取图 —— 占用「图片并发」位，**取回后立刻归还** ──
                            // ⚠️ v1.8.5 修正 v1.8.4 的严重回退：
                            // v1.8.4 把 `sem.release()` 放在整个「取图 + 解扰 + 写盘」的外层，
                            // 于是解扰（每个名额都要几百毫秒 CPU）**也占着网络并发位**，
                            // 叠加解扰闸门只有 3 路 → 有效并发被压到 3，吞吐直接掉到 1/4。
                            // 现在网络位只覆盖真正的网络请求，解扰不再拖住后面排队的图。
val t0 = System.currentTimeMillis()
                                var raw: ByteArray? = null
                                var usedHost = ""
                                try {
                                    acquireImgSlot()
                                    try {
                                        val r = fetchRaw(job.photoId, job.name, job.domain, job.vParam)
                                        raw = r.first
                                        usedHost = r.second
                                    } finally {
                                        releaseImgSlot()          // ★ 关键：取图一结束就放开网络位
                                    }
                                } catch (e: Exception) {
                                failures.add("${job.name}：${e.message ?: e.javaClass.simpleName}")
                                Log.w(TAG, "图片下载失败 photo=${job.photoId} name=${job.name}", e)
                            }
                            if (raw == null) return@async
                            val fetchMs = System.currentTimeMillis() - t0

                            // ── 阶段②：解扰 + 写盘 —— 只受解扰闸门约束，不占网络并发位 ──
                            val got = try {
                                writeImage(
                                    photoId = job.photoId,
                                    name = job.name,
                                    scrambleId = job.scrambleId,
                                    target = job.target,
                                    raw = raw,
                                    fetchMs = fetchMs,
                                    usedHost = usedHost,
                                )
                            } catch (e: Exception) {
                                failures.add("${job.name}：${e.message ?: e.javaClass.simpleName}")
                                Log.w(TAG, "图片处理失败 photo=${job.photoId} name=${job.name}", e)
                                0L
                            }
                            if (JmDebug.verbose && fetchMs > SLOW_IMAGE_MS) {
                                JmDebug.w(TAG) { "慢图取回 ${fetchMs}ms photo=${job.photoId} ${job.name}" }
                            }
                            if (got > 0L) {
                                done.incrementAndGet()
                                bytes.addAndGet(got)
                                // v1.8.1：每秒落一次盘，界面数字才连续（CAS 保证同一秒只有一个协程写）
                                val nowMs = System.currentTimeMillis()
                                val prevMs = lastProgWrite.get()
                                if (nowMs - prevMs >= 1_000L &&
                                    lastProgWrite.compareAndSet(prevMs, nowMs)
                                ) {
                                    writeProgress(
                                        albumId, "DOWNLOADING",
                                        done.get(), totalImages, bytes.get()
                                    )
                                }
                                // v1.7.5：**每张图完成就推一次进度** ——
                                // 原来只在章末推一次，长章节（40+ 张）界面会长时间不动。
                                onProgress(Progress(done.get(), totalImages, bytes.get()))
                            }
                            // ── v1.8.6：心跳独立于「有没有图完成」──────────────
                            // v1.8.5 把心跳放在 `got > 0` 里，结果最需要它的场景
                            // （图片普遍很慢、几十秒一张都没完成）反而一条都不打。
                            // 现在放在外面：只要还在跑，每 30 秒必有一条。
                            val beatNow = System.currentTimeMillis()
                            val prevBeat = lastBeat.get()
                            if (beatNow - prevBeat >= 30_000L &&
                                lastBeat.compareAndSet(prevBeat, beatNow)
                            ) {
                                val sec = ((beatNow - dlT0) / 1000L).coerceAtLeast(1L)
                                val kb = bytes.get() / 1024L
                                Log.i(
                                    TAG,
                                    "心跳：$albumId ${done.get()}/$totalImages 张、" +
                                        "${kb}KB、已用 ${sec}s、均速 ${kb / sec} KB/s" +
                                        "（失败 ${failures.size}）"
                                )
                            }
                        }
                    }.forEach { it.await() }
                }
            }
            writeProgress(albumId, "DOWNLOADING", done.get(), totalImages, bytes.get())
            JmDebug.i(TAG) {
                "整本图片阶段结束：$albumId，${jobs.size} 张待下 / 共 $totalImages 张，" +
                    "耗时 ${System.currentTimeMillis() - dlT0}ms，累计 ${done.get()}/$totalImages"
            }
            runCatching {
                val totalSec = ((System.currentTimeMillis() - dlT0) / 1000).coerceAtLeast(1)
                DownloadEventLog.event(
                    saveDir, albumId,
                    "图片阶段结束：处理 ${jobs.size} 张（累计 ${done.get()}/$totalImages），" +
                        "耗时 ${totalSec}s，平均 ${"%.1f".format(bytes.get() / 1048576.0 / totalSec)}MB/s"
                )
            }

            if (failures.isEmpty()) {
                writeProgress(albumId, "DONE", done.get(), totalImages, bytes.get())
                // 完成摘要：平均 / 峰值速度（触发型日志的最后一条，App 内可查）
                runCatching {
                    val totalSec = ((System.currentTimeMillis() - dlT0) / 1000).coerceAtLeast(1)
                    val avg = bytes.get() / 1048576.0 / totalSec
                    val peak = peakSpeed.get()
                    DownloadEventLog.event(
                        saveDir, albumId,
                        "✅ 完成：${done.get()}/$totalImages 张，${"%.0f".format(bytes.get() / 1048576.0)}MB，" +
                            "用时 ${totalSec}s，平均 ${"%.1f".format(avg)}MB/s，峰值 ${"%.1f".format(peak)}MB/s"
                    )
                }
                Outcome.Success(chapters = plans.size, images = done.get())
            } else {
                // ── v1.6.5 修「重新下载永远失败」──────────────────────
                // 原实现：**任何一张图失败 → 整本 FAILED**。而重新下载时
                // 已下好的图走断点续传跳过，只剩那张" intrinsically 有问题"的图
                // （CDN 上确实不存在 / 被删）每次都要重试、每次都失败 →
                // **这本书永远无法下载成功**（用户实测"重新下载可能会失败"）。
                //
                // 现在：绝大多数图下成功就算 DONE（缺个别页不影响阅读），
                // 只有**失败比例超过 5% 或整本一张没下成**才判 FAILED。
                val total = if (totalImages > 0) totalImages else 1
                val failRate = failures.size.toFloat() / total
                val downloaded = done.get() - failures.size
                if (failRate <= 0.05f && downloaded > 0) {
                    Log.w(
                        TAG,
                        "部分图片失败（${failures.size}/$totalImages），仍按成功处理：" +
                            failures.take(2).joinToString("；")
                    )
                    writeProgress(albumId, "DONE", done.get(), totalImages, bytes.get())
                    DownloadEventLog.event(
                        saveDir, albumId,
                        "⚠️ 完成（缺页）：${downloaded}/$totalImages 张成功，${failures.size} 张失败（≤5% 按成功处理），" +
                            "平均 ${"%.1f".format(bytes.get() / 1048576.0 / ((System.currentTimeMillis() - dlT0) / 1000).coerceAtLeast(1))}MB/s"
                    )
                    Outcome.Success(chapters = plans.size, images = downloaded)
                } else {
                    writeProgress(albumId, "FAILED", done.get(), totalImages, bytes.get())
                    DownloadEventLog.event(
                        saveDir, albumId,
                        "❌ 失败：${downloaded}/$totalImages 张成功，${failures.size} 张失败（>5%），" +
                            "例如：${failures.take(2).joinToString("；")}"
                    )
                    Outcome.Failed(
                        "共 ${failures.size}/${totalImages} 张失败，例如：" +
                            failures.take(2).joinToString("；")
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "整本下载异常", e)
            writeProgress(albumId, "FAILED", 0, 0, 0L)
            DownloadEventLog.event(saveDir, albumId, "❌ 异常中断：${e.message ?: e.javaClass.simpleName}")
            Outcome.Failed(e.message ?: "下载失败")
        }
        } finally {
            // ★ 必须显式取消采样器 —— 它是 withContext 的子协程，
            //   withContext 返回前会等所有子协程结束，死循环的它不 cancel 就死锁
            //   （真机 2026-10-08 抓到：下载完成 2 分钟后还在每秒打 0.000）。
            speedSampler.cancel()
        }
    }

    /**
     * **并发**拉取所有章节的图片清单（v1.8.4）。
     *
     * 原来是一句串行 `for (ref in chapters) api.chapterDetail(ref.id)`：
     * 每章一次真实网络往返（[JmApiClient.chapterDetail] 里 scramble_id 缺失时还要
     * 再打一次 `/chapter_view_template`），30 章 = 30~60 次串行 RTT。
     * 这段时间**一张图都没在下**，字节数零增长，界面看着像"没速度"，
     * 还容易被 DownloadManager 的停滞判据误杀成卡死。
     *
     * 现在做两件事：
     *  ① 用 [CHAPTER_FETCH_CONCURRENCY] 路并发拉取；
     *  ② 结果**按 photoId 缓存 10 分钟** —— watchdog 续下 / 断点续传 / 同一本重试
     *     都不必再付一次预热成本（原来每次重下都要重新串行问一遍所有章节）。
     *
     * @return 按 [chapters] **原顺序**排列的计划（并发完成顺序是乱的，
     *         进度语义与目录 sort 要求有序，最后按原顺序重排）
     */
    private suspend fun fetchChapterPlans(
        albumId: Int,
        chapters: List<ChapterRefOf>,
    ): List<ChapterPlan> {
        val now = System.currentTimeMillis()
        val cached = ConcurrentHashMap<Int, JmApiClient.ChapterDetail>()
        val missing = ArrayList<ChapterRefOf>()
        for (ref in chapters) {
            val hit = chapterDetailCache[ref.id]
            if (hit != null && now - hit.first < CHAPTER_CACHE_TTL_MS) cached[ref.id] = hit.second
            else missing += ref
        }
        if (missing.isNotEmpty()) {
            val t0 = System.currentTimeMillis()
            val sem = Semaphore(CHAPTER_FETCH_CONCURRENCY)
            coroutineScope {
                missing.map { ref ->
                    async(Dispatchers.IO) {
                        sem.acquire()
                        try {
                            // v1.8.6：把 albumId 传下去 —— 现代本子可直接跳过
                            // `/chapter_view_template`（getNum 在该分支不含 scramble_id），
                            // 章节数越多省得越多（43 章 = 省 43 次 HTTP 往返）。
                            val d = runCatching { api.chapterDetail(ref.id, albumId) }.getOrNull()
                            if (d != null) {
                                chapterDetailCache[ref.id] = now to d
                                cached[ref.id] = d
                            }
                        } finally {
                            sem.release()
                            // v1.8.6：这一段**没有任何图片字节产生**，但确实是"在干活"。
                            // 每拉完一章就 touch 一次进度文件 mtime，
                            // 让 DownloadManager 的停滞判据能看出"任务还活着"，
                            // 不至于把 40~60 秒的清单拉取误判成卡死并取消。
                            touchProgress(albumId)
                        }
                    }
                }.forEach { it.await() }
            }
            Log.i(
                TAG,
                "章节清单：${missing.size} 章并发拉取完成，" +
                    "耗时 ${System.currentTimeMillis() - t0}ms（缓存命中 ${chapters.size - missing.size} 章）"
            )
        }
        return chapters.mapNotNull { ref -> cached[ref.id]?.let { ChapterPlan(ref, it) } }
    }

    /**
     * 只更新进度文件的 mtime（不改内容），作为「任务仍在活动」的信号（v1.8.6）。
     *
     * 用于**章节清单拉取阶段**：这个阶段一个图片字节都不产生，
     * 光看字节数必然"零增长"，会被 DownloadManager 当成卡死。
     *
     * 安全性：touch 的时机绑定在「真有一章拉完了」上 ——
     * 如果网络真挂起、一章也拿不回来，就不会有 touch，照样能被判出卡死。
     */
    private fun touchProgress(albumId: Int) {
        runCatching {
            val dir = File(saveDir, ".jm_progress")
            val f = File(dir, "$albumId.json")
            if (f.isFile) {
                f.setLastModified(System.currentTimeMillis())
            } else {
                // 还没有进度文件（全新下载）→ 建一个占位，语义就是"已开始、还没下到东西"
                if (!dir.isDirectory && !dir.mkdirs()) return
                f.writeText(
                    org.json.JSONObject()
                        .put("status", "DOWNLOADING")
                        .put("done", 0)
                        .put("total", 0)
                        .put("bytes", 0)
                        .toString()
                )
            }
        }
    }

    /** 下载并按需还原单张图片，返回落盘字节数。 */
    /**
     * 找到该页**实际落盘**的图片文件（v1.7.6 新增）。
     *
     * 为什么需要它：接口 `pageArr` 给的文件名是 `.webp`，但**需要解扰的图会被写成 `.jpg`**
     * （v1.6.6 起改的扩展名）。只用 `File(dir, name)` 去判断「是否已下载」永远找不到 `.jpg`，
     * 后果有两个：
     *  ① 断点续传失效 —— 每次重新下载都会把已有图片**再下一遍**（浪费流量）；
     *  ② 进度基准统计为 0 —— 续传时进度仍会「从头爬」，看起来像倒退。
     * 这里两种名字都认。
     *
     * @return 可用的文件；不存在或（对 `.webp`）是旧算法写坏的则返回 null
     */
    private fun existingImage(dir: File, name: String): File? {
        val a = File(dir, name)
        if (a.isFile && a.length() > 0L && !isStaleBrokenFile(a)) return a
        val b = File(dir, name.substringBeforeLast('.') + ".jpg")
        if (b.isFile && b.length() > 0L) return b
        return null
    }

    /**
     * 是否是「旧版本解扰写坏的」文件（v1.7.3 新增）。
     *
     * 背景：v1.6.6 之前的实现把**解扰后的 JPEG** 写到了接口给的 `.webp` 文件名里；
     * 而解扰算法本身在 v1.7.3 之前对「有余数的块」处理错误 → 这些文件是**花屏**的。
     *
     * 判据：文件名以 `.webp` 结尾，但**内容是 JPEG**（前 3 字节 `FF D8 FF`）。
     *  - 正确路径下，需要解扰的图会写成 `.jpg`；不需要解扰的图才是真 webp。
     *  - 所以「`.webp` 却是 JPEG」必然是坏文件 → 删掉重下。
     *
     * 这样老用户无需手动删库，重新下载时会自动修复。
     */
    private fun isStaleBrokenFile(f: File): Boolean {
        if (!f.name.endsWith(".webp", ignoreCase = true)) return false
        return runCatching {
            f.inputStream().use { ins ->
                val head = ByteArray(3)
                if (ins.read(head) < 3) return false
                head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
            }
        }.getOrDefault(false)
    }

    /**
     * 阶段②：把取回的原始字节**解扰 + 落盘**（v1.8.5 从 `downloadOne` 拆出）。
     *
     * 拆分的意义：解扰是**纯 CPU + 大内存**的活儿（解码 + JPEG 重编码），
     * 必须与「网络取图」解耦 —— 否则解扰期间会一直占着图片并发位，
     * 把整个下载流水线压窄（v1.8.4 就是因为这个把速度做慢了）。
     *
     * @param fetchMs  网络取图耗时，用于慢域名判定
     * @param usedHost 实际取图成功的域名（v1.8.5 修正：v1.8.4 传的是 `job.domain`，
     *                 而它现在是空串 → 慢域名判定每次都命中 indexOf(-1) → 每 15 秒
     *                 无条件换域名 → 连接池反复重建、TLS 握手不断，越优化越慢）
     */
    private suspend fun writeImage(
        /**
         * ★ v1.9.0：这里以前传的是 **albumId**，是错的 ——
         * 官方 `JmImageTool.get_num_by_url` 的 aid 是
         *     `JmcomicText.parse_to_jm_id(url)`，而它的正则 `(photos?|albums?)/(\d+)`
         *     从图片 URL `.../media/photos/{photo_id}/00001.webp` 取的是
         *     **photo_id（章节资源 ID）**，不是 album_id。
         *
         * 后果：**单行本恰好没事**（albumId == photoId），
         * 但**多章漫画每一张图的 num 都算错** → 条带数不对 → 解扰错位 →
         * 视觉上就是「多张画叠在一起」（真机 2026-10-08 用户报告）。
         *
         * 正确值由调用方从 [ImgJob.photoId] 传入。
         */
        photoId: Int,
        name: String,
        scrambleId: Int,
        target: File,
        raw: ByteArray,
        fetchMs: Long,
        usedHost: String,
    ): Long {
        // 加扰图需要还原：num 由 (scramble_id, photo_id, 文件名 stem) 三者共同决定
        val num = ImageScrambler.getNum(scrambleId, photoId, name)
        // v1.8.4：原来这里每张图都无条件打一条 Log.i —— 789 张就是 789 次同步
        // logd 写入，在 12 路并发下 logd 串行成瓶颈。改为只在 verbose 下打。
        JmDebug.i(TAG) {
            "解扰判定 photo=$photoId scrambleId=$scrambleId name=$name → num=$num（取图 ${fetchMs}ms）"
        }
        // v1.8.7：这里原本还有「慢域名惩罚」（fetchMs ≥ 15s 就换域名）。
        // 已移除 —— 它与新的「随机分散」策略冲突：分散本身就是把负载摊到 6 个 CDN 上，
        // 慢域名自然只占 1/6 份额；再叠加惩罚反而会破坏分散性、逼回单域名。
        // 慢只记日志（下面这行），不做调度决策。
        if (JmDebug.verbose && fetchMs >= SLOW_IMAGE_MS) {
            JmDebug.w(TAG) { "慢取图 ${fetchMs}ms @ $usedHost" }
        }

        // ── v1.6.6 修「好几页叠在一起」─────────────────────────────
        // 坑：接口给的文件名是 `00001.webp`，但**解扰后写出的是 JPEG**
        // （descrambleJpeg 内部 `Bitmap.compress(JPEG, 95)`）。
        // 于是落盘文件「扩展名 .webp、内容实为 JPEG」——
        // Coil 按扩展名选WebP 解码器去解 JPEG，解码出错的 bitmap 高度不对
        // → LazyColumn 里各页高度错乱 → **视觉上多页叠在一起**。
        // 真机实测：拉下 00001.webp，前16 字节是 `ffd8ffe0…`（JPEG SOI + JFIF）。
        //
        // 修法：**按实际写出的格式决定扩展名** ——
        //  - 需要解扰（num>0）→ 输出 JPEG，文件名换成 `.jpg`
        //  - 不需要解扰（num<=0）→ 原样透传（本来就是接口给的格式，扩展名保持）
        val actualFile: File = if (num > 0) {
            val jpg = File(target.parentFile, target.nameWithoutExtension + ".jpg")
            val out = descrambleJpeg(raw, num)
            jpg.outputStream().use { it.write(out) }
            // 旧文件名（错的 .webp）删掉，避免阅读器扫到
            if (target.exists() && target.absolutePath != jpg.absolutePath) target.delete()
            jpg
        } else {
            target.outputStream().use { it.write(raw) }
            target
        }
        return if (actualFile.isFile) actualFile.length() else raw.size.toLong()
    }

    /**
     * 下载一张图片，**逐个 CDN 域名重试**。
     *
     * ── v1.8.7：起点从「固定上次成功的那个」改为「**在健康域名里随机挑一个**」──
     *
     * 为什么必须改：官方 jmcomic 是 **每张图** `random.choice(DOMAIN_IMAGE_LIST)`
     * （`jm_toolkit.py:444`）—— 30 个并发请求自然摊到 6 个 CDN 上，单个 CDN 的限速
     * 不会成为天花板。而我们固定用一个域名，**所有并发都压在同一个 CDN 上**，
     * 单域名限速 = 整体天花板，表现就是「跑一会停一会」。
     *
     * 但不能退回成"完全随机"：历史上实测过第一个域名 `cdn-msp.jmapiproxy1.cc`
     * 在真机上长时间不可用，纯随机会让每 6 张图就有 1 张先撞一次超时。
     * 所以这里加了一层**健康度记忆**：
     *  - 域名连续失败 [DOMAIN_FAIL_THRESHOLD] 次 → 进入 [DOMAIN_COOLDOWN_MS] 冷却；
     *  - 起步时只在**未冷却**的域名里随机挑；冷却中的仍会作为回退候选排在后面。
     *
     * 这样既拿到了"负载分散"，又不会反复踩死域名。
     */
    private fun fetchRaw(
        photoId: Int,
        name: String,
        domain: String,
        vParam: String,
    ): Pair<ByteArray, String> {
        val candidates = if (domain.isNotBlank()) {
            (listOf(domain) + JmApiClient.IMAGE_DOMAINS).distinct()
        } else {
            JmApiClient.IMAGE_DOMAINS
        }
        val startIdx = pickStartDomainIndex(candidates.size)
        val ordered = (candidates.drop(startIdx) + candidates.take(startIdx)).distinct()
        // ── v1.8.9：回退链**只走健康域名** ──────────────────────────────
        // 原来 ordered 包含**全部**域名（含正在冷却的）。真机日志抓到的问题：
        // `cdn-msp3.jmapinodeudzn.net` 在手机网络下不可用，撞上一次要白等
        // **connectTimeout 8 秒**，然后还会继续尝试其它被冷却的域名 ——
        // 单张图最坏等 8s × 5 = 40 秒，**24 个名额全被占住却一个字节没进**。
        // 这正是「跑一会停一会」的机制之一：速度从 691 KB/s 掉到 435 KB/s。
        // 现在：优先只试健康域名；全部在冷却时才退回全集（宁可慢也不能全卡）。
        val healthy = ordered.filter { isDomainHealthy(it) }
        val plan0 = if (domain.isNotBlank()) {
            listOf(domain) + ordered.filter { it != domain }
        } else {
            ordered
        }
        val plan = healthy.ifEmpty { plan0 }

        var lastErr: Throwable? = null
        for ((i, host) in plan.withIndex()) {
            val url = buildString {
                append("https://").append(host)
                append("/media/photos/").append(photoId).append('/').append(name)
                if (vParam.isNotEmpty()) append("?v=").append(vParam)
            }
            val r = runCatching {
                http.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        // JM 的图片校验 Referer —— jmcomic 把它写死为 18comic.vip
                        .header("Referer", REFERER)
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    resp.body?.bytes() ?: error("空响应体")
                }
            }
            val idx = JmApiClient.IMAGE_DOMAINS.indexOf(host)
            val bytes = r.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                if (idx >= 0) markDomainOk(idx)
                return bytes to host
            }
            lastErr = r.exceptionOrNull()
            if (idx >= 0) markDomainFail(idx, lastErr)
            // 回退日志降级为 verbose —— 并发分散后容易刷屏
            JmDebug.w(TAG) { "图片域名 $host 失败（photo=$photoId）：${lastErr?.message}" }
        }
        throw lastErr ?: IllegalStateException("所有图片域名都不可用")
    }

    /**
     * 在**未冷却**的域名里随机挑一个起点（v1.8.7）。
     *
     * 全部都在冷却时（极端情况，说明网络整体挂了）退化为全域随机，
     * 至少不会卡在一个永远不动的域名上。
     */
    /**
     * 域名是否处于健康状态（不在冷却期）。
     *
     * 注意 `domain` 可能是接口直接给的域名（不在内置列表里），一律视为健康。
     */
    private fun isDomainHealthy(domain: String): Boolean {
        val idx = JmApiClient.IMAGE_DOMAINS.indexOf(domain)
        if (idx < 0) return true
        return (domainCooldownUntil[idx] ?: 0L) <= System.currentTimeMillis()
    }

    /**
     * 在**未冷却**的域名里随机挑一个起点（v1.8.7）。
     *
     * 全部都在冷却时（极端情况，说明网络整体挂了）退化为全域随机，
     * 至少不会卡在一个永远不动的域名上。
     */
    private fun pickStartDomainIndex(n: Int): Int {
        if (n <= 1) return 0
        val now = System.currentTimeMillis()
        val healthy = (0 until n).filter { (domainCooldownUntil[it] ?: 0L) <= now }
        val pool = if (healthy.isEmpty()) (0 until n).toList() else healthy
        return pool.random()
    }

    /** 域名成功 → 清掉失败计数与冷却（v1.8.7）。 */
    private fun markDomainOk(index: Int) {
        domainFailStreak.remove(index)
        domainCooldownUntil.remove(index)
    }

    /**
     * 域名失败 → 累计失败次数，达阈值进入冷却（v1.8.9 起阈值 = 1）。
     *
     * ── v1.8.9 两处修正 ──
     * **① 阈值 2 → 1。** 真机上 `cdn-msp3.jmapinodeudzn.net` 不可用，
     * 阈值 2 意味着前两张图要各浪费一次 8 秒超时才开始避开它。
     * 偶发抖动误判的代价只是"10 分钟内少一个候选"，远小于持续白等超时的代价。
     *
     * **② 日志节流。** 原来 24 个并发可能**同时**失败、同时打同一条日志
     * （实测刷了 10+ 条），而且因为 `markDomainFail` 里会清零计数，
     * 后到的协程又从 0 累加 → 形成刷屏循环。现在同一个域名 60 秒内只记一次。
     */
    private fun markDomainFail(index: Int, err: Throwable? = null) {
        val streak = (domainFailStreak[index] ?: 0) + 1
        domainFailStreak[index] = streak
        if (streak < DOMAIN_FAIL_THRESHOLD) return
        val host = JmApiClient.IMAGE_DOMAINS.getOrNull(index)
        domainCooldownUntil[index] = System.currentTimeMillis() + DOMAIN_COOLDOWN_MS
        domainFailStreak.remove(index)
        // ── v1.8.9：失败**原因**分类统计（诊断用）──────────────────────
        // 没有原因就分不清"被限流"和"网络慢"，而这两者的对策完全相反
        // （限流要降并发，网络慢要加超时）。按 host 归类，最后一次打印各类计数。
        val kind = when (err) {
            null -> "空响应"
            is java.net.SocketTimeoutException -> "超时"
            is java.net.UnknownHostException -> "DNS解析失败"
            is java.net.ConnectException -> "连接失败"
            is javax.net.ssl.SSLException -> "TLS失败"
            else -> (err.message ?: err.javaClass.simpleName).take(40)
        }
        domainFailKinds.computeIfAbsent(index) { java.util.concurrent.ConcurrentHashMap() }
            .merge(kind, 1, Int::plus)
        val now = System.currentTimeMillis()
        val last = lastDomainLog.getOrDefault(index, 0L)
        if (now - last < DOMAIN_LOG_THROTTLE_MS) return
        lastDomainLog[index] = now
        val breakdown = domainFailKinds[index]?.entries?.joinToString { "${it.key}×${it.value}" } ?: ""
        Log.w(
            TAG,
            "图片域名 [$index] $host 不可用 → 冷却 ${DOMAIN_COOLDOWN_MS / 60_000} 分钟，" +
                "期间只尝试其余域名。失败原因：$breakdown"
        )
    }

    /**
     * 把加扰的 JPEG 字节流还原为正常图片字节流。
     *
     * `num <= 0` 表示该图未加扰，原样返回。还原后以 JPEG 95% 写出（漫画是照片类内容，
     * 95% 几乎不可见损失）。真机核验（2026-10-06）：926×1331、`num=6`（`1331 % 6 = 5`，
     * 正好覆盖「除不尽有余数」的分支），图片顺序与原版完全一致。
     */
    private suspend fun descrambleJpeg(raw: ByteArray, num: Int): ByteArray {
        // v1.8.4：解码 + JPEG 重编码是**纯 CPU + 大内存**操作 ——
        // 一张 1200×1700 的图解码后约 8MB（ARGB_8888），再 95% 重编码要几百毫秒。
        // 图片并发开到 12 时若 12 张同时编解码，会瞬间吃掉上百 MB 并打满 CPU，
        // 引发 GC 抖动、把网络线程饿死 → 吞吐周期性掉到很低（"时好时坏"）。
        // 这里限制同时编解码的路数；**网络下载仍然全并发**，不受这个闸门影响。
        descrambleSem.acquire()
        try {
            val bmp: Bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
            val fixed = ImageScrambler.decode(bmp, num)
            if (fixed === bmp) {
                bmp.recycle()
                return raw
            }
            val bos = ByteArrayOutputStream(raw.size)
            fixed.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            fixed.recycle()
            bmp.recycle()
            return bos.toByteArray()
        } finally {
            descrambleSem.release()
        }
    }

    /** 从首图 URL 里取 `v=` 参数（2023-07-14 起必需，缺失会返回空数据）。 */
    private fun extractV(dataOriginal0: String): String {
        if (dataOriginal0.isBlank()) return ""
        val i = dataOriginal0.indexOf("?v=")
        if (i < 0) return ""
        val tail = dataOriginal0.substring(i + 3)
        val amp = tail.indexOf('&')
        return if (amp >= 0) tail.substring(0, amp) else tail
    }

    /**
     * 写入进度文件（与 Python 侧 `jm_bridge._write_progress_file` 同格式同路径）。
     * 卡死检测靠 `mtime` 判断"长时间无更新"，因此每张图后都要写一次。
     */
    private fun writeProgress(albumId: Int, status: String, done: Int, total: Int, bytes: Long) {
        runCatching {
            val dir = File(saveDir, ".jm_progress")
            if (!dir.isDirectory && !dir.mkdirs()) return
            val f = File(dir, "$albumId.json")
            f.writeText(
                org.json.JSONObject()
                    .put("status", status)
                    .put("done", done)
                    .put("total", total)
                    .put("bytes", bytes)
                    .toString()
            )
            // v1.7.3：整本完成后盖「解扰算法版本」章，供下次判断是否需要自愈重下
            if (status == "DONE") {
                runCatching {
                    File(File(saveDir, albumId.toString()), VER_FILE)
                        .writeText(DESC_VERSION.toString())
                }
            }
        }.onFailure { Log.w(TAG, "写进度文件失败", it) }
    }

    internal companion object {   // v1.7.9：internal 以便 DownloadManager 读取并发常量
        const val TAG = "NativeAlbumDl"

        /**
         * 图片级并发**默认值**。
         *
         * ── v1.8.7 改 6→16；v1.8.9 再改 16→**24**（都是拍脑袋，实测后定的）──
         *
         * v1.8.9 在本机做了基准测试（30 张真实图 / CDN 边缘缓存已预热 / 每组取 2 次最好成绩）：
         *
         * | 图片并发 | 吞吐 |
         * |---|---|
         * | 1 | 36.8 KB/s |
         * | 2 | 62.7 KB/s |
         * | 4 | 81.5 KB/s |
         * | 6 | 107 KB/s |
         * | 12 | 210 KB/s |
         * | 16 | 477 KB/s |
         * | 24 | **681 KB/s** |
         * | 30（jmcomic 默认） | ≈ 1 MB/s |
         *
         * 用户实测「Python 轻量版能稳 1 MB/s」—— 轻量版走 jmcomic，
         * 它的 `download.threading.image` 默认就是 **30**（全局共享，见 [imgConcurrency] 语义）。
         * 我们取 24：已能到 681 KB/s，再往上对手机 CPU/内存压力大
         * （每张图解码后约 8MB，24 路同解码峰值 200MB+），留 24 作为默认。
         */
        const val DEFAULT_IMAGE_CONCURRENCY = 24

        /** 图片级并发**上限**（设置页最大只能选到它）。对齐官方 30。 */
        const val MAX_IMAGE_CONCURRENCY = 32

        /**
         * v1.8.7 之前的默认值（v1.7.9 起的 6）。
         *
         * 保留它只为做**一次性升级**：老用户的 DB 里已经持久化了 `"max_concurrent_downloads=6"`，
         * 改默认值对他们无效。而 6 已从 v1.8.7 的档位（4/8/16/24/32）里移除，
         * 所以读到 6 必然是"老默认值"而非用户主动选的 → 直接提到 16。
         */
        const val LEGACY_DEFAULT_IMAGE_CONCURRENCY = 6

        /**
         * 章节清单并发拉取路数（v1.8.4 引入 6；v1.8.6 提到 8）。
         *
         * 真机实测单次 `/chapter` 要 4~8 秒（接口本身慢），43 章在 6 路下要 55 秒。
         * 提到 8 路 + 跳过现代本子多余的 `/chapter_view_template`，清单阶段 ≈ 降到 1/3。
         */
        const val CHAPTER_FETCH_CONCURRENCY = 8

        /** 章节清单缓存有效期（v1.8.4）。watchdog 续下/重下时免掉重复预热。 */
        const val CHAPTER_CACHE_TTL_MS = 10 * 60_000L

        /**
         * 同时做「解码 + JPEG 重编码」的路数上限（v1.8.4 引入，**v1.8.5 从 3 放宽到 6**）。
         *
         * 这一步是纯 CPU + 大内存，完全不限流（12 路全上）会有内存压力和 GC 抖动；
         * 但定得太低（3）会直接变成吞吐瓶颈。v1.8.5 起网络取图已与解扰解耦
         * （网络位在取图完成时就释放），所以这里的数值只影响解扰本身的吞吐，
         * 取 6 与常见 8 核 SoC 的解码并行度匹配。
         */
        const val DESCRAMBLE_CONCURRENCY = 12

        /**
         * 单张图取回耗时超过此值 → **只记日志**（v1.8.7 起不再触发域名切换）。
         *
         * 域名调度改为「随机分散 + 失败冷却」后，慢不再需要单独惩罚 ——
         * 负载本来就摊在 6 个 CDN 上，慢域名自然只占 1/6 份额。
         */
        const val SLOW_IMAGE_MS = 15_000L

        /** 域名连续失败多少次进入冷却（v1.8.7 定 2；**v1.8.9 改为 1**）。 */
        const val DOMAIN_FAIL_THRESHOLD = 1

        /**
         * 域名冷却时长（v1.8.9：10 → 30 分钟）。
         *
         * 真机上 `cdn-msp3.jmapinodeudzn.net` 确实不可用（PC 上却正常），
         * 说明是**该手机网络下**的问题，可能持续很久 → 冷却拉长，
         * 避免每10 分钟重新踩一次。
         */
        const val DOMAIN_COOLDOWN_MS = 30 * 60_000L

        /** 同一域名的失败日志节流间隔（v1.8.9，避免 24 并发同时失败刷屏）。 */
        const val DOMAIN_LOG_THROTTLE_MS = 60_000L

        /** 图片解扰算法版本（引用 ImageScrambler，保证单一真源）。 */
        const val DESC_VERSION = ImageScrambler.ALGO_VERSION

        /** 记录解扰算法版本的标记文件名。 */
        const val VER_FILE = ImageScrambler.VER_MARKER
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        const val REFERER = "https://18comic.vip/"

        /**
         * 域名健康度（v1.8.7）：host 下标 → 连续失败次数。
         *
         * 达 [DOMAIN_FAIL_THRESHOLD] 次即进冷却、计数清零。
         * 进程级记忆（by lazy 单例下静态等价），重启下载后仍有效。
         */
        private val domainFailStreak = ConcurrentHashMap<Int, Int>()

        /** 域名冷却截止时刻（v1.8.7）：host 下标 → epochMillis。 */
        private val domainCooldownUntil = ConcurrentHashMap<Int, Long>()

        /** 各域名上次打印失败日志的时刻（v1.8.9，用于节流）。 */
        private val lastDomainLog = ConcurrentHashMap<Int, Long>()

        /**
         * 各域名的失败原因分类计数（v1.8.9，诊断用）。
         *
         * `host下标 -> ("超时" | "连接失败" | "HTTPxxx" ...) -> 次数`
         * 没有它就分不清「被源站限流」和「网络本身慢」—— 而这两者对策完全相反。
         */
        private val domainFailKinds =
            ConcurrentHashMap<Int, ConcurrentHashMap<String, Int>>()

        /**
         * 章节详情缓存：`photoId -> (取回时刻, ChapterDetail)`（v1.8.4）。
         *
         * 命中缓存就不再打 `/chapter`，watchdog 续下 / 断点续传 / 同一本重试
         * 都不必重新串行问一遍所有章节。
         */
        private val chapterDetailCache =
            ConcurrentHashMap<Int, Pair<Long, JmApiClient.ChapterDetail>>()
    }

    /**
     * 解扰并发闸门实例（v1.8.4）。
     *
     * 放在实例里而不是 companion：by lazy 单例下等价，但实例字段语义更清晰，
     * 也便于将来按设备内存动态调整 [DESCRAMBLE_CONCURRENCY]。
     */
    private val descrambleSem = Semaphore(DESCRAMBLE_CONCURRENCY)

    // ── v1.8.9：全局共享的图片并发闸门（跨书）──────────────────────────
    //
    // 为什么要令牌式而不是 `Semaphore`：
    //  kotlinx 的 Semaphore **许可数在构造时固定**，改配置就得重建；
    //  而下载进行中重建会让挂在旧 Semaphore 上的协程**永远等不到许可 → 死锁**。
    // 令牌式把「上限」变成可变原子量，改配置时立刻生效，且不可能死锁：
    //     许可 = `tokens.get() < tokenLimit.get()`
    //     竞争 = `compareAndSet` 抢票
    //     等待 = `delay(15)` 轮询重试
    // 满载时轮询开销可忽略（每个等待协程约 66 次/秒），换取的是配置动态生效 + 零竞态。
// ★ v1.8.9 首版曾把 `tokens` 初始化成 `DEFAULT_IMAGE_CONCURRENCY`（24）——
//   那其实是「上限」，不是「已占用数」。于是 `tokens < tokenLimit` 在启动时就是
//   `24 < 24` = false → **所有协程永远拿不到名额 → 0 并发卡死**。
//   真机启动日志当场抓到：「全局图片并发上限 → 24（当前在飞 24）」（此时零下载在跑）。
//   `tokens` 是**已占用计数**，初始必须为 0。
private val tokens = AtomicInteger(0)
private val tokenLimit = AtomicInteger(DEFAULT_IMAGE_CONCURRENCY)
private val tokenLock = Any()

    /** 当前在飞的图片下载数（诊断用）。 */
    val inFlightImages: Int get() = tokens.get()

    /** 当前的全局图片并发上限（诊断用）。 */
    val imageConcurrencyLimit: Int get() = tokenLimit.get()

    /**
     * 设置页改动并发后由 [DownloadManager.refreshConcurrency] 调用，**立即生效**
     * （下一张图就能感觉到，不必等下一本 / 下一章）。
     */
    fun updateImageConcurrency(n: Int) {
        val t = n.coerceIn(1, MAX_IMAGE_CONCURRENCY)
        synchronized(tokenLock) { tokenLimit.set(t) }
        Log.i(TAG, "全局图片并发上限 → $t（当前在飞 ${tokens.get()}）")
    }

    /** 占一个全局图片下载名额。 */
    private suspend fun acquireImgSlot() {
        while (true) {
            synchronized(tokenLock) {
                if (tokens.get() < tokenLimit.get()) {
                    tokens.incrementAndGet()
                    return
                }
            }
            delay(15)
        }
    }

    /** 归还一个全局图片下载名额。 */
    private fun releaseImgSlot() {
        synchronized(tokenLock) { tokens.decrementAndGet() }
    }
}
