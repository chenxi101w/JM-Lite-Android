package com.example.jmreader.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.SubscriptionUpdate
import com.example.jmreader.data.repository.JmRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import android.util.Log

/**
 * 主页推荐引擎（v2.4.3）。
 * 推荐 = 喜欢驱动的搜索：以喜欢标签（手动优先=100、主喜欢=90、微喜欢=10）为搜索种子，
 * 对每个标签发起 tag 搜索，再经 filterSearchResults 排除已下载/已看完/讨厌标签，
 * 合并去重形成推荐流。与搜索行为一致、且卡片可下载。
 * 无任何喜欢时回退到「未下载且未看完」的书架漫画，避免把看过的再推荐一遍。
 */
class HomeViewModel(private val repo: JmRepository) : ViewModel() {

    private val _recommendations = MutableStateFlow<List<Book>>(emptyList())
    val recommendations: StateFlow<List<Book>> = _recommendations

    /** 书架每行列数（0 = 默认 3 列），来自设置 bookshelf_columns。 */
    private val _bookshelfColumns = MutableStateFlow(0)
    val bookshelfColumns: StateFlow<Int> = _bookshelfColumns.asStateFlow()

    // ── 下载/收藏状态（让推荐卡片可下载、可收藏，与搜索页一致）──
    private val _downloadingIds = MutableStateFlow<Set<Int>>(emptySet())
    val downloadingIds: StateFlow<Set<Int>> = _downloadingIds.asStateFlow()

    /** 已下载漫画集合（下载完成即出现，卡片显示 ✓、不再可点下载）。
     *  v14：改用只查 id 列的 SQL 流 + 计算挪出主线程。 */
    val downloadedIds: StateFlow<Set<Int>> = repo.observeDownloadedIds()
        .map { it.toSet() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .catch { emit(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** 收藏集合（卡片左上角收藏态）。v14：SQL 下推（命中 index_books_favorite）+ 计算挪出主线程。 */
    val favoriteIds: StateFlow<Set<Int>> = repo.observeFavoriteIds()
        .map { it.toSet() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .catch { emit(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** 订阅作者的新作记录（v7.2.0 从书架移到发现页推荐流上方，未看的排前面）。 */
    val subscriptionUpdates: StateFlow<List<SubscriptionUpdate>> =
        repo.observeSubscriptionUpdates()
            .map { list ->
                list.sortedWith(
                    compareByDescending<SubscriptionUpdate> { it.seen == 0 }.thenByDescending { it.foundAt }
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 未看新作数（角标「N 部新作」）。 */
    val unseenUpdateCount: StateFlow<Int> =
        subscriptionUpdates.map { list -> list.count { it.seen == 0 } }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        viewModelScope.launch {
            runCatching { buildRecommendations() }
                .onFailure { Log.e("HomeViewModel", "buildRecommendations failed", it) }
        }
        viewModelScope.launch {
            runCatching {
                _bookshelfColumns.value =
                    repo.getConfig("bookshelf_columns")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            }.onFailure { Log.e("HomeViewModel", "load columns failed", it) }
        }
    }

    /** 从推荐卡片直接整本下载到书架（重复/下载中忽略）。 */
    fun download(book: Book) {
        val id = book.id
        if (_downloadingIds.value.contains(id)) return
        viewModelScope.launch {
            _downloadingIds.value = _downloadingIds.value + id
            try {
                repo.saveBook(book)
                repo.downloadAlbum(id)
            } catch (_: Exception) {
            } finally {
                _downloadingIds.value = _downloadingIds.value - id
            }
        }
    }

    /** 收藏/取消收藏（推荐卡片左上角）。 */
    fun toggleFavorite(bookId: Int) = viewModelScope.launch { repo.toggleFavorite(bookId) }
    fun toggleFinished(bookId: Int) = viewModelScope.launch { repo.toggleFinished(bookId) }

    /** 全部订阅新作标记已看（发现页「全部已看」）。 */
    fun markAllUpdatesSeen() = viewModelScope.launch { repo.markAllUpdatesSeen() }

    private suspend fun buildRecommendations() {
        val books = repo.observeBooks().first()
        val downloadedIds = repo.getDownloadedIds()
        val finishedIds = books.filter { it.finished == 1 }.map { it.id }.toSet()
        val hated = repo.getHatedTags()
        // 喜欢权重表：手动=100 / 主喜欢=90 / 微喜欢=10（手动优先）
        val weights = repo.getPreferredTagWeights()

        if (weights.isEmpty()) {
            // 回退：没喜欢时展示未下载且未看完的书架漫画，不把看过的再推一遍
            _recommendations.value = books
                .filter { it.id !in downloadedIds && it.id !in finishedIds && it.coverUrl.isNotBlank() }
                .take(40)
            return
        }

        // 按权重分层（手动100 / 主喜欢90 / 微喜欢10），每层一次多标签 OR 搜索（Python 内部并发抓取），
        // 替代原先「前 8 个标签串行各搜一次」——启动时不再 8 次串行霸占 Python 单线程，
        // 用户紧跟的搜索无需排队，主页推荐也更快出（每层仍按权重优先，推荐质量基本不变）。
        val byWeight = weights.entries.groupBy { it.value }
        val layers = listOf(100 to 16, 90 to 12, 10 to 8)
        val merged = LinkedHashMap<Int, Book>()
        for ((w, cap) in layers) {
            val tags = byWeight[w]?.map { it.key }?.take(8) ?: emptyList()
            if (tags.isEmpty()) continue
            val tagsJson = "[" + tags.joinToString(",") { "\"" + it.replace("\"", "\\\"") + "\"" } + "]"
            val res = runCatching {
                repo.search("", "tags", 1, "mr", "a", "or", tagsJson)
            }.getOrDefault(emptyList())
            // 与搜索一致：排除已下载/已看完/讨厌标签
            val (visible) = repo.filterSearchResults(res, "hide", downloadedIds, "hide", finishedIds, hated)
            for (b in visible.take(cap)) merged.putIfAbsent(b.id, b)
            if (merged.size >= 60) break
        }
        _recommendations.value = merged.values.take(60).toList()
    }

    /** 手动刷新推荐（如改了喜欢/清了历史后）。 */
    fun refresh() {
        viewModelScope.launch {
            runCatching { buildRecommendations() }
                .onFailure { Log.e("HomeViewModel", "buildRecommendations failed", it) }
        }
    }
}
