package com.example.jmreader.data.repository

import com.example.jmreader.data.local.Alias
import com.example.jmreader.data.local.AliasDao
import com.example.jmreader.data.local.BlacklistDao
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.BookDao
import com.example.jmreader.data.local.SystemConfig
import com.example.jmreader.data.local.SystemConfigDao
import com.example.jmreader.data.local.TagLibrary
import com.example.jmreader.data.local.TagLibraryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import android.util.Log
import com.example.jmreader.data.api.JmApiClient

/**
 * 标签库 / 偏好推荐（v7.0.0 从 BookLibrary 拆出）。
 *
 * 只负责「标签库数据 + 无关标签 + 喜欢/讨厌偏好画像 + 标签相关性/一键分类」，
 * 不碰书籍正文、章节、进度与阅读状态。需要 books / blacklists / tag_library / aliases 等表，
 * 以及读取配置的 configProvider、标签已录入标记（来自 BookStatusStore），故把对应依赖直接注入
 * （避免反向依赖 BookLibrary 造成循环引用）。
 *
 * 注：TagCorrelation / CategoryProposal / PreferenceProfile 三个 data class 仍定义在 BookLibrary，
 * 外部（JmRepository / 各 ViewModel）按 `BookLibrary.Xxx` 引用，故本类返回类型用全限定名。
 *
 * @param bookDao         books 表 dao（标签/收藏/拉黑样本来源）
 * @param blacklistDao    blacklists 表 dao（拉黑作品/作者判定）
 * @param configDao       system_config 表 dao（手动喜欢/讨厌/无关/开关持久化）
 * @param tagLibraryDao   tag_library 表 dao（标签库累加/快照）
 * @param aliasDao        aliases 表 dao（同义词归一）
 * @param configProvider  读取配置（smart_association / pref_min_hits 等），由门面注入
 * @param ledger          漫画账本（v1.12.0：标签已录入以 tags 非空判定）
 */
