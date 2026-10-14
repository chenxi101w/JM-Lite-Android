package com.example.jmreader.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.Download
import com.example.jmreader.data.repository.DownloadProgress
import com.example.jmreader.data.repository.JmRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 下载管理：对齐 Windows 版底部悬浮按钮「下载管理」。
 * 展示进行中/已完成的下载任务与本地书架漫画（标题由 books 流补全）。
 */
class DownloadsViewModel(private val repo: JmRepository) : ViewModel() {

    /** 下载记录行：local=true 表示该漫画当前在本地（账本行 savedDir 非空，可离线读）。
     *  blocked=true 表示该漫画已被拉黑（账本 blocked 列，4.13.0）。 */
    data class Row(val download: Download, val title: String, val local: Boolean, val blocked: Boolean = false)

    /** 是否连「已隐藏」的下载记录一起显示（设置里下载后行为=自动隐藏时会产生隐藏记录）。 */
    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()
    fun toggleShowHidden() { _showHidden.value = !_showHidden.value }

    val rows: StateFlow<List<Row>> =
        combine(repo.observeDownloads(), repo.observeBooks(), _showHidden) { dls, books, showHidden ->
            // v1.12.0 单表：books 流即账本全量（含已删本地的行），title 兜底/blocked/local 全部从行上取
            val bookById = books.associateBy { it.id }
            dls.filter { showHidden || it.hidden == 0 }
                .map { dl ->
                    val b = bookById[dl.bookId]
                    Row(
                        dl,
                        b?.title?.takeIf { it.isNotBlank() } ?: "JM-${dl.bookId}",
                        local = b != null && b.savedDir.isNotBlank(),
                        blocked = b?.blocked == 1
                    )
                }
                // v14：三流合并 + 建全表 Map 的开销挪出主线程
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 被隐藏的记录数（用于提示「N 条已隐藏」）。 */
    val hiddenCount: StateFlow<Int> =
        repo.observeDownloads()
            .map { list -> list.count { it.hidden == 1 } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 仅被隐藏的记录（设置页「被隐藏的下载记录」页面展示，支持删除/清空）。 */
    val hiddenRows: StateFlow<List<Row>> =
        combine(repo.observeDownloads(), repo.observeBooks()) { dls, books ->
            val bookById = books.associateBy { it.id }
            dls.filter { it.hidden == 1 }
                .map { dl ->
                    val b = bookById[dl.bookId]
                    Row(
                        dl,
                        b?.title?.takeIf { it.isNotBlank() } ?: "JM-${dl.bookId}",
                        local = b != null && b.savedDir.isNotBlank()
                    )
                }
                // v14：计算挪出主线程；且本流仅供设置页「被隐藏的下载记录」使用，
                // 改 WhileSubscribed(0) 让页面不可见时立刻停止订阅，不再常驻空转。
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())

    /** 实时下载进度（图片级 done/total + 速度），以 bookId 为键。 */
    private val _progresses = MutableStateFlow<Map<Int, DownloadProgress>>(emptyMap())

    /** 单本下载进度流（v14）：供下载卡片**独立**订阅。
     * 原实现在页面顶层 collect 整张 Map —— 下载中每 500ms 推一个新 Map，
     * 整页（含 MainScaffold 与全部卡片）跟着每 500ms 重组一次。
     * 现在每张卡片只订阅自己那一条，且 distinctUntilChanged 过滤掉内容未变的推送。
     */
    fun progressFor(bookId: Int): Flow<DownloadProgress?> =
        _progresses.map { it[bookId] }.distinctUntilChanged()
    val progresses: StateFlow<Map<Int, DownloadProgress>> = _progresses.asStateFlow()

    /** 下载失败提示（v7.3.1）：JM 号直达下载 / 重新下载失败时写入，页面 Toast 展示后清空。 */
    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()
    fun consumeDownloadError() { _downloadError.value = null }

    // ── v1.8.8：速度改为「每 1 秒的真实差值」，**不做任何平滑** ──────────
    //
    // 用户原话：「下载速度别搞滑动了，自己骗自己」。
    //
    // v1.8.3 我把「上一帧 500ms 差值」改成了「4 秒滑动窗口 + 指数平滑(0.55/0.45)」，
    // 读数确实变好看了 —— 但那是**修饰过的数字**：网络卡顿时窗口会被几秒前的样本拉高、
    // 停顿后又会缓慢衰减，看上去"还在下"，实际上那一刻可能一个字节都没进。
    // 这类数字**没法用来判断问题**，只是好看。
    //
    // 现在回到最朴素的定义：**相邻两次采样之间，实际下载了多少字节 / 实际过了多少秒**。
    //   · 采样周期固定 1 秒；
    //   · 这一秒字节数没变 → 就显示 0，**不沿用上一次的数**（那才是骗人）；
    //   · 跨度用实测时间差，不假设正好 1.0 秒（进度文件每秒才落一次盘，读写有相位差）。
    //
    // 存 `albumId -> (上次采样时刻ms, 上次字节数)`。
    private val _speedPrev = HashMap<Int, Pair<Long, Long>>()

    // 下载中断判定阈值：进度文件/DB 行超过此时长无更新，视为进程被杀/卡死的死任务
    private val STALE_MS = 10 * 60 * 1000L

    /** 取消下载并删除内容（清理「下载一半出问题」的卡死任务：停飞 + 删本地 + 删 DB）。 */
    fun cancelAndDelete(bookId: Int) {
        viewModelScope.launch {
            runCatching { repo.cancelAndDeleteDownload(bookId) }
        }
    }

    /** 重新下载（下载记录卡片「重新下载」/ JM 号直达下载：重新整本下载到书架）。
     *  v7.3.1：失败原因写入 downloadError，页面 Toast 提示（此前静默，用户以为没反应）。
     *  v2.0：走 DownloadManager.redownload（force=true）——正在下载的先取消再重新入队，
     *  旧版会被去重挡住 = 点了没反应。 */
    fun redownload(bookId: Int) {
        viewModelScope.launch {
            runCatching { repo.redownload(bookId) }
                .onFailure { _downloadError.value = it.message ?: "下载失败" }
        }
    }


    /** 清空全部下载记录（仅删 downloads 表，不影响书架与已下载内容）。 */
    fun clearAllRecords() {
        viewModelScope.launch {
            runCatching { repo.clearAllDownloadRecords() }
        }
    }

    /** 删除单条下载记录（隐藏记录页用；仅删记录，不影响书架与已下载内容）。 */
    fun deleteRecord(bookId: Int) {
        viewModelScope.launch {
            runCatching { repo.deleteDownloadRecord(bookId) }
        }
    }

    init {
        viewModelScope.launch {
            while (true) {
                val active = runCatching { repo.getDownloads() }.getOrDefault(emptyList())
                    .filter { it.status == "DOWNLOADING" }
                if (active.isEmpty()) {
                    if (_progresses.value.isNotEmpty()) _progresses.value = emptyMap()
                    _speedPrev.clear()
                    delay(1000)
                    continue
                }
                val map = HashMap<Int, DownloadProgress>(active.size)
                val now = System.currentTimeMillis()
                for (dl in active) {
                    val prog = runCatching { repo.getDownloadProgress(dl.bookId) }.getOrNull()
                    if (prog == null) {
                        _speedPrev.remove(dl.bookId)
                        continue
                    }
                    when (prog.status) {
                        "DONE", "FAILED", "CANCELLED" -> {
                            // 进度文件已定稿，但 DB 仍 DOWNLOADING（Kotlin 落库被协程取消/异常跳过）→ 纠正
                            _speedPrev.remove(dl.bookId)
                            runCatching {
                                repo.reconcileDownloadStatus(
                                    dl.bookId, if (prog.status == "DONE") "DONE" else "FAILED"
                                )
                            }
                            continue
                        }
                        "DOWNLOADING" -> {
                            // 进度文件长时间未更新（>10min）= 下载已中断（进程被杀/卡死），纠正为失败
                            if (prog.mtime > 0 && now - prog.mtime > STALE_MS) {
                                _speedPrev.remove(dl.bookId)
                                runCatching { repo.reconcileDownloadStatus(dl.bookId, "FAILED") }
                                continue
                            }
                        }
                        "NOT_FOUND" -> {
                            // 预取阶段（文件尚未创建）或文件缺失。若 DB 行很旧（>10min）仍无进度文件，
                            // 视为下载已中断，纠正为失败；否则按预取中处理（不展示进度）。
                            _speedPrev.remove(dl.bookId)
                            if (now - dl.createdAt > STALE_MS) {
                                runCatching { repo.reconcileDownloadStatus(dl.bookId, "FAILED") }
                                continue
                            }
                        }
                    }
                    // 仍在下载中：**最近一次采样间隔内的真实平均速度**（见上方字段注释）
                    var speed = 0f
                    if (prog.status == "DOWNLOADING") {
                        val prev = _speedPrev[dl.bookId]
                        if (prev != null) {
                            val dt = (now - prev.first) / 1000f
                            // 跨度太小说明两次采样几乎同时（首帧 / 卡顿），这次不算数
                            if (dt >= 0.5f) {
                                // 字节数没增长就是 0 —— 不沿用上一次，不做任何修饰
                                speed = ((prog.bytes - prev.second) / dt).coerceAtLeast(0f)
                            }
                        }
                        _speedPrev[dl.bookId] = now to prog.bytes
                    } else {
                        _speedPrev.remove(dl.bookId)
                    }
                    map[dl.bookId] = prog.copy(speed = speed)
                }
                _progresses.value = map
                // v1.8.8：采样周期 1 秒（v1.8.3 曾用 500ms + 平滑掩盖问题）。
                // 与进度文件「每秒节流写一次盘」的频率对齐 → 差值恰好覆盖一次写入，不会漏采样。
                delay(1000)
            }
        }
    }
}
