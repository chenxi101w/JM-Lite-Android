@file:OptIn(ExperimentalMaterial3Api::class)
package com.example.jmreader.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.jmreader.ui.MainScaffold
import com.example.jmreader.ui.Routes
import com.example.jmreader.ui.topLevelRouteOf
import com.example.jmreader.util.AppIconManager
import com.example.jmreader.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置**首页**（v1.5.4 重做）：只放 6 个分类入口，取代 v1.5.0 那种"一长条全展开"的排版。
 *
 * 设计要点：
 *  - 每个分类一张**渐变卡**，图标放在圆角底块上，卡片右侧显示当前取值摘要；
 *  - 分类用**不同色系**区分（外观=紫、书架=蓝、搜索=青、阅读=琥珀、下载=绿、关于=灰）；
 *  - 顶部保留 App 图标 + 名称 + 版本，一眼看清"这是设置"；
 *  - 具体设置项在二级页 [SettingsGroupScreen]。
 */
@Composable
fun SettingsScreen(nav: NavController, factory: JmViewModelFactory) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    MainScaffold(
        currentRoute = topLevelRouteOf(Routes.SETTINGS),
        nav = nav,
        // v1.5.1：关掉默认「下载」悬浮按钮 —— 它会压住下方的开关行。
        // 设置首页已有「下载」分类入口，不需要 FAB。
        fab = {},
    ) { padding ->
        val context = LocalContext.current
        val cfg by vm.config.collectAsState()
        val theme by vm.theme.collectAsState()

        // 占用摘要（页面底部一行小字）
        var apk by remember { mutableStateOf("…") }
        var userData by remember { mutableStateOf("…") }
        var comics by remember { mutableStateOf("…") }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                apk = formatBytes(runCatching {
                    java.io.File(
                        context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
                    ).length()
                }.getOrDefault(0L))
                userData = formatBytes(runCatching {
                    context.dataDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }.getOrDefault(0L))
                comics = formatBytes(runCatching { vm.downloadedBytes() }.getOrDefault(0L))
            }
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            AppHeader(context)
            Spacer(Modifier.height(16.dp))

            GroupCard(
                title = "外观", desc = "主题、风格、夜间时段",
                icon = Icons.Filled.Brush, tint = Color(0xFF7C6BF0),
                summary = when (theme) {
                    "light" -> "浅色"; "dark" -> "深色"; "auto" -> "定时"; else -> "跟随系统"
                },
                onClick = { nav.goSettings(Routes.SUB_APPEARANCE) }
            )
            Spacer(Modifier.height(10.dp))
            GroupCard(
                title = "书架", desc = "每行列数",
                icon = Icons.Filled.MenuBook, tint = Color(0xFF3B82F6),
                summary = when (cfg["bookshelf_columns"] ?: "0") {
                    "0" -> "自适应"; else -> "${cfg["bookshelf_columns"]} 列"
                },
                onClick = { nav.goSettings(Routes.SUB_LIBRARY) }
            )
            Spacer(Modifier.height(10.dp))
            GroupCard(
                title = "搜索", desc = "排序、列数、屏蔽规则",
                icon = Icons.Filled.Search, tint = Color(0xFF06B6D4),
                summary = when (cfg["default_sort"] ?: "mr") {
                    "mv" -> "最多点击"; "tf" -> "评分"; "mp" -> "有配图"; else -> "最新"
                },
                onClick = { nav.goSettings(Routes.SUB_SEARCH) }
            )
            Spacer(Modifier.height(10.dp))
            GroupCard(
                title = "阅读", desc = "览模式页数、读完处理",
                icon = Icons.Filled.MenuBook, tint = Color(0xFFF59E0B),
                summary = (cfg["reader_pages_per_screen"] ?: "1") + " 页/屏",
                onClick = { nav.goSettings(Routes.SUB_READER) }
            )
            Spacer(Modifier.height(10.dp))
            GroupCard(
                title = "下载", desc = "下载管理、完成后处理",
                icon = Icons.Filled.CloudDownload, tint = Color(0xFF10B981),
                summary = when (cfg["download_after"] ?: "none") {
                    "hide" -> "隐藏记录"; "delete" -> "删除记录"; else -> "保持记录"
                },
                onClick = { nav.goSettings(Routes.SUB_DOWNLOAD) }
            )
            Spacer(Modifier.height(10.dp))
            GroupCard(
                title = "关于", desc = "版本、占用、署名",
                icon = Icons.Filled.Info, tint = Color(0xFF64748B),
                summary = "v${BuildConfig.VERSION_NAME}",
                onClick = { nav.goSettings(Routes.SUB_ABOUT) }
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "安装包 $apk · 数据 $userData · 漫画 $comics",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 跳到某个设置二级页。 */
private fun NavController.goSettings(group: String) {
    navigate(Routes.SETTINGS_SUB.replace("{group}", group))
}

// ── 组件 ──────────────────────────────────────────────

@Composable
private fun AppHeader(context: Context) {
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
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(76.dp).padding(8.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(context.getString(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Text(
            "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 分类入口卡：**渐变底 + 圆角图标块 + 标题/描述 + 右侧摘要胶囊 + 箭头**。
 *
 * 摘要（"3 列" / "最新" / "v1.5.4"）让用户不进二级页也知道当前设置是什么。
 */
@Composable
private fun GroupCard(
    title: String,
    desc: String,
    icon: ImageVector,
    tint: Color,
    summary: String,
    onClick: () -> Unit,
) {
    // v1.6.1 修：改用 Modifier.clickable —— `Card(onClick=)` 内部包了一层点击层，
    // 与 Row 上自己画的 background 叠在一起时，点击会被 Row 的绘制层吃掉（实测点卡片无反应）。
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(tint.copy(alpha = 0.18f), tint.copy(alpha = 0.05f))
                    )
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(tint.copy(alpha = 0.20f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
                Text(
                    desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .background(tint.copy(alpha = 0.24f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    maxLines = 1
                )
            }
            Spacer(Modifier.size(6.dp))
            Text(
                "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 信息行（左标签右数值），二级页复用。 */
@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
