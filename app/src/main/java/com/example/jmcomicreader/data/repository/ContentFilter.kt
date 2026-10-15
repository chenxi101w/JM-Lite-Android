package com.example.jmreader.data.repository

import com.example.jmreader.data.local.Book

/**
 * 搜索结果客户端过滤（已下载 / 已看完的隐藏与灰显）。
 *
 * v1.17.0 轻量化：原本还管「拉黑（blacklists）」与「同义词/别名（aliases）」，
 * 随屏蔽与别名两个功能下线一并删除；对应的表由 MIGRATION_14_15 DROP。
 * 「讨厌标签」原本来自 BookTagEngine 的黑名单派生，也随之移除。
 */
class ContentFilter {
    /**
     * 对搜索结果做客户端过滤（不重新请求接口）：
     * - 已下载漫画(id 在 downloadedIds)按 hideMode 处理：hide=丢弃，gray=保留并标记为灰显；
     * - 已看完漫画(id 在 finishedIds)按 hideFinishedMode 处理：hide=丢弃，gray=保留并标记为灰显；
     *   「已下载」「已看完」两种状态独立、可叠加（看完且已下载的漫画需同时通过两道过滤）。
     * 返回 (可见结果, 灰显 id 集合)。
     */
    fun filterSearchResults(
        results: List<Book>,
        hideMode: String,
        downloadedIds: Set<Int>,
        hideFinishedMode: String = "off",
        finishedIds: Set<Int> = emptySet()
    ): Pair<List<Book>, Set<Int>> {
        if (hideMode == "off" && hideFinishedMode == "off") return results to emptySet()
        val visible = mutableListOf<Book>()
        val grayed = mutableSetOf<Int>()
        for (r in results) {
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