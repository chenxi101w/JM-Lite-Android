package com.example.jmreader.data.api

import android.util.Log
import com.example.jmreader.data.image.ImageScrambler
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 同源 JSON 接口客户端（v1.1.0）。
 *
 * 直连 JM 镜像方提供的 JSON 接口，**绕开 Chaquopy/Python**：
 * 旧链路「Kotlin → Python 解释器 → jmcomic 抓 HTML → 解析 DOM」，
 * 新链路「Kotlin → OkHttp → JSON」，是搜索/详情提速的关键。
 *
 * 协议：见 [JmApiCrypto]（签名 + AES-256-ECB 响应解密）与 [JmApiHost]（线路发现）。
 * 单例客户端，遇失败自动切换线路重试；全部失败返回 null，由调用方回退 Python 链路。
 */
internal object JmApiClient {

    private const val TAG = "JmApiClient"

    /** 协议里的客户端版本号（参与 Tokenparam）。 */
    private const val APP_VERSION = "2.1.9"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 15; V2417A) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/128.0.0.0 Mobile Safari/537.36"

    /** 单次请求失败后最多换线路重试的次数。 */
    private const val MAX_TRY = 3

    /**
     * 封面 CDN 候选（按实测速度排序，取第一个）。
     * 与 API 线路解耦 —— API 那几条线路（cdnhjk/cdngwc）做封面反而更慢。
     * 保留多个便于随时切换/容灾。
     */
    private val COVER_HOSTS = listOf(
        "https://cdn-msp.jmapiproxy1.cc",
        "https://cdn-msp3.jmapiproxy1.cc",
        "https://cdn-msp2.jmapiproxy1.cc",
    )

    /** 封面缩略图后缀（比原图更小更快，列表展示足够）。 */
    private const val COVER_THUMB = "_3x4"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 关键词搜索。
     *
     * @return 命中结果（可能为空列表）；**接口不可用时返回 null**，调用方据此回退 Python 链路。
     */
    suspend fun search(keyword: String, page: Int = 1): List<Book>? {
        if (keyword.isBlank()) return null
        val json = request(
            path = "search",
            params = mapOf(
                "search_query" to keyword,
                "page" to page.toString(),
            )
        ) ?: return null

        // v1.1.5：纯数字（JM 号）查询时接口**不返回列表**，而是给出跳转目标：
        //   {"search_query":"350234","total":1,"redirect_aid":350234,"content":[]}
        // 原实现只认 content，看到空就回退 Python（1.5s），白白多等 1 秒多。
        // 这里按接口语义取 album 详情，直接返回单条结果（约 0.6s）。
        val redirectAid = json.optInt("redirect_aid", 0)
        if (redirectAid > 0) {
            val brief = albumBrief(redirectAid)
            // v1.3.2：albumBrief 为 null 时原来静默返回空列表，线上表现为"搜 JM 号没反应"。
            Log.i(TAG, "JM号搜索 redirect_aid=$redirectAid → albumBrief=${if (brief != null) "命中《${brief.title}》" else "null"}")
            return listOfNotNull(brief)
        }

        val arr = json.optJSONArray("content") ?: return emptyList()
        val books = ArrayList<Book>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            toBook(item)?.let { books += it }
        }
        return books
    }

    /** 搜索结果总条数（用于「加载更多」判定）。 */
    suspend fun searchTotal(keyword: String): Int? =
        request("search", mapOf("search_query" to keyword, "page" to "1"))
            ?.optInt("total")

    // ── 内部实现 ────────────────────────────────────────────────

    /**
     * 发一次带签名的 GET，解密响应体并返回解析后的 JSON 对象。
     * 失败会自动换线路重试，全部失败返回 null。
     */
    private suspend fun request(path: String, params: Map<String, String>): JSONObject? =
        withContext(Dispatchers.IO) {
            repeat(MAX_TRY) { attempt ->
                val host = JmApiHost.current() ?: return@withContext null
                val time = System.currentTimeMillis() / 1000

                val url = buildString {
                    append("https://").append(host).append('/').append(path).append('?')
                    val query = LinkedHashMap<String, String>()
                    query.putAll(params)
                    query["lang"] = "TW"
                    append(
                        query.entries.joinToString("&") { (k, v) ->
                            "${enc(k)}=${enc(v)}"
                        }
                    )
                }

                val result = runCatching {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", UA)
                        .header("Tokenparam", "$time,$APP_VERSION")
                        .header("Token", JmApiCrypto.signToken(time))
                        .header("Accept", "application/json, text/plain, */*")
                        .build()

                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val body = resp.body?.string() ?: error("空响应体")
                        val outer = JSONObject(body)
                        val code = outer.optInt("code", -1)
                        if (code != 200) error("code=$code")
                        val dataB64 = outer.optString("data")
                        if (dataB64.isBlank()) error("data 为空")
                        val plain = JmApiCrypto.decryptResponse(dataB64, time)
                            ?: error("解密失败")
                        JSONObject(plain)
                    }
                }

                result.getOrNull()?.let { return@withContext it }
                // v1.2.8：握手类错误说明这条线路在本机不可用 —— 标记掉，后续直接跳过，
                // 不必每次都先撞一次墙（真机实测 www.cdnhjk.net 必然 Chain validation failed）。
                val err = result.exceptionOrNull()
                val msg = err?.message.orEmpty()
                val isTls = err is javax.net.ssl.SSLException ||
                    msg.contains("Chain validation", true) ||
                    msg.contains("handshake", true) ||
                    msg.contains("certificate", true)
                if (isTls) JmApiHost.markBad(host, msg.take(40)) else JmApiHost.failover()
                Log.w(TAG, "请求失败(${msg})：$path，${if (isTls) "已标记线路不可用" else "尝试换线路"}")
            }
            null
        }

    /**
     * 取某专辑的真实标签（v1.4.0，替代 Python 的 `albumTags`）。
     *
     * 数据来自 `/album` 的 `tags` 字段（**字符串数组**）。
     * 用途：搜索结果后台补抓标签库（搜索接口不返回 tags）。
     */
    suspend fun albumTags(albumId: Int): List<String> {
        val json = request("album", mapOf("id" to albumId.toString())) ?: return emptyList()
        val arr = json.optJSONArray("tags") ?: return emptyList()
        return (0 until arr.length())
            .mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
    }

    /**
     * 取专辑详情（v1.3.4，v1.4.1 改为**单次请求**）—— 详情页与下载都用它。
     *
     * ⚠️ v1.4.0 的实现是 `albumBrief()` + `albumChapters()` **两次** `/album` 请求，
     * 任何一次失败（尤其 `albumBrief` 返回 null）就整个返回 null → 详情页与下载一起挂。
     * 现在**只请求一次**，从同一个 JSON 里同时解析书籍信息与 `series` 章节：
     *  - `series` 为空（单行本）也照样返回 Book（章节列表为空是合法状态，不是失败）。
     *
     * @return 图书 + 章节；接口不可用返回 null
     */
    suspend fun albumDetail(aid: Int): Pair<Book, List<Chapter>>? {
        val json = request("album", mapOf("id" to aid.toString())) ?: return null

        // ── 书籍信息（字段与 albumBrief 一致）──
        val name = json.optString("name").trim().ifBlank { "JM-$aid" }
        val author = json.optJSONArray("author")?.let { arr ->
            (0 until arr.length())
                .mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } }
                .joinToString(", ")
        }.orEmpty()
        val tags = json.optJSONArray("tags")?.let { arr ->
            (0 until arr.length())
                .mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } }
                .joinToString("|")
        }.orEmpty()
        val desc = if (json.isNull("description")) "" else json.optString("description").trim()
        val book = Book(
            id = aid,
            title = name,
            author = author,
            coverUrl = coverUrl(aid),
            tags = tags,
            description = desc.take(200),
            pageCount = json.optString("total_photos").toIntOrNull() ?: 0,
            savedDir = "",
            addedAt = System.currentTimeMillis(),
        )

        // ── 章节（/album 的 series；空 = 单行本，不是失败）──
        val ser = json.optJSONArray("series")
        val chapters = if (ser == null) emptyList() else (0 until ser.length()).mapNotNull { i ->
            val o = ser.optJSONObject(i) ?: return@mapNotNull null
            val cid = o.optString("id").trim().toIntOrNull() ?: return@mapNotNull null
            if (cid <= 0) return@mapNotNull null
            Chapter(
                bookId = aid,
                id = cid,
                name = o.optString("name").trim().ifBlank { "第 ${o.optInt("sort", i + 1)} 话" },
                sort = o.optInt("sort", i + 1),
                finished = 0
            )
        }

        Log.i(TAG, "albumDetail($aid) = 《${book.title}》 ${chapters.size} 章 tags=${book.tags.take(30)}")
        return book to chapters
    }

    /**
     * 按 JM 号取专辑简讯（v1.1.5）。
     *
     * 用于「搜索框里直接输 JM 号」的场景 —— 接口的 search 只给 `redirect_aid`，
     * 需要再取一次 album 才能拿到标题/作者/标签。
     * 字段说明：album 的 `author` 与 `tags` 都是**数组**，需拼接。
     */
    private suspend fun albumBrief(aid: Int): Book? {
        val json = request("album", mapOf("id" to aid.toString())) ?: return null
        val name = json.optString("name").trim().ifBlank { "JM-$aid" }
        val author = json.optJSONArray("author")?.let { arr ->
            (0 until arr.length())
                .mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } }
                .joinToString(", ")
        }.orEmpty()
        val tags = json.optJSONArray("tags")?.let { arr ->
            (0 until arr.length())
                .mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } }
                .joinToString("|")
        }.orEmpty()
        val desc = if (json.isNull("description")) "" else json.optString("description").trim()
        return Book(
            id = aid,
            title = name,
            author = author,
            coverUrl = coverUrl(aid),
            tags = tags,
            description = desc.take(200),
            pageCount = json.optString("total_photos").toIntOrNull() ?: 0,
            savedDir = "",
            addedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 封面 URL。
     *
     * v1.1.4：改用**独立的最快封面源**，不再跟随 API 线路。
     * 实测（2026-09-30，5 个样本并行取均值）：
     * | 源 | 单张 |
     * |---|---|
     * | `cdn-msp.jmapiproxy1.cc` + `_3x4` | **133 ms** ← 采用 |
     * | `cdn-msp3 / cdn-msp2.jmapiproxy1.cc` + `_3x4` | 137 / 142 ms |
     * | `www.cdnhjk.net` + 原图（原实现） | 227 ms |
     *
     * 缩略图后缀 `_3x4`（约 61KB）比原图更小也更快，列表展示足够。
     * 「搜索本身 0.3s 就好、封面却要 0.4s/张」是搜索体感差的主因，故此处单列优化。
     */
    private fun coverUrl(id: Int): String =
        "${COVER_HOSTS[0]}/media/albums/$id$COVER_THUMB.jpg"

    /** 接口条目 → [Book]（字段对齐现有数据结构；接口不返回标签，tags 留空）。 */
    private fun toBook(item: JSONObject): Book? {
        val id = item.optString("id").trim().toIntOrNull() ?: return null
        if (id <= 0) return null

        val name = item.optString("name").trim().ifBlank { "未知标题" }
        val author = item.optString("author").trim()
        val desc = if (item.isNull("description")) "" else item.optString("description").trim()

        val cover = coverUrl(id)

        return Book(
            id = id,
            title = name,
            author = author,
            coverUrl = cover,
            tags = "",
            description = desc.take(200),
            pageCount = 0,
            savedDir = "",
            addedAt = item.optLong("update_at", 0L) * 1000L,
        )
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ── v1.2.8：图片加扰参数（为「去 Python」铺路）────────────────────

    /** 取不到 scramble_id 时的兜底值，与 jmcomic 的 `SCRAMBLE_220980` 一致。 */
    private const val FALLBACK_SCRAMBLE = 220980

    /** 从 HTML 里提取 `var scramble_id = 数字;`（对应 jmcomic 的同名正则）。 */
    private val SCRAMBLE_RE = Regex("""var\s+scramble_id\s*=\s*(\d+);""")

    /**
     * 取指定图片的 `scramble_id`（图片加扰参数）。
     *
     * ⚠️ 这个端点与其它接口有两点不同（均对齐 jmcomic 的 `fetch_scramble_id`）：
     *  1. **签名密钥不同** —— 用 [JmApiCrypto.signContentToken]，不是普通的 `signToken`；
     *  2. **响应不是加密 JSON，而是 HTML 片段** —— 形如 `var scramble_id = 220980;`，
     *     因此这里不做 AES 解密，直接正则提取。
     *
     * 拿不到时返回 [FALLBACK_SCRAMBLE]（220980），与 jmcomic 的降级行为一致。
     */
    suspend fun fetchScrambleId(photoId: Int): Int = withContext(Dispatchers.IO) {
        repeat(MAX_TRY) { _ ->
            val host = JmApiHost.current() ?: return@withContext FALLBACK_SCRAMBLE
            val time = System.currentTimeMillis() / 1000
            val url = buildString {
                append("https://").append(host).append("/chapter_view_template?")
                append("id=").append(photoId)
                append("&mode=vertical&page=0&app_img_shunt=1&express=off")
                append("&v=").append(time)
                append("&lang=TW")
            }
            val result = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Tokenparam", "$time,$APP_VERSION")
                    .header("Token", JmApiCrypto.signContentToken(time))
                    .header("Accept", "*/*")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val text = resp.body?.string().orEmpty()
                    SCRAMBLE_RE.find(text)?.groupValues?.get(1)?.toIntOrNull()
                        ?: error("响应中未匹配到 scramble_id")
                }
            }
            result.getOrNull()?.let { return@withContext it }
            Log.w(TAG, "scramble_id 获取失败(${result.exceptionOrNull()?.message})，换线路重试")
            JmApiHost.failover()
        }
        FALLBACK_SCRAMBLE
    }

    /** 章节引用（取自 `/album` 的 `series` 数组）。 */
    data class ChapterRef(val id: Int, val sort: Int, val name: String)

    /** 章节详情：图片文件名数组 + 加扰参数 + CDN 域名（取自 `/chapter`）。
     *
     * @param scrambleId 图片加扰参数 —— 巧的是**本接口直接就给了**，不必再单独取。
     * @param pageArr 该章节所有图片的**文件名**（如 `00001.webp`），不是完整 URL。
     * @param domain CDN 域名，配合 photoId 拼出图片地址。
     * @param dataOriginal0 第一张图的完整 URL —— **里面含必需的 `?v=` 参数**。
     */
    data class ChapterDetail(
        val scrambleId: Int,
        val pageArr: List<String>,
        val domain: String,
        val dataOriginal0: String,
    )

    /**
     * 取专辑的章节列表（v1.2.8）。
     *
     * 对应 jmcomic 的 `JmApiAdaptTool.post_adapt_album`：`data['series']` 里每项取
     * `(id, sort, name)`，并按 `sort` 升序。
     */
    suspend fun albumChapters(albumId: Int): List<ChapterRef> {
        val json = request("album", mapOf("id" to albumId.toString())) ?: return emptyList()
        val arr = json.optJSONArray("series") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optInt("id", 0)
            if (id <= 0) return@mapNotNull null
            ChapterRef(id, o.optInt("sort", i + 1), o.optString("name").trim())
        }.sortedBy { it.sort }
    }

    /**
     * 取章节详情（v1.3.0 真机修正）。
     *
     * ⚠️ **三个字段都要自己补**，实测 `/chapter?id=331143` 的真实响应是：
     * ```json
     * {"id":331143,"series":[…],"tags":"filename",
     *  "name":"…第02話.zip","images":["00001.webp","00002.webp", …]}
     * ```
     * 即：**没有** `page_arr`（用 `images`）、**没有** `scramble_id`、
     * **没有** `data_original_domain` / `data_original_0`。补法见下方注释。
     */
    suspend fun chapterDetail(photoId: Int, albumId: Int = 0): ChapterDetail? {
        val json = request("chapter", mapOf("id" to photoId.toString())) ?: return null
        // 图片清单：images（实测）优先，回退 page_arr（其他接口版本）
        val arr = json.optJSONArray("images")
            ?: json.optJSONArray("page_arr")
            ?: runCatching {
                org.json.JSONArray(json.optString("images").ifBlank { json.optString("page_arr") })
            }.getOrNull()
            ?: return null
        val pages = (0 until arr.length())
            .mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
        if (pages.isEmpty()) return null

        // scramble_id 不在 chapter 响应里 —— jmcomic 走**另一个端点** `/chapter_view_template` 单独取
        // （见 fetch_scramble_id），并用 220980 兜底。取不到时按 jmcomic 约定用兜底值。
        //
        // ── v1.8.6：现代本子**跳过**这次请求 ────────────────────────────
        // `ImageScrambler.getNum` 的分支决定：`aid >= 268850` 时
        // `num = ord(md5(f"{aid}{name}")[-1]) % x * 2 + 2`，**公式里不含 scramble_id**。
        // 也就是说对 aid 在百万级的现代本子，这次 `/chapter_view_template` 请求
        // 对结果毫无影响 —— 而它是章节清单慢的主因（**每章多一次 HTTP 往返**）。
        // 真机实测：43 章并发拉取要 **55.5 秒**（单请求 4~8 秒），跳过这一半请求后 ≈ 减半。
        val rawScramble = json.optString("scramble_id")
        val scrambleId = rawScramble.toIntOrNull()
            ?: if (albumId > 0 && ImageScrambler.scrambleIrrelevant(albumId)) {
                FALLBACK_SCRAMBLE      // 与真实值等效，省一次网络往返
            } else {
                fetchScrambleId(photoId) ?: FALLBACK_SCRAMBLE
            }

        // data_original_domain 也不在响应里 —— jmcomic 是**随机挑一个**内置域名
        // （random.choice(DOMAIN_IMAGE_LIST)）。
        //
        // ⚠️ v1.8.4：**不要**在这里兜底成 `IMAGE_DOMAINS.first()`。
        // 这个兜底值会被 `NativeAlbumDownloader.fetchWithDomainFallback` 当成「接口指定的域名」
        // 放在候选列表**最前面** —— 于是下载器里「记住上次成功的域名」（goodDomainIndex）
        // 的机制被彻底架空：每张图都先去试列表第一个域名，失败/超时后才回退。
        // 实测该域名在真机上长时间不可用或很慢，789 张图就是 789 次无用往返，
        // 这是「下载速度时好时坏」的直接来源之一。
        // 接口没给就留空，交给下载器按 goodDomainIndex 选。
        val domain = json.optString("data_original_domain").trim()

        // data_original_0 是首图完整 URL，仅用于取 ?v= 参数；缺失时 v 用当前时间戳
        // （与 jmcomic 的 get_data_original_query_params 行为一致 —— v 缺失会返回空数据）。
        val dataOriginal0 = json.optString("data_original_0").trim()

        return ChapterDetail(
            scrambleId = scrambleId,
            pageArr = pages,
            domain = domain,
            dataOriginal0 = dataOriginal0,
        )
    }

    /**
     * 图片 CDN 域名列表（对应 jmcomic 的 `JmModuleConfig.DOMAIN_IMAGE_LIST`）。
     * 第一个用得最多；失败时由 [NativeAlbumDownloader] 换域名重试。
     */
    val IMAGE_DOMAINS = listOf(
        "cdn-msp.jmapiproxy1.cc",
        "cdn-msp.jmapiproxy2.cc",
        "cdn-msp2.jmapiproxy2.cc",
        "cdn-msp3.jmapiproxy2.cc",
        "cdn-msp.jmapinodeudzn.net",
        "cdn-msp3.jmapinodeudzn.net",
    )
}
