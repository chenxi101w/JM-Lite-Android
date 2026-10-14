package com.example.jmreader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.repository.JmRepository
import com.example.jmreader.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 轻量版书架 ViewModel（v1.0.0）：只保留「已下载漫画列表 + 阅读 + 删除 + 看完标记」。
 * 去掉：分类 / 排序 / 订阅 / 收藏 / 回收站相关状态与操作。
 */
@OptIn(FlowPreview::class)
class LibraryViewModel(private val repo: JmRepository) : ViewModel() {

    /** 书架数据：已下载（savedDir 非空）的漫画，随下载/删除实时更新。
     *  轻量版不再区分回收站，删除 = 彻底删除，看完不清本地。 */
    val displayBooks: StateFlow<List<Book>> = repo.observeDownloadedBooks()
        .debounce(50)
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 书架每行列数（0 = 默认 3 列），来自配置 bookshelf_columns。 */
    private val _bookshelfColumns = MutableStateFlow(0)
    val bookshelfColumns: StateFlow<Int> = _bookshelfColumns.asStateFlow()

    init {
        viewModelScope.launch {
            _bookshelfColumns.value =
                repo.getConfig("bookshelf_columns")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        }
    }

    /**
     * 卡片点击目标：优先恢复上次阅读章节；无进度则首章。
     * 本地首章为空（下载时未写入 chapters 表）时直接进详情页（详情页自带获取章节流程），
     * 不联网等待，点击秒回。
     */
    suspend fun openRouteFor(book: Book): String? {
        val prog = runCatching { repo.getProgress(book.id) }.getOrNull()
        if (prog != null && prog.lastChapterId > 0) {
            return Routes.reader(book.id, prog.lastChapterId, prog.lastChapterId)
        }
        val ch = runCatching { repo.firstChapterId(book.id, local = true) }.getOrNull()
        if (ch != null) return Routes.reader(book.id, ch, ch)
        return Routes.detail(book.id)
    }

    /** 删除漫画（轻量版：彻底删除，删本地内容 + books/chapters + 下载记录与进度，不可恢复）。 */
    fun removeBook(bookId: Int) {
        viewModelScope.launch { runCatching { repo.removeBookCompletely(bookId) } }
    }

    /** 标记看完 / 取消看完（看完的漫画在书架沉底并带「已看完」分隔线）。 */
    fun toggleFinished(bookId: Int) {
        viewModelScope.launch { repo.toggleFinished(bookId) }
    }

    // ── 批量删除（v1.11.0）：长按进多选，工具栏支持 全选/未读/已读 一键勾选 ──

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedIds: StateFlow<Set<Int>> = _selectedIds.asStateFlow()

    /** 已读集合（账本行 viewCount/lastReadAt 非零 = 打开过阅读器，v1.12.0）。进多选模式时加载一次。 */
    private val _readIds = MutableStateFlow<Set<Int>>(emptySet())
    val readIds: StateFlow<Set<Int>> = _readIds.asStateFlow()

    /** 进入多选模式（长按触发）：首本默认选中，后台加载已读集合。 */
    fun enterSelection(first: Book) {
        _selectionMode.value = true
        _selectedIds.value = setOf(first.id)
        viewModelScope.launch {
            _readIds.value = repo.getReadBookIds()
        }
    }

    /** 多选模式下点卡片：切换选中。 */
    fun toggleSelect(book: Book) {
        if (!_selectionMode.value) return
        _selectedIds.value = _selectedIds.value.toMutableSet().apply {
            if (!add(book.id)) remove(book.id)
        }
    }

    /** 一键勾选：null = 全部，true = 只选已读，false = 只选未读。 */
    fun selectByRead(read: Boolean?) {
        val books = displayBooks.value
        _selectedIds.value = when (read) {
            null -> books.map { it.id }.toSet()
            true -> books.filter { it.id in _readIds.value }.map { it.id }.toSet()
            false -> books.filter { it.id !in _readIds.value }.map { it.id }.toSet()
        }
    }

    /** 退出多选并清空选中。 */
    fun clearSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    /**
     * 批量彻底删除（与单本删除同语义：删本地内容 + books/chapters + 下载记录与进度，不可恢复）。
     * 逐本串行删，单本失败不影响其余；完成后回调成功数（UI toast）并退出多选。
     */
    fun removeSelected(onDone: (Int, Int) -> Unit) {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var ok = 0
            for (id in ids) {
                runCatching { repo.removeBookCompletely(id) }.onSuccess { ok++ }
            }
            clearSelection()
            onDone(ok, ids.size)
        }
    }
}