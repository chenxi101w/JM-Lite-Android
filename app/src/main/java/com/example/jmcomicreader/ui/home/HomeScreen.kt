package com.example.jmreader.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.runtime.collectAsState
import com.example.jmreader.data.local.SubscriptionUpdate
import com.example.jmreader.ui.RemoteDecodedImage
import com.example.jmreader.ui.Routes
import com.example.jmreader.ui.components.ComicGrid

/**
 * 推荐流内容（v6.1.0 从 HomeScreen 抽出为独立可组合项；v7.0.0 删除已无用的 HomeScreen 空壳）。
 *
 * 「发现」页（DiscoverScreen）在未搜索态直接复用它，避免同一份推荐流维护两遍。
 * @param padding 宿主 Scaffold 传入的内边距（发现页会把自己那份传进来）
 * @param onGoSearch 点「去搜索」时的回调；独立主页是 navigate，发现页是切到搜索态
 */
@Composable
fun HomeRecommendBody(
    padding: PaddingValues,
    nav: NavController,
    vm: HomeViewModel,
    onGoSearch: () -> Unit
) {
    val recs by vm.recommendations.collectAsState()
    val cols by vm.bookshelfColumns.collectAsState()
    val downloadingIds by vm.downloadingIds.collectAsState()
    val downloadedIds by vm.downloadedIds.collectAsState()
    val favoriteIds by vm.favoriteIds.collectAsState()

    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
            // 为你推荐分区标题 + 数量徽标
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "为你推荐",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.width(8.dp))
                AnimatedVisibility(
                    visible = recs.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(
                            alpha = 0.7f
                        ),
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Text(
                            text = "${recs.size} 部",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(
                                horizontal = 8.dp,
                                vertical = 2.dp
                            )
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
            }

            // 内容：空状态 / 网格
            AnimatedVisibility(
                visible = recs.isEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                EmptyRecommendation(onGoSearch = onGoSearch)
            }
            AnimatedVisibility(
                visible = recs.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                ComicGrid(
                    books = recs,
                    onBookClick = { book ->
                        // v6.0.13：首页推荐点击进详情页（与搜索页一致），不再直接进阅读器
                        nav.navigate(
                            Routes.detail(book.id, local = book.savedDir.isNotBlank())
                        ) { launchSingleTop = true }
                    },
                    columns = cols,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onDownload = { vm.download(it) },
                    downloadingIds = downloadingIds,
                    downloadedIds = downloadedIds,
                    onFavorite = { vm.toggleFavorite(it.id) },
                    favoriteIds = favoriteIds
                )
            }
        // v7.1.1：删除发现页 FAB 后，不再需要给 FAB 留 80dp 底部空白
    }
}

/**
 * 空推荐态：居中引导卡 — 图标 / 标题 / 说明 / 引导按钮。
 * 参考 Mihon / Komikku 空状态设计：温暖插画 + 清晰引导。
 */
@Composable
private fun EmptyRecommendation(onGoSearch: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.88f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 2.dp
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .padding(14.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "正在为你准备推荐",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Text(
                text = "先去搜索或下载几部漫画吧，\n我们会根据你的兴趣口味生成个性化推荐。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onGoSearch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "去搜索漫画",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/**
 * 「订阅更新」区块（v7.2.0 从书架移到发现页推荐流上方）：标题行（未看数量 + 全部已看 + 管理）+ 横滑新作封面卡片。
 * 未看的新作带「新」角标；点封面进详情（详情页自动标记已看）。
 */
@Composable
fun SubscriptionUpdateSection(
    updates: List<SubscriptionUpdate>,
    onOpen: (SubscriptionUpdate) -> Unit,
    onMarkAllSeen: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val unseen = updates.count { it.seen == 0 }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (unseen > 0) "订阅更新 · $unseen 部新作" else "订阅更新",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (unseen > 0) {
                TextButton(onClick = onMarkAllSeen) { Text("全部已看") }
            }
            TextButton(onClick = onManage) { Text("管理") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(updates, key = { it.id }, contentType = { "update" }) { u ->
                Column(
                    modifier = Modifier
                        .width(96.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpen(u) }
                ) {
                    Box {
                        RemoteDecodedImage(
                            imgUrl = u.coverUrl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(128.dp),
                            contentScale = ContentScale.Crop,
                            contentDescription = u.title
                        )
                        if (u.seen == 0) {
                            Text(
                                "新",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        u.title,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (u.seen == 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        u.author,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
