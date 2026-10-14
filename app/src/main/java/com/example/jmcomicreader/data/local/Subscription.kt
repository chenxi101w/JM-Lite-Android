package com.example.jmreader.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 作者订阅（P1-4，3.0.0）：收藏作者、检查更新、新作置顶与通知入口。 */
@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(defaultValue = "") val author: String,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastCheckedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastUpdateAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val newCount: Int = 0,
    @ColumnInfo(defaultValue = "") val lastTitle: String = ""
)
