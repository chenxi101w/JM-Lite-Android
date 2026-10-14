package com.example.jmreader.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.SearchHistoryAgg
import com.example.jmreader.data.repository.JmRepository
import com.example.jmreader.ui.UiState
import com.example.jmreader.ui.Routes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

class SearchViewModel(private val repo: JmRepository) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Book>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Book>>> = _state

    /** 是否还有更多页（用于「加载更多」按钮显隐）。无去重命中或空页即判为到底。 */
    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()


    /** 搜索喜欢库（最近搜索）：点击一键回填搜索框并搜索，对齐 Windows 版。 */
    val recentSearches: StateFlow<List<SearchHistoryAgg>> =
        repo.observeSearchHistoryAggregated(10)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 默认排序（设置项 default_sort：mr 最新 / mv 浏览 / mp 收藏）。 */
    private val _defaultSort = MutableStateFlow("mr")
    val defaultSort: StateFlow<String> = _defaultSort.asStateFlow()

    /** 搜索后搜索栏行为（设置项 search_bar_behavior：stay 留顶 / hide 隐藏）。 */
    private val _searchBarBehavior = MutableStateFlow("stay")
    val searchBarBehavior: StateFlow<String> = _searchBarBehavior.asStateFlow()

    // ── 搜索页 UI 状态（提升到 VM：切底部导航/配置变更时不丢失，对齐 PC 版保留搜索页）──
    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()
    fun setKeyword(v: String) { _keyword.value = v }

    private val _mode = MutableStateFlow("keyword")
    val mode: StateFlow<String> = _mode.asStateFlow()
    fun setMode(v: String) { _mode.value = v }

    private val _orderBy = MutableStateFlow("mr")
    val orderBy: StateFlow<String> = _orderBy.asStateFlow()
    fun setOrderBy(v: String) { _orderBy.value = v }

    private val _time = MutableStateFlow("a")
    val time: StateFlow<String> = _time.asStateFlow()
    fun setTime(v: String) { _time.value = v }

    private val _tagsText = MutableStateFlow("")
    val tagsText: StateFlow<String> = _tagsText.asStateFlow()
    fun setTagsText(v: String) { _tagsText.value = v }

    private val _tagsLogic = MutableStateFlow("or")
    val tagsLogic: StateFlow<String> = _tagsLogic.asStateFlow()
    fun setTagsLogic(v: String) { _tagsLogic.value = v }

    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()
    fun setExpanded(v: Boolean) { _expanded.value = v }

    private val _searched = MutableStateFlow(false)
    val searched: StateFlow<Boolean> = _searched.asStateFlow()
    fun setSearched(v: Boolean) { _searched.value = v }

    private val _sortExpanded = MutableStateFlow(false)
    val sortExpanded: StateFlow<Boolean> = _sortExpanded.asStateFlow()
    fun setSortExpanded(v: Boolean) { _sortExpanded.value = v }

    /**
     * 触发一次搜索（标记已搜 + 收起筛选面板 + **按当前条件真正发起抓取**）。
     *
     * v1.3.1 修复：此前这里只置了 `_searched = true`，**从未调用 [search]**，
     * 而注释写着"实际抓取交给 search()"—— 可键盘回车那条路径
     * （`SearchScreen` 的 `keyboardActions.onSearch`）只调 `onSearch()`，
     * 于是**回车搜索完全没反应**（无网络请求、无结果）。
     * 引入 v1.2.3 搜索短时缓存、改写 `onSearch` 时把真正的抓取调用弄丢了。
     * 副作用：搜索页会一直显示「输入关键词开始搜索」占位，且不发任何请求。
     */
    fun onSearch() {
        _searched.value = true
        _expanded.value = false
        search(
            keyword = _keyword.value,
            mode = _mode.value,
            orderBy = _orderBy.value,
            time = _time.value,
            tagsLogic = _tagsLogic.value,
            tagsJson = _tagsText.value,
        )
    }

    // ── 搜索结果封面下载键状态（本会话内）──
    private val _downloadingIds = MutableStateFlow<Set<Int>>(emptySet())
    val downloadingIds: StateFlow<Set<Int>> = _downloadingIds.asStateFlow()

    /**
     * 已下载漫画集合（供「已下载屏蔽」判断）。
     * v14 优化：改用只查 id 列的 SQL 流（原为整表物化后 map），并把建 Set 的计算挪出主线程。
     */
    val downloadedIds: StateFlow<Set<Int>> = repo.observeDownloadedIds()
        .map { it.toSet() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** 已看完漫画集合（供「已看完屏蔽」判断）。v14：SQL 下推 + 计算挪出主线程。 */
    val finishedIds: StateFlow<Set<Int>> = repo.observeFinishedIds()
        .map { it.toSet() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** 收藏的漫画集合（搜索结果卡片左上角收藏按钮的实心/空心态）。v14：SQL 下推 + 计算挪出主线程。 */
    val favoriteIds: StateFlow<Set<Int>> = repo.observeFavoriteIds()
        .map { it.toSet() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * 讨厌标签集合（推荐/搜索中排除，与屏蔽并列）。
     * 由配置表响应式流派生：设置页改「讨厌标签」后返回搜索页即实时刷新（修复 #2 数据陈旧）。
     */
    val hatedTags: StateFlow<Set<String>> = repo.observeConfigAll()
        .map { repo.getHatedTags() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * 喜欢标签权重表（手动=100/主喜欢=90/微喜欢=10），用于搜索结果喜欢加权排序。
     * 同样由配置表响应式流派生，手动喜欢变更后实时生效（修复 #2 数据陈旧）。
     */
    private val prefWeights: StateFlow<Map<String, Int>> = repo.observeConfigAll()
        .map { runCatching { repo.getPreferredTagWeights() }.getOrElse { e -> Log.e("SearchViewModel", "getPreferredTagWeights failed", e); emptyMap() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 全局总开关「智能关联拓展」：关闭时不向搜索注入同义词、不做喜欢加权排序（退化为无喜欢纯列表）。 */
    val smartAssociation: StateFlow<Boolean> = repo.observeConfigAll()
        .map { (repo.getConfig("smart_association") ?: "1") != "0" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** 搜索排序策略：classic 权重累加 / binary 命中优先。随设置实时变化。 */
    val searchStrategy: StateFlow<String> = repo.observeConfigAll()
        .map { repo.getConfig("search_strategy") ?: "classic" }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "classic")

    /** 搜索结果每行列数（设置 search_columns：0=默认3列；v6.0.20 新增）。随设置实时变化。 */
    val gridColumns: StateFlow<Int> = repo.observeConfigAll()
        .map { (repo.getConfig("search_columns") ?: "0").toIntOrNull()?.coerceAtLeast(0) ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 搜索里已下载漫画处理方式（设置 hide_downloaded_in_search）：off / hide / gray。 */
    private val _hideMode = MutableStateFlow("hide")
    val hideMode: StateFlow<String> = _hideMode.asStateFlow()

    /** 搜索里已看完漫画处理方式（设置 hide_finished_in_search）：off / hide / gray。默认 hide（看完默认搜不到）。 */
    private val _hideFinishedMode = MutableStateFlow("hide")
    val hideFinishedMode: StateFlow<String> = _hideFinishedMode.asStateFlow()

    /** 两种隐藏模式合并（combine 强类型重载最多 5 路，故合并为 1 路 StateFlow 供 collect）。 */
    private data class HideOpts(val downloaded: String, val finished: String)
    private val hideOpts: StateFlow<HideOpts> =
        combine(_hideMode, _hideFinishedMode) { d, f -> HideOpts(d, f) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HideOpts("hide", "hide"))

    /** 经 Python 返回、尚未做客户端过滤的原始结果（loading/error 仍由 _state 承载）。 */
    private val _rawResults = MutableStateFlow<List<Book>>(emptyList())

    /** 灰显（已下载且 hideMode=gray）的漫画 id 集合。 */
    private val _grayedIds = MutableStateFlow<Set<Int>>(emptySet())
    val grayedIds: StateFlow<Set<Int>> = _grayedIds.asStateFlow()

    /** 搜索实时计时：搜索中每 100ms 刷新，结束后定格（供结果区顶部小字动态显示）。 */
    private val _searchRunning = MutableStateFlow(false)
    val searchRunning: StateFlow<Boolean> = _searchRunning.asStateFlow()

    private val _searchElapsedMs = MutableStateFlow(0L)
    val searchElapsedMs: StateFlow<Long> = _searchElapsedMs.asStateFlow()

    private var searchStartMs = 0L
    private var tickerJob: Job? = null

    /** 搜索开始：清零并启动实时计时（0s 起每 100ms 跳动）。 */
    private fun startTicker() {
        searchStartMs = System.currentTimeMillis()
        _searchElapsedMs.value = 0
        _searchRunning.value = true
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                _searchElapsedMs.value = System.currentTimeMillis() - searchStartMs
                // v14：100ms → 250ms。原频率下每 100ms 推一次新值，搜索结果网格跟着重组，
                // 正好卡在出结果的关键时刻；秒级计时 250ms 刷新肉眼无差别。
                delay(250)
            }
        }
    }

    /** 搜索结束（成功/失败）：停表并定格最终耗时。 */
    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
        _searchElapsedMs.value = System.currentTimeMillis() - searchStartMs
        _searchRunning.value = false
    }

    /** 从搜索结果直接整本下载到书架；重复点击或下载中则忽略。 */
    fun download(book: Book) {
        val id = book.id
        if (_downloadingIds.value.contains(id)) return
        // downloadedIds 由 observeDownloadedBooks 派生：下载完成落盘后自动出现，无需手动维护；
        // 若 hideMode=hide，该漫画会在重过滤时从搜索结果中消失（符合「已下载屏蔽」默认行为）。
        viewModelScope.launch {
            _downloadingIds.value = _downloadingIds.value + id
            try {
                // 先把图书落到 books 表，下载完成后 savedDir 才会被正确回写（否则只下到文件夹、书架看不到）
                repo.saveBook(book)
                repo.downloadAlbum(id)
            } catch (e: Exception) {
                // 失败：移出「下载中」，保留可再次点击
            } finally {
                _downloadingIds.value = _downloadingIds.value - id
            }
        }
    }

    // ── 长按拉黑（blacklists，讨厌标记；不隐藏搜索结果，供喜欢/讨厌算法用）──
    fun blacklistWork(book: Book) = viewModelScope.launch { repo.addBlacklist("work", book.id.toString()) }
    fun blacklistAuthor(book: Book) = viewModelScope.launch { repo.addBlacklist("author", book.author) }

    /** 同时拉黑作品和作者（v7.0.6 长按菜单组合项，一次完成两件事）。 */
    fun blacklistWorkAndAuthor(book: Book) = viewModelScope.launch {
        repo.addBlacklist("work", book.id.toString())
        if (book.author.isNotBlank()) repo.addBlacklist("author", book.author)
    }

    // ── 收藏（搜索结果卡片右上角，v5）：先确保该书已落 books 表（搜索结果未下载时也落一行），
    // 否则 repo.toggleFavorite 因 bookDao.get 返回 null 而整体无效（修复「收藏点了没反应」）。
    fun toggleFavorite(book: Book) = viewModelScope.launch {
        repo.saveBook(book)
        repo.toggleFavorite(book.id)
    }
    fun toggleFinished(bookId: Int) = viewModelScope.launch { repo.toggleFinished(bookId) }

    // ── 结果列表滚动位置（切页/配置变更后恢复，对齐 PC 版保留搜索页位置）──
    private val _scrollIndex = MutableStateFlow(0)
    val scrollIndex: StateFlow<Int> = _scrollIndex.asStateFlow()
    private val _scrollOffset = MutableStateFlow(0)
    val scrollOffset: StateFlow<Int> = _scrollOffset.asStateFlow()
    fun saveScroll(index: Int, offset: Int) {
        _scrollIndex.value = index
        _scrollOffset.value = offset
    }

    init {
        viewModelScope.launch {
            _defaultSort.value = repo.getConfig("default_sort") ?: "mr"
            _searchBarBehavior.value = repo.getConfig("search_bar_behavior") ?: "stay"
            _hideMode.value = repo.getConfig("hide_downloaded_in_search") ?: "hide"
            _hideFinishedMode.value = repo.getConfig("hide_finished_in_search") ?: "hide"
        }
        // 客户端重过滤：原始结果 / 已下载 / 已看完 / 两个隐藏模式 / 讨厌标签 / 喜欢权重 任一变化即重算可见列表
        // 注意：combine 超过 5 个流会退回 vararg 重载（transform 仅收一个 Array 参数），需手动拆箱。
        // 讨厌标签 / 喜欢权重已改为配置表响应式流派生（见上方 hatedTags/prefWeights），改设置返回后实时生效。
        // 性能：过滤/排序是纯 CPU 计算（字符串 split/正则），移到 Default 线程，避免主线程卡顿。
        viewModelScope.launch {
            combine(
                _rawResults, downloadedIds, finishedIds, hideOpts, hatedTags, prefWeights
            ) { arr ->
                val raw = arr[0] as List<Book>
                val dl = arr[1] as Set<Int>
                val fin = arr[2] as Set<Int>
                val opts = arr[3] as HideOpts
                val hated = arr[4] as Set<String>
                val weights = arr[5] as Map<String, Int>
                withContext(Dispatchers.Default) {
                    val (visible, grayed) = repo.filterSearchResults(
                        raw, opts.downloaded, dl, opts.finished, fin, hated
                    )
                    sortByPreference(visible, weights, searchStrategy.value) to grayed
                }
            }.collect { (visible, grayed) ->
                _grayedIds.value = grayed
                if (_rawResults.value.isNotEmpty() || _state.value is UiState.Success) {
                    _state.value = UiState.Success(visible)
                }
            }
        }
    }

    private var currentKeyword = ""
    private var currentMode = "keyword"
    private var currentPage = 1
    // 保留最近一次搜索的排序/时间/标签设置，用于“加载更多”
    private var lastOrderBy = "mr"
    private var lastTime = "a"
    private var lastTagsLogic = "or"
    private var lastTagsJson = ""

    /** 搜索结果喜欢加权排序：匹配喜欢标签权重越高越靠前；无匹配保持原顺序（稳定排序）。手动=100/主喜欢=90/微喜欢=10。
     * strategy=classic 累加权重；strategy=binary 只看是否命中（命中=1，未命中=0），不区分权重高低。 */
    private fun sortByPreference(results: List<Book>, weights: Map<String, Int> = emptyMap(), strategy: String = "classic"): List<Book> {
        val w = if (weights.isEmpty()) prefWeights.value else weights
        if (w.isEmpty()) return results
        return results.sortedByDescending { book ->
            val score = book.tags.split("|").map { it.trim().lowercase() }.sumOf { w[it] ?: 0 }
            if (strategy == "binary") (if (score > 0) 1 else 0) else score
        }
    }

    /** 新搜索：重置到第一页并替换结果。 */
    fun search(
        keyword: String,
        mode: String,
        orderBy: String = "mr",
        time: String = "a",
        tagsLogic: String = "or",
        tagsJson: String = ""
    ) {
        val kw = keyword.trim()
        // v6.0.22 修复：tags 模式的搜索词走 tagsJson（不是 keyword），keyword 本就为空。
        // 原写法对空 keyword 一律 return，导致「按标签搜索」从未真正发起网络请求，
        // 「加载更多」也因 currentKeyword 恒空而失效。这里对 tags 模式放行。
        if (kw.isBlank() && mode != "tags") return
        currentKeyword = kw
        currentMode = mode
        currentPage = 1
        lastOrderBy = orderBy
        lastTime = time
        lastTagsLogic = tagsLogic
        lastTagsJson = tagsJson
        _hasMore.value = true
        load(reset = true)
    }

    /** 加载下一页：累积到当前结果后。 */
    fun loadMore() {
        // 同 search()：tags 模式的关键词在 tagsJson 里，keyword 恒空，需一并放行。
        if (currentKeyword.isBlank() && currentMode != "tags") return
        currentPage += 1
        load(reset = false)
    }

    private var searchJob: Job? = null

    /**
     * 搜索结果短时缓存（v1.2.3）。
     *
     * 场景：从详情 / 阅读页返回搜索页、或短时间内重复搜同一条件 ——
     * 直接复用上次结果，不必重新走网络（keyword）或 Python（author / tags）链路。
     *
     * 60 秒窗口：既消除「返回即重搜」的等待，又不会让用户看到明显过时的数据。
     * 仅对**新搜索（reset=true，即第一页）**生效；「加载更多」不参与（页码不同）。
     * 注意：命中时直接复用上次的可见列表，因此 60 秒内改动「隐藏已下载/已看完」等
     * 过滤设置不会即时反映 —— 这是为省下一次完整搜索取舍的，窗口很短，可接受。
     */
    private class SearchCacheEntry(
        val raw: List<Book>,
        val visible: List<Book>,
        val hasMore: Boolean,
        val at: Long
    )

    private val searchCache = LinkedHashMap<String, SearchCacheEntry>()
    private val SEARCH_CACHE_TTL_MS = 60_000L
    private val SEARCH_CACHE_MAX = 8

    /** 缓存键：搜索条件全量参与，任一条件不同即不命中。 */
    private fun currentCacheKey(): String =
        "$currentMode|$currentKeyword|$currentPage|$lastOrderBy|$lastTime|$lastTagsLogic|$lastTagsJson"

    private fun load(reset: Boolean) {
        // v6.0.5 修复：快速连续搜索/翻页时取消上一个搜索协程，
        // 避免旧协程的 Python 搜索后返回覆盖新结果（搜索竞态 → 结果错乱）
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (reset) {
                // v1.2.3：命中短时缓存则直接复用，跳过网络 / Python 链路
                val hit = searchCache[currentCacheKey()]
                if (hit != null &&
                    android.os.SystemClock.elapsedRealtime() - hit.at < SEARCH_CACHE_TTL_MS
                ) {
                    _rawResults.value = hit.raw
                    _state.value = UiState.Success(hit.visible)
                    _hasMore.value = hit.hasMore
                    return@launch
                }
                _state.value = UiState.Loading
                _rawResults.value = emptyList()
                startTicker()   // 新搜索：0s 起实时计时
            }
            val opts = hideOpts.value
            val merged = try {
                // 同义词 / 拉黑过滤来自本地 DB，注入到 bridge；
                // 4.13.0：smart_association 关闭时同义词展开一并停用（Python 侧同受开关控制）
                val aliasesJson = if (smartAssociation.value) repo.getAliasesJson() else ""
                val page = repo.search(
                    currentKeyword, currentMode, currentPage,
                    orderBy = lastOrderBy, time = lastTime,
                    tagsLogic = lastTagsLogic, tagsJson = lastTagsJson,
                    aliasesJson = aliasesJson,
                    smartAssociation = smartAssociation.value
                )
                val prev =
                    if (reset) emptyList() else _rawResults.value
                val prevIds = prev.map { it.id }.toSet()
                val newItems = page.filter { it.id !in prevIds }   // 去重：避免只有一页时重复追加
                // 搜索结果写入标签库：每本漫画的每个标签各 +1（按数量排序，供设置页挑喜欢/讨厌）
                val searchTags = newItems.flatMap { b ->
                    b.tags.split("|").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                }
                val merged = prev + newItems
                // 本页新增条目经客户端过滤（已下载/已看完隐藏）后是否仍有可见项；
                // 整页被滤光则判到底，避免「加载更多」反复拉空页（修复 #5）
                val (newVisible, _) = repo.filterSearchResults(
                    newItems, opts.downloaded, downloadedIds.value,
                    opts.finished, finishedIds.value, hatedTags.value
                )
                _hasMore.value = newItems.isNotEmpty() && page.isNotEmpty() && newVisible.isNotEmpty()
                // 写库（搜索历史/标签库）挪到后台异步执行，不再阻塞结果展示：
                // 结果先出（用户在等封面/列表），写库与后续 UI 渲染并行
                viewModelScope.launch {
                    if (reset) runCatching { repo.addSearchHistory(currentMode, currentKeyword, page.size) }
                    if (searchTags.isNotEmpty()) runCatching { repo.addSearchTags(searchTags) }
                    // 标签模式：查询的标签本身就是真实标签，直接入库（搜索结果不一定带 tags）
                    if (currentMode == "tags" && lastTagsJson.isNotBlank()) {
                        val qt = lastTagsJson
                            .removeSurrounding("[", "]")
                            .split(",")
                            .map { it.trim().removeSurrounding("\"") }
                            .filter { it.isNotBlank() }
                        if (qt.isNotEmpty()) runCatching { repo.addSearchTags(qt.map { it.lowercase() }) }
                    }
                    // 关键词/作者搜索结果往往不带 tags（jmcomic 搜索接口不返回标签），
                    // 后台补抓详情取真实标签写入标签库。延迟 2.5s 启动 + 降低封顶：
                    // 避免「搜索刚返回、用户紧接着再搜/点详情」时被这批 Python 调用排队拖慢
                    // （Python 单线程队列，15 本串行抓详情会阻塞后续所有搜索/详情请求）。
                    if (searchTags.isEmpty() && newItems.isNotEmpty()) {
                        delay(2500)
                        runCatching { repo.enrichTagLibraryFromSearch(newItems, cap = 8) }
                    }
                }
                merged
            } catch (e: Exception) {
                stopTicker()   // 失败也停表，定格本次耗时
                if (reset) _state.value = UiState.Error(e.message ?: "搜索失败")
                return@launch
            }
            // 落库原始结果，并立即由客户端过滤得到可见列表（已下载/已看完隐藏/灰显）
            _rawResults.value = merged
            val (rawVisible, grayed) = withContext(Dispatchers.Default) {
                val (v, g) = repo.filterSearchResults(
                    merged, opts.downloaded, downloadedIds.value,
                    opts.finished, finishedIds.value, hatedTags.value
                )
                v to g
            }
            val visible = sortByPreference(rawVisible, emptyMap(), searchStrategy.value)
            _grayedIds.value = grayed
            stopTicker()
            _state.value = UiState.Success(visible)
            // v1.2.3：写回短时缓存（简易上限，超出淘汰最旧）
            if (searchCache.size >= SEARCH_CACHE_MAX) {
                searchCache.keys.firstOrNull()?.let { searchCache.remove(it) }
            }
            searchCache[currentCacheKey()] = SearchCacheEntry(
                raw = merged,
                visible = visible,
                hasMore = _hasMore.value,
                at = android.os.SystemClock.elapsedRealtime()
            )
        }
    }

    /**
     * 卡片点击目标（v5.0.18：在线阅读已移除，搜索结果点漫画一律进**详情页**）：
     * local 依据该书是否已下载（savedDir 非空）判断，详情页据此决定展示本地章节还是引导下载。
     */
    suspend fun openRouteFor(book: Book): String? {
        return Routes.detail(book.id, local = book.savedDir.isNotBlank())
    }
}
