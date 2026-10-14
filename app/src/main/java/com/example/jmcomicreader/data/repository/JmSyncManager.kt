package com.example.jmreader.data.repository

import android.util.Log
import com.example.jmreader.data.local.AppDatabase
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.SystemConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 云同步引擎（v1.10.0）：把「看过的漫画 JM 号」与用户自有服务器双向合并。
 *
 * 服务器：阿里云 ECS 上的纯标准库 HTTP 服务（/opt/jm-sync/server.py，端口 8765）。
 *   - GET  /pull?token=T   → {"count":N,"detail":[{id,added_at,last_read_at}...]}
 *   - POST /push?token=T   body {"ids":[...]} → {"ok":true,"received":N,"new":M}
 *   - INSERT OR IGNORE 幂等入库；token 常量比较鉴权；服务器不存任何书名/文本（隐私最小化）。
 *
 * 合并语义（双向、幂等、可重跑）：
 *   - 入：服务器有、本地账本无 → 落一张最小账本行（只有 id + lastReadAt，title/cover 留空——
 *     同步的是「看过的 JM 号」不是完整书架；要看哪本直接搜 JM 号，详情会重新落库）。
 *   - 出：本地账本全集（comics 全表 id）全量 push（服务器幂等，重复 push 无副作用）。
 *     v1.11.1 曾按 PUSH_MASK 只推实质号；v1.12.0 单表后行即「看过」，全表推送口径天然一致。
 *   - 本地为权威：本地已有的行绝不被服务器数据覆盖。
 *
 * 配置（system_config 键值表，无 DB 迁移）：
 *   sync_enabled / sync_server_url / sync_token / sync_last_at / sync_last_result
 */
class JmSyncManager(private val db: AppDatabase) {

    companion object {
        const val KEY_ENABLED = "sync_enabled"
        const val KEY_URL = "sync_server_url"
        const val KEY_TOKEN = "sync_token"
        const val KEY_LAST_AT = "sync_last_at"
        const val KEY_LAST_RESULT = "sync_last_result"

        /** 自动同步节流：距上次成功同步超过 6 小时才在启动时静默跑（手动「立即同步」不受限）。 */
        private const val AUTO_SYNC_INTERVAL_MS = 6L * 60 * 60 * 1000
        private const val TAG = "JmSync"
    }

    data class SyncResult(
        val pulled: Int,       // 从服务器拉回、本地新增的状态行数
        val pushed: Int,       // 本地推送给服务器的 JM 号总数
        val newOnServer: Int,  // 服务器端实际新增（响应里的 new）
        val serverTotal: Int   // 同步完成后服务器账本总数
    ) {
        fun describe(): String =
            "拉回 $pulled · 推上 $pushed（服务器新增 $newOnServer，账本共 $serverTotal）"
    }

    private val bookDao = db.bookDao()
    private val configDao = db.systemConfigDao()

    /** 重入保护：同步是全量幂等操作，但并发跑两次浪费且结果列会互相踩，串行化。 */
    private val mutex = Mutex()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ── 配置读写 ─────────────────────────────────────────────

    private suspend fun cfg(key: String): String =
        configDao.get(key) ?: ""

    suspend fun isEnabled(): Boolean = cfg(KEY_ENABLED) == "1"

    /** 同步配置是否完整（地址 + 密钥都非空）。 */
    suspend fun isConfigured(): Boolean {
        val url = cfg(KEY_URL).trim().trimEnd('/')
        val token = cfg(KEY_TOKEN).trim()
        return url.startsWith("http") && token.isNotBlank()
    }

    suspend fun setConfigValue(key: String, value: String) {
        configDao.set(SystemConfig(key, value))
    }

    // ── 同步入口 ─────────────────────────────────────────────

    /**
     * 立即双向同步（设置页「立即同步」按钮）。
     * @return Result包裹的 SyncResult；失败时 message 已是用户可读的中文描述。
     */
    suspend fun syncNow(): Result<SyncResult> = mutex.withLock {
        runCatching { doSync() }
            .onSuccess { writeLastResult("✅ ${it.describe()}") }
            .onFailure { writeLastResult("❌ ${it.message ?: "同步失败"}") }
    }

    /**
     * 启动时静默自动同步：启用 + 配置完整 + 距上次成功 ≥6h 才跑。
     * 任何失败只记日志，绝不影响启动流程。
     */
    suspend fun autoSyncIfDue() {
        runCatching {
            if (!isEnabled() || !isConfigured()) return
            val last = cfg(KEY_LAST_AT).toLongOrNull() ?: 0L
            // 只有「成功同步」才会写 sync_last_at（doSync 成功路径），失败重试不受节流限制
            if (System.currentTimeMillis() - last < AUTO_SYNC_INTERVAL_MS) return
            val r = mutex.withLock { doSync() }
            writeLastResult("✅ ${r.describe()}")
            Log.i(TAG, "启动自动同步完成：${r.describe()}")
        }.onFailure { Log.w(TAG, "启动自动同步失败（静默忽略）", it) }
    }

    /** 核心：pull → 合并入 → push → 记账。持锁调用（syncNow/autoSyncIfDue 已锁）。 */
    private suspend fun doSync(): SyncResult = withContext(Dispatchers.IO) {
        val url = cfg(KEY_URL).trim().trimEnd('/')
        val token = cfg(KEY_TOKEN).trim()
        if (!url.startsWith("http") || token.isBlank()) {
            error("未配置服务器地址或密钥（设置 → 书架 → 云同步）")
        }

        // 1) pull：服务器账本全量
        val serverRows = pull(url, token)

        // 2) 合并入：服务器有、本地无 → 落最小账本行（本地已有的行绝不动）
        val localIds = bookDao.getAllBookIds().toHashSet()
        var pulled = 0
        for (row in serverRows) {
            if (row.id !in localIds) {
                bookDao.upsert(
                    Book(
                        id = row.id,
                        title = "",
                        author = "",
                        // 服务器时间戳是秒 → 本地毫秒；服务器当前恒存 0，字段留作未来扩展
                        lastReadAt = row.lastReadAt * 1000L
                    )
                )
                pulled++
            }
        }

        // 3) 合并出：账本全表（行即「看过」，v1.12.0 单表后无需再按位掩码过滤；服务器幂等）
        val pushIds = bookDao.getAllBookIds()
        val newOnServer = push(url, token, pushIds)

        // 4) 记账：成功才写 sync_last_at（autoSyncIfDue 的节流依据）
        configDao.set(SystemConfig(KEY_LAST_AT, System.currentTimeMillis().toString()))
        SyncResult(
            pulled = pulled,
            pushed = pushIds.size,
            newOnServer = newOnServer,
            serverTotal = serverRows.size + newOnServer   // 同步前账本 + 本次新增
        )
    }

    private suspend fun writeLastResult(msg: String) {
        runCatching { configDao.set(SystemConfig(KEY_LAST_RESULT, msg)) }
    }

    // ── HTTP ─────────────────────────────────────────────────

    private data class ServerRow(val id: Int, val addedAt: Long, val lastReadAt: Long)

    private class HttpError(message: String, val code: Int) : Exception(message)

    /** GET /pull → 解析 detail。非 200 抛 HttpError（403 → 密钥不对）。 */
    private suspend fun pull(url: String, token: String): List<ServerRow> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$url/pull?token=$token")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw httpError(resp.code, body)
                val json = JSONObject(body)
                val detail = json.optJSONArray("detail") ?: JSONArray()
                (0 until detail.length()).mapNotNull { i ->
                    detail.optJSONObject(i)?.let {
                        ServerRow(
                            id = it.optInt("id"),
                            addedAt = it.optLong("added_at"),
                            lastReadAt = it.optLong("last_read_at")
                        )
                    }
                }
            }
        }

    /** POST /push {"ids":[...]} → 返回服务器实际新增数（new）。 */
    private suspend fun push(url: String, token: String, ids: List<Int>): Int =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext 0
            val arr = JSONArray()
            ids.forEach { arr.put(it) }
            val body = JSONObject().put("ids", arr).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("$url/push?token=$token")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw httpError(resp.code, text)
                JSONObject(text).optInt("new", 0)
            }
        }

    private fun httpError(code: Int, body: String): HttpError = when (code) {
        403 -> HttpError("密钥不对（服务器拒绝访问）", code)
        else -> HttpError("服务器返回 $code${if (body.isBlank()) "" else "：${body.take(80)}"}", code)
    }
}
