package com.example.jmreader.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jmreader.data.repository.JmRepository
import com.example.jmreader.data.repository.LogicReport
import com.example.jmreader.data.repository.BookLibrary
import com.example.jmreader.data.local.Subscription
import com.example.jmreader.ui.theme.ThemeState
import com.example.jmreader.ui.ShowHintsState
import com.example.jmreader.util.AppIconManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: JmRepository,
    context: Context
) : ViewModel() {

    /** 被隐藏的下载记录（hidden=1），设置页「被隐藏的下载记录」页面展示。 */
    data class HiddenDownloadRow(val bookId: Int, val title: String)

    val hiddenDownloadRows: StateFlow<List<HiddenDownloadRow>> =
        combine(repo.observeDownloads(), repo.observeBooks()) { dls, books ->
            val titleMap = books.associate { it.id to it.title }
            dls.filter { it.hidden == 1 }
                .map { HiddenDownloadRow(it.bookId, titleMap[it.bookId] ?: "JM-${it.bookId}") }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteDownloadRecord(bookId: Int) {
        viewModelScope.launch { runCatching { repo.deleteDownloadRecord(bookId) } }
    }

    fun clearAllDownloadRecords() {
        viewModelScope.launch { runCatching { repo.clearAllDownloadRecords() } }
    }

    /** 导出收藏（漫画+标签）为 JSON 字符串；异常返回 null。 */
    fun exportFavorites(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { repo.exportFavorites() }.getOrNull())
        }
    }

    /** 解析收藏备份 JSON（不写库），供导入弹窗预览。 */
    fun parseImportFavorites(json: String): BookLibrary.ImportParseResult = repo.parseImportFavorites(json)

    /**
     * 导入收藏（4.9.0 后台下载版）：收藏立即落库后**立刻回调**关闭弹窗；
     * 勾选下载的 id 交由 DownloadManager 后台队列执行（并发受 max_concurrent_downloads 控制），
     * 不阻塞 UI——用户无需在导入界面等待。
     */
    fun importFavoritesWithChoice(json: String, downloadIds: Set<Int>, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(
                runCatching { repo.importFavoritesBackground(json, downloadIds) }
                    .getOrElse { "导入失败：${it.message}" }
            )
        }
    }

    val keys = listOf(
        "max_concurrent_downloads", "image_quality",
        "search_result_limit", "search_tag_limit", "reader_chapter_toast",
        "hide_page_chapter", "search_priority", "default_sort",
        "search_bar_behavior", "reader_pages_per_screen", "bookshelf_columns",
        // 1.6.0 新增：下载后行为（open_directly 4.3.1 起移除，卡片统一直接阅读）
        "download_after",
        // 2.0.0 新增：搜索已下载屏蔽 / 进度滑轨颜色 / 书架排序 / 看完沉底
        "hide_downloaded_in_search", "reader_progress_color",
        "library_sort", "library_unfinished_first",
        // v5 新增：看完漫画处理
        "hide_finished_in_search", "finished_in_library",
        // v7 读完设置
        "reader_auto_finish_scroll", "reader_auto_finish_percent", "reader_auto_delete_chapter",
        "reader_auto_delete_chapter_delay",
        // 3.0.0 看完自动删除整本本地内容（延迟+重入取消）——v7.1.0 已由回收站机制取代（配置移除）
        // 4.3.1 阅读器「不删」按钮后续策略——v7.1.0 移除（配置移除）
        // 3.0.0 智能关联拓展（P2/P3）：全局总开关 + 喜欢红线 + 可插拔策略
        "smart_association", "pref_min_hits", "search_strategy", "recommend_strategy",
        // v7.2.0 新增：订阅作者新作自动下载（1=开，发现新作自动入书架下载）
        "sub_auto_download",
        // v1.10.0 云同步（JmSyncManager）
        "sync_enabled", "sync_server_url", "sync_token", "sync_last_at", "sync_last_result"
    )

    private val _config = MutableStateFlow<Map<String, String>>(emptyMap())
    val config: StateFlow<Map<String, String>> = _config

    // ── 喜欢 / 讨厌标签（v2.4.3：手动/计算拆分 + 主喜欢/微喜欢）────
    /** 手动喜欢（独立存储，用户显式增删）。 */
    private val _manualTags = MutableStateFlow<List<String>>(emptyList())
    val manualTags: StateFlow<List<String>> = _manualTags.asStateFlow()

    /** 主喜欢（计算出的前 N 个，偏重 90）。 */
    private val _primaryTags = MutableStateFlow<List<String>>(emptyList())
    val primaryTags: StateFlow<List<String>> = _primaryTags.asStateFlow()

    /** 微喜欢（计算出的其余标签，隐藏，偏重 10）。 */
    private val _microTags = MutableStateFlow<List<String>>(emptyList())
    val microTags: StateFlow<List<String>> = _microTags.asStateFlow()

    /** 主喜欢标签上限（设置 pref_max_tags）。 */
    private val _maxTags = MutableStateFlow(10)
    val maxTags: StateFlow<Int> = _maxTags.asStateFlow()

    private val _hatedTags = MutableStateFlow<List<String>>(emptyList())
    val hatedTags: StateFlow<List<String>> = _hatedTags.asStateFlow()

    /** 标签库（v2.7.0：从搜索累积，按数量降序）：(tag, count)。设置页「标签」浏览与挑选喜欢/讨厌。 */
    val tagLibrary: StateFlow<List<Pair<String, Int>>> = repo.observeTagLibrary()
        .map { list -> list.map { it.tag to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 合并标签集（v5.6.1）：激活库 ∪ 本地书架漫画标签，按数量降序。同义词选择用（避免标签太少）。 */
    val mergedTagLibrary: StateFlow<List<Pair<String, Int>>> = repo.observeMergedTagLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 无关标签体系（5.0.19：用户可决定）──────────────────────
    /** 用户手动标记的无关标签（标签库「选无关标签」按钮写入）。 */
    private val _ignoredTags = MutableStateFlow<Set<String>>(emptySet())
    val ignoredTags: StateFlow<Set<String>> = _ignoredTags.asStateFlow()

    /** 默认无关标签（固定种子，来自旧硬编码清单；仅读取，不提供编辑 UI）。 */
    private val _defaultIrrelevantTags = MutableStateFlow<Set<String>>(emptySet())
    val defaultIrrelevantTags: StateFlow<Set<String>> = _defaultIrrelevantTags.asStateFlow()

    /** 「隐藏无关标签」开关。 */
    private val _hideIrrelevant = MutableStateFlow(false)
    val hideIrrelevant: StateFlow<Boolean> = _hideIrrelevant.asStateFlow()

    /** 标签同义词分组（canonical → 别名列表），供标签库/搜索分组显示。 */
    private val _aliasTagGroups = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val aliasTagGroups: StateFlow<Map<String, List<String>>> = _aliasTagGroups.asStateFlow()

    // ── 拉黑/收藏漫画标签汇总（v4.14.0）────────────────────────
    /** 拉黑漫画的标签出现次数（降序，已排除无关性标签）。 */
    private val _blacklistTagStats = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val blacklistTagStats: StateFlow<List<Pair<String, Int>>> = _blacklistTagStats.asStateFlow()

    /** 收藏漫画的标签出现次数（降序，已排除无关性标签）。 */
    private val _favoriteTagStats = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val favoriteTagStats: StateFlow<List<Pair<String, Int>>> = _favoriteTagStats.asStateFlow()

    /** 无关性标签判定（结构规则 + 默认无关 + 用户手动无关）；同步版读取已加载集合，供 UI 渲染。 */
    private val IRRELEVANT_TAG_REGEX = Regex("^[cv]\\d+$", RegexOption.IGNORE_CASE)
    fun isIrrelevantTag(tag: String): Boolean {
        val t = tag.trim().lowercase()
        if (t.isBlank()) return true
        if (IRRELEVANT_TAG_REGEX.matches(t)) return true
        return t in _defaultIrrelevantTags.value || t in _ignoredTags.value
    }

    /** 重新统计拉黑/收藏漫画的标签汇总（进入标签库页时调用）。 */
    fun loadTagStats() {
        viewModelScope.launch {
            _blacklistTagStats.value =
                runCatching { repo.aggregateComicTags("blacklisted") }.getOrDefault(emptyList())
            _favoriteTagStats.value =
                runCatching { repo.aggregateComicTags("favorited") }.getOrDefault(emptyList())
        }
    }

    /** 算法逻辑报告（P2-3/P3-2，3.0.0）：喜欢画像 + 权重 + 屏蔽 + 同义词 + 策略，供逻辑查看页可视化。 */
    private val _logicReport = MutableStateFlow<LogicReport?>(null)
    val logicReport: StateFlow<LogicReport?> = _logicReport.asStateFlow()

    /** 重新计算并刷新算法逻辑报告。 */
    fun loadLogicReport() {
        viewModelScope.launch { _logicReport.value = runCatching { repo.getLogicReport() }.getOrNull() }
    }

    /** 作者订阅列表（P1-4，3.0.0）。 */
    val subscriptions: StateFlow<List<Subscription>> = repo.observeSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 加载喜欢画像（进喜欢页时调用）。 */
    fun loadPreference() {
        viewModelScope.launch {
            val profile = repo.getPreferenceProfile()
            _manualTags.value = profile.manual.toList()
            _primaryTags.value = profile.primary.toList()
            _microTags.value = profile.micro.toList()
            _maxTags.value = repo.getMaxPreferredTags()
            _hatedTags.value = repo.getHatedTags().toList()
            // 5.0.19：无关标签体系 + 同义词分组
            _ignoredTags.value = repo.getIgnoredTags()
            _defaultIrrelevantTags.value = repo.getDefaultIrrelevantTags()
            _hideIrrelevant.value = repo.isHideIrrelevantEnabled()
            repo.observeAliasGroups("tag").first().also { _aliasTagGroups.value = it }
        }
    }

    // ── 无关标签操作（5.0.19）─────────────────────────────────

    /** 切换某标签的用户手动无关状态（标签库「选无关标签」按钮）。 */
    fun toggleIgnoredTag(tag: String) {
        viewModelScope.launch {
            repo.toggleIgnoredTag(tag)
            _ignoredTags.value = repo.getIgnoredTags()
        }
    }

    /** 设置「隐藏无关标签」开关。 */
    fun setHideIrrelevant(on: Boolean) {
        _hideIrrelevant.value = on
        viewModelScope.launch { repo.setHideIrrelevant(on) }
    }

    /** 改主喜欢标签上限（重新划分主/微）。 */
    fun setMaxTags(n: Int) {
        val v = n.coerceAtLeast(1)
        _maxTags.value = v
        viewModelScope.launch {
            repo.setConfig("pref_max_tags", v.toString())
            val profile = repo.getPreferenceProfile()
            _primaryTags.value = profile.primary.toList()
            _microTags.value = profile.micro.toList()
        }
    }

    /** 手动喜欢/讨厌写库串行锁（v6.0.6）：快速连点三态时多个协程并发写库会乱序，
     *  统一用 Mutex 串行化，且每次写「当前 StateFlow 最新值」，保证 DB 最终与 UI 一致。 */
    private val tagWriteMutex = Mutex()

    fun addManualTag(tag: String) {
        val t = tag.trim().lowercase()
        if (t.isEmpty()) return
        _manualTags.value = (_manualTags.value + t).distinct()
        viewModelScope.launch { tagWriteMutex.withLock { repo.saveManualPreferredTags(_manualTags.value.toSet()) } }
    }

    fun removeManualTag(tag: String) {
        _manualTags.value = _manualTags.value - tag
        viewModelScope.launch { tagWriteMutex.withLock { repo.saveManualPreferredTags(_manualTags.value.toSet()) } }
    }

    fun addHatedTag(tag: String) {
        val t = tag.trim().lowercase()
        if (t.isEmpty()) return
        _hatedTags.value = (_hatedTags.value + t).distinct()
        viewModelScope.launch { tagWriteMutex.withLock { repo.saveHatedTags(_hatedTags.value) } }
    }

    fun removeHatedTag(tag: String) {
        _hatedTags.value = _hatedTags.value - tag
        viewModelScope.launch { tagWriteMutex.withLock { repo.saveHatedTags(_hatedTags.value) } }
    }

    /** 从标签库选择器批量保存手动喜欢（4.1.0）。 */
    fun saveManualTags(tags: List<String>) {
        val list = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        _manualTags.value = list
        viewModelScope.launch { tagWriteMutex.withLock { repo.saveManualPreferredTags(_manualTags.value.toSet()) } }
    }

    /** 从标签库选择器批量保存讨厌标签（4.1.0）。 */
    fun saveHatedTags(tags: List<String>) {
        val list = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        _hatedTags.value = list
        viewModelScope.launch { tagWriteMutex.withLock { repo.saveHatedTags(_hatedTags.value) } }
    }

    /**
     * 三态循环（4.13.0）：喜欢 → 普通 → 讨厌 → 喜欢。
     * 依赖 _manualTags / _hatedTags 的本地镜像做判断，然后走既有的增删持久化。
     */
    fun cycleTag(tag: String) {
        val t = tag.trim().lowercase()
        if (t.isEmpty()) return
        val inLike = t in _manualTags.value
        val inHate = t in _hatedTags.value
        when {
            inLike -> removeManualTag(t)                                   // 喜欢 → 普通
            inHate -> { removeHatedTag(t); addManualTag(t) }               // 讨厌 → 喜欢
            else -> addHatedTag(t)                                         // 普通 → 讨厌
        }
    }

    /** 选喜欢/讨厌模式（标签库「选喜欢/讨厌」按钮）：普通 → 喜欢 → 讨厌 → 普通 循环。 */
    fun cycleLikeHate(tag: String) {
        val t = tag.trim().lowercase()
        if (t.isEmpty()) return
        when {
            t in _manualTags.value -> { removeManualTag(t); addHatedTag(t) }   // 喜欢 → 讨厌
            t in _hatedTags.value -> removeHatedTag(t)                          // 讨厌 → 普通
            else -> addManualTag(t)                                             // 普通 → 喜欢
        }
    }

    /** 标签相关性（同义词智能推荐用）。 */
    suspend fun computeTagCorrelations(): List<BookLibrary.TagCorrelation> =
        repo.computeTagCorrelations()

    /** 漫画本体占用字节数（设置页「关于」）。 */
    suspend fun downloadedBytes(): Long = repo.getDownloadedBytes()

    /** 相关标签分组（用户自己选择页用）。 */
    suspend fun groupCorrelatedTags(): List<List<String>> = repo.groupCorrelatedTags()

    /** 标签不存在于标签库时：自动入库再加入喜欢/讨厌（4.1.0）。 */
    fun addTagToLibrary(tag: String) {
        viewModelScope.launch {
            val norm = tag.trim().lowercase().ifEmpty { return@launch }
            repo.addSearchTags(listOf(norm))
        }
    }

    /** 清空标签库（从搜索累积的标签字典）。 */
    fun clearTagLibrary() {
        viewModelScope.launch { repo.clearTagLibrary() }
    }

    // ── 作者订阅（P1-4，3.0.0 / 4.0.0）──────────────────────────
    suspend fun addSubscription(name: String): Boolean = repo.addSubscription(name)
    /** 检查订阅更新（v7.2.0 起带新作自动下载，受设置 sub_auto_download 控制）。 */
    suspend fun checkSubscriptions(): Int = repo.checkSubscriptionsForUpdatesWithAutoDownload()
    fun clearSubscriptionNew(id: Int) { viewModelScope.launch { repo.clearSubscriptionNew(id) } }
    fun removeSubscription(id: Int, author: String = "") {
        viewModelScope.launch { repo.removeSubscriptionAndUpdates(id, author) }
    }

    // ── 智能关联拓展（P2/P3，3.0.0）─────────────────────────────
    /** 重置喜欢样本（清空手动喜欢与讨厌，异常自动回滚到上一版）。返回是否成功。 */
    suspend fun resetPreferenceSample(): Boolean = repo.resetPreferenceSample()

    // ── 云同步（v1.10.0，委托 repo.syncNow）────────────────────

    /** 同步进行中的 UI 状态（按钮禁用 + 结果展示）。 */
    data class SyncUiState(val running: Boolean = false, val message: String = "")

    private val _syncUi = MutableStateFlow(SyncUiState())
    val syncUi: StateFlow<SyncUiState> = _syncUi.asStateFlow()

    /** 启用/关闭云同步。 */
    fun setSyncEnabled(on: Boolean) {
        viewModelScope.launch {
            repo.setSyncEnabled(on)
            refreshConfig()
        }
    }

    /** 保存服务器地址（空白忽略）。 */
    fun setSyncServerUrl(url: String) {
        val v = url.trim()
        if (v.isEmpty()) return
        viewModelScope.launch {
            repo.setSyncServerUrl(v)
            refreshConfig()
        }
    }

    /** 保存鉴权密钥（空白忽略）。 */
    fun setSyncToken(token: String) {
        val v = token.trim()
        if (v.isEmpty()) return
        viewModelScope.launch {
            repo.setSyncToken(v)
            refreshConfig()
        }
    }

    /** 手动立即同步（不受 6h 节流限制；完成/失败都会刷新 sync_last_result 到 config）。 */
    fun syncNow() {
        if (_syncUi.value.running) return
        _syncUi.value = SyncUiState(running = true, message = "同步中…")
        viewModelScope.launch {
            val result = repo.syncNow()
            _syncUi.value = SyncUiState(
                running = false,
                message = result.fold({ "✅ ${it.describe()}" }, { "❌ ${it.message}" })
            )
            refreshConfig()
        }
    }

    /** 从 DB 重读全部 keys 配置（同步结果等后台变更后刷 UI）。 */
    private suspend fun refreshConfig() {
        val all = repo.getAllConfig()
        _config.value = keys.associateWith { all[it] ?: "" }
    }

    // 主题（亮/暗/跟随系统）走 SharedPreferences，与 MainActivity 启动读取一致
    private val prefs: SharedPreferences =
        context.getSharedPreferences("jmcomic_prefs", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(prefs.getString("theme", "system") ?: "system")
    val theme: StateFlow<String> = _theme

    /** 主题风格（v5.0.0）：ink 墨夜沉浸 / paper 纸感暖橘 / neon 霓虹紫夜。 */
    private val _themeStyle = MutableStateFlow(prefs.getString("theme_style", "ink") ?: "ink")
    val themeStyle: StateFlow<String> = _themeStyle.asStateFlow()

    /** 应用图标变体（v5.0.x）：default / alt1 / alt2。 */
    private val _appIcon = MutableStateFlow(AppIconManager.getSelectedIcon(context))
    val appIcon: StateFlow<String> = _appIcon.asStateFlow()

    /** 是否显示设置页的提示说明小字（v5.0.12：总开关，关闭后所有辅助说明文字隐藏）。 */
    private val _showHints = MutableStateFlow(prefs.getBoolean("show_hints", true))
    val showHints: StateFlow<Boolean> = _showHints.asStateFlow()

    fun setShowHints(on: Boolean) {
        prefs.edit().putBoolean("show_hints", on).apply()
        ShowHintsState.enabled = on   // 全局状态：MainActivity 顶层 CompositionLocal 自动重组，全 app 即时生效
        _showHints.value = on
    }

    init { viewModelScope.launch { load() } }

    private suspend fun load() {
        // 一次查询全部配置（替代 31 次串行 getConfig，进设置页/子页不再逐个查库）
        val all = repo.getAllConfig()
        _config.value = keys.associateWith { all[it] ?: "" }
    }

    fun get(key: String): String = _config.value[key] ?: ""

    fun set(key: String, value: String) {
        viewModelScope.launch {
            repo.setConfig(key, value)
            if (key == "max_concurrent_downloads" || key == "book_download_concurrency") {
                repo.applyDownloadConcurrency()   // v1.7.9：并发设置立即生效（下一本/下一章）
            }
            _config.value = _config.value.toMutableMap().apply { put(key, value) }
        }
    }

    fun setTheme(mode: String) {
        prefs.edit().putString("theme", mode).apply()
        ThemeState.theme = mode   // 更新全局状态 → MainActivity 即时重组
        _theme.value = mode
    }

    /** 切换主题风格（v5.0.0）：即时生效并持久化。 */
    fun setThemeStyle(style: String) {
        prefs.edit().putString("theme_style", style).apply()
        ThemeState.themeStyle = style   // 更新全局状态 → MainActivity 即时重组
        _themeStyle.value = style
    }

    /** 切换应用图标（v5.0.x）：通过启用/禁用 activity-alias 实现。 */
    fun setAppIcon(context: Context, variant: String) {
        AppIconManager.setIcon(context, variant)
        _appIcon.value = variant
    }

    // 定时夜间模式时间段（theme=auto 时生效；与 ThemeState/MainActivity 共用）
    private val _nightStart = MutableStateFlow(prefs.getString("night_mode_start", "22:00") ?: "22:00")
    val nightStart: StateFlow<String> = _nightStart

    private val _nightEnd = MutableStateFlow(prefs.getString("night_mode_end", "06:00") ?: "06:00")
    val nightEnd: StateFlow<String> = _nightEnd

    fun setNightStart(hhmm: String) {
        prefs.edit().putString("night_mode_start", hhmm).apply()
        ThemeState.nightStart = hhmm
        _nightStart.value = hhmm
    }

    fun setNightEnd(hhmm: String) {
        prefs.edit().putString("night_mode_end", hhmm).apply()
        ThemeState.nightEnd = hhmm
        _nightEnd.value = hhmm
    }
}
