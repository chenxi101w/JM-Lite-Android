package com.example.jmreader.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 订阅作者的新作记录（P1-4，4.0.0）：检查订阅时把发现的新作逐部落库，
 * 书架顶部「订阅更新」区块据此展示，点击可进详情，可一键标记已看。
 */
@Entity(tableName = "sub_updates")
data class SubscriptionUpdate(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(defaultValue = "") val author: String,
    @ColumnInfo(defaultValue = "0") val bookId: Int = 0,
    @ColumnInfo(defaultValue = "") val title: String = "",
    @ColumnInfo(defaultValue = "") val coverUrl: String = "",
    @ColumnInfo(defaultValue = "0") val foundAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val seen: Int = 0
)
