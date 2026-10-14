package com.example.jmreader.data.repository

import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.BookDao
import com.example.jmreader.data.local.Subscription
import com.example.jmreader.data.local.SubscriptionDao
import com.example.jmreader.data.local.SubscriptionUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 作者订阅（v7.0.0 从 BookLibrary 拆出）。
 *
 * 只负责「订阅关系 + 新作更新记录」，不碰书籍正文数据。
 * 检查更新时需要联网搜索作者作品，故把搜索能力以 [searchFn] 注入（避免反向依赖
 * BookLibrary 造成循环引用）。
 *
 * @param searchFn 参数依次为 keyword / mode / page / orderBy / time，返回作品列表
 */
class BookSubscriptionManager(
    private val subscriptionDao: SubscriptionDao,
    private val bookDao: BookDao,
    private val searchFn: suspend (String, String, Int, String, String) -> List<Book>
) {

    suspend fun addSubscription(author: String): Boolean {
        val a = author.trim()
        if (a.isEmpty()) return false
        if (subscriptionDao.findByAuthor(a) != null) return false
        subscriptionDao.insert(Subscription(author = a, createdAt = System.currentTimeMillis()))
        return true
    }

    suspend fun removeSubscription(id: Int) = subscriptionDao.deleteById(id)

    fun observeSubscriptions(): Flow<List<Subscription>> = subscriptionDao.observeAll()

    suspend fun clearSubscriptionNew(id: Int) = subscriptionDao.clearNew(id)

    suspend fun clearAllSubscriptions() = subscriptionDao.clearAll()

    /**
     * 检查所有订阅作者的更新：拉取作者最新作品，与本地已知漫画（books 表 id 集合）对比，
     * 发现新作则逐部落库 sub_updates（seen=0），并累加 subscriptions.newCount、记录最新作品标题；
     * 无新增则仅刷新 lastCheckedAt。每个作者的网络/解析异常单独隔离，不影响其它作者。
     * 返回本次发现的新作列表（v7.2.0 起返回新作本身，供「新作自动下载」直接用）。
     */
    suspend fun checkSubscriptionsForUpdates(): List<Book> {
        val subs = subscriptionDao.observeAll().first()
        val knownIds = bookDao.observeAll().first().map { it.id }.toMutableSet()
        val newWorks = mutableListOf<Book>()
        val now = System.currentTimeMillis()
        for (sub in subs) {
            runCatching {
                val works = searchFn(sub.author, "author", 1, "mr", "a")
                val newOnes = works.filter { it.id !in knownIds }
                if (newOnes.isNotEmpty()) {
                    for (w in newOnes) {
                        subscriptionDao.insertUpdate(
                            SubscriptionUpdate(
                                author = sub.author,
                                bookId = w.id,
                                title = w.title,
                                coverUrl = w.coverUrl,
                                foundAt = now,
                                seen = 0
                            )
                        )
                        newWorks += w
                        // 防同一次检查里多个作者命中同一部新作重复入库/重复下载
                        knownIds.add(w.id)
                    }
                    subscriptionDao.noteUpdate(sub.author, now, newOnes.size, newOnes.first().title)
                } else {
                    subscriptionDao.noteChecked(sub.author, now)
                }
            }
        }
        return newWorks
    }

    /** 订阅新作流（发现页「订阅更新」区块，先未看后已看，按发现时间倒序）。 */
    fun observeSubscriptionUpdates(): Flow<List<SubscriptionUpdate>> =
        subscriptionDao.observeUpdates()

    /** 未看新作流（启动通知判定用）。 */
    fun observeUnseenUpdates(): Flow<List<SubscriptionUpdate>> =
        subscriptionDao.observeUnseen()

    /** 某部新作已看（进详情时标记）。 */
    suspend fun markUpdateSeen(bookId: Int) = subscriptionDao.markSeenByBook(bookId)

    /** 全部新作标记已看。 */
    suspend fun markAllUpdatesSeen() = subscriptionDao.markAllSeen()

    /** 取消订阅时联动清掉该作者的新作记录。 */
    suspend fun removeSubscriptionAndUpdates(id: Int, author: String) {
        subscriptionDao.deleteById(id)
        subscriptionDao.deleteByAuthor(author)
    }
}
