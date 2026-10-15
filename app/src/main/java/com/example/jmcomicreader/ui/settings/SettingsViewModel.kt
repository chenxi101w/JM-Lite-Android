package com.example.jmreader.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jmreader.data.repository.JmRepository
import com.example.jmreader.data.repository.BookLibrary
import com.example.jmreader.ui.theme.ThemeState
import com.example.jmreader.ui.ShowHintsState
import com.example.jmreader.util.AppIconManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: JmRepository,
    context: Context
) : ViewModel() {

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
        "max_concurrent_downloads",
        "search_result_limit", "search_tag_limit", "reader_chapter_toast",
        "hide_page_chapter", "search_priority", "default_sort",
        "search_bar_behavior", "reader_pages_per_screen", "bookshelf_columns",
        // 2.0.0 新增：搜索已下载屏蔽 / 进度滑轨颜色 / 书架排序 / 看完沉底
        "hide_downloaded_in_search", "reader_progress_color",
        "library_sort", "library_unfinished_first",
        // v5 新增：看完漫画处理
        "hide_finished_in_search", "finished_in_library",
        // v7 读完设置
        "reader_auto_finish_scroll", "reader_auto_finish_percent",
        // 3.0.0 看完自动删除整本本地内容（延迟+重入取消）——v7.1.0 已由回收站机制取代（配置移除）
        // 4.3.1 阅读器「不删」按钮后续策略——v7.1.0 移除（配置移除）
        // v1.10.0 云同步（JmSyncManager）
        "sync_enabled", "sync_server_url", "sync_token", "sync_last_at", "sync_last_result"
    )

    private val _config = MutableStateFlow<Map<String, String>>(emptyMap())
    val config: StateFlow<Map<String, String>> = _config

    /** 漫画本体占用字节数（设置页「关于」）。 */
    suspend fun downloadedBytes(): Long = repo.getDownloadedBytes()

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
