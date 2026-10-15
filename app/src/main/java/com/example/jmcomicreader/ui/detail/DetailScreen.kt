package com.example.jmreader.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import com.example.jmreader.ui.LocalShowHints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.compose.runtime.collectAsState
import com.example.jmreader.data.local.Chapter
import com.example.jmreader.ui.JmViewModelFactory
import com.example.jmreader.ui.RemoteDecodedImage
import com.example.jmreader.util.formatSpeed
import com.example.jmreader.ui.Routes
import com.example.jmreader.ui.UiState

private const val TAG_LIMIT = 10

/**
 * 详情页。
 *
 * 性能优化要点：
 * - tagList 用 remember 缓存，避免每次重组重新 split/trim/过滤
 * - 收集尽量少的 StateFlow，相关状态派生用 combine 在 VM 层完成，UI 层减少重组
 * - Loading 态用骨架屏而非纯转圈，首屏感知更快
 * - 章节列表用 items(key = { it.id }) 精准定位，滚动更稳
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(nav: NavController, factory: JmViewModelFactory, albumId: Int, local: Boolean = false) {
    val vm: DetailViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsState()
    val downloading by vm.downloading.collectAsState()
    val downloadProgress by vm.downloadProgress.collectAsState()
    val downloadError by vm.downloadError.collectAsState()
    val isLocal by vm.isLocal.collectAsState()
    val isFavorite by vm.isFavorite.collectAsState()
    val finishedChapterIds by vm.finishedChapterIds.collectAsState()
    val finishedChapters by vm.finishedChapters.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var showJmDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(albumId) { vm.load(albumId, preferLocal = local) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? UiState.Success)?.data?.book?.title ?: "详情") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    // 收藏整本漫画（4.4.1）：心形按钮，与下载菜单并列
                    IconButton(onClick = { vm.toggleFavorite() }) {
                        Icon(
                            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isFavorite) "取消收藏" else "收藏",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.Download, contentDescription = "下载") }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isLocal) "已下载到书架" else if (downloading) "下载中…" else "下载到书架") },
                                enabled = !isLocal && !downloading,
                                onClick = { showMenu = false; vm.download(albumId) }
                            )
                            DropdownMenuItem(
                                text = { Text("输入 JM 号下载") },
                                onClick = { showMenu = false; showJmDialog = true }
                            )
                            if (isLocal) {
                                DropdownMenuItem(
                                    text = { Text("删除下载") },
                                    onClick = { showMenu = false; showDeleteConfirm = true }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        when (val s = state) {
            UiState.Loading -> DetailSkeleton(Modifier.fillMaxSize().padding(padding))
            is UiState.Error -> Box(
                Modifier.fillMaxSize().padding(padding).padding(12.dp)
            ) { Text("错误: ${s.message}", color = MaterialTheme.colorScheme.error) }
            is UiState.Success -> {
                val b = s.data.book
                val chapters = s.data.chapters
                // 标签按 | 或 , 拆分（JM 源有时两种都有），去空白、去空项；remember 缓存避免重组反复算
                val tagList = remember(b.tags) {
                    b.tags.split("|", ",").map { it.trim() }.filter { it.isNotBlank() }
                }
                var tagsExpanded by remember { mutableStateOf(false) }
                val favTags by vm.favTags.collectAsState()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(12.dp)
                ) {
                    item {
                        Row(Modifier.fillMaxWidth()) {
                            RemoteDecodedImage(
                                imgUrl = b.coverUrl,
                                modifier = Modifier.size(120.dp),
                                contentScale = ContentScale.Crop,
                                contentDescription = "封面"
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                // v1.2.2：标题旁加搜索入口（此前只有作者 / 标签能搜，标题没有入口）
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        b.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    IconButton(
                                        onClick = { nav.navigate(Routes.search(b.title, "keyword")) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Search,
                                            contentDescription = "按标题搜索",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                // 作者：点一下直接按作者搜索
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "作者：",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (b.author.isNotBlank()) {
                                        Text(
                                            b.author,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline,
                                            modifier = Modifier
                                                .clickable { nav.navigate(Routes.search(b.author, "author")) }
                                                .padding(vertical = 2.dp)
                                        )
                                    } else {
                                        Text("未知", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                if (isLocal) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            "已下载到本地书架（离线可读）",
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 4.dp)
                                        )
                                    }
                                } else {
                                    if (LocalShowHints.current)
                                    Text(
                                        "点右上角下载到书架",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        // v7.0.1：此处原有的「封面下方操作条」（下载 + 收藏）已删除 ——
                        // 它与顶栏的收藏/下载按钮功能完全重复，用户要求只留上面那组。
                    }
                    // 标签：全宽独立 item，从页面左边缘开始铺满整行，避免被封面挤到右侧留白
                    if (tagList.isNotEmpty()) {
                        item {
                            var tagMode by remember { mutableStateOf(true) }   // true=收藏模式 / false=搜索模式
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "标签（${tagList.size}）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { tagMode = !tagMode }) {
                                    Icon(
                                        if (tagMode) Icons.Filled.StarBorder else Icons.Filled.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (tagMode) "收藏模式" else "搜索模式",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            if (LocalShowHints.current)
                            Text(
                                if (tagMode) "点标签收藏 / 再点取消（已收藏标签带 ✓）"
                                else "点标签按标签搜索",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            val shownTags = if (tagsExpanded || tagList.size <= TAG_LIMIT) tagList else tagList.take(TAG_LIMIT)
                            // 标签 Chip 行（v1.17.0：原 SynonymGroupChips 随别名功能下线，
                            // 这里直接铺一排 FilterChip，行为不变：收藏模式点标签切换收藏，搜索模式点标签搜同标签）
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                shownTags.forEach { t ->
                                    val selected = tagMode && t in favTags
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            if (tagMode) vm.toggleFavTag(t)
                                            else nav.navigate(Routes.search(t, "tags"))
                                        },
                                        label = {
                                            Text(t, style = MaterialTheme.typography.labelSmall)
                                        }
                                    )
                                }
                            }
                            if (tagList.size > TAG_LIMIT) {
                                Spacer(Modifier.height(2.dp))
                                TextButton(onClick = { tagsExpanded = !tagsExpanded }) {
                                    Text(
                                        if (tagsExpanded) "收起 ▲" else "展开全部 ${tagList.size} 个 ▼",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                    if (downloading) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("下载中…", style = MaterialTheme.typography.titleSmall)
                                    val p = downloadProgress
                                    if (p != null && p.total > 0) {
                                        LinearProgressIndicator(
                                            progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp)
                                        )
                                        Text(
                                            "图片 ${p.done}/${p.total} · ${formatSpeed(p.speed)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp)
                                        )
                                        Text(
                                            "正在准备…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    item {
                        Text(
                            "章节 (${chapters.size})" +
                                if (finishedChapters > 0) " · 已读 $finishedChapters" else "",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    items(chapters, key = { it.id }, contentType = { "chapter" }) { ch ->
                        ChapterRow(ch, finished = ch.id in finishedChapterIds) {
                            nav.navigate(Routes.reader(b.id, ch.id, ch.id))
                        }
                    }
                    downloadError?.let {
                        item {
                            Text("下载错误: $it", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showJmDialog) {
        var jm by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJmDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val n = jm.trim().toIntOrNull()
                    if (n != null && n > 0) {
                        showJmDialog = false
                        // v7.3.1：与下载页「JM 号下载」一致改为**直达整本下载**（此前只是跳到该号详情页，
                        // 名不副实）；失败原因经 vm.download 显示在下方「下载错误」处。
                        vm.download(n)
                    }
                }) { Text("下载") }
            },
            dismissButton = { TextButton(onClick = { showJmDialog = false }) { Text("取消") } },
            title = { Text("JM 号下载") },
            text = {
                OutlinedTextField(
                    value = jm,
                    onValueChange = { jm = it.filter { c -> c.isDigit() } },
                    label = { Text("输入 JM 数字编号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.removeBook(albumId)
                    nav.popBackStack()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } },
            title = { Text("删除下载内容？") },
            text = {
                Text(
                    "将从本机删除该漫画的全部已下载图片与进度，同时移除书架与下载记录。" +
                            "此操作不可恢复。"
                )
            }
        )
    }
}

/**
 * 详情页骨架屏：加载时显示占位轮廓，感知上比纯转圈更快。
 * 封面 + 标题 + 作者 + 章节列表占位条。
 */
@Composable
private fun DetailSkeleton(modifier: Modifier = Modifier) {
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    LazyColumn(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier.size(120.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = baseColor.copy(alpha = 0.4f)
                ) {}
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = baseColor.copy(alpha = 0.4f)
                    ) {}
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.width(160.dp).height(16.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = baseColor.copy(alpha = 0.3f)
                    ) {}
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.width(120.dp).height(16.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = baseColor.copy(alpha = 0.3f)
                    ) {}
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(8.dp),
                color = baseColor.copy(alpha = 0.25f)
            ) {}
        }
        item {
            Surface(
                modifier = Modifier.width(120.dp).height(16.dp),
                shape = RoundedCornerShape(4.dp),
                color = baseColor.copy(alpha = 0.3f)
            ) {}
        }
        items(6, contentType = { "related" }) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                color = baseColor.copy(alpha = 0.25f)
            ) {}
        }
    }
}

@Composable
private fun ChapterRow(ch: Chapter, finished: Boolean = false, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(ch.name, modifier = Modifier.weight(1f))
            if (finished) {
                Text(
                    "已读",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}
