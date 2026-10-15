package com.example.jmreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.Chapter
import com.example.jmreader.data.repository.JmRepository
import com.example.jmreader.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderViewModel(private val repo: JmRepository) : ViewModel() {

    /** 章节加载状态：Success 携带**总页数**（本地阅读模式，页数=本地图片数）。 */
    private val _state = MutableStateFlow<UiState<Int>>(UiState.Loading)
    val state: StateFlow<UiState<Int>> = _state

    /** 已解码页面的本地路径（index → path），本地已下载章节一次性全量填充。 */
    private val _pages = MutableStateFlow<Map<Int, String>>(emptyMap())
    val pages: StateFlow<Map<Int, String>> = _pages.asStateFlow()

    /** 当前章节总页数（= state Success 的 data，独立字段便于 UI 预取判断）。 */
    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    /**
     * 每页宽高比（v1.2.0）。
     *
     * 进入章节时一次性读图片**文件头**得到（`inJustDecodeBounds`，不解码像素，极快），
     * 供列表**预先确定每项高度**。
     *
     * 为什么必须预读：若改用「图片加载完成后从 painter 取 intrinsicSize」，
     * 比例会从占位值突变为真实值 → 列表项高度变化 → LazyColumn 重算布局
     * → **用户往后滚动时位置被拽回去**（实测 bug）。
     */
    private val _pageRatios = MutableStateFlow<Map<Int, Float>>(emptyMap())
    val pageRatios: StateFlow<Map<Int, Float>> = _pageRatios.asStateFlow()

    /** 当前书的章节列表（用于上一章 / 下一章翻页）。 */
    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters

    /** 阅读「览」模式每屏页数（来自设置 reader_pages_per_screen，1~6）。 */
    private val _pagesPerScreen = MutableStateFlow(1)
    val pagesPerScreen: StateFlow<Int> = _pagesPerScreen.asStateFlow()

    /** 当前漫画是否已下载到本地（决定右上角「删除本书」是否可用）。 */
    private val _isLocal = MutableStateFlow(false)
    val isLocal: StateFlow<Boolean> = _isLocal.asStateFlow()

    /** 当前漫画基本信息（阅读器底部信息卡展示：封面/标题/作者）。 */
    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    /** 当前漫画是否已收藏（阅读器底部信息卡收藏键）。 */
    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    /** 下载进行中（阅读器信息卡下载键转圈，防重复点击）。 */
    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    /** 是否「曾下载过但本地已被删」：comic_status 的 DOWNLOADED 位为 1 且当前不在本地。
     *  用于阅读页底部显示「本地已删」标识（自动删除/手动删本地后重新进入阅读器可见）。 */
    private val _localDeleted = MutableStateFlow(false)
    val localDeleted: StateFlow<Boolean> = _localDeleted.asStateFlow()

    /** 拉黑/删除的漫画在阅读器里的处理方式（v5.11.0）：none=不处理 / mark=标记出来 / auto_delete=自动删除并退出。 */
    private val _blackDeletedHandle = MutableStateFlow("none")
    val blackDeletedHandle: StateFlow<String> = _blackDeletedHandle.asStateFlow()

    /** 删除进行中（防重复点击）。 */
    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()

    /** 阅读器右侧进度滑轨颜色（配置 reader_progress_color，十六进制，可设置页更改）。 */
    private val _progressColor = MutableStateFlow("#7B6FE0")
    val progressColor: StateFlow<String> = _progressColor.asStateFlow()

    // ── 读完相关配置（v7）────────────────────────────────────

    /** 滚到最后一张图自动标记整本「已看完」（默认关）。 */
    private val _autoFinishScroll = MutableStateFlow(false)
    val autoFinishScroll: StateFlow<Boolean> = _autoFinishScroll.asStateFlow()

    /** 阅读进度达到该百分比自动标记整本「已看完」（默认 90，可设置页改）。 */
    private val _autoFinishPercent = MutableStateFlow(90)
    val autoFinishPercent: StateFlow<Int> = _autoFinishPercent.asStateFlow()

    /** 章节读完自动删除本章本地图片（仅删本章，不动整本记录；默认关）。 */
    private val _autoDeleteChapter = MutableStateFlow(false)
    val autoDeleteChapter: StateFlow<Boolean> = _autoDeleteChapter.asStateFlow()

    /** 沉浸式阅读：滚动时自动隐藏顶栏/进度条/底部按钮（默认开）。 */
    private val _immersiveEnabled = MutableStateFlow(true)
    val immersiveEnabled: StateFlow<Boolean> = _immersiveEnabled.asStateFlow()

    /** 进入本页时需恢复的页码（上次阅读进度），0 = 从头开始。 */
    private val _resumePage = MutableStateFlow(0)
    val resumePage: StateFlow<Int> = _resumePage.asStateFlow()

    fun clearResumePage() { _resumePage.value = 0 }

    /** 当前漫画是否已看完（决定阅读器底部「标记已看完」按钮的显示/隐藏，P0-4）。 */
    private val _isFinished = MutableStateFlow(false)
    val isFinished: StateFlow<Boolean> = _isFinished.asStateFlow()

    init {
        viewModelScope.launch {
            // 一次批量读全部配置（替代 6 次串行 getConfig），进阅读页更快
            val all = repo.getAllConfig()
            _pagesPerScreen.value =
                all["reader_pages_per_screen"]?.toIntOrNull()?.coerceIn(1, 6) ?: 1
            all["reader_progress_color"]?.let { _progressColor.value = it }
            _autoFinishScroll.value = all["reader_auto_finish_scroll"] == "1"
            _autoFinishPercent.value =
                all["reader_auto_finish_percent"]?.toIntOrNull()?.coerceIn(1, 100) ?: 90
            _autoDeleteChapter.value = all["reader_auto_delete_chapter"] == "1"
            _immersiveEnabled.value = all["reader_immersive"] != "0"
            _blackDeletedHandle.value = all["reader_black_deleted_handle"] ?: "none"
        }
    }

    /**
     * 阅读位置变化回调（滚动监听触发）：
     * 1) 始终把当前章标记为「已读完」（读了几章标几）；
     * 2) 若开启「读完自动删本章」，不再当场删除——改为退出阅读器后延迟删除（见 onReaderExit），重入同本取消；
     * 3) 仅当**最后一章**满足「滚到底自动看完」或「阅读进度达阈值自动看完」时，标记整本已看完
     *    （4.2.1 修复：此前任何一章滚到底都会 markFinished，导致「没读完就触发整本自动删除」）。
     * 幂等：重复触发不会造成副作用（UPDATE / 目录已删 / finished 覆盖写）。
     */
    fun onChapterRead(bookId: Int, chapterId: Int, totalPages: Int, currentPage: Int, isLastChapter: Boolean = false) {
        if (totalPages <= 0 || currentPage < 0) return
        viewModelScope.launch {
            repo.markChapterFinished(bookId, chapterId)
            val reachedEnd = currentPage >= totalPages - 1
            val reachedPercent = (currentPage + 1) * 100 / totalPages >= _autoFinishPercent.value
            // 整本看完判定只在最后一章生效：避免读中间章节滚到底就误标整本完成
            if (isLastChapter && ((reachedEnd && _autoFinishScroll.value) || reachedPercent)) {
                repo.markFinished(bookId)
            }
        }
    }

    /**
     * 删除当前正在看的这本：停飞行下载 + 删本地内容目录 + 级联清 DB。
     * 删完回调给 UI 退出阅读器（内容已不存在，留在页面上会白屏）。
     */
    fun deleteCurrent(bookId: Int, onDone: () -> Unit) {
        if (_deleting.value) return
        viewModelScope.launch {
            _deleting.value = true
            runCatching { repo.cancelAndDeleteDownload(bookId) }
            _deleting.value = false
            onDone()
        }
    }

    /** 收藏 / 取消收藏当前漫画（阅读器底部信息卡收藏键）。 */
    fun toggleFavorite(bookId: Int) {
        viewModelScope.launch {
            runCatching { repo.toggleFavorite(bookId) }
            _isFavorite.value = !_isFavorite.value
        }
    }

    /**
     * 从阅读器下载整本漫画（下载到书架）。
     * 完成后回调 onDone(成功?)：成功时 UI 弹「是否删除本地内容」询问。
     */
    fun download(albumId: Int, onDone: (Boolean) -> Unit) {
        if (_downloading.value) return
        viewModelScope.launch {
            _downloading.value = true
            val ok = runCatching {
                repo.downloadAlbum(albumId)
                _isLocal.value = true
            }.isSuccess
            _downloading.value = false
            onDone(ok)
        }
    }

    /**
     * 加载某章节（本地阅读模式，v5.0.18 起仅支持已下载章节）：
     * - 本地已下载：一次读取全部页面路径（离线秒开）；
     * - 未下载：置 Error 状态，提示到详情页下载，绝不联网偷偷读。
     */
    fun loadChapter(photoId: Int, bookId: Int, chapterId: Int) {
        viewModelScope.launch {
            repo.cancelPendingChapterDelete(bookId) // 重新打开同本：取消其待执行的「读完自动删本章」
            _state.value = UiState.Loading
            _pageRatios.value = emptyMap()   // v1.2.0：换章先清旧尺寸，避免残留比例错位
            try {
                val chapters = repo.getChapters(bookId)
                _chapters.value = chapters
                // 底部漫画信息卡：封面/标题/作者 + 收藏态
                val book = runCatching { repo.getBook(bookId) }.getOrNull()
                _book.value = book
                _isFavorite.value = book?.favorite == 1
                // v1.1.2：目录扫描是磁盘 IO，必须切到 IO 线程 ——
                // 原实现在 viewModelScope（Main）里直接 File.isDirectory/listFiles/isFile，
                // StrictMode 实测主线程阻塞约 31ms（11+10+10），即日志里那些 33ms 掉帧的来源。
                val local = withContext(Dispatchers.IO) { loadLocalPaths(bookId, chapterId, chapters) }
                if (local != null) {
                    // 本地章节：全量页面前端直出（离线秒开）
                    _totalPages.value = local.size
                    _pages.value = local.withIndex().associate { it.index to it.value }
                    _state.value = UiState.Success(local.size)
                } else {
                    // 未下载章节：在线阅读已移除（v5.0.18），不再联网偷偷读；
                    // 提示用户到详情页下载后再阅读，避免空白闪烁/卡顿感
                    _totalPages.value = 0
                    _pages.value = emptyMap()
                    _state.value = UiState.Error("本章未下载，请到详情页下载后阅读")
                    return@launch
                }
                // 恢复上次阅读进度：同一章且有页码则保留（UI 滚过去）；否则从头并记录
                val prog = runCatching { repo.getProgress(bookId) }.getOrNull()
                if (prog != null && prog.lastChapterId == chapterId && prog.lastPageIndex > 0) {
                    _resumePage.value = prog.lastPageIndex.coerceAtMost((_totalPages.value - 1).coerceAtLeast(0))
                } else {
                    _resumePage.value = 0
                    repo.saveProgress(bookId, chapterId, 0)
                }
                _isFinished.value = runCatching { repo.getBook(bookId)?.finished == 1 }.getOrDefault(false)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "加载章节失败")
            }
        }
    }

    /**
     * 读取本地已下载章节的图片路径列表；未下载（savedDir 为空）或本地缺失时返回 null
     * （调用方据此置 Error 提示到详情页下载，v5.0.18 起不再走在线读章）。
     */
    private suspend fun loadLocalPaths(
        bookId: Int,
        chapterId: Int,
        chapters: List<Chapter>
    ): List<String>? {
        val book = repo.getBook(bookId)
        _isLocal.value = book != null && book.savedDir.isNotBlank()
        // 曾下载过（DOWNLOADED 位）但当前不在本地 → 本地已被删（自动删除/手动删本地后重进阅读器可见）
        _localDeleted.value = !_isLocal.value &&
            runCatching { repo.hasDownloadRecord(bookId) }.getOrDefault(false)
        if (book != null && book.savedDir.isNotBlank()) {
            val chapter = chapters.firstOrNull { it.id == chapterId }
            if (chapter != null) {
                val dir = File(book.savedDir, "%03d".format(chapter.sort))
                if (dir.isDirectory) {
                    val local = dir.listFiles { f ->
                        f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
                    }?.sortedBy { it.name }?.map { it.absolutePath }
                    if (!local.isNullOrEmpty()) {
                        // v1.2.0：预读各页尺寸（此处已在 Dispatchers.IO 上下文），
                        // 让列表首帧就拿到真实比例，避免加载完成后高度变化把滚动位置拽回。
                        _pageRatios.value = readPageRatios(local)
                        return local
                    }
                }
            }
        }
        return null
    }

    /**
     * 取下一章的**前 N 张**图片路径，供 UI 用 Coil 预热（v1.2.3）。
     *
     * 为什么不做「预取整章数据」：本 App 自 v5.0.18 起只支持阅读**已下载**章节，
     * 切章就是扫描本地目录（几十毫秒），预取路径列表几乎没有收益。
     * 真正让用户感觉"卡"的是切过去之后**图片解码**那一下 —— 因此这里只返回前几张路径，
     * 由调用方提前 enqueue 到 Coil 内存缓存，切章后首屏即可见。
     *
     * 本方法**不修改任何阅读状态**（不动 pages / totalPages / isLocal 等），只读。
     */
    suspend fun peekNextChapterPaths(bookId: Int, chapterId: Int, limit: Int = 3): List<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val book = repo.getBook(bookId) ?: return@withContext emptyList()
                if (book.savedDir.isBlank()) return@withContext emptyList()
                val chapter = repo.getChapters(bookId).firstOrNull { it.id == chapterId }
                    ?: return@withContext emptyList()
                val dir = File(book.savedDir, "%03d".format(chapter.sort))
                if (!dir.isDirectory) return@withContext emptyList()
                dir.listFiles { f ->
                    f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
                }?.sortedBy { it.name }?.take(limit)?.map { it.absolutePath } ?: emptyList()
            }.getOrDefault(emptyList())
        }

    /**
     * 只读图片文件头拿宽高比（不解码像素）。
     * 单张耗时约 0.1~1ms，一话数十张也在毫秒级；读失败则退回占位比例。
     */
    private fun readPageRatios(paths: List<String>): Map<Int, Float> {
        val out = HashMap<Int, Float>(paths.size)
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        paths.forEachIndexed { i, path ->
            runCatching {
                android.graphics.BitmapFactory.decodeFile(path, opts)
                val w = opts.outWidth
                val h = opts.outHeight
                if (w > 0 && h > 0) out[i] = w.toFloat() / h.toFloat()
                // 复用 Options 前重置，避免上一次的 outWidth 残留
                opts.outWidth = 0
                opts.outHeight = 0
            }
        }
        return out
    }

    fun savePage(bookId: Int, chapterId: Int, pageIndex: Int) {
        viewModelScope.launch { repo.saveProgress(bookId, chapterId, pageIndex) }
    }

    /** 根据当前章节 id 计算相邻章节（用于翻章）。 */
    fun neighborChapter(currentChapterId: Int, forward: Boolean): Chapter? {
        val list = _chapters.value
        val idx = list.indexOfFirst { it.id == currentChapterId }
        if (idx < 0) return null
        val next = if (forward) idx + 1 else idx - 1
        return list.getOrNull(next)
    }

    /** 标记整本漫画「已看完」（阅读器底部按钮调用），并同步刷新 isFinished。 */
    fun markFinished(bookId: Int) {
        viewModelScope.launch {
            repo.markFinished(bookId)
            _isFinished.value = true
        }
    }

    /** 取消「已看完」。 */
    fun markUnfinished(bookId: Int) {
        viewModelScope.launch { repo.markUnfinished(bookId); _isFinished.value = false }
    }

    /**
     * 退出阅读器时由 UI 的 DisposableEffect(onDispose) 调用（v1.0.0 轻量版简化）：
     * 整本已看完（以 DB 为准）→ 只保留 finished 标记（书架「看完」沉底），**不清本地**——
     * 轻量版删除 = 手动彻底删除，看完不自动清理、不进回收站。
     * 若开启「读完自动删本章」，调度延迟删除该本所有已读完章节的本地图片（重入会被取消）。
     */
    fun onReaderExit(bookId: Int) {
        viewModelScope.launch {
            if (_autoDeleteChapter.value) {
                repo.scheduleChapterDelete(bookId)
            }
        }
    }
}
