package com.example.jmreader.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Upsert
    suspend fun upsert(book: Book)

    @Query("SELECT * FROM comics ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Book>>

    /**
     * 已下载漫画（书架数据源）。命中 index_comics_localDir。
     * v1.12.0：books → comics 单表，savedDir 列更名 localDir。
     */
    @Query("SELECT * FROM comics WHERE localDir <> '' ORDER BY addedAt DESC")
    fun observeDownloaded(): Flow<List<Book>>

    /** 已下载 id 集合流（搜索页「已下载屏蔽」判断）。 */
    @Query("SELECT id FROM comics WHERE localDir <> ''")
    fun observeDownloadedIds(): Flow<List<Int>>

    /** 收藏 id 集合（搜索页/书架收藏态高亮）。 */
    @Query("SELECT id FROM comics WHERE favorite = 1")
    fun observeFavoriteIds(): Flow<List<Int>>

    /** 已看完 id 集合（书架沉底排序）。 */
    @Query("SELECT id FROM comics WHERE finished = 1")
    fun observeFinishedIds(): Flow<List<Int>>

    @Query("SELECT id FROM comics WHERE favorite = 1")
    suspend fun getFavoriteIds(): List<Int>

    @Query("SELECT id FROM comics WHERE finished = 1")
    suspend fun getFinishedIds(): List<Int>

    @Query("SELECT id FROM comics WHERE localDir <> ''")
    suspend fun getDownloadedIds(): List<Int>

    @Query("SELECT * FROM comics WHERE id = :id")
    suspend fun get(id: Int): Book?

    /** 真删行（账本清理入口用；「删除本地」应使用 setLocalDir(id, "") 保留账本行）。 */
    @Delete
    suspend fun delete(book: Book)

    // ── v1.12.0：单一写入点（ComicLedger）所需的定向 UPDATE ──

    /** 下载完成 / 删除本地：更新本地目录。行不存在由调用方先 upsert 保证。 */
    @Query("UPDATE comics SET localDir = :dir WHERE id = :id")
    suspend fun setLocalDir(id: Int, dir: String)

    /** 打开阅读器：viewCount+1 并刷新最后阅读时间。 */
    @Query("UPDATE comics SET viewCount = viewCount + 1, lastReadAt = :ts WHERE id = :id")
    suspend fun bumpViewCount(id: Int, ts: Long)

    /** 阅读进度（原 read_progress 表写入点）。 */
    @Query("UPDATE comics SET lastChapterId = :chapterId, lastPageIndex = :page, lastReadAt = :ts WHERE id = :id")
    suspend fun updateProgress(id: Int, chapterId: Int, page: Int, ts: Long)

    @Query("UPDATE comics SET finished = :v WHERE id = :id")
    suspend fun setFinished(id: Int, v: Int)

    @Query("UPDATE comics SET favorite = :v WHERE id = :id")
    suspend fun setFavorite(id: Int, v: Int)

    @Query("UPDATE comics SET blocked = :v WHERE id = :id")
    suspend fun setBlocked(id: Int, v: Int)

    /** 同步 push 源：账本全集 id（看过即账本）。 */
    @Query("SELECT id FROM comics")
    suspend fun getAllBookIds(): List<Int>

    /** 已读（进过阅读器）的 bookId 全集：原 read_progress 有行 = 已读 → viewCount/lastReadAt 非零。 */
    @Query("SELECT id FROM comics WHERE viewCount > 0 OR lastReadAt > 0")
    suspend fun getReadBookIds(): List<Int>

    /** 账本全量快照（作者拉黑批量同步 / 回收站到期扫描用）。 */
    @Query("SELECT * FROM comics")
    suspend fun getAll(): List<Book>

    /** 回收站列表流（宽限中或已删本地的行，原 comic_status.ext JSON 遍历改 SQL 直推）。 */
    @Query("SELECT * FROM comics WHERE recycleAt > 0 OR trashed = 1")
    fun observeTrashedRows(): Flow<List<Book>>

    /** 清空阅读画像①：删「不在本地且未收藏」的纯账本行（浏览痕迹）。 */
    @Query("DELETE FROM comics WHERE localDir = '' AND favorite = 0")
    suspend fun deleteLedgerOnlyRows()

    /** 清空阅读画像②：在架/收藏行清阅读记忆（保留行与 finished/favorite/blocked 标记）。 */
    @Query(
        "UPDATE comics SET viewCount = 0, lastReadAt = 0, lastChapterId = 0, lastPageIndex = 0, " +
            "finished = 0 WHERE localDir <> '' OR favorite = 1"
    )
    suspend fun clearReadingMemory()
}

@Dao
interface ChapterDao {
    @Upsert
    suspend fun upsertAll(chapters: List<Chapter>)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY sort ASC")
    fun observe(bookId: Int): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY sort ASC")
    suspend fun get(bookId: Int): List<Chapter>

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun delete(bookId: Int)

    @Query("UPDATE chapters SET finished = 1 WHERE bookId = :bookId AND id = :chapterId")
    suspend fun markFinished(bookId: Int, chapterId: Int)

    @Query("UPDATE chapters SET finished = 0 WHERE bookId = :bookId AND id = :chapterId")
    suspend fun markUnfinished(bookId: Int, chapterId: Int)

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND finished = 1")
    suspend fun countFinished(bookId: Int): Int

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND finished = 1")
    suspend fun getFinished(bookId: Int): List<Chapter>

    /**
     * v1.12.0（vc71 修正点）：清理单章漫画旧章节残留。
     * 旧版单行本按 id=sort（=1）落库；v1.12.0 新约定单章不入 chapters 表、进度 chapterId=bookId。
     * 残留危害：离线详情返回旧「第 1 话」（id=1）且与进度（chapterId=bookId）匹配不上。
     * 命中条件：某 bookId 仅 1 行且该行 id≠bookId → 删除；删后离线打开自动触发
     * rebuildChaptersFromLocal 按「全一话」（id=bookId）重建，在线打开走 getDetail 亦正确。
     * 多章书（行数>1）不受影响。幂等。
     */
    @Query(
        "DELETE FROM chapters WHERE bookId IN " +
            "(SELECT bookId FROM chapters GROUP BY bookId HAVING COUNT(*) = 1 AND MIN(id) <> bookId)"
    )
    suspend fun deleteLegacySingleChapterRows(): Int
}

@Dao
interface DownloadDao {
    @Upsert
    suspend fun upsert(download: Download)

    @Query("SELECT * FROM downloads WHERE bookId = :bookId")
    suspend fun get(bookId: Int): Download?

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Download>>

    @Query("DELETE FROM downloads WHERE bookId = :bookId")
    suspend fun delete(bookId: Int)

    /** 清空全部下载记录（下载管理「清空历史记录」；不影响本地书架与已下载内容）。 */
    @Query("DELETE FROM downloads")
    suspend fun clearAll()
}

// ─── 配置 / 过滤 / 分类 / 历史 DAO（对齐 PC 2.4.9）──────────────

@Dao
interface SystemConfigDao {
    @Query("SELECT value FROM system_config WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM system_config")
    suspend fun getAll(): List<SystemConfig>

    @Query("SELECT * FROM system_config")
    fun observeAll(): Flow<List<SystemConfig>>

    @Upsert
    suspend fun set(config: SystemConfig)
}

@Dao
interface BlockDao {
    @Query("SELECT * FROM blocks ORDER BY id DESC")
    fun observeAll(): Flow<List<Block>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(block: Block): Long

    @Delete
    suspend fun delete(block: Block)

    @Query("DELETE FROM blocks WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT * FROM blocks WHERE type = :type AND value = :value LIMIT 1")
    suspend fun findByValue(type: String, value: String): Block?
}

@Dao
interface BlacklistDao {
    @Query("SELECT * FROM blacklists ORDER BY id DESC")
    fun observeAll(): Flow<List<Blacklist>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(blacklist: Blacklist): Long

    @Query("DELETE FROM blacklists WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT * FROM blacklists WHERE type = :type AND value = :value LIMIT 1")
    suspend fun findByValue(type: String, value: String): Blacklist?

    @Query("SELECT * FROM blacklists WHERE id = :id")
    suspend fun getById(id: Int): Blacklist?
}

@Dao
interface AliasDao {
    @Query("SELECT * FROM aliases WHERE type = :type ORDER BY canonical, `alias`")
    fun observeAll(type: String): Flow<List<Alias>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alias: Alias): Long

    @Delete
    suspend fun delete(alias: Alias)

    @Query("DELETE FROM aliases WHERE id = :id")
    suspend fun deleteById(id: Int)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<Category>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: Int): Category?
}

@Dao
interface ComicCategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ComicCategory>)

    @Query("DELETE FROM comic_categories WHERE comicJmId = :comicJmId")
    suspend fun deleteForComic(comicJmId: Int)

    @Query("DELETE FROM comic_categories WHERE categoryId = :categoryId")
    suspend fun deleteForCategory(categoryId: Int)

    @Query("SELECT categoryId FROM comic_categories WHERE comicJmId = :comicJmId")
    suspend fun getForComic(comicJmId: Int): List<Int>

    @Query("SELECT categoryId FROM comic_categories WHERE comicJmId = :comicJmId")
    fun observeForComic(comicJmId: Int): Flow<List<Int>>

    @Query("SELECT * FROM comic_categories")
    fun observeAll(): Flow<List<ComicCategory>>

    @Query("SELECT comicJmId FROM comic_categories WHERE categoryId = :categoryId")
    suspend fun getComicsForCategory(categoryId: Int): List<Int>
}

@Dao
interface SearchHistoryDao {
    @Insert
    suspend fun insert(history: SearchHistory)

    @Query("SELECT * FROM search_history ORDER BY time DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SearchHistory>

    @Query(
        "SELECT type, content, COUNT(*) AS freq, MAX(time) AS lastTime " +
        "FROM search_history GROUP BY type, content " +
        "ORDER BY freq DESC, lastTime DESC LIMIT :limit"
    )
    suspend fun getAggregated(limit: Int): List<SearchHistoryAgg>

    @Query(
        "SELECT type, content, COUNT(*) AS freq, MAX(time) AS lastTime " +
        "FROM search_history GROUP BY type, content " +
        "ORDER BY freq DESC, lastTime DESC LIMIT :limit"
    )
    fun observeAggregated(limit: Int): Flow<List<SearchHistoryAgg>>
}

@Dao
interface TagLibraryDao {
    /** 搜索命中某标签：首次插入 count=1，已有则 count+1、刷新 lastSeenAt（每本漫画标签各+1）。 */
    @Query(
        "INSERT INTO tag_library(tag, count, lastSeenAt) VALUES(:tag, 1, :ts) " +
            "ON CONFLICT(tag) DO UPDATE SET count = count + 1, lastSeenAt = :ts"
    )
    suspend fun bump(tag: String, ts: Long)

    /** 标签库响应流派生（按数量降序，次级按最近命中）。 */
    @Query("SELECT * FROM tag_library ORDER BY count DESC, lastSeenAt DESC")
    fun observeAll(): Flow<List<TagLibrary>>

    /** 标签库快照（按数量降序）。 */
    @Query("SELECT * FROM tag_library ORDER BY count DESC, lastSeenAt DESC")
    suspend fun getAll(): List<TagLibrary>

    /** 清空标签库（设置页「清空标签库」）。 */
    @Query("DELETE FROM tag_library")
    suspend fun clearAll()
}

@Dao
interface TagLibraryExpDao {
    /** 实验库写入：首次插入 count=1，已有则 count+1、刷新 lastSeenAt。 */
    @Query(
        "INSERT INTO tag_library_exp(tag, count, lastSeenAt) VALUES(:tag, 1, :ts) " +
            "ON CONFLICT(tag) DO UPDATE SET count = count + 1, lastSeenAt = :ts"
    )
    suspend fun bump(tag: String, ts: Long)

    /** 实验库响应流（按数量降序）。 */
    @Query("SELECT * FROM tag_library_exp ORDER BY count DESC, lastSeenAt DESC")
    fun observeAll(): Flow<List<TagLibraryExp>>

    /** 实验库快照。 */
    @Query("SELECT * FROM tag_library_exp ORDER BY count DESC, lastSeenAt DESC")
    suspend fun getAll(): List<TagLibraryExp>

    /** 清空实验库（重建用）。 */
    @Query("DELETE FROM tag_library_exp")
    suspend fun clearAll()

