@file:OptIn(ExperimentalMaterial3Api::class)
package com.example.jmreader.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.jmreader.BuildConfig
import com.example.jmreader.R
import com.example.jmreader.ui.JmViewModelFactory
import com.example.jmreader.ui.Routes
import com.example.jmreader.util.AppIconManager
import com.example.jmreader.util.JmDebug
import com.example.jmreader.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置**二级页**（v1.5.4）：按 [group] 渲染对应分类的具体设置项。
 *
 * 用 Scaffold + TopAppBar（带返回箭头）而不是 MainScaffold —— 二级页不该再有底部导航栏，
 * 否则会与"当前选中设置"的高亮冲突。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGroupScreen(group: String, nav: NavController, factory: JmViewModelFactory) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val cfg by vm.config.collectAsState()
    val theme by vm.theme.collectAsState()
    val themeStyle by vm.themeStyle.collectAsState()
    val nightStart by vm.nightStart.collectAsState()
    val nightEnd by vm.nightEnd.collectAsState()
    val showHints by vm.showHints.collectAsState()

    val meta = groupMeta(group)
    var nightPicker by remember { mutableStateOf(false) }
    // v1.10.0 云同步：地址/密钥编辑弹窗开关
    var editSyncUrl by remember { mutableStateOf(false) }
    var editSyncToken by remember { mutableStateOf(false) }

    Scaffold(
        // v1.6.4：二级页**不用** MainScaffold（那个把 contentWindowInsets 置0），
        // 让本 Scaffold 自己处理 insets —— 否则 TopAppBar 顶到状态栏下面，
        // 标题栏上半截被时间/电量遮住（用户反馈"最上面那栏被挡住一部分"）。
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(meta.icon, contentDescription = null, tint = meta.tint,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(meta.title)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            when (group) {
                Routes.SUB_APPEARANCE -> {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            ChipRow("主题",
                                listOf("system" to "跟随系统", "light" to "浅色",
                                    "dark" to "深色", "auto" to "定时"),
                                theme) { vm.setTheme(it) }
                            Divider()
                            ChipRow("主题风格",
                                listOf("violet" to "暮紫", "ink" to "墨夜", "paper" to "纸感", "neon" to "霓虹"),
                                themeStyle) { vm.setThemeStyle(it) }
                            if (theme == "auto") {
                                Divider()
                                RowItem("夜间时段", "$nightStart – $nightEnd") { nightPicker = true }
                            }
                            Divider()
                            SwitchRow("显示提示小字", "关闭后界面更简洁", showHints) { vm.setShowHints(it) }
                        }
                    }
                }

                Routes.SUB_LIBRARY -> {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        ChipRow("每行列数",
                            listOf("0" to "自适应", "2" to "2 列", "3" to "3 列",
                                "4" to "4 列", "5" to "5 列"),
                            cfg["bookshelf_columns"] ?: "0") { vm.set("bookshelf_columns", it) }
                    }
                    Spacer(Modifier.height(12.dp))
                    SyncCard(vm, cfg,
                        onEditUrl = { editSyncUrl = true },
                        onEditToken = { editSyncToken = true })
                }

                Routes.SUB_SEARCH -> {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            ChipRow("默认排序",
                                listOf("mr" to "最新", "mv" to "最多点击", "tf" to "评分", "mp" to "有配图"),
                                cfg["default_sort"] ?: "mr") { vm.set("default_sort", it) }
                            Divider()
                            ChipRow("搜索栏",
                                listOf("hide" to "输入时隐藏", "keep" to "保持显示"),
                                cfg["search_bar_behavior"] ?: "hide") { vm.set("search_bar_behavior", it) }
                            Divider()
                            ChipRow("结果列数",
                                listOf("0" to "自适应", "2" to "2 列", "3" to "3 列", "4" to "4 列"),
                                cfg["search_columns"] ?: "0") { vm.set("search_columns", it) }
                            Divider()
                            SwitchRow("屏蔽已下载", "搜索结果里隐藏已下载的",
                                (cfg["hide_downloaded_in_search"] ?: "hide") != "off") {
                                vm.set("hide_downloaded_in_search", if (it) "hide" else "off")
                            }
                            Divider()
                            SwitchRow("屏蔽已看完", "搜索结果里隐藏已看完的",
                                (cfg["hide_finished_in_search"] ?: "hide") != "off") {
                                vm.set("hide_finished_in_search", if (it) "hide" else "off")
                            }
                        }
                    }
                }

                Routes.SUB_READER -> {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            ChipRow("览模式每屏页数",
                                (1..6).map { it.toString() to "$it 页" },
                                cfg["reader_pages_per_screen"] ?: "1") {
                                vm.set("reader_pages_per_screen", it)
                            }
                            Divider()
                            SwitchRow("沉浸模式", "阅读时隐藏系统栏",
                                cfg["reader_immersive"] != "0") {
                                vm.set("reader_immersive", if (it) "1" else "0")
                            }
                            Divider()
                            SwitchRow("读完自动标记", "进度超阈值后自动标记已读完",
                                (cfg["reader_auto_finish_scroll"] ?: "0") == "1") {
                                vm.set("reader_auto_finish_scroll", if (it) "1" else "0")
                            }
                            Divider()
                        }
                    }
                }

                Routes.SUB_DOWNLOAD -> {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            RowItem("下载管理", "查看进度 / 重试 / 清理") {
                                nav.navigate(Routes.DOWNLOADS)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // v1.7.9：并发设置（总并发 = 本数 × 图片并发）
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            // v1.8.7：档位整体上调 —— 官方客户端默认 30 并发，
                            // 原来最高只能选到 12，天花板比官方低一半以上。
                            // v1.8.9：语义从「每本多少」改为「**全局一共多少**」
                            // （闸门改为跨书共享，与 jmcomic 的 image_concurrency 一致）。
                            ChipRow("图片并发数",
                                listOf("8" to "8", "16" to "16", "24" to "24 · 默认",
                                    "30" to "30", "32" to "32"),
                                (cfg["max_concurrent_downloads"] ?: "24")
                                    .let { if (it in listOf("8", "16", "24", "30", "32")) it else "24" }
                            ) { vm.set("max_concurrent_downloads", it) }
                            Divider()
                            ChipRow("同时下载本数",
                                listOf("1" to "1", "2" to "2 · 默认", "3" to "3"),
                                (cfg["book_download_concurrency"] ?: "2")
                                    .let { if (it in listOf("1", "2", "3")) it else "2" }
                            ) { vm.set("book_download_concurrency", it) }
                            val bc = (cfg["book_download_concurrency"] ?: "2").toIntOrNull() ?: 2
                            val ic = (cfg["max_concurrent_downloads"] ?: "24").toIntOrNull() ?: 24
                            Text(
                                "图片并发 = **全局** ${ic} 个请求（不限单本，多本共享这 $ic 个名额）。" +
                                    "实测：6→107 KB/s、12→210 KB/s、24→681 KB/s，官方客户端默认 30。" +
                                    "同时下 $bc 本只是把这 $ic 个名额分给 $bc 本，总量不变。" +
                                    "请求会分散到 6 个 CDN 域名上；弱网 / 被限流时调低更稳。改动立即生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                else -> {
                    // 关于
                    val context = LocalContext.current
                    var apk by remember { mutableStateOf("…") }
                    var userData by remember { mutableStateOf("…") }
                    var comics by remember { mutableStateOf("…") }
                    LaunchedEffect(Unit) {
                        withContext(Dispatchers.IO) {
                            apk = formatBytes(runCatching {
                                java.io.File(context.packageManager
                                    .getApplicationInfo(context.packageName, 0).sourceDir).length()
                            }.getOrDefault(0L))
                            userData = formatBytes(runCatching {
                                context.dataDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                            }.getOrDefault(0L))
                            comics = formatBytes(runCatching { vm.downloadedBytes() }.getOrDefault(0L))
                        }
                    }
                    AboutCard(context)
                    Spacer(Modifier.height(12.dp))
                    // v1.7.1：调试日志开关（排查下载/搜索问题时开）
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            var dbg by remember { mutableStateOf(JmDebug.verbose) }
                            SwitchRow("调试日志", "记录全链路日志（排查问题时开启）", dbg) { on ->
                                JmDebug.setVerbose(context, on)
                                dbg = on
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            InfoRow("版本", "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                            InfoRow("安装包大小", apk)
                            InfoRow("用户数据占用", userData)
                            InfoRow("漫画本体占用", comics)
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (nightPicker) {
        NightTimeDialog(
            start = nightStart, end = nightEnd,
            onDismiss = { nightPicker = false },
            onConfirm = { s, e -> vm.setNightStart(s); vm.setNightEnd(e); nightPicker = false }
        )
    }

    if (editSyncUrl) {
        SyncEditDialog(
            title = "服务器地址",
            initial = cfg["sync_server_url"] ?: "",
            hint = "https://your-server:port",
            onDismiss = { editSyncUrl = false },
            onConfirm = { vm.setSyncServerUrl(it); editSyncUrl = false }
        )
    }
    if (editSyncToken) {
        SyncEditDialog(
            title = "同步密钥",
            initial = cfg["sync_token"] ?: "",
            hint = "服务器 token（鉴权用）",
            onDismiss = { editSyncToken = false },
            onConfirm = { vm.setSyncToken(it); editSyncToken = false }
        )
    }
}

/** 分类的标题/图标/主题色。 */
private data class GroupMeta(
    val title: String,
    val icon: ImageVector,
    val tint: Color
)

private fun groupMeta(group: String): GroupMeta = when (group) {
    Routes.SUB_APPEARANCE -> GroupMeta("外观", Icons.Filled.Brush, Color(0xFF7C6BF0))
    Routes.SUB_LIBRARY -> GroupMeta("书架", Icons.Filled.MenuBook, Color(0xFF3B82F6))
    Routes.SUB_SEARCH -> GroupMeta("搜索", Icons.Filled.Search, Color(0xFF06B6D4))
    Routes.SUB_READER -> GroupMeta("阅读", Icons.Filled.MenuBook, Color(0xFFF59E0B))
    Routes.SUB_DOWNLOAD -> GroupMeta("下载", Icons.Filled.CloudDownload, Color(0xFF10B981))
    else -> GroupMeta("关于", Icons.Filled.Info, Color(0xFF64748B))
}

// ── 组件 ──────────────────────────────────────────────

/**
 * 云同步卡片（v1.10.0）：启用开关 + 服务器/密钥编辑 + 立即同步。
 * 同步的是「看过的漫画 JM 号」账本（comic_status 全集），本地为权威、服务器为备份账本。
 */
@Composable
private fun SyncCard(
    vm: SettingsViewModel,
    cfg: Map<String, String>,
    onEditUrl: () -> Unit,
    onEditToken: () -> Unit,
) {
    val syncUi by vm.syncUi.collectAsState()
    val enabled = (cfg["sync_enabled"] ?: "0") == "1"
    val lastAt = cfg["sync_last_at"]?.toLongOrNull() ?: 0L
    val lastTime = if (lastAt > 0)
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastAt))
    else "从未同步"
    // 本次会话点过同步 → 显示即时结果；否则显示上次持久化结果
    val resultLine = when {
        syncUi.running -> "同步中…"
        syncUi.message.isNotBlank() -> "${syncUi.message} · $lastTime"
        (cfg["sync_last_result"] ?: "").isNotBlank() -> "${cfg["sync_last_result"]} · $lastTime"
        else -> "启用后每次启动自动备份；换机/重装一键找回"
    }
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth()) {
            SwitchRow("云同步", "看过的漫画 JM 号备份到自有服务器", enabled) {
                vm.setSyncEnabled(it)
            }
            Divider()
            RowItem("服务器", cfg["sync_server_url"] ?: "", onClick = onEditUrl)
            Divider()
            RowItem("密钥", maskToken(cfg["sync_token"] ?: ""), onClick = onEditToken)
            Divider()
            RowItem(
                label = "立即同步",
                value = resultLine,
                onClick = { if (!syncUi.running) vm.syncNow() }
            )
        }
    }
}

