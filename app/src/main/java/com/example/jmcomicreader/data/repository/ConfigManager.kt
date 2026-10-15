package com.example.jmreader.data.repository

import com.example.jmreader.data.image.NativeAlbumDownloader
import com.example.jmreader.data.local.AppDatabase
import com.example.jmreader.data.local.SystemConfig
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import android.util.Log

/**
 * 配置域：读写全局键值配置，并把启动需要的配置同步到 Python 客户端。
 * 从 JmRepository 拆出（原 739 行 → 5 个单一职责类 + 门面）。
 */
class ConfigManager(
    db: AppDatabase
) {
    private val configDao = db.systemConfigDao()

    // 默认配置（对齐 PC 2.4.9）
    private val defaultConfigs = listOf(
        // v1.8.7：6 → 16。官方 jmcomic 的默认 `download.threading.image = 30`（全局），
        // 我们按「每本一份」的结构取 16，× 2 本 = 32，与官方同量级。
        "max_concurrent_downloads" to "24",
        "book_download_concurrency" to "2",
        "search_result_limit" to "80",
        "search_priority" to "input",
        "hide_page_chapter" to "false",
        "search_tag_limit" to "0",
        "reader_chapter_toast" to "1",
        "default_sort" to "mr",
        "search_bar_behavior" to "hide",
        "reader_pages_per_screen" to "1",
        "bookshelf_columns" to "0",
        "search_columns" to "0",   // 搜索结果每行列数（0=默认3列；v6.0.20 新增）
        // ── 1.6.0 新增 ──
        // 4.3.1：open_directly 已移除——点漫画（主页/搜索/书架）统一直接进阅读器，详情从阅读器右上角进
        // ── 2.0.0 新增 ──
        "hide_downloaded_in_search" to "hide", // 搜索里已下载漫画：off 不处理 / hide 彻底隐藏 / gray 灰显可点
        "reader_progress_color" to "#7B6FE0",  // 阅读器右侧进度滑轨颜色（十六进制）
        "library_sort" to "download",         // 书架排序：download 下载时间 / views_desc 阅读次数↓ / views_asc 阅读次数↑ / preference 喜欢(标签匹配)
        "library_unfinished_first" to "0",     // 1 = 已看完的沉底、未看完的置顶；0 = 不沉底（4.4.1 默认改 0，看完不再消失）
        // ── 2.1.0 新增 ──
        "hide_finished_in_search" to "hide",   // 搜索里已看完漫画：off 不处理 / hide 彻底隐藏 / gray 灰显可点
        "finished_in_library" to "divider",    // 书架里看完漫画：divider 沉底+分隔线 / hidden 隐藏
        // ── v7 读完设置 ──
        "reader_auto_finish_scroll" to "0",    // 滚到最后一张图自动标记整本看完（默认关）
        "reader_auto_finish_percent" to "90",  // 阅读进度达该百分比自动算看完（1~100）
        // v1.17.0 轻量化：整本「看完自动删除」机制随回收站下线；喜欢/讨厌标签体系、无关标签、
        // 智能关联（同义词展开 + 喜欢加权排序）、订阅新作自动下载的默认配置键一并移除。
        // 数据库里已写入的旧键是惰性残留（无读取方），不主动清——留着不影响运行。
        // ── v1.10.0 云同步（JmSyncManager）：看过的漫画 JM 号备份到用户自有服务器 ──
        // ⚠️ v1.17.1：服务器地址与密钥**不再硬编码**（原先是明文写在源码里的真实地址与密钥，
        // 一旦推上公开仓库就等于公开凭据）。出厂一律留空，由用户在
        // 设置 → 书架 → 云同步卡片里填一次，配置落 system_config 表，不进版本库。
        "sync_enabled" to "0",                 // 1 = 启用（启动静默同步 + 设置页手动同步）
        "sync_server_url" to "",              // 用户自有服务器地址（如 https://sync.example.com）
        "sync_token" to "",                   // 服务器鉴权密钥
        "sync_last_at" to "0",                 // 上次成功同步时间戳 ms（0 = 从未成功）
        "sync_last_result" to ""               // 上次同步结果描述（UI 直接显示）
    )

    /** 写入默认配置（不存在才写，避免覆盖用户设置）。 */
    suspend fun initConfigDefaults() {
        for ((k, v) in defaultConfigs) {
            if (configDao.get(k) == null) configDao.set(SystemConfig(k, v))
        }
    }

    suspend fun getConfig(key: String): String? = configDao.get(key)
    suspend fun setConfig(key: String, value: String) = configDao.set(SystemConfig(key, value))

    /** 批量读取全部配置（一次查询替代 N 次单查，设置页/启动等场景提速）。 */
    suspend fun getAllConfig(): Map<String, String> =
        configDao.getAll().associate { it.key to (it.value ?: "") }

    /** 配置表响应式流（配置变更实时反映到依赖方，如搜索页讨厌/喜欢流派生）。 */
    fun observeAllConfig(): Flow<List<SystemConfig>> = configDao.observeAll()

    /**
     * 应用 DB 中的代理设置（v1.4.0：Chaquopy 已移除，此处只读出配置备用）。
     *
     * 原生链路（OkHttp）暂不支持自定义代理 —— 需要代理时应在 `JmApiClient` /
     * `NativeAlbumDownloader` 的 OkHttpClient 上加 `Proxy`，而不是在这里透传给 Python。
     * 保留本方法是为了**不改动启动流程的调用点**。
     */
    suspend fun applyProxyFromConfig() {
        val enabled = configDao.get("proxy_enabled")
        val url = configDao.get("proxy_url") ?: ""
        if (enabled == "1" || enabled == "true") {
            Log.i("ConfigManager", "代理已启用（$url），但原生链路暂不支持代理，将直连")
        }
    }

    /**
     * 应用并发下载数（v1.4.0：Chaquopy 已移除）。
     *
     * v1.7.9 起该配置**真正生效**：由 DownloadManager.refreshConcurrency() 读取，
     * 作用于原生下载器的图片级并发。
     */
    suspend fun applyDownloadConcurrency() {
        val n = configDao.get("max_concurrent_downloads")?.toIntOrNull()
            ?: NativeAlbumDownloader.DEFAULT_IMAGE_CONCURRENCY
        Log.i("ConfigManager", "图片并发数配置 = $n（v1.7.9 起对原生下载器生效）")
    }

    /** 代理设置（v1.4.0：原生链路不使用，仅保留接口供设置页调用）。 */
    suspend fun setProxy(proxyJson: String) {
        Log.i("ConfigManager", "setProxy 已忽略（原生链路暂不支持代理）")
    }
}
