package com.example.jmreader.data.repository

import com.example.jmreader.data.local.Alias
import com.example.jmreader.data.local.AppDatabase
import com.example.jmreader.data.local.Blacklist
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.normalizeAuthorName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * 内容过滤域：拉黑（blacklists，讨厌标记不过滤）、同义词/别名（aliases）、搜索结果客户端过滤。
 * 从 JmRepository 拆出。（v7.0.7 起屏蔽 blocks 体系已移除，仅保留表结构不动。）
 */
class ContentFilter(
    db: AppDatabase
) {
    private val aliasDao = db.aliasDao()
    private val blacklistDao = db.blacklistDao()

    // 别名 JSON 内存缓存：数据变更时失效，避免每次搜索都全量查库组装
    @Volatile private var aliasesJsonCache: String? = null

    private fun invalidateCaches() {
        aliasesJsonCache = null
    }

    // ── 拉黑（blacklists，讨厌标记；不参与搜索过滤，供喜欢/讨厌算法用）──

    fun observeBlacklists(): Flow<List<Blacklist>> = blacklistDao.observeAll()

    suspend fun addBlacklist(type: String, value: String): Long {
        val v = if (type == "author") normalizeAuthorName(value) else value.trim()
        if (v.isEmpty()) return -1
        val existing = blacklistDao.findByValue(type, v)
        if (existing != null) return existing.id.toLong()
        return blacklistDao.insert(Blacklist(type = type, value = v))
    }

    suspend fun getBlacklist(id: Int): Blacklist? = blacklistDao.getById(id)

    suspend fun removeBlacklist(id: Int) = blacklistDao.deleteById(id)

    // ── 同义词 / 别名 ─────────────────────────────────────────

    fun observeAliases(type: String): Flow<List<Alias>> = aliasDao.observeAll(type)

    suspend fun addAlias(type: String, alias: String, canonical: String): Long {
        // v5.6.0：lowercase 归一（与标签库存储一致，避免英文大小写不匹配）；别名==规范词的自环拒绝
        val a = alias.trim().lowercase()
        val c = canonical.trim().lowercase()
        if (a.isEmpty() || c.isEmpty() || a == c) return -1
        val id = aliasDao.insert(Alias(type = type, alias = a, canonical = c))
        invalidateCaches()
        return id
    }

    suspend fun removeAlias(id: Int) {
        aliasDao.deleteById(id)
        invalidateCaches()
    }

    /** 构造 bridge 用的别名 JSON（tag、author 两类映射）。 */
    suspend fun getAliasesJson(): String {
        aliasesJsonCache?.let { return it }
        val tagMap = mutableMapOf<String, String>()
        aliasDao.observeAll("tag").first().forEach { tagMap[it.alias] = it.canonical }
        val authorMap = mutableMapOf<String, String>()
        aliasDao.observeAll("author").first().forEach { authorMap[it.alias] = it.canonical }
        return JSONObject().apply {
            put("tag", JSONObject(tagMap))
            put("author", JSONObject(authorMap))
        }.toString().also { aliasesJsonCache = it }
    }

    /** 同义词/别名按类型计数（tag/author），逻辑查看页展示规则覆盖规模。 */
    suspend fun countAliasesByType(): Map<String, Int> {
        val tag = aliasDao.observeAll("tag").first().size
        val author = aliasDao.observeAll("author").first().size
        return mapOf("tag" to tag, "author" to author)
    }

    // ── 搜索结果过滤（2.0.0：已下载屏蔽 + 长按拉黑立即生效）──────

    /**
     * 对搜索结果做客户端过滤（不依赖 Python 重搜）：
     * - 已下载漫画(id 在 downloadedIds)按 hideMode 处理：hide=丢弃，gray=保留并标记为灰显；
     * - 已看完漫画(id 在 finishedIds)按 hideFinishedMode 处理：hide=丢弃，gray=保留并标记为灰显；
     *   「已下载」「已看完」两种状态独立、可叠加（看完且已下载的漫画需同时通过两道过滤）。
     * - 讨厌标签（hatedTags）命中的漫画直接丢弃（搜索/推荐中排除）。
     * 返回 (可见结果, 灰显 id 集合)。
     */
    fun filterSearchResults(
        results: List<Book>,
        hideMode: String,
        downloadedIds: Set<Int>,
        hideFinishedMode: String = "off",
        finishedIds: Set<Int> = emptySet(),
        hatedTags: Set<String> = emptySet()
    ): Pair<List<Book>, Set<Int>> {
        val hated = hatedTags.map { it.trim().lowercase() }.toSet()
        if (hideMode == "off" && hideFinishedMode == "off" && hated.isEmpty()) {
            return results to emptySet()
        }
        val visible = mutableListOf<Book>()
        val grayed = mutableSetOf<Int>()
        for (r in results) {
            // 讨厌标签：推荐/搜索中排除
            if (hated.isNotEmpty()) {
                val rtags = r.tags.split("|").map { it.trim().lowercase() }.toSet()
                if (rtags.intersect(hated).isNotEmpty()) continue
            }
            if (r.id in downloadedIds) {
                if (hideMode == "hide") continue
                if (hideMode == "gray") grayed.add(r.id)
            }
            if (r.id in finishedIds) {
                if (hideFinishedMode == "hide") continue
                if (hideFinishedMode == "gray") grayed.add(r.id)
            }
            visible.add(r)
        }
        return visible to grayed
    }
}
