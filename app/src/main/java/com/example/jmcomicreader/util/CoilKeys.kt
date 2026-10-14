package com.example.jmreader.util

import android.content.Context
import coil.request.ImageRequest
import java.io.File

/**
 * 为「本地文件」构建**带文件修改时间**的图片请求（v1.8.0）。
 *
 * ── 为什么必须这么做 ──────────────────────────────────────────
 * Coil 默认用「请求数据」当缓存 key；本地文件就是**路径字符串**。
 * 而下载器修复花屏时是**原地重写同一路径**的图片 —— 路径没变，
 * 于是 Coil 认为缓存命中，**继续返回旧的花屏图**，
 * 表现为「文件已经修好了、App 里看还是坏的」，排查时极易误判成"根本没修"。
 *
 * 把 `lastModified()` 拼进 memory/disk cache key：
 *  - 文件一被重写，key 就变 → 自动失效、重新解码；
 *  - 只影响被改过的文件，其他图片仍走缓存（不像"清空整个缓存"那样伤性能）。
 */
fun fileImageRequest(context: Context, path: String): ImageRequest {
    val f = File(path)
    val stamp = runCatching { f.lastModified() }.getOrDefault(0L)
    return ImageRequest.Builder(context)
        .data(f)
        .memoryCacheKey("${f.path}#$stamp")
        .diskCacheKey("${f.path}#$stamp")
        .build()
}