class BookTagEngine(
    private val bookDao: BookDao,
    private val blacklistDao: BlacklistDao,
    private val configDao: SystemConfigDao,
    private val tagLibraryDao: TagLibraryDao,
    private val aliasDao: AliasDao,
    private val configProvider: suspend (String) -> String?,
    private val ledger: ComicLedger
) {

    private fun splitTags(tags: String): Set<String> =
        tags.split("|").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    // ── 无关性标签（v4.14.0 起；5.0.19 改为用户可决定）──────────────
    // 非核心属性标签：与人物/行为/性格等关键属性无关，如 c107、c108（JM 内部编号）、
    // 中文（语言）、全彩（格式）。它们会污染喜欢/讨厌画像与同义词推荐，故：
    //   ① 不参与偏好计算（computePreferredTags）；② 不参与相关性/同义词计算；
    //   ③ 标签库管理页弱化显示（灰显 + 可筛选），汇总统计中排除。
    // 判定 = 结构规则(^[cv]\d+$)  OR  默认无关标签(default_irrelevant_tags，固定种子)  OR  用户手动无关(ignored_tags)。
    // 默认无关标签种子来自旧硬编码清单，为固定默认值；用户可自定义的是「标签库 → 选无关标签」写入的 ignored_tags。

    private val IRRELEVANT_TAG_REGEX = Regex("^[cv]\\d+$", RegexOption.IGNORE_CASE)

    /** 无关性标签判定：结构规则 + 默认无关 + 用户手动无关，任一命中即视为无关。 */
    suspend fun isIrrelevantTag(tag: String): Boolean {
        val t = tag.trim().lowercase()
        if (t.isBlank()) return true
        if (IRRELEVANT_TAG_REGEX.matches(t)) return true
        if (t in getDefaultIrrelevantTags()) return true
        return t in getIgnoredTags()
    }

    /** 默认无关标签集合（config `default_irrelevant_tags`，逗号分隔；未设置时回退空，由 ConfigManager 种子保证有值）。 */
    suspend fun getDefaultIrrelevantTags(): Set<String> {
        val raw = configProvider("default_irrelevant_tags") ?: return emptySet()
        return raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /** 用户手动标记的无关标签集合（config `ignored_tags`）。 */
    suspend fun getIgnoredTags(): Set<String> {
        val raw = configProvider("ignored_tags") ?: return emptySet()
        return raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /** 持久化用户手动无关标签集合（config `ignored_tags`）。 */
    suspend fun setIgnoredTags(tags: Set<String>) {
        configDao.set(SystemConfig("ignored_tags", tags.distinct().joinToString(",")))
    }

    /** 切换某标签的用户手动无关状态（在/不在 ignored_tags 间切换）。 */
    suspend fun toggleIgnoredTag(tag: String) {
        val t = tag.trim().lowercase()
        if (t.isEmpty()) return
        val set = getIgnoredTags()
        setIgnoredTags(if (t in set) set - t else set + t)
    }

    /** 是否开启「隐藏无关标签」（config `hide_irrelevant_tags` == "1"）。 */
    suspend fun isHideIrrelevantEnabled(): Boolean = configProvider("hide_irrelevant_tags") == "1"

    /** 设置「隐藏无关标签」开关。 */
    suspend fun setHideIrrelevant(on: Boolean) {
        configDao.set(SystemConfig("hide_irrelevant_tags", if (on) "1" else "0"))
    }

    /**
     * 汇总统计（v4.14.0）：拉黑漫画 / 收藏漫画的标签出现次数（降序）。
     * @param scope "blacklisted" = 拉黑漫画（作品级 jmId 或作者级命中），"favorited" = 收藏漫画。
     * 仅统计本地 books 表中可见的漫画（黑名单中从未拉取过详情的作品无标签数据，属已知限制）。
     * 无关性标签（c107/中文/全彩 等）自动排除。
     */
    suspend fun aggregateComicTags(scope: String, cap: Int = 30): List<Pair<String, Int>> {
        val all = bookDao.observeAll().first()
        val blacklists = blacklistDao.observeAll().first()
        val blackWorkIds = blacklists.filter { it.type == "work" }
            .mapNotNull { it.value.trim().toIntOrNull() }.toSet()
        val blackAuthors = blacklists.filter { it.type == "author" }
            .map { it.value.trim().lowercase() }.toSet()
        val aliasMap = canonicalAliasMap()   // v5.6.0：同义词归一后再统计（中文/汉化/汉语合并）
        val score = mutableMapOf<String, Int>()
        for (b in all) {
            val hit = when (scope) {
                "blacklisted" ->
                    b.id in blackWorkIds || b.author.trim().lowercase() in blackAuthors
                else -> b.favorite == 1
            }
            if (!hit) continue
            for (t in splitTags(b.tags)) {
                val ct = canonicalize(aliasMap, t)
                if (isIrrelevantTag(ct)) continue
                score[ct] = (score[ct] ?: 0) + 1
            }
        }
        return score.entries.sortedByDescending { it.value }
            .take(cap).map { it.key to it.value }
    }

    /**
     * 名称特征提取（P3-1，3.0.0）：从漫画标题切出有意义的特征词，作为喜欢的补充信号。
     * 处理：①去掉括号及括号内说明（如「(全彩)」「（中文）」）；②按空白/标点/分隔符切分；
     * ③过滤纯数字、长度<2、以及停用词（合集/汉化/无修正…）避免噪声。
     * 仅当 smart_association 开启、且特征在收藏样本里出现 ≥ pref_min_hits 次时才计入喜欢。
     */
    private val NAME_STOPWORDS = setOf(
        "合集", "全集", "单行本", "短篇", "同人", "全彩", "汉化", "中文",
        "无修正", "無修正", "ce", "galgame", "gal", "rj", "cg", "漫画", "本子",
        "第", "卷", "话", "章", "篇", "特典", "附录", "完全版", "hd", "4k", "无码", "有码",
        // 4.1.0 补充：常见英文停用词 / 无信息量词
        "the", "a", "an", "of", "and", "or", "to", "in", "on", "for", "with",
        "vol", "volume", "ch", "chapter", "part", "series", "full", "color", "dl",
        "manga", "comic", "novel", "special", "extra", "bonus", "ver", "version"
    )

    private fun extractNameFeatures(title: String): Set<String> {
        val cleaned = title
            .replace(Regex("[(\\[（【][^()\\[\\]（）【】]*[)\\]）】]"), " ")
            .replace(Regex("[\\s/\\-、，,.!！?？:：;；|｜《》「」“”\"'…~～+＋*＊=＝]"), " ")
        return cleaned.split(Regex("\\s+"))
            .map { it.trim().lowercase() }
            .filter {
                it.isNotBlank() &&
                    it.length >= 2 &&
                    !it.all { c -> c.isDigit() } &&
                    !it.all { c -> c in "0123456789xX" } &&   // "1x2" 尺寸类噪声
                    it !in NAME_STOPWORDS
            }
            .toSet()
    }

    /** 全局总开关「智能关联拓展」是否开启（P2-5，3.0.0）。默认开。 */
    suspend fun isSmartAssociationEnabled(): Boolean =
        (configProvider("smart_association") ?: "1") != "0"

    /** 喜欢红线阈值：标签/名称特征需在收藏样本里出现至少 N 次才计入（P2-5，3.0.0）。默认 1。 */
    suspend fun getMinHits(): Int =
        (configProvider("pref_min_hits")?.toIntOrNull()?.coerceAtLeast(1)) ?: 1

    /** 收藏样本数（喜欢正样本 = favorite==1 的漫画数），逻辑查看页展示基线规模。 */
    suspend fun countFavorites(): Int = bookDao.getFavoriteIds().toSet().size

    /**
     * 标题名称特征（关键字分词）单独计分：遍历收藏样本，只用 extractNameFeatures 计分，
     * 与标签喜欢分开（标签 → primaryTop/microTop，分词 → keywordTop），
     * 同受喜欢红线 minHits 约束。实时派生、不落库。
     */
    suspend fun computeNameFeatureTags(): List<Pair<String, Int>> = withContext(Dispatchers.Default) {
        val all = bookDao.observeAll().first()
        val blacklists = blacklistDao.observeAll().first()
        val blackWorkIds = blacklists.filter { it.type == "work" }.mapNotNull { it.value.toIntOrNull() }.toSet()
        val blackAuthors = blacklists.filter { it.type == "author" }.map { it.value.lowercase() }.toSet()
        val smart = isSmartAssociationEnabled()
        if (!smart) return@withContext emptyList()
        val score = mutableMapOf<String, Int>()
        for (b in all) {
            if (b.favorite != 1) continue
            if (b.id in blackWorkIds) continue
            if (b.author.trim().lowercase() in blackAuthors) continue
            for (nf in extractNameFeatures(b.title)) score[nf] = (score[nf] ?: 0) + 1
        }
        val minHits = getMinHits()
        score.filter { it.value >= minHits }
            .entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /**
     * 喜欢样本库（v2.6.2 起）：以「收藏的漫画」为正样本。
     * 每本 favorite==1 的漫画贡献其全部标签各 +1 分；当 smart_association 开启时，
     * 额外把标题名称特征（extractNameFeatures）也各 +1 分（P3-1，3.0.0）。
     * 合并后按「喜欢红线」pref_min_hits 阈值过滤单次噪声，再按得分降序返回 (标签, 得分)。
     * 实时派生、不落库：取消收藏（favorite=0）后下次取喜欢自动排除该样本，无需样本表。
     */
    suspend fun computePreferredTags(): List<Pair<String, Int>> = withContext(Dispatchers.Default) {
        val all = bookDao.observeAll().first()
        val blacklists = blacklistDao.observeAll().first()
        val blackWorkIds = blacklists.filter { it.type == "work" }.mapNotNull { it.value.toIntOrNull() }.toSet()
        val blackAuthors = blacklists.filter { it.type == "author" }.map { it.value.lowercase() }.toSet()
        val smart = isSmartAssociationEnabled()
        val aliasMap = canonicalAliasMap()   // v5.6.0：喜欢画像同义词归一（中文/汉化/汉语 合并计分）
        val score = mutableMapOf<String, Int>()
        for (b in all) {
            if (b.favorite != 1) continue                          // 仅收藏漫画作为正样本
            if (b.id in blackWorkIds) continue                     // 拉黑的作品去掉
            if (b.author.trim().lowercase() in blackAuthors) continue // 拉黑的作者去掉
            for (t in splitTags(b.tags)) {
                val ct = canonicalize(aliasMap, t)
                if (isIrrelevantTag(ct)) continue   // 无关性标签（c107/中文/全彩等）不污染喜欢画像
                score[ct] = (score[ct] ?: 0) + 1
            }
            if (smart) for (nf in extractNameFeatures(b.title)) score[nf] = (score[nf] ?: 0) + 1
        }
        // 喜欢红线：标签/特征需出现至少 minHits 次才计入（过滤偶发噪声）
        val minHits = getMinHits()
        score.filter { it.value >= minHits }
            .entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** 当前生效的喜欢标签（书架「喜欢排行」、讨厌取反依据）：手动喜欢 ∪ 计算喜欢（实时合并，二者独立存储但一起生效）。
     * 全局开关 smart_association 关闭时返回空集（退化为无喜欢纯列表，搜索/书架/推荐均不再加权）。 */
    suspend fun getPreferredTags(): Set<String> {
        if (!isSmartAssociationEnabled()) return emptySet()
        val manual = getManualPreferredTags()
        val computed = computePreferredTags().map { it.first }.toSet()
        return manual + computed
    }

    /**
     * 当前生效的讨厌标签（v2.6.2 起实时派生，不再持久化快照）：
     * = 拉黑作品/作者的标签 − 当前喜欢标签。拉黑即等同于讨厌，无需手动维护；
     * 收藏某标签会自动从讨厌中剔除（喜欢与讨厌对立）。
     */
    /** 当前生效的讨厌标签（v2.7.0 起手动维护，存于 config `hated_tags`）：推荐/搜索中排除。 */
    suspend fun getHatedTags(): Set<String> {
        val raw = configProvider("hated_tags") ?: return emptySet()
        return raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /** 持久化手动讨厌标签（config `hated_tags`，逗号分隔）。 */
    suspend fun saveHatedTags(tags: List<String>) {
        configDao.set(SystemConfig("hated_tags", tags.distinct().joinToString(",")))
    }

    // ── 标签库（v2.7.0：从搜索结果累积，按数量排序）────────────

    /** 搜索结果写入标签库：每本漫画的每个标签各 +1（总曝光计数）。tags 可含重复。 */
    suspend fun addSearchTags(tags: List<String>) {
        val ts = System.currentTimeMillis()
        for (t in tags) if (t.isNotEmpty()) tagLibraryDao.bump(t, ts)
    }

    /**
     * 只读获取某漫画的真实标签（不落库），供搜索结果后台补抓标签库。
     *
     * v1.4.0：Chaquopy 已移除 → 改用原生 `JmApiClient.albumTags`（`/album` 的 `tags` 数组）。
     * 取不到时返回空列表（调用方逐本容错，不影响搜索结果展示）。
     */
    suspend fun getAlbumTags(albumId: Int): List<String> =
        runCatching { JmApiClient.albumTags(albumId) }
            .onFailure { Log.w("BookTagEngine", "取标签失败 $albumId：${it.message}") }
            .getOrDefault(emptyList())

    /**
     * 搜索结果后台补抓标签：关键词/作者搜索的返回往往不带 tags（jmcomic 搜索接口不返回标签），
     * 故对「tags 为空且尚未落库」的本，逐本抓详情取真实标签写入标签库；封顶 cap 本、逐本容错，
     * 调用方在 viewModelScope 后台跑（不阻塞搜索结果展示）。已落库（下载/看过）的本已有真实 tags，跳过免重复抓。
     * 这是「搜索几次后标签库仍为空」的根因修复：搜索结果本身无标签，必须补抓详情。
     */
    suspend fun enrichTagLibraryFromSearch(books: List<Book>, cap: Int = 15) {
        var n = 0
        for (b in books) {
            if (n >= cap) break
            // 跳过判断：仅当 books 表中该书已带真实标签（非空）才免抓。
            // 旧逻辑用 `bookDao.get(b.id) != null`：搜索结果 Book 的 tags 本就为空，
            // 点收藏时 toggleFavorite 先 saveBook 会把这本（空 tags）落库，
            // 此后任何搜索的 enrich 都因「已落库」误跳过，其标签永进不了标签库（标签抓取不全）。
            val existing = bookDao.get(b.id)
            // v5.8.0：跳过条件 = 账本行已带真实标签 或 已录入标志（v1.12.0 起以 tags 非空判定，防重复抓详情）
            if (existing?.tags?.isNotBlank() == true || ledger.hasTagsRecorded(b.id)) continue
            val tags = runCatching { getAlbumTags(b.id) }.getOrNull() ?: continue
            if (tags.isNotEmpty()) {
                val norm = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                addSearchTags(norm)
                // 回写账本行：把搜索/收藏落库的空 tags 行补成真实标签，单本与标签库都补全
                // （v1.12.0：tags 非空即视为「已录入」，无需再写独立标志）
                existing?.let { bookDao.upsert(it.copy(tags = norm.joinToString("|"))) }
                n++
            }
        }
    }

    /** 标签库响应式流（按数量降序）。 */
    fun observeTagLibrary(): Flow<List<TagLibrary>> = tagLibraryDao.observeAll()

    /**
     * 合并标签集（v5.6.1）：兴趣库 ∪ 本地书架漫画的全部标签，按出现次数降序。
     * 本地书架标签始终并入，避免可选标签过少。
     */
    fun observeMergedTagLibrary(): Flow<List<Pair<String, Int>>> =
        combine(
            tagLibraryDao.observeAll(),
            bookDao.observeAll()
        ) { lib, books ->
            val count = mutableMapOf<String, Int>()
            for ((tag, c) in lib.map { it.tag to it.count }) count[tag] = (count[tag] ?: 0) + c
            for (b in books) for (t in splitTags(b.tags)) count[t] = (count[t] ?: 0) + 1
            count.entries.sortedByDescending { it.value }.map { it.key to it.value }
            // v14 优化：合并计算（整表遍历 + 建 Map）挪出调用线程；books 表任何写入都会触发重算。
        }.flowOn(Dispatchers.Default).distinctUntilChanged()

    /** 标签库快照（按数量降序）。 */
    suspend fun getTagLibrary(): List<TagLibrary> = tagLibraryDao.getAll()

    /** 清空标签库（设置页「清空标签库」）。 */
    suspend fun clearTagLibrary() = tagLibraryDao.clearAll()

    // ── 标签相关性（4.13.0：同义词智能推荐 / 一键分组）────────

    /**
     * 构建「别名 → 规范词」映射（tag 类，lowercase 归一，排除别名==规范词的自环）。
     * v5.6.0 起所有统计/相关性计算共用本映射做同义词归一：
     * 中文 / 汉化 / 汉语 等互为同义词的标签只按一个规范词参与计算。
     */
    private suspend fun canonicalAliasMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (a in aliasDao.observeAll("tag").first()) {
            val al = a.alias.trim().lowercase()
            val c = a.canonical.trim().lowercase()
            if (al.isNotEmpty() && c.isNotEmpty() && al != c) map[al] = c
        }
        return map
    }

    /** 单标签归一：沿 alias→canonical 链传递闭包解析（a→b→c 最终归 c）；seen 防环避免死循环。 */
    private fun canonicalize(map: Map<String, String>, tag: String): String {
        var cur = tag
        val seen = HashSet<String>()
        while (true) {
            val next = map[cur]
            if (next == null) return cur
            if (!seen.add(cur)) return cur   // 出现环（a→b、b→a）：停在当前节点
            cur = next
        }
    }

    /** 计算标签两两相关性（共现次数，基于 books 表全部漫画的标签）。minScore 过滤偶发共现。
     *  v5.6.0：先把同义词归一成规范词再统计——一本漫画的多个同义词只算一个词，
     *  且不同漫画的 中文/汉化/汉语 归到同一个规范词后共现/总数合并计算。 */
    suspend fun computeTagCorrelations(minScore: Int = 2): List<BookLibrary.TagCorrelation> {
        val all = bookDao.observeAll().first()
        val aliasMap = canonicalAliasMap()
        val total = mutableMapOf<String, Int>()
        val co = mutableMapOf<Pair<String, String>, Int>()
        for (b in all) {
            val tags = splitTags(b.tags)
                .map { canonicalize(aliasMap, it) }        // 同义词 → 规范词
                .filter { !isIrrelevantTag(it) }            // 无关性按规范词判定（整组一致）
                .distinct()                                  // 一本漫画的同义词只算一次
                .sorted()
            for (t in tags) total[t] = (total[t] ?: 0) + 1
            for (i in tags.indices) for (j in i + 1 until tags.size) {
                val p = tags[i] to tags[j]
                co[p] = (co[p] ?: 0) + 1
            }
        }
        return co.entries
            .filter { it.value >= minScore }
            .map { (p, s) ->
                val (a, b) = p
                val (alias, canon) = if ((total[a] ?: 0) >= (total[b] ?: 0)) b to a else a to b
                BookLibrary.TagCorrelation(alias, canon, s)
            }
            .sortedByDescending { it.score }
    }

    /**
     * 相关标签分组（4.13.0，并查集）：把 computeTagCorrelations 的标签对两两并组，
     * 重叠自动合并（ab 同义、bc 同义 → abc 一组），组内按出现次数降序，组间按总出现次数降序。
     * 供「用户自己选择」页面按行展示，行间用分隔线隔开。
     */
    suspend fun groupCorrelatedTags(): List<List<String>> {
        val pairs = computeTagCorrelations(2)
        val parent = mutableMapOf<String, String>()
        fun find(x: String): String {
            var r = x
            while (parent[r] != r) r = parent[r]!!
            return r
        }
        for (p in pairs) {
            for (t in listOf(p.alias, p.canonical)) parent.putIfAbsent(t, t)
            val ra = find(p.alias); val rc = find(p.canonical)
            if (ra != rc) parent[rc] = ra
        }
        val total = mutableMapOf<String, Int>()
        val aliasMap = canonicalAliasMap()   // v5.6.0：组内排序总次数同样按规范词统计
        for (b in bookDao.observeAll().first())
            for (t in splitTags(b.tags)) {
                val ct = canonicalize(aliasMap, t)
                total[ct] = (total[ct] ?: 0) + 1
            }
        val groups = mutableMapOf<String, MutableList<String>>()
        for (t in parent.keys) groups.getOrPut(find(t)) { mutableListOf() }.add(t)
        return groups.values
            .map { g -> g.sortedByDescending { total[it] ?: 0 } }
            .sortedByDescending { g -> g.sumOf { total[it] ?: 0 } }
    }

    // ── 一键分类（4.13.0）：按已下载漫画标签 + 同义词组自动拟分类 ──

    /**
     * 一键分类：①取「已下载漫画」标签，按同义词（aliases tag 表）归组（无别名标签自成一组）；
     * ②统计每组命中漫画数（含组内任一标签即计入），取高频前 topN 组拟创建分类，名字=组内出现最多的标签；
     * ③对每本漫画算对各组的置信度（命中组标签数 / 组标签总数），拟归入置信度最高的组（>0 才拟）。
     */
    suspend fun proposeCategories(minBooks: Int = 3, topN: Int = 8): List<BookLibrary.CategoryProposal> {
        val books = bookDao.observeAll().first().filter { it.savedDir.isNotBlank() }
        if (books.isEmpty()) return emptyList()
        val aliasMap = mutableMapOf<String, String>()
        for (a in aliasDao.observeAll("tag").first()) {
            val al = a.alias.trim().lowercase(); val c = a.canonical.trim().lowercase()
            if (al.isNotEmpty() && c.isNotEmpty()) aliasMap[al] = c
        }
        val bookTags = books.associate { b -> b.id to splitTags(b.tags) }
        val groups = mutableMapOf<String, MutableSet<String>>()
        for (tags in bookTags.values) for (t in tags) {
            val canon = aliasMap[t] ?: t
            groups.getOrPut(canon) { mutableSetOf() }.add(t)
        }
        if (groups.isEmpty()) return emptyList()
        val tagFreq = mutableMapOf<String, Int>()
        for (tags in bookTags.values) for (t in tags) tagFreq[t] = (tagFreq[t] ?: 0) + 1
        data class G(val name: String, val tags: Set<String>, val hits: Int)
        val gs = groups.map { (canon, tags) ->
            val hits = bookTags.values.count { bt -> bt.intersect(tags).isNotEmpty() }
            val name = tags.maxByOrNull { tagFreq[it] ?: 0 } ?: canon
            G(name, tags, hits)
        }.filter { it.hits >= minBooks }.sortedByDescending { it.hits }.take(topN)
        if (gs.isEmpty()) return emptyList()
        val out = mutableListOf<BookLibrary.CategoryProposal>()
        for ((bid, bt) in bookTags) {
            var best: G? = null
            var bestConf = 0f
            for (g in gs) {
                if (g.tags.isEmpty()) continue
                val conf = bt.intersect(g.tags).size.toFloat() / g.tags.size
                if (conf > bestConf) { bestConf = conf; best = g }
            }
            best?.let { g ->
                out.add(
                    BookLibrary.CategoryProposal(
                        g.name, bid,
                        books.firstOrNull { it.id == bid }?.title ?: "JM-$bid",
                        bestConf
                    )
                )
            }
        }
        return out.sortedWith(compareByDescending<BookLibrary.CategoryProposal> { it.confidence }.thenBy { it.bookId })
    }

    /**
     * 重置喜欢样本（P2-6，3.0.0）：清空手动喜欢与手动讨厌（收藏样本是实时收藏、无需清空）。
     * 异常安全：先快照旧值，失败回滚到上一版，保证「清空」不会把数据清丢（保留上一版）。
     */
    suspend fun resetPreferenceSample(): Boolean {
        val snapManual = configProvider("manual_preferred_tags") ?: ""
        val snapHated = configProvider("hated_tags") ?: ""
        return try {
            configDao.set(SystemConfig("manual_preferred_tags", ""))
            configDao.set(SystemConfig("hated_tags", ""))
            true
        } catch (e: Exception) {
            // 保留上一版：回滚快照（各自 try，互不影响）
            runCatching { configDao.set(SystemConfig("manual_preferred_tags", snapManual)) }
            runCatching { configDao.set(SystemConfig("hated_tags", snapHated)) }
            false
        }
    }

    // ── 喜欢画像（v2.4.3：手动/计算拆分 + 主喜欢/微喜欢）──────────

    /** 主喜欢标签上限（设置 pref_max_tags，默认 10）。 */
    suspend fun getMaxPreferredTags(): Int =
        (configProvider("pref_max_tags")?.toIntOrNull()?.coerceAtLeast(1)) ?: 10

    /** 手动喜欢标签（与计算喜欢独立存储，用户显式增删）。 */
    suspend fun getManualPreferredTags(): Set<String> {
        val raw = configProvider("manual_preferred_tags") ?: return emptySet()
        return raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /** 持久化手动喜欢标签。 */
    suspend fun saveManualPreferredTags(tags: Set<String>) {
        configDao.set(SystemConfig("manual_preferred_tags", tags.distinct().joinToString(",")))
    }

    /**
     * 计算喜欢画像：计算喜欢按权重降序，前 N(=pref_max_tags) 个为主喜欢，其余隐藏为微喜欢；
     * 手动喜欢独立返回。推荐/搜索同时用三者，手动优先。
     */
    suspend fun getPreferenceProfile(): BookLibrary.PreferenceProfile {
        val maxTags = getMaxPreferredTags()
        val manual = getManualPreferredTags()
        val computed = computePreferredTags().map { it.first }
        val primary = computed.take(maxTags).toSet()
        val micro = computed.drop(maxTags).toSet()
        return BookLibrary.PreferenceProfile(manual, primary, micro)
    }

    /**
     * 喜欢标签权重表（推荐/搜索用）：手动=100（优先）、主喜欢=90、微喜欢=10。
     * 同一标签若同时出现在手动与计算集，取较高权重。
     * 全局开关 smart_association 关闭时返回空表（调用方据此退化为无权重排序）。
     */
    suspend fun getPreferredTagWeights(): Map<String, Int> {
        if (!isSmartAssociationEnabled()) return emptyMap()
        val p = getPreferenceProfile()
        val map = mutableMapOf<String, Int>()
        for (t in p.manual) map[t] = 100
        for (t in p.primary) map[t] = map[t]?.coerceAtLeast(90) ?: 90
        for (t in p.micro) map[t] = map[t]?.coerceAtLeast(10) ?: 10
        return map
    }

    // ── 书架排序（2.0.0）────────────────────────────────────────

    internal fun countPreferred(book: Book, preferred: Set<String>): Int {
        if (preferred.isEmpty()) return 0
        val tags = book.tags.split("|").map { it.trim().lowercase() }.toSet()
        return tags.count { it in preferred }
    }
}
