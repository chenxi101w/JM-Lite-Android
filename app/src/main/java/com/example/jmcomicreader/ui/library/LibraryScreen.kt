package com.example.jmreader.ui.library

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.jmreader.ui.JmViewModelFactory
import com.example.jmreader.ui.MainScaffold
import com.example.jmreader.ui.Routes
import com.example.jmreader.ui.components.ComicGrid
import com.example.jmreader.ui.topLevelRouteOf
import kotlinx.coroutines.launch

/**
 * 轻量版书架页：阅读（点卡片）+ 删除。
 * v1.0.0 保留「看完」分区：未看完在前、看完沉底带「已看完」分隔线。
 *
 * v1.11.0 批量删除：
 *  - 长按卡片 → 进入多选模式（首本选中），点卡片 = 切换选中（不再导航）；
 *  - 顶部工具栏：已选 N 本 + 「全选 / 未读 / 已读」一键勾选 + ✕ 退出；
 *    已读 = read_progress 有记录（打开过阅读器），未读 = 下载了但没打开过；
 *  - 「删除所选」走确认弹窗，语义与单本删除相同 = 彻底删除（本地文件 + 记录，不可恢复）。
 */
@Composable
fun LibraryScreen(nav: NavController, factory: JmViewModelFactory, vm: LibraryViewModel = viewModel(factory = factory)) {
    val books by vm.displayBooks.collectAsState()
    val cols by vm.bookshelfColumns.collectAsState()
    // v1.11.0 多选状态
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()

    val scope = rememberCoroutineScope()
    var navigating by remember { mutableStateOf(false) }
    // 单本删除目标（多选模式外：点卡片上的删除按钮）
    var deleteTarget by remember { mutableStateOf<com.example.jmreader.data.local.Book?>(null) }
    // v1.11.0 批量删除确认
    var showBatchConfirm by remember { mutableStateOf(false) }

    MainScaffold(
        currentRoute = topLevelRouteOf(Routes.LIBRARY),
        nav = nav,
        showTopBar = false,

    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(12.dp)
        ) {
            if (selectionMode) {
                SelectionToolbar(
                    selectedCount = selectedIds.size,
                    onAll = { vm.selectByRead(null) },
                    onUnread = { vm.selectByRead(false) },
                    onRead = { vm.selectByRead(true) },
                    onExit = { vm.clearSelection() }
                )
                if (selectedIds.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { showBatchConfirm = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("删除所选（${selectedIds.size} 本）")
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (books.isEmpty()) {
                Text(
                    "书架还是空的，去发现页找找吧",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                val finishedIds = remember(books) { books.filter { it.finished == 1 }.map { it.id }.toSet() }
                ComicGrid(
                    books = books,
                    onBookClick = { book ->
                        if (selectionMode) {
                            vm.toggleSelect(book)   // v1.11.0：多选中点击 = 切换选中
                        } else if (!navigating) {
                            navigating = true
                            scope.launch {
                                try {
                                    val r = vm.openRouteFor(book)
                                    if (r != null) nav.navigate(r) { launchSingleTop = true }
                                    else Toast.makeText(nav.context, "获取章节失败，请稍后重试", Toast.LENGTH_SHORT).show()
                                } finally {
                                    navigating = false
                                }
                            }
                        }
                    },
                    // v1.11.0：长按 = 进入多选模式（此前长按是弹单本删除确认）
                    onBookLongClick = { book -> if (!selectionMode) vm.enterSelection(book) },
                    columns = cols,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    finishedIds = finishedIds,
                    // v1.11.0：多选模式下隐藏卡片上的删除小按钮，避免干扰勾选
                    onDelete = if (selectionMode) null else { book -> deleteTarget = book },
                    // v1.11.0：多选态透传（卡片主色描边高亮）
                    selectionMode = selectionMode,
                    selectedIds = selectedIds
                )
            }
        }
    }

    // ── 单本删除确认（v1.2.3）─────────────────────────────────────
    // 删除 = 彻底删除（本地文件 + 记录，不可恢复），因此必须二次确认并写明范围。
    deleteTarget?.let { b ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除《${b.title}》？") },
            text = {
                Text(
                    if (b.savedDir.isNotBlank())
                        "将删除已下载的本地文件，以及下载记录与阅读进度。**此操作不可恢复**。"
                    else
                        "将从书架移除这本漫画，并清除其阅读进度记录。**此操作不可恢复**。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeBook(b.id)
                    deleteTarget = null
                    Toast.makeText(nav.context, "已删除", Toast.LENGTH_SHORT).show()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    // ── 批量删除确认（v1.11.0）────────────────────────────────────
    if (showBatchConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchConfirm = false },
            title = { Text("删除所选 ${selectedIds.size} 本？") },
            text = {
                Text("将删除这些漫画的本地文件，并清除书架记录与阅读进度。此操作无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatchConfirm = false
                    vm.removeSelected { ok, total ->
                        Toast.makeText(
                            nav.context,
                            if (ok == total) "已删除 $ok 本" else "已删除 $ok/$total 本",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 多选模式工具栏：已选计数 + 全选/未读/已读 + 退出（v1.11.0）。 */
@Composable
private fun SelectionToolbar(
    selectedCount: Int,
    onAll: () -> Unit,
    onUnread: () -> Unit,
    onRead: () -> Unit,
    onExit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "已选 $selectedCount 本",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onAll) { Text("全选") }
        TextButton(onClick = onUnread) { Text("未读") }
        TextButton(onClick = onRead) { Text("已读") }
        Spacer(Modifier.width(2.dp))
        IconButton(onClick = onExit) {
            Icon(Icons.Filled.Close, contentDescription = "退出多选")
        }
    }
}
