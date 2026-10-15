package com.example.jmreader.ui.downloads

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.compose.runtime.collectAsState
import com.example.jmreader.data.local.Download
import com.example.jmreader.data.repository.DownloadProgress
import com.example.jmreader.ui.JmViewModelFactory
import com.example.jmreader.ui.MainScaffold
import com.example.jmreader.ui.Routes
import com.example.jmreader.ui.topLevelRouteOf
import com.example.jmreader.util.formatSpeed

@Composable
fun DownloadsScreen(nav: NavController, factory: JmViewModelFactory) {
    val vm: DownloadsViewModel = viewModel(factory = factory)
    val rows by vm.rows.collectAsState()
    // progresses（整张进度 Map）**不在此处 collect**：下载中每 500ms 推一次新 Map，
    // 顶层订阅会让整页（含 MainScaffold 与所有卡片）每 500ms 重组。
    // v14 起每张 DownloadCard 通过 vm.progressFor(bookId) 只订阅自己那一条。

    // v1.8.1：算出每本在「下载队列」里的位置（按入队时间）。
    // 之前所有等待中的书都只显示「排队中…」，7 本一起排队时看着像"全卡住了"。
    val queueOrder = remember(rows) {
        rows.filter { it.download.status == "DOWNLOADING" || it.download.status == "QUEUED" }
            .sortedBy { it.download.createdAt }
            .mapIndexed { i, r -> r.download.bookId to (i + 1) }
            .toMap()
    }

    // v7.3.1：JM 号直达下载 / 重新下载失败 → Toast 提示原因（此前静默失败，用户以为没反应）
    val downloadError by vm.downloadError.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(downloadError) {
        downloadError?.let { msg ->
            Toast.makeText(ctx, "下载失败：$msg", Toast.LENGTH_LONG).show()
            vm.consumeDownloadError()
        }
    }

    // 待确认「取消并删除」的目标 bookId（null 表示无弹窗）
    var pendingDelete by remember { mutableStateOf<Int?>(null) }
    // 待确认「清空历史记录」
    var pendingClearAll by remember { mutableStateOf(false) }
    // JM 号直达下载输入弹窗
    var showJmDialog by remember { mutableStateOf(false) }

    MainScaffold(
        currentRoute = topLevelRouteOf(Routes.DOWNLOADS),
        nav = nav,
        // v6.0.23：此前本页没有开 TopAppBar（showTopBar 默认 false），
        // 于是 topBarActions 里的「JM号下载」按钮**根本不会渲染** —— 这是 JM 号下载
        // 找不到的真正原因（不只是图标隐晦）。同 LibraryScreen v6.0.11 踩过的坑。
        showTopBar = true,
        topBarTitle = { Text("下载管理") },
        // 默认 FAB 的 onClick 是 nav.navigate(Routes.DOWNLOADS)，在本页点它会把下载页
        // 再压一层进返回栈（自己叠自己），故置空。
        fab = {},
        topBarActions = {
            // 4.1.0 起 JM 号直达入口在下载页（搜索页已不再承担）。
            // 原为纯「+」号图标按钮，无任何文字，过于隐晦 —— 用户找不到会以为功能被删了，
            // 故改为带文字的填充按钮，与右侧「清空历史」区分开。
            FilledTonalButton(
                onClick = { showJmDialog = true },
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("JM号下载", style = MaterialTheme.typography.labelLarge)
            }
            if (rows.isNotEmpty()) {
                TextButton(onClick = { pendingClearAll = true }) {
                    Text("清空历史")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(12.dp)
        ) {
            if (rows.isEmpty()) {
                // 原文案「去搜索页下载漫画吧」是 4.1.0 之前的旧引导：搜索页早已没有 JM 号入口，
                // 属于误导；改为直接把 JM 号下载的入口摆在空状态里。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "暂无下载任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = { showJmDialog = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("用 JM 号下载")
                    }
                    Text(
                        "也可以在发现页找到漫画后下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.download.bookId }, contentType = { "download" }) { row ->
                    DownloadCard(
                        dl = row.download,
                        title = row.title,
                        vm = vm,
                        onCancelDelete = { pendingDelete = row.download.bookId },
                        onRedownload = { vm.redownload(row.download.bookId) },
                        local = row.local,
                        queuePos = queueOrder[row.download.bookId]
                    )
                }
            }
        }
    }

    // 确认弹窗：取消下载并删除本地内容
    pendingDelete?.let { bookId ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    vm.cancelAndDelete(bookId)
                    pendingDelete = null
                }) { Text("取消并删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("保留") }
            },
            title = { Text("取消并删除内容？") },
            text = {
                Text(
                    "将停止该漫画的下载（若仍在进行）并从本机删除已下载的内容，" +
                            "同时移除下载记录。此操作不可恢复。"
                )
            }
        )
    }


    // 确认弹窗：清空全部下载记录
    if (pendingClearAll) {
        AlertDialog(
            onDismissRequest = { pendingClearAll = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAllRecords()
                    pendingClearAll = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearAll = false }) { Text("取消") }
            },
            title = { Text("清空下载历史？") },
            text = {
                Text("将清空「下载管理」里的全部记录，本地书架与已下载内容不受影响。")
            }
        )
    }

    // JM 号直达下载：输入数字编号直接整本下载（4.1.0，替代原搜索里的 JM 号模式）
    if (showJmDialog) {
        var jm by remember { mutableStateOf("") }
        // 原来输入为空/非法时点「下载」什么都不发生（弹窗不关也不提示），
        // 用户会以为按钮坏了；改为显式报错。
        var invalid by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showJmDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val n = jm.trim().toIntOrNull()
                    if (n != null && n > 0) {
                        showJmDialog = false
                        vm.redownload(n)   // downloadAlbum(albumId)，JM 号即 albumId
                    } else {
                        invalid = true
                    }
                }) { Text("下载") }
            },
            dismissButton = { TextButton(onClick = { showJmDialog = false }) { Text("取消") } },
            title = { Text("JM 号下载") },
            text = {
                Column {
                    OutlinedTextField(
                        value = jm,
                        onValueChange = {
                            jm = it.filter { c -> c.isDigit() }
                            invalid = false
                        },
                        label = { Text("输入 JM 数字编号") },
                        singleLine = true,
                        isError = invalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (invalid) {
                        Text(
                            "请输入有效的 JM 数字编号",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun DownloadCard(
    dl: Download,
    title: String,
    vm: DownloadsViewModel,
    onCancelDelete: () -> Unit,
    onRedownload: () -> Unit,
    local: Boolean = true,
    /** v1.8.1：在下载队列里的位置（1 起）；null = 不在队列中。 */
    queuePos: Int? = null,
) {
    // v14：只订阅本卡片这一条进度。原实现由页面顶层订阅整张 Map 后按 id 取值传进来，
    // 任何一本的进度变化都会让整页重组；现在只有这本书的进度变化才会重绘本卡片。
    // 注意用 .value 而非 by 委托：委托属性在下方 `if (progress != null)` 后无法 smart cast
    val progress = remember(dl.bookId) { vm.progressFor(dl.bookId) }.collectAsState(initial = null).value
    // ── v1.2.6：进度平滑动画 ────────────────────────────────────────
    // 进度来自每 500ms 一次的轮询，直接赋值会让进度条"一格一格地跳"。
    // 这里补间 550ms（线性缓动）—— 略长于轮询间隔，使相邻两次采样首尾相接，
    // 视觉上成为连续推进，而不是阶梯式跳动。
    val targetFraction = when {
        dl.status == "DONE" -> 1f
        progress != null && progress.total > 0 ->
            (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
        else -> 0f
    }
    val fraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 550, easing = LinearEasing),
        label = "download-progress"
    )

    // 进度条配色随状态变化：下载中=主色 / 已完成=tertiary / 失败=error / 排队=outline
    val barColor = when (dl.status) {
        "DONE" -> MaterialTheme.colorScheme.tertiary
        "FAILED" -> MaterialTheme.colorScheme.error
        "QUEUED" -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    color = if (local) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,   // 不在本地：标题标红
                    modifier = Modifier.weight(1f)
                )
                // v1.2.5：失败 / 排队中的记录同样提供续下入口。
                // 此前这个「重新下载」按钮只对 DONE 显示 —— 下载一旦 FAILED，
                // 除了「取消并删除」就无路可走（用户反馈「失败的、长时间无进度的要能续下」）。
                // 续下走的是 Python 断点续传：已下载的图片会跳过，不会从头再来。
                if (dl.status == "DONE" || dl.status == "FAILED" || dl.status == "QUEUED") {
                    IconButton(onClick = onRedownload, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = if (dl.status == "DONE") "重新下载" else "继续下载",
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                IconButton(onClick = onCancelDelete, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "取消并删除",
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            // ── 状态行（状态 + 图片数 + 速度合并，省一行高度）──
            val statusLine = buildString {
                append(statusText(dl, progress, queuePos))
                if (dl.status == "DOWNLOADING" && progress != null && progress.total > 0) {
                    append(" · ${progress.done}/${progress.total} 张")
                    append(" · ${formatSpeed(progress.speed)}")
                }
            }
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = if (dl.status == "FAILED") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp)
            )

            // ── 进度条：下载中与已完成都显示（已完成走满格，视觉闭环）──
            if (dl.status == "DOWNLOADING" || dl.status == "DONE") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 9.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = barColor,
                        trackColor = trackColor,
                        strokeCap = StrokeCap.Round
                    )
                    // 百分比只对「下载中」显示；已完成时状态行已写明，不重复
                    if (dl.status == "DOWNLOADING" && progress != null && progress.total > 0) {
                        Text(
                            text = "${(fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .width(36.dp)
                        )
                    }
                }
            }

            // ── 警示信息 ──
            if (dl.status == "DONE" && !local) {
                Text(
                    text = "⚠ 已不在本地（重新下载可恢复）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp)
                )
            }
        }
    }
}

private fun statusText(
    dl: Download,
    progress: DownloadProgress?,
    queuePos: Int? = null,
): String = when (dl.status) {
    // v1.8.1：显示队列位置，避免"一片排队中"看着像卡死
    "QUEUED" -> if (queuePos != null) "排队中（队列第 $queuePos 位）" else "排队中…"
    "DOWNLOADING" ->
        if (progress != null && progress.status == "DOWNLOADING" && progress.total > 0)
            "下载中 ${progress.done}/${progress.total}"
        else "下载中…"
    "DONE" -> "已完成"
    // v1.2.5：明确告诉用户"还能续" —— 此前只写「可取消并删除」，等于宣判死刑
    "FAILED" -> "失败 · 点右侧 ⟳ 继续下载（已下部分会保留）"
    else -> dl.status
}
