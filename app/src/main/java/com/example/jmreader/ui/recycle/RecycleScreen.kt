package com.example.jmreader.ui.recycle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.compose.runtime.collectAsState
import com.example.jmreader.data.repository.RecycleManager
import com.example.jmreader.ui.JmViewModelFactory
import com.example.jmreader.ui.RemoteDecodedImage
import com.example.jmreader.ui.Routes

/**
 * 回收站页（v1.15.0 自全量版 v7.1.x 移植）：封面 / 标题 / 剩余天数 / 恢复 / 彻底删除 / 清空。
 * 点封面进详情页（重新下载完成后自动捡回，清回收站标记）；进入页面时扫描 7 天超期项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleScreen(
    nav: NavController,
    factory: JmViewModelFactory,
    vm: RecycleViewModel = viewModel(factory = factory)
) {
    val items by vm.items.collectAsState()
    val busy by vm.busy.collectAsState()

    // 待确认删除项（null = 无弹窗）：deleteForever 单本 / clearAll 清空
    var pendingDeleteId by remember { mutableStateOf<Int?>(null) }
    var pendingClearAll by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text(
                "看完的漫画有 ${vm.retentionDays} 天宽限期（期间可直接阅读），宽限期没再看会自动删除本地内容、想再看需重新下载；手动删除的漫画在此可恢复。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (items.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { pendingClearAll = true },
                        enabled = !busy
                    ) { Text("清空回收站（${items.size}）") }
                }
            }
            if (items.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "回收站是空的",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "看完的漫画宽限期过后会出现在这里",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.bookId }) { item ->
                        TrashRow(
                            item = item,
                            enabled = !busy,
                            onClickCover = { nav.navigate(Routes.detail(item.bookId, local = false)) },
                            onPrimary = {
                                if (item.trashed) vm.restore(item.bookId) else vm.deleteLocal(item.bookId)
                            },
                            onDeleteForever = { pendingDeleteId = item.bookId }
                        )
                    }
                }
            }
        }
    }

    // 单本彻底删除确认
    pendingDeleteId?.let { id ->
        val item = items.firstOrNull { it.bookId == id }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("彻底删除？") },
            text = { Text("「${item?.title ?: "该漫画"}」将从书架和回收站完全移除（含下载记录与阅读进度），不可恢复。") },
            confirmButton = {
                Button(onClick = {
                    pendingDeleteId = null
                    vm.deleteForever(id)
                }) { Text("彻底删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
            }
        )
    }

    // 清空回收站确认
    if (pendingClearAll) {
        AlertDialog(
            onDismissRequest = { pendingClearAll = false },
            title = { Text("清空回收站？") },
            text = { Text("回收站里 ${items.size} 部漫画将被彻底删除（含下载记录与阅读进度），不可恢复。") },
            confirmButton = {
                Button(onClick = {
                    pendingClearAll = false
                    vm.clearAll()
                }) { Text("全部删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearAll = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TrashRow(
    item: RecycleManager.TrashItem,
    enabled: Boolean,
    onClickCover: () -> Unit,
    onPrimary: () -> Unit,
    onDeleteForever: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClickCover)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面
        RemoteDecodedImage(
            imgUrl = item.coverUrl,
            modifier = Modifier
                .width(56.dp)
                .height(74.dp)
                .clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
            contentDescription = item.title
        )
        Spacer(Modifier.width(12.dp))
        // 标题 + 状态（宽限中可读 / 已删本地需重下）
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    item.trashed -> "本地已删，重新下载恢复"
                    item.remainDays > 0 -> "${item.remainDays} 天后删除 · 可直接阅读"
                    else -> "即将到期，尽快阅读"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (item.trashed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 操作：宽限中 = 立即删除（提前删本地进回收站）；已删 = 恢复（清标记，本地仍无内容）
        if (!item.trashed) {
            TextButton(onClick = onPrimary, enabled = enabled) { Text("删除") }
        } else {
            TextButton(onClick = onPrimary, enabled = enabled) { Text("恢复") }
        }
        OutlinedButton(onClick = onDeleteForever, enabled = enabled) { Text("彻底删") }
    }
}