    /** 实验库标签总数（管理页展示）。 */
    @Query("SELECT COUNT(*) FROM tag_library_exp")
    suspend fun count(): Int
}

@Database(
    entities = [
        Book::class, Chapter::class, Download::class,
        SystemConfig::class, Block::class, Alias::class, Blacklist::class,
        Category::class, ComicCategory::class, SearchHistory::class,
        TagLibrary::class, TagLibraryExp::class, Subscription::class, SubscriptionUpdate::class,
    ],
    version = 14,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun downloadDao(): DownloadDao
    abstract fun systemConfigDao(): SystemConfigDao
    abstract fun blockDao(): BlockDao
    abstract fun aliasDao(): AliasDao
    abstract fun blacklistDao(): BlacklistDao
    abstract fun categoryDao(): CategoryDao
    abstract fun comicCategoryDao(): ComicCategoryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun tagLibraryDao(): TagLibraryDao
    abstract fun tagLibraryExpDao(): TagLibraryExpDao
    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

    /**
     * v2 → v3：新增 books.viewCount / books.cached / downloads.hidden，仅 ADD COLUMN，保留用户数据。
     * 注意：列名必须与实体字段名**逐字一致**（Room 默认不做 snake_case 转换），
     * 否则升级时 Room 校验 schema 不一致会抛 IllegalStateException 直接崩溃。
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN viewCount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE books ADD COLUMN cached INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE downloads ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v3 → v4：新增 books.finished（已看完标记，书架排序沉底用），仅 ADD COLUMN，保留用户数据。
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN finished INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v4 → v5：新增 books.favorite（收藏标记，书架「收藏」分类入口用），仅 ADD COLUMN，保留用户数据。
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v5 → v6：新增 blacklists 表（拉黑=讨厌标记，不参与搜索过滤；供喜欢/讨厌算法用）。
     * 新表 CREATE，不影响既有数据。
     */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `blacklists` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`value` TEXT NOT NULL)"
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_blacklists_type_value` ON `blacklists` (`type`, `value`)")
        }
    }

    /**
     * v6 → v7：新增 chapters.finished（章节读完标记，读了几章标几），仅 ADD COLUMN，保留数据。
     */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chapters ADD COLUMN finished INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v7 → v8：地基（位掩码方案）。
     * 1) books 增加 downloaded_ever（legacy 兼容列，新逻辑不再写入，仅保留供安全迁移；
     *    列名逐字为 `downloaded_ever`，否则 Room 校验 schema 不一致会崩溃）。
     * 2) 新建 comic_status 表（漫画关系状态位掩码登记表，jm 号主键，删除本地不删行），新表 CREATE，不影响既有数据。
     * 全程仅 ADD COLUMN + CREATE TABLE，禁用 fallbackToDestructiveMigration 的静默清数据（发版铁律）。
     */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN downloaded_ever INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `comic_status` (" +
                    "`bookId` INTEGER NOT NULL, " +
                    "`status` INTEGER NOT NULL DEFAULT 0, " +
                    "`title` TEXT NOT NULL DEFAULT '', " +
                    "`author` TEXT NOT NULL DEFAULT '', " +
                    "`coverUrl` TEXT NOT NULL DEFAULT '', " +
                    "`tags` TEXT NOT NULL DEFAULT '', " +
                    "`viewCount` INTEGER NOT NULL DEFAULT 0, " +
                    "`lastReadAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`ext` TEXT NOT NULL DEFAULT '', " +
                    "PRIMARY KEY(`bookId`))"
            )
        }
    }

    /**
     * v8 → v9：把 v2.5.0 早期的 read_samples 方案切换为 comic_status 位掩码登记表。
     * - 删除已废弃的 read_samples 表（若此前某次构建已建则清掉，否则无操作）；
     * - comic_status 表已在 v7→v8 新建，Room 按版本顺序逐级升级保证其先于本迁移存在，故此处不再重复建表。
     * 仅 DROP，保留其它表数据。
     */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `read_samples`")
        }
    }

    /**
     * v9 → v10：新增 tag_library 表（标签库，从搜索结果累积标签并按数量排序）。
     * 新表 CREATE，不影响既有数据；仅 ADD COLUMN/CREATE TABLE，沿用发版铁律禁用破坏性迁移。
     */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `tag_library` (" +
                    "`tag` TEXT NOT NULL, " +
                    "`count` INTEGER NOT NULL DEFAULT 0, " +
                    "`lastSeenAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`tag`))"
            )
        }
    }

    /**
     * v10 → v11：新增 subscriptions 表（作者订阅体系，P1-4）。
     * 新表 CREATE，不影响既有数据；仅 ADD COLUMN/CREATE TABLE，沿用发版铁律禁用破坏性迁移。
     */
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `subscriptions` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`author` TEXT NOT NULL DEFAULT '', " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`lastCheckedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`lastUpdateAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`newCount` INTEGER NOT NULL DEFAULT 0, " +
                    "`lastTitle` TEXT NOT NULL DEFAULT '')"
            )
        }
    }

    /**
     * v11 → v12：新增 sub_updates 表（订阅新作记录，P1-4，4.0.0）。
     * 新表 CREATE，不影响既有数据；仅 ADD COLUMN/CREATE TABLE，沿用发版铁律禁用破坏性迁移。
     */
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sub_updates` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`author` TEXT NOT NULL DEFAULT '', " +
                    "`bookId` INTEGER NOT NULL DEFAULT 0, " +
                    "`title` TEXT NOT NULL DEFAULT '', " +
                    "`coverUrl` TEXT NOT NULL DEFAULT '', " +
                    "`foundAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`seen` INTEGER NOT NULL DEFAULT 0)"
            )
        }
    }

    /**
     * v12 → v13：新增 tag_library_exp 表（实验标签库，v5.10.0，与 tag_library 同构、完全独立）。
     * 新表 CREATE，不影响既有数据；沿用发版铁律禁用破坏性迁移。
     */
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `tag_library_exp` (" +
                    "`tag` TEXT NOT NULL, " +
                    "`count` INTEGER NOT NULL DEFAULT 0, " +
                    "`lastSeenAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`tag`))"
            )
        }
    }

    /**
     * v13 → v14：数据层单表重构（v1.12.0）——books + comic_status + read_progress 三表合一 → `comics`。
     *
     * 语义（docs/重构方案/数据层重构设计.md）：
     *  - 「看过」= 账本最大类，行永不因删本地而删；收藏/看完/拉黑都在看过范围内；
     *  - 「下载过」降级为物理状态 localDir（空 = 不在本地）；「没看完」= 看完的补集，无标记列；
     *  - 云同步 push = comics 全表 id。
     *
     * 迁移规则（账本为准、只置位不误删）：
     *  ① books 全量迁入（savedDir→localDir，finished/favorite 直搬，blocked=0）；
     *  ② comic_status 实质号（status & 15 ≠ 0；BIT_LOCAL=1/BIT_FINISHED=2/BIT_DOWNLOADED=4/
     *     BIT_FAVORITE=8）无 books 行的补建行（元数据取账本，localDir=''；仅拉黑未看过的丢弃）；
     *  ③ 有账本行的五列并回取 MAX（IFNULL 包裹防 NULL 传染违反 NOT NULL）；
     *  ④ read_progress 并入进度三列（有行 UPDATE / 无行 INSERT 补建，updatedAt → lastReadAt）；
     *  ⑤ 单章漫画清出 chapters（唯一行且章节 id == bookId，JM 单行本 photoId≡bookId 特征）；
     *  ⑥ DROP 旧三表。表重建模式，不依赖 DROP COLUMN（minSdk24 老设备 SQLite 3.9 安全）。
     */
    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ① 新表（列集/默认值与 @Entity 逐字一致，否则 Room 启动校验抛 IllegalStateException）
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `comics` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`author` TEXT NOT NULL, " +
                    "`coverUrl` TEXT NOT NULL, " +
                    "`tags` TEXT NOT NULL DEFAULT '', " +
                    "`description` TEXT NOT NULL, " +
                    "`pageCount` INTEGER NOT NULL, " +
                    "`localDir` TEXT NOT NULL DEFAULT '', " +
                    "`addedAt` INTEGER NOT NULL, " +
                    "`cached` INTEGER NOT NULL DEFAULT 0, " +
                    "`finished` INTEGER NOT NULL DEFAULT 0, " +
                    "`favorite` INTEGER NOT NULL DEFAULT 0, " +
                    "`blocked` INTEGER NOT NULL DEFAULT 0, " +
                    "`viewCount` INTEGER NOT NULL DEFAULT 0, " +
                    "`lastReadAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`lastChapterId` INTEGER NOT NULL DEFAULT 0, " +
                    "`lastPageIndex` INTEGER NOT NULL DEFAULT 0, " +
                    "`recycleAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`trashed` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_comics_localDir` ON `comics` (`localDir`)")
            // search_history 实体声明了该索引，老库若缺必须补齐，否则 Room 启动校验崩溃（幂等）
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_search_history_type_content` " +
                    "ON `search_history` (`type`, `content`)"
            )

            // ② books 全量迁入
            db.execSQL(
                "INSERT INTO comics (id, title, author, coverUrl, tags, description, pageCount, localDir, " +
                    "addedAt, cached, finished, favorite, blocked, viewCount, lastReadAt, lastChapterId, lastPageIndex) " +
                    "SELECT id, title, author, coverUrl, tags, description, pageCount, savedDir, " +
                    "addedAt, cached, finished, favorite, 0, viewCount, 0, 0, 0 FROM books"
            )

            // ③ 账本实质号补行（books 已迁入的行被 INSERT OR IGNORE 跳过，幂等）
            db.execSQL(
                "INSERT OR IGNORE INTO comics (id, title, author, coverUrl, tags, description, pageCount, " +
                    "localDir, addedAt, cached, finished, favorite, blocked, viewCount, lastReadAt, " +
                    "lastChapterId, lastPageIndex) " +
                    "SELECT bookId, title, author, coverUrl, tags, '', 0, '', 0, 0, " +
                    "CASE WHEN (status & 2) != 0 THEN 1 ELSE 0 END, " +
                    "CASE WHEN (status & 8) != 0 THEN 1 ELSE 0 END, " +
                    "0, viewCount, lastReadAt, 0, 0 " +
                    "FROM comic_status WHERE (status & 15) != 0"
            )

            // ④ 账本并回取 MAX（与 books 旧列合并，修复「books 副本落后于账本」的历史漂移）
            db.execSQL(
                "UPDATE comics SET " +
                    "finished = MAX(finished, IFNULL((SELECT CASE WHEN (status & 2) != 0 THEN 1 ELSE 0 END " +
                        "FROM comic_status WHERE bookId = comics.id), 0)), " +
                    "favorite = MAX(favorite, IFNULL((SELECT CASE WHEN (status & 8) != 0 THEN 1 ELSE 0 END " +
                        "FROM comic_status WHERE bookId = comics.id), 0)), " +
                    "blocked = MAX(blocked, IFNULL((SELECT CASE WHEN (status & 16) != 0 THEN 1 ELSE 0 END " +
                        "FROM comic_status WHERE bookId = comics.id), 0)), " +
                    "viewCount = MAX(viewCount, IFNULL((SELECT viewCount FROM comic_status " +
                        "WHERE bookId = comics.id), 0)), " +
                    "lastReadAt = MAX(lastReadAt, IFNULL((SELECT lastReadAt FROM comic_status " +
                        "WHERE bookId = comics.id), 0)) " +
                    "WHERE id IN (SELECT bookId FROM comic_status WHERE (status & 15) != 0)"
            )

            // ⑤ read_progress 并入（有行：进度三列覆盖，进度表是阅读记忆唯一真源）
            db.execSQL(
                "UPDATE comics SET " +
                    "lastChapterId = (SELECT chapterId FROM read_progress WHERE bookId = comics.id), " +
                    "lastPageIndex = (SELECT pageIndex FROM read_progress WHERE bookId = comics.id), " +
                    "lastReadAt = MAX(lastReadAt, IFNULL((SELECT updatedAt FROM read_progress " +
                        "WHERE bookId = comics.id), 0)) " +
                    "WHERE id IN (SELECT bookId FROM read_progress)"
            )

            // ⑤b 纯 online 阅读记录补建行（books/账本都没有，元数据尽量从账本取）
            db.execSQL(
                "INSERT OR IGNORE INTO comics (id, title, author, coverUrl, tags, description, pageCount, " +
                    "localDir, addedAt, cached, finished, favorite, blocked, viewCount, lastReadAt, " +
                    "lastChapterId, lastPageIndex) " +
                    "SELECT rp.bookId, IFNULL(cs.title, ''), IFNULL(cs.author, ''), IFNULL(cs.coverUrl, ''), " +
                    "IFNULL(cs.tags, ''), '', 0, '', 0, 0, 0, 0, 0, IFNULL(cs.viewCount, 0), " +
                    "rp.updatedAt, rp.chapterId, rp.pageIndex " +
                    "FROM read_progress rp LEFT JOIN comic_status cs ON cs.bookId = rp.bookId"
            )

            // ⑥ 单章漫画清出章节表（唯一行且章节 id == bookId = JM 单行本特征；阅读器走内存路由）
            db.execSQL(
                "DELETE FROM chapters WHERE bookId IN (" +
                    "SELECT bookId FROM chapters GROUP BY bookId HAVING COUNT(*) = 1 AND MIN(id) = bookId)"
            )

            // ⑦ 旧表退役
            db.execSQL("DROP TABLE IF EXISTS `books`")
            db.execSQL("DROP TABLE IF EXISTS `comic_status`")
            db.execSQL("DROP TABLE IF EXISTS `read_progress`")
        }
    }

    fun build(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "jmcomic.db"
            ).addMigrations(
                MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14
            )
                // 发版铁律：禁用破坏性迁移。任何 schema 变更必须写对应 Migration，
                // 缺失迁移时直接抛异常暴露问题，绝不静默清空用户数据。
                .build().also { INSTANCE = it }
        }
    }
    }
}
