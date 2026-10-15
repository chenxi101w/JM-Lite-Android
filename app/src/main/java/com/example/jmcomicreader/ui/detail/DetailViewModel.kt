package com.example.jmreader.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.Chapter
import com.example.jmreader.data.repository.DownloadProgress
import com.example.jmreader.data.repository.JmRepository
import com.example.jmreader.ui.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 详情页数据载体：书籍 + 章节列表，统一进 UiState。 */
data class DetailContent(val book: Book, val chapters: List<Chapter>)

class DetailViewModel(private val repo: JmRepository) : ViewModel() {

    private val _state = MutableStateFlow<UiState<DetailContent>>(UiState.Loading)
    val state: StateFlow<UiState<DetailContent>> = _state

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError

    private val _isLocal = MutableStateFlow(false)
    val isLocal: StateFlow<Boolean> = _isLocal

    /** 已读完章节数（v7：详情页「已读 X/N」；阅读器读完返回后实时刷新）。 */
    private val _finishedChapters = MutableStateFlow(0)
    val finishedChapters: StateFlow<Int> = _finishedChapters.asStateFlow()

    /** 已读完章节 id 集合（DB 实时观察，行内「已读」标记用，不受网络章节覆盖影响）。 */
    private val _finishedChapterIds = MutableStateFlow<Set<Int>>(emptySet())
    val finishedChapterIds: StateFlow<Set<Int>> = _finishedChapterIds.asStateFlow()

    /** 是否已收藏整本漫画（顶部心形按钮状态）。 */
    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    /** 已收藏标签集合（详情页标签「收藏模式」点标签收藏，存配置 fav_tags，| 分隔）。 */
    private val _favTags = MutableStateFlow<Set<String>>(emptySet())
    val favTags: StateFlow<Set<String>> = _favTags.asStateFlow()

    /** 本 VM 已成功加载过的 albumId：从搜索/阅读页返回时直接复用，不再转圈重拉。 */
    private var loadedId: Int? = null

    /** 已读章节观察 job：load() 前先取消旧 job，避免多次 load 累积多个 collect（修复卡顿/泄漏）。 */
    private var chapterObserveJob: Job? = null

    /**
     * 加载详情。
     * - 已加载过同一本且仍是成功态 → 直接返回（「返回上一页不重新加载」）。
     * - 否则先查内存详情缓存（repo.getDetailCached），再回落联网。
     * - 加载成功后查看次数 +1（阅读频率统计）。
     */
    fun load(albumId: Int, preferLocal: Boolean = false, forceRefresh: Boolean = false) {
        if (!forceRefresh && loadedId == albumId && _state.value is UiState.Success) return
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = try {
                // v1.1.1：本地优先 —— 已下载的漫画直接吃本地数据（含从目录重建的章节），
                // 离线也能进详情页；只有未下载才联网，且联网失败时若本地有数据就回退本地，
                // 不再因为「没网」而整个详情页打不开（此前书架点已下载漫画会跳这里并卡死）。
                val local = repo.getLocalDetail(albumId)
                val (b, ch) = if (local != null && !forceRefresh) {
                    local
                } else {
                    runCatching { repo.getDetailCached(albumId, forceRefresh) }
                        .getOrElse { local ?: throw it }
                }
                _isLocal.value = local != null || b.savedDir.isNotBlank()
                _isFavorite.value = b.favorite == 1
                _favTags.value = (repo.getConfig("fav_tags") ?: "").split("|").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                loadedId = albumId
                repo.bumpViewCount(albumId)   // 查看次数+1（阅读频率统计）
                UiState.Success(DetailContent(b, ch))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "加载详情失败")
            }
            // 已读章节实时观察（阅读器读完返回本页自动刷新「已读 X/N」与行内标记）：
            // 只保留一个 collect；重进详情页（forceRefresh）先取消旧的，避免累积多个协程。
            chapterObserveJob?.cancel()
            chapterObserveJob = viewModelScope.launch {
                repo.observeChapters(albumId).collect { list ->
                    val ids = list.filter { it.finished == 1 }.map { it.id }.toSet()
                    _finishedChapterIds.value = ids
                    _finishedChapters.value = ids.size
                }
            }
        }
    }

    /** 删除已下载内容（删本地图片 + DB 记录 + 进度文件）。 */
    fun removeBook(albumId: Int) {
        viewModelScope.launch { runCatching { repo.removeBook(albumId) } }
    }

    /** 收藏 / 取消收藏整本漫画（详情页顶部心形按钮）。 */
    fun toggleFavorite() {
        viewModelScope.launch {
            val albumId = loadedId ?: return@launch
            runCatching { repo.toggleFavorite(albumId) }
            _isFavorite.value = !_isFavorite.value
        }
    }

    /** 收藏 / 取消收藏某个标签（详情页标签「收藏模式」点击）。 */
    fun toggleFavTag(tag: String) {
        viewModelScope.launch {
            val cur = _favTags.value
            val next = if (tag in cur) cur - tag else cur + tag
            _favTags.value = next
            repo.setConfig("fav_tags", next.joinToString("|"))
        }
    }

    fun download(albumId: Int) {
        viewModelScope.launch {
            _downloading.value = true
            _downloadError.value = null
            _downloadProgress.value = null
            // 与阻塞式整本下载并行：每 500ms 读一次进度文件（不调 Python，避免抢 GIL）
            var prevBytes = -1L
            var prevTime = 0L
            val poll = launch {
                while (true) {
                    val prog = runCatching { repo.getDownloadProgress(albumId) }.getOrNull()
                    if (prog != null) {
                        var speed = 0f
                        if (prog.status == "DOWNLOADING") {
                            val now = System.currentTimeMillis()
                            if (prevBytes >= 0) {
                                val dt = (now - prevTime) / 1000f
                                if (dt > 0f) speed =
                                    ((prog.bytes - prevBytes) / dt).toFloat().coerceAtLeast(0f)
                            }
                            prevBytes = prog.bytes
                            prevTime = now
                        }
                        _downloadProgress.value = prog.copy(speed = speed)
                    }
                    delay(500)
                }
            }
            try {
                repo.downloadAlbum(albumId)
                // v7.3.1：仅当下载的就是当前展示的这本时，才把本页标记为「已下载」——
                // 详情页「JM 号下载」现在可直达下载别的号，不能把当前专辑误标为已下载。
                val curId = (state.value as? UiState.Success)?.data?.book?.id
                if (curId != null && curId == albumId) _isLocal.value = true
            } catch (e: Exception) {
                _downloadError.value = e.message ?: "下载失败"
            } finally {
                poll.cancel()
                _downloading.value = false
                _downloadProgress.value = null
            }
        }
    }
}
