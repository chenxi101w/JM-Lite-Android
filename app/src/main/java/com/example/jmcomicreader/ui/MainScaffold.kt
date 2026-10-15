package com.example.jmreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavController
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────
// 全局「显示提示说明」开关（v5.0.12 起）
// ─────────────────────────────────────────────────────────────
val LocalShowHints = compositionLocalOf { true }

object ShowHintsState {
    var enabled by mutableStateOf(true)
}

/**
 * v6.0.0 MD3 一级页面通用骨架：
 *  - TopAppBar 大标题 + 品牌感（与搜索页 SearchBar 形成对照）
 *  - NavigationBar 含 tonalElevation（MD3 规范：3.dp）+ 窗口 insets 自动内边距
 *  - ExtendedFAB（可收起）悬停在右下角，带阴影与 tonal surface color
 *  - 全屏 edge-to-edge：contentWindowInsets 设为 0，内容自行使用 padding 避开系统栏
 *  - topBarActions 注入（页面专属按钮如分类管理），放 TopAppBar actions 槽
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    currentRoute: String,
    nav: NavController,
    topBarTitle: @Composable () -> Unit = { Text("JM 漫画") },
    topBarActions: @Composable RowScope.() -> Unit = {},
    showTopBar: Boolean = false,
    /**
     * v2.1：默认改为**空** —— 下载入口不再是钉死在右下角的 FAB，
     * 而是全屏 overlay 层上的**可拖动悬浮球**（[DraggableDownloadFab]）：
     * 位置随手拖、自动记忆（SharedPreferences）、点击进下载管理。
     * 需要页面专属 FAB 的仍可传此参数（如设置页传空）。
     */
    fab: @Composable () -> Unit = {},
    content: @Composable (padding: androidx.compose.foundation.layout.PaddingValues) -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        // 关键：edge-to-edge。默认 contentWindowInsets 会加 systemBars padding，
        // 设为 0 让内容延伸到系统栏下方，由页面自己通过 padding 处理避让
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showTopBar) {
                // v7.0.4：标题栏内容区压缩到 48dp（M3 默认 64dp），
                // 消除「标题 ↔ 下方内容」之间的大段留白。TopAppBar 自带 statusBars
                // insets 会把它撑到 64dp+状态栏，这里改由外层 Column 手动补状态栏，
                // TopAppBar 内部 windowInsets 置 0 —— 其内部 Row 固定 64dp 会向下溢出，
                // 但标题/操作按钮实际内容只占中间 ~44dp，完全落在 48dp 可视区内，无裁剪。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    Surface(
                        color = Color.Transparent,
                        modifier = Modifier.height(48.dp)
                    ) {
                        TopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) { topBarTitle() }
                            },
                            actions = topBarActions,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                scrolledContainerColor = MaterialTheme.colorScheme
                                    .surfaceColorAtElevation(2.dp),
                                titleContentColor = MaterialTheme.colorScheme.onBackground,
                                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        },
        bottomBar = {
            // ── v1.22.1：底部栏改为自绘极简栏（对齐参考实现 CompactBottomBar）──
            // 为什么换掉 M3 的 NavigationBar：它每次点击都会跑
            //   ① 选中指示器（胶囊）的缩放/淡入动画（约 300ms spring）；
            //   ② Material 涟漪（ripple）。
            // 结果就是「页面已经瞬时切过去了，底部栏自己还在动」——
            // 用户感知到的「不跟手/慢半拍」最后就落在这里。
            // 参考实现的做法是：**整条栏零动画**，靠三件事表达选中态：
            //   tint 变色 + 字重加粗 + 图标实心/线性互换（所以连指示器都不需要）。
            // 另外它是 52dp 紧凑高度（M3 默认 80dp），内容区能多出一截。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (r in TopLevelRoute.values()) {
                        val selected = currentRoute == r.route
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    // indication = null：关掉涟漪。参考实现如此，
                                    // 「点了就变」代替「点了先荡一圈水波」。
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    if (currentRoute != r.route) {
                                        // popUpTo 到**起始目的地**（书架）：用根图 id 会在
                                        // 当前页是普通目的地时把整张返回栈清掉 → 点了没反应（v1.7.5 的教训）
                                        nav.navigate(r.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = if (selected) r.icon else r.iconIdle,
                                    contentDescription = r.label,
                                    tint = if (selected) r.tint else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                                Text(
                                    text = r.label,
                                    color = if (selected) r.tint else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = fab
    ) { padding ->
        // padding 来自 Scaffold：top = TopAppBar 高度（含状态栏），bottom = NavigationBar 高度（含导航栏）
        // 但由于我们设置了 contentWindowInsets=0，无 TopAppBar 时需自己补 statusBars.top，
        // 否则内容会顶到状态栏下面、最上面点不到
        val systemInsets = WindowInsets.systemBars.asPaddingValues()
        val merged = androidx.compose.foundation.layout.PaddingValues(
            start = padding.calculateStartPadding(LayoutDirection.Ltr) +
                systemInsets.calculateStartPadding(LayoutDirection.Ltr),
            top = padding.calculateTopPadding() +
                (if (!showTopBar) systemInsets.calculateTopPadding() else 0.dp),
            end = padding.calculateEndPadding(LayoutDirection.Ltr) +
                systemInsets.calculateEndPadding(LayoutDirection.Ltr),
            bottom = padding.calculateBottomPadding()
        )
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                content(merged)
            }
            // ── v2.1：可拖动的下载悬浮球（下载页自身隐藏）──────────
            // ⚠️ v2.1.1 修正：M3 Scaffold 的 content 是**全屏**的（edge-to-edge），
            // bottomBar 只是绘制在 content 之上、避让量通过 merged padding 另发。
            // 悬浮球必须把 merged 的 top/bottom 安全区扣掉，否则初始位置/拖动边界
            // 会落进 NavigationBar 底下 —— 用户实测「球在导航栏下面被挡住」。
            val density = androidx.compose.ui.platform.LocalDensity.current
            val safeTopPx = with(density) { merged.calculateTopPadding().roundToPx() }
            val safeBottomPx = with(density) { merged.calculateBottomPadding().roundToPx() }
            if (currentRoute != Routes.DOWNLOADS) {
                DraggableDownloadFab(nav, safeTopPx = safeTopPx, safeBottomPx = safeBottomPx)
            }
        }
    }
}

