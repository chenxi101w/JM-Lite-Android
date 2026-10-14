package com.example.jmreader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Subscription>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: Subscription): Long

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT * FROM subscriptions WHERE author = :author LIMIT 1")
    suspend fun findByAuthor(author: String): Subscription?

    @Query("UPDATE subscriptions SET lastCheckedAt = :ts, lastUpdateAt = :ts, newCount = newCount + :delta, lastTitle = :title WHERE author = :author")
    suspend fun noteUpdate(author: String, ts: Long, delta: Int, title: String)

    @Query("UPDATE subscriptions SET lastCheckedAt = :ts WHERE author = :author")
    suspend fun noteChecked(author: String, ts: Long)

    @Query("UPDATE subscriptions SET newCount = 0 WHERE id = :id")
    suspend fun clearNew(id: Int)

    @Query("DELETE FROM subscriptions")
    suspend fun clearAll()

    // ── 订阅新作记录（sub_updates，4.0.0）──────────────────────

    @Query("SELECT * FROM sub_updates ORDER BY foundAt DESC")
    fun observeUpdates(): Flow<List<SubscriptionUpdate>>

    @Query("SELECT * FROM sub_updates WHERE seen = 0 ORDER BY foundAt DESC")
    fun observeUnseen(): Flow<List<SubscriptionUpdate>>

    @Query("SELECT * FROM sub_updates WHERE author = :author ORDER BY foundAt DESC")
    suspend fun findUpdatesByAuthor(author: String): List<SubscriptionUpdate>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUpdate(u: SubscriptionUpdate): Long

    @Query("UPDATE sub_updates SET seen = 1 WHERE bookId = :bookId")
    suspend fun markSeenByBook(bookId: Int)

    @Query("UPDATE sub_updates SET seen = 1")
    suspend fun markAllSeen()

    @Query("DELETE FROM sub_updates WHERE author = :author")
    suspend fun deleteByAuthor(author: String)

    @Query("DELETE FROM sub_updates")
    suspend fun clearAllUpdates()
}
