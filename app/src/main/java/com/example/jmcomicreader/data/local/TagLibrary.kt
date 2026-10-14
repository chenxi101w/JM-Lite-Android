package com.example.jmreader.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 标签库（v2.7.0）：从每次搜索结果累积的标签字典，按出现次数（count）降序排序。
 *
 * - [tag] 为主键：小写归一化（与 [com.example.jmreader.data.repository.BookLibrary.splitTags] 同一规则）。
 * - [count]：标签累计出现次数（每本漫画的每个标签各 +1，即总曝光次数；按用户决策「每本漫画标签各+1」）。
 * - [lastSeenAt]：最近一次被搜索命中的时间，作为排序次级键、也便于日后清理。
 *
 * 标签库只做累积与浏览，不自动参与喜欢/讨厌算法；用户在设置「标签」页可点选标签加入
 * 手动喜欢（权重 100）或手动讨厌（搜索/推荐排除）。
 */
@Entity(tableName = "tag_library")
data class TagLibrary(
    @PrimaryKey val tag: String,
    @ColumnInfo(defaultValue = "0") val count: Int = 0,
    @ColumnInfo(defaultValue = "0") val lastSeenAt: Long = 0
)

/**
 * 实验标签库（v5.10.0，**v6.0.18 起功能已删除/停用**）：与「兴趣标签库」（tag_library）结构一致、完全独立的表。
 * 原由「实验标签库」功能批量抓取（遍历 JM 号）写入；现功能入口与逻辑已全部移除，
 * 仅**保留表结构**（避免改动数据库 schema 引发迁移风险），表数据可留空、无任何读取方。
 */
@Entity(tableName = "tag_library_exp")
data class TagLibraryExp(
    @PrimaryKey val tag: String,
    @ColumnInfo(defaultValue = "0") val count: Int = 0,
    @ColumnInfo(defaultValue = "0") val lastSeenAt: Long = 0
)