/**
 * v2.1：**可拖动的下载悬浮球**。
 *
 * 用户需求：「下载入口的按钮能改成悬浮可以移动的」。
 *
 *  - 圆形 FAB，全屏 overlay 层，初始在内容区右下角；
 *  - **拖动**：`detectDragGestures` 实时更新位置，clamp 在
 *    `[safeTopPx + margin, 屏高 - safeBottomPx - margin - 球高]` —— 避开顶栏与底栏；
 *  - **位置记忆**：拖动结束写 `jmcomic_prefs` 的 `fab_x/fab_y`（像素），
 *    下次启动（含进程重启）回到上次的位置（越界自动拉回边界内）；
 *  - **点击 vs 拖动**：累计位移 < 12px 视为点击 → 进下载管理；
 *  - `pointerInput(maxX, maxY)`：边界变化（旋转/分屏）时重启手势检测，
 *    避免闭包捕获旧边界。
 */
@Composable
private fun DraggableDownloadFab(
    nav: NavController,
    safeTopPx: Int,
    safeBottomPx: Int,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("jmcomic_prefs", android.content.Context.MODE_PRIVATE)
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fabSizePx = with(density) { 56.dp.roundToPx() }
    val marginPx = with(density) { 16.dp.roundToPx() }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val minX = marginPx
        val maxX = (constraints.maxWidth - fabSizePx - marginPx).coerceAtLeast(minX)
        val minY = (safeTopPx + marginPx).coerceAtLeast(0)
        val maxY = (constraints.maxHeight - fabSizePx - marginPx - safeBottomPx).coerceAtLeast(minY)
        var pos by remember {
            val sx = prefs.getInt("fab_x", -1)
            val sy = prefs.getInt("fab_y", -1)
            // 未初始化 → 右下角；有记录但越出安全区（换机/旋转/旧版残留）→ clamp 回来
            mutableStateOf(
                if (sx in minX..maxX && sy in minY..maxY) IntOffset(sx, sy)
                else IntOffset(maxX, maxY)
            )
        }
        FloatingActionButton(
            // ★ v2.1.2 修「点悬浮球进不去下载页」：
            //   `detectDragGestures` 在**未超过拖动阈值**的轻点时不会触发 onDragEnd，
            //   把导航放在 onDragEnd 里 = 纯点击永远没反应（真机实测）。
            //   正确分工：**点击交给 FAB 原生 onClick**（内层手势先收到轻点）；
            //   **拖动由外层 detectDragGestures 消费**（consume 后 FAB 的 clickable
            //   自动取消 onClick）—— 两者经 Compose 事件消费机制天然互斥。
            onClick = { nav.navigate(Routes.DOWNLOADS) },
            shape = androidx.compose.foundation.shape.CircleShape,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 6.dp,
                pressedElevation = 8.dp
            ),
            modifier = Modifier
                .offset { IntOffset(pos.x.coerceIn(minX, maxX), pos.y.coerceIn(minY, maxY)) }
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = {
                            prefs.edit()
                                .putInt("fab_x", pos.x)
                                .putInt("fab_y", pos.y)
                                .apply()
                        },
                        onDrag = { c, amt ->
                            c.consume()
                            pos = IntOffset(
                                (pos.x + amt.x.roundToInt()).coerceIn(minX, maxX),
                                (pos.y + amt.y.roundToInt()).coerceIn(minY, maxY)
                            )
                        }
                    )
                }
        ) {
            Icon(Icons.Filled.Download, contentDescription = "下载管理（可拖动）")
        }
    }
}

/** 当前路由的一级片段（去掉参数），用于高亮底部导航。 */
fun topLevelRouteOf(route: String?): String {
    return when {
        route == null -> ""
        route.startsWith("search") -> Routes.SEARCH
        route.startsWith("library") -> Routes.LIBRARY
        route.startsWith("settings") -> Routes.SETTINGS
        else -> route
    }
}
