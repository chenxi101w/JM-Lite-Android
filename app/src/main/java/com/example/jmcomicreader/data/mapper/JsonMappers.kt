package com.example.jmreader.data.mapper

import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.Chapter
import com.example.jmreader.util.toStringList
import org.json.JSONObject

/** JSONObject -> Book（搜索 / 详情共用，消除 JmRepository 中重复的解析样板）。 */
fun JSONObject.toBook(addedAt: Long = 0L): Book = Book(
    id = optInt("id", 0),
    title = optString("title", "未知标题"),
    author = optString("author", ""),
    coverUrl = optString("cover", ""),
    tags = optJSONArray("tags")?.toStringList().orEmpty().joinToString("|"),
    description = optString("description", ""),
    pageCount = optInt("pages", 0),
    addedAt = addedAt
)

/** JSONObject -> Chapter（绑定到所属 bookId）。 */
fun JSONObject.toChapter(bookId: Int): Chapter = Chapter(
    bookId = bookId,
    id = optInt("id", 0),
    name = optString("name", ""),
    sort = optInt("sort", 0)
)
