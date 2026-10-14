package com.example.jmreader.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 接口线路发现与容灾（v1.1.0）。
 *
 * 同源客户端把「当前可用服务器列表」放在一个公开可读的加密配置文件里（字节云对象存储），
 * 客户端拉取后在内存里随机挑一条使用；线路失效时切换下一条。这里做等价实现：
 *   1. 依次尝试 [HOST_SOURCES]，首个成功即用；
 *   2. 解密得到 `Server` 数组（形如 ["www.xxx.net", ...]）；
 *   3. 进程内缓存，遇失败调用 [failover] 切下一条。
 *
 * 实测（2026-09-29）：两条配置源均 200，解出 4 条线路且 search 接口全部可用。
 */
internal object JmApiHost {

    private const val TAG = "JmApiHost"

    /** 线路配置源（客户端常量）。 */
    private val HOST_SOURCES = listOf(
        "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt",
        "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt",
    )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 15; V2417A) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/128.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var hosts: List<String> = emptyList()

    @Volatile
    private var index: Int = 0

    private val lock = Mutex()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 已确认 TLS 不可用的线路（v1.2.8 真机实测）。
     *
     * 诊断结论：设备上逐条直连 `/album` 验证，只有 `www.cdnhjk.net` 抛
     * `SSLHandshakeException: Chain validation failed`，cause 是
     * `TrustManagerImpl.getTrustedChainForServer` —— Android 走证书里的
     * AIA「颁发者网址」去补中间证书，而该路径在当前网络下不通。
     * 另外三条（cdngwc.cc / .net / .club）TLS 正常。
     *
     * 证书本身没坏（电脑端握手成功、链完整 3 层），**因此不需要放宽证书校验** ——
     * 直接把这台设备的坏线路排除掉即可，也就不必牺牲中间人防护。
     */
    @Volatile
    private var badHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 标记线路不可用（仅限 TLS 握手类错误），后续 current()/failover() 会跳过。
     *
     * 非 suspend：内部只做集合操作与切换，不触发 `refresh()`（那是 suspend）。
     */
    fun markBad(host: String, reason: String) {
        if (host.isBlank()) return
        if (badHosts.add(host)) {
            Log.w(TAG, "线路标记为不可用并跳过：$host（$reason）")
        }
        // 若这条正是当前在用的，索引前移一格（等价于同步的 failover）
        if (hosts.isNotEmpty() && hosts.getOrNull(index) == host) {
            val n = hosts.size
            repeat(n) {
                index = (index + 1) % n
                if (hosts[index] !in badHosts) {
                    Log.w(TAG, "线路切换 → ${hosts[index]}")
                    return
                }
            }
            Log.w(TAG, "已无可用线路")
        }
    }

    /** 当前线路（未初始化时先拉配置）；无可用线路返回 null。 */
    suspend fun current(): String? {
        if (hosts.isEmpty()) refresh()
        return pickUsable()
    }

    /** 切换下一条**可用**线路（容灾）。 */
    suspend fun failover(): String? {
        if (hosts.size <= 1) return pickUsable()
        // 最多轮一圈，跳过已被标记不可用的线路
        repeat(hosts.size) {
            index = (index + 1) % hosts.size
            if (pickUsable() != null) {
                Log.w(TAG, "线路切换 → ${hosts[index % hosts.size]}")
                return pickUsable()
            }
        }
        return null
    }

    /** 在 hosts 中找一条未被标记 bad 的线路；找不到返回 null。 */
    private fun pickUsable(): String? {
        val list = hosts
        if (list.isEmpty()) return null
        for (i in list.indices) {
            val h = list[(index + i) % list.size]
            if (h !in badHosts) {
                if (i > 0) index = (index + i) % list.size
                return h
            }
        }
        return null
    }

    /** 拉取并解密线路配置；成功则替换缓存并复位到第 0 条。 */
    suspend fun refresh(): List<String> = lock.withLock {
        for (src in HOST_SOURCES) {
            val text = runCatching { fetchText(src) }.getOrNull()?.trim()
            if (text.isNullOrEmpty()) continue

            val plain = JmApiCrypto.decryptHostConfig(text) ?: continue
            val list = runCatching {
                val arr = JSONObject(plain).optJSONArray("Server") ?: return@runCatching null
                (0 until arr.length())
                    .mapNotNull { arr.optString(it).takeIf { h -> h.isNotBlank() } }
            }.getOrNull()

            if (!list.isNullOrEmpty()) {
                hosts = list
                index = 0
                Log.i(TAG, "线路已就绪：$list")
                return@withLock list
            }
        }
        Log.w(TAG, "线路配置拉取失败，沿用旧值：$hosts")
        hosts
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.string() ?: error("空响应体")
        }
    }
}
