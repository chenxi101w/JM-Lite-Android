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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
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
    // v1.27.0：底栏收放只挂在**书架 / 搜索**两个内容页（设置页与详情页常驻）
    val barCollapsible = currentRoute == Routes.LIBRARY || currentRoute == Routes.SEARCH
    // 底栏展开度（1=全展开，0=全收起）。声明在 Scaffold 之外，
    // 因为 bottomBar 与 content 两个槽位都要读它。
    val tabExpand = remember { Animatable(1f) }
    val tabDir = remember { mutableIntStateOf(0) }
    val outerScope = rememberCoroutineScope()
    // 「刚进去时是拉起的」：切页（含 Tab 切换）一律复位成展开
    LaunchedEffect(currentRoute) {
        tabExpand.snapTo(1f)
        tabDir.intValue = 0
    }

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
            CompactTabBar(
                currentRoute, nav,
                expand = if (barCollapsible) tabExpand.value else 1f,
            )
        },
        floatingActionButton = fab
    ) { padding ->
        // ── v1.23.0：悬浮球随内容滚动收拉 ──────────────────────────────
        // 下滑时缩到 0（完全收起，点不到），上滑立刻复原。
        // 用 nestedScroll 在**这一层**统一观察，不动每个页面的列表代码。
        // v1.27.0：同一个连接器顺带驱动**底栏**收放（书架/搜索页），方向一致：
        // 内容下滑 → 悬浮球与底栏一起收起；上滑 → 一起复原。
        val fabScale = remember { Animatable(1f) }
        val scrollDir = remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        val scrollConn = remember(barCollapsible) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    val dy = consumed.y
                    if (dy > 6f && scrollDir.intValue != 1) {
                        scrollDir.intValue = 1
                        scope.launch { fabScale.animateTo(0f, tween(180)) }
                        if (barCollapsible && tabDir.intValue != 1) {
                            tabDir.intValue = 1
                            outerScope.launch { tabExpand.animateTo(0f, tween(200)) }
                        }
                    } else if (dy < -6f && scrollDir.intValue != -1) {
                        scrollDir.intValue = -1
                        scope.launch { fabScale.animateTo(1f, tween(180)) }
                        if (barCollapsible && tabDir.intValue != -1) {
                            tabDir.intValue = -1
                            outerScope.launch { tabExpand.animateTo(1f, tween(200)) }
                        }
                    }
                    return Offset.Zero
                }
            }
        }
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
        Box(Modifier.fillMaxSize().nestedScroll(scrollConn)) {
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
                DraggableDownloadFab(
                    nav, safeTopPx = safeTopPx, safeBottomPx = safeBottomPx,
                    scale = fabScale.value,
                )
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
    /** v1.23.0：随内容滚动收拉的缩放（1=正常，0=完全收起）。
     *  用**真实尺寸变化**而不是 graphicsLayer 缩放 —— 后者会留下一个透明的
     *  56dp 点击区，收起后点那块地方会误进下载页。 */
    scale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("jmcomic_prefs", android.content.Context.MODE_PRIVATE)
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fabSizePx = with(density) { (56 * scale).dp.roundToPx() }
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
        if (scale > 0.02f) FloatingActionButton(
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
                .size((56 * scale).dp)
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

/**
 * 极简底部导航栏（v1.22.1 抽出；v1.27.0 按参考实现重做外观 + 加上滑收放）。
 *
 * ── 为什么要照抄参考实现 ──────────────────────────────────────────
 * 用户原话：「三个导航键丑的不行，你学习一下人家行吗」。
 * 翻 `jm-comic-android` 的 `CompactBottomBar` 逐项对齐，得到下面这套尺寸：
 *   · 内容区高 **52dp**（M3 NavigationBar 默认 80dp，砍掉一大半指示器留白）
 *   · 图标 **22dp**、文字 **12sp**（字比图标小一档，正是用户要的「字比图标稍小」）
 *   · 选中/未选中**换字形**（Filled ↔ Outlined），不靠指示器动画
 *   · `indication = null` —— 零涟漪，零动画
 *   · 点击区 `weight(1f) + fillMaxHeight`，整条栏三等分，无空隙
 *
 * 与参考实现的差别只有两处，都是用户明确要求的：
 *   1. 图标用 `Brush` 渐变着色（参考是单一 primary 色；用户说「可以用彩色但要好看，
 *      比如渐变」）。渐变通过 `colorFilter = ColorFilter.tint(brush)` 打在矢量上，
 *      **不需要**为每个 Tab 准备彩色图源。
 *   2. 参考实现的 `Label` 用 primary；我们按 Tab 取 `TopLevelRoute.gradient` 的深色端。
 *
 * ── 收放 ───────────────────────────────────────────────────────
 * 需求：「刚进去时是拉起的，向下滑动漫画展示时收起来，上滑自动拉起」。
 * 用**真实高度动画**而不是 `graphicsLayer` 平移 —— 平移会在收起后留下一块
 * 空白高度、且点得到（与悬浮球同一个坑）。这里同样不跟手，只认方向、阈值 6px。
 */
@Composable
fun CompactTabBar(
    currentRoute: String,
    nav: NavController,
    /** v1.27.0：1=完全展开，0=完全收起。外部（MainScaffold 的 nestedScroll）驱动。 */
    expand: Float = 1f,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 高度动画容器：clipToBounds 保证收起时里面的图标/文字被裁掉而不是溢出
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { (52f * expand).toDp() })
                    .clipToBounds()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (r in TopLevelRoute.values()) {
                        val selected = currentRoute == r.route
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    if (!selected) {
                                        // popUpTo 到**起始目的地**（书架）：用根图 id 会在当前页是
                                        // 普通目的地时把整张返回栈清掉 → 点了没反应（v1.7.5 的教训）
                                        nav.navigate(r.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            // 竖排：图标在上、文字在下 —— 这是用户嫌「字比图标小」
                            // 时真正的诉求来源（横排时两者基线不齐，字显得更小更歪）。
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                // 选中态用 `tint = Transparent` 把图标画成「空模」，
                                // 再用 drawWithContent + SrcIn 把渐变**灌进图标形状**里。
                                // （material3 的 Icon 只接受 Color 型 tint，
                                //   要上渐变只能走后处理这一条路 —— 不新增任何图片资源。）
                                // 未选中直接给灰 tint。
                                if (selected) {
                                    Icon(
                                        imageVector = r.icon,
                                        contentDescription = r.label,
                                        tint = Color.Transparent,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .drawWithContent {
                                                drawContent()
                                                drawRect(
                                                    brush = Brush.horizontalGradient(r.gradient),
                                                    blendMode = BlendMode.SrcIn,
                                                )
                                            },
                                    )
                                } else {
                                    Icon(
                                        imageVector = r.iconIdle,
                                        contentDescription = r.label,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                                Text(
                                    text = r.label,
                                    fontSize = 11.sp,
                                    lineHeight = 13.sp,
                                    color = if (selected) r.gradient.first() else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
            // 系统导航栏安全区：始终保留，收起时也不让内容顶到小白条下面
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}