/** token 遮罩展示：前4 + … + 后4，过短整体打码。 */
private fun maskToken(t: String): String =
    if (t.length <= 8) "••••••" else "${t.take(4)}••••${t.takeLast(4)}"

/** 云同步配置编辑弹窗（服务器地址 / 密钥共用）。 */
@Composable
private fun SyncEditDialog(
    title: String,
    initial: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(hint, style = MaterialTheme.typography.labelSmall) }
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    label: String,
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = current == value,
                    onClick = { onSelect(value) },
                    label = { Text(text, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint.isNotBlank()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RowItem(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (value.isNotBlank()) {
                Text(
                    value,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AboutCard(context: Context) {
    val iconRes = when (AppIconManager.getSelectedIcon(context)) {
        AppIconManager.ICON_ALT1 -> R.drawable.ic_launcher_alt1_preview
        AppIconManager.ICON_ALT2 -> R.drawable.ic_launcher_alt2_preview
        AppIconManager.ICON_DOUBAO2 -> R.drawable.ic_launcher_doubao2_preview
        AppIconManager.ICON_DOUBAO3 -> R.drawable.ic_launcher_doubao3_preview
        AppIconManager.ICON_DOUBAO4 -> R.drawable.ic_launcher_doubao4_preview
        else -> R.drawable.ic_launcher_preview
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(92.dp).padding(10.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(context.getString(R.string.app_name), style = MaterialTheme.typography.titleMedium)
        Text(
            "本地漫画阅读器 · 数据全部存储在本机",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NightTimeDialog(
    start: String,
    end: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var s by remember { mutableStateOf(start) }
    var e by remember { mutableStateOf(end) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("夜间时段") },
        text = {
            Column {
                Text("开始（HH:mm）", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = s, onValueChange = { s = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text("结束（HH:mm）", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = e, onValueChange = { e = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(s, e) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
