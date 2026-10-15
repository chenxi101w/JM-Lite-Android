package com.example.jmreader.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 漫画（**唯一主表 comics**，v1.12.0 数据层重构）。
 *
 * 一行 = 一本看过的漫画。原 books + comic_status + read_progress 三表合一：
 *  - 元数据快照（title/author/coverUrl/tags/description）唯一副本，删了本地也能显示；
 *  - localDir 空 = 不在本地（删本地 = 清 localDir，行永不删 → 账本即「看过的」历史）；
 *  - finished/favorite 两个 0/1 标记（原三标记里的 blocked 已随屏蔽功能删除）；
 *  - lastReadAt/lastChapterId/lastPageIndex 为阅读记忆（原 read_progress 表并入）。
 *
 * 写入规则（唯一写入点收敛在 ComicLedger）：
 *  打开阅读器→viewCount/进度/快照；标看完→finished；收藏→favorite；
 *  下载完成→localDir 填上（行不存在则建）；删除本地→清 localDir（行保留）。
 *
 * v1.17.0：删 `blocked` / `recycleAt` / `trashed` 三列（屏蔽、回收站两个功能下线），
 * 由 MIGRATION_14_15 用「建新表→拷数据→改名」12 步法重建本表（低版本 SQLite 不支持 DROP COLUMN）。
 */
@Entity(
    tableName = "comics",
    indices = [
        Index(value = ["localDir"])
    ]
)
data class Book(
    @PrimaryKey val id: Int,
    val title: String,
    val author: String,
    val coverUrl: String = "",
    @ColumnInfo(defaultValue = "") val tags: String = "",   // 以 "|" 分隔存储
    val description: String = "",
    val pageCount: Int = 0,
    /** 本地下载目录。空 = 不在本地（行保留，账本历史不丢）。 */
    @ColumnInfo(name = "localDir", defaultValue = "") val savedDir: String = "",
    val addedAt: Long = 0,                                  // 加入时间
    @ColumnInfo(defaultValue = "0") val cached: Int = 0,    // 1 = 智能缓存（自动下载、可被低频淘汰）
    @ColumnInfo(defaultValue = "0") val finished: Int = 0,  // 1 = 已看完（书架沉底；没看完的 = 剩下的）
    @ColumnInfo(defaultValue = "0") val favorite: Int = 0,  // 1 = 收藏
    @ColumnInfo(defaultValue = "0") val viewCount: Int = 0, // 打开阅读器次数（唯一副本）
    @ColumnInfo(defaultValue = "0") val lastReadAt: Long = 0,   // 最后打开阅读器时间 ms
    @ColumnInfo(defaultValue = "0") val lastChapterId: Int = 0, // 最后读到的章节（原 read_progress）
    @ColumnInfo(defaultValue = "0") val lastPageIndex: Int = 0  // 最后读到的页（原 read_progress）
)

/**
 * 章节表（只存**多章**漫画的章节清单；单章漫画 photoId≡bookId，不建行，v1.12.0）。
 * finished=1 表示该章已读完（阅读器读到末尾自动标记），多章全读完 → 整本 finished=1。 */
@Entity(tableName = "chapters", primaryKeys = ["bookId", "id"])
data class Chapter(
    val bookId: Int,
    val id: Int,
    val name: String,
    val sort: Int,
    val finished: Int = 0
)

/** 下载任务状态。 */
@Entity(tableName = "downloads", primaryKeys = ["bookId"])
data class Download(
    val bookId: Int,
    val status: String = "QUEUED",   // QUEUED | DOWNLOADING | DONE | FAILED
    val progress: Int = 0,           // 已完成章节数
    val total: Int = 0,
    val savedDir: String = "",
    val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val hidden: Int = 0    // 1 = 下载完成后按设置自动隐藏（download_after=hide）
)

// ─── 配置 / 历史 ──────────────────────────────────────────────

/** 全局键值配置（代理 / 画质 / 并发 / 搜索上限等）。 */
@Entity(tableName = "system_config")
data class SystemConfig(
    @PrimaryKey val key: String,
    val value: String = "",
    val description: String = ""
)

/** 搜索历史。索引服务于 getAggregated / observeAggregated 的 GROUP BY type, content。 */
@Entity(tableName = "search_history", indices = [Index(value = ["type", "content"])])
data class SearchHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: String,
    val content: String,
    val count: Int = 0,
    val time: Long = System.currentTimeMillis()
)

/** 搜索历史聚合结果（GROUP BY type, content）。 */
data class SearchHistoryAgg(
    val type: String,
    val content: String,
    val freq: Int,
    val lastTime: Long
)
