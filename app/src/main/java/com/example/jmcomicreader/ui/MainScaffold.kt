package com.example.jmreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
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
    // v1.27.1：底栏收放只挂在**书架 / 搜索**两个内容页（设置页与详情页常驻）
    val barCollapsible = currentRoute == Routes.LIBRARY || currentRoute == Routes.SEARCH
    // 底栏「滑出度」：0 = 完全可见，1 = 完全滑出屏幕下方。
    // ⚠️ 用**平移**而不是改高度 —— v1.27.0 改高度把点击区也压成 0，导致点不了。
    val tabHide = remember { Animatable(0f) }
    val tabDir = remember { mutableIntStateOf(0) }
    val outerScope = rememberCoroutineScope()
    // 「刚进去时是拉起的」：切页（含 Tab 切换）一律复位成完全可见
    LaunchedEffect(currentRoute) {
        tabHide.snapTo(0f)
        tabDir.intValue = 0
    }
    // 底栏占位总高 = 胶囊 56dp + 底边距（实测 15dp，系统导航栏更大时取系统值）。
    // 内容按这个值留底部空间，否则最后一张卡片会被胶囊压住。
    val tabBarHeight = 56.dp
    val sysInsetsOuter = WindowInsets.systemBars.asPaddingValues()
    val navBarsOuter = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabBarTotal = tabBarHeight + maxOf(15.dp, navBarsOuter)
    val densityOuter = androidx.compose.ui.platform.LocalDensity.current
    val tabBarTotalPx = with(densityOuter) { tabBarTotal.roundToPx() }

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
        // v1.27.1：底栏**不再走 bottomBar 槽位**。
        // 原因：槽位高度一变，Scaffold 就把 content 的 padding 一起改 → 内容跳动；
        // 而且「高度压 0」会让点击区消失。现在底栏改成内容层之上的**悬浮覆盖层**，
        // 由内容层用固定底部留白避让，收放只是水平滑动，不动任何布局尺寸。
        floatingActionButton = fab
    ) { padding ->
        // ── v1.23.0：悬浮球随内容滚动收拉 ──────────────────────────────
        // 用 nestedScroll 在**这一层**统一观察，不动每个页面的列表代码。
        // v1.27.1：同一个连接器驱动**底栏**收放（书架/搜索页）。
        //  ⚠️ v1.27.0 用「单帧 dy > 6px」判方向 —— 手指随便抖一下就满足，
        //     底栏等于常驻收起状态，用户自然觉得「点不了」。现在改成**累积位移**
        //     判方向：下滑累计 48px 才收，上滑累计 12px 就放回来。
        //     48px ≈ 16dp（3x 屏），正常翻页轻松超过、手抖不会触发。
        val fabScale = remember { Animatable(1f) }
        val scrollDir = remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        val scrollConn = remember(barCollapsible) {
            object : NestedScrollConnection {
                /** 方向切换后累积的位移（px），用来过滤手抖。 */
                private var acc = 0f

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    acc += consumed.y
                    if (acc > 48f && scrollDir.intValue != 1) {
                        scrollDir.intValue = 1
                        acc = 0f
                        scope.launch { fabScale.animateTo(0f, tween(180)) }
                        // 下滑 → 底栏整条滑出屏幕
                        if (barCollapsible && tabDir.intValue != 1) {
                            tabDir.intValue = 1
                            outerScope.launch { tabHide.animateTo(1f, tween(220)) }
                        }
                    } else if (acc < -12f && scrollDir.intValue != -1) {
                        scrollDir.intValue = -1
                        acc = 0f
                        scope.launch { fabScale.animateTo(1f, tween(180)) }
                        // 上滑 → 底栏滑回来（恢复门槛更低：想切页时蹭一下就回来）
                        if (barCollapsible && tabDir.intValue != -1) {
                            tabDir.intValue = -1
                            outerScope.launch { tabHide.animateTo(0f, tween(220)) }
                        }
                    }
                    return Offset.Zero
                }
            }
        }
        // padding 来自 Scaffold：top = TopAppBar 高度（含状态栏）。
        // 由于我们设置了 contentWindowInsets=0，无 TopAppBar 时需自己补 statusBars.top，
        // 否则内容会顶到状态栏下面、最上面点不到。
        // v1.27.1：底部留白改成**自己算的 tabBarTotal** —— 底栏已不在 bottomBar 槽位，
        // Scaffold 给的 bottom padding 会是 0。这个值恒定，所以底栏滑动时内容不跳。
        val systemInsets = WindowInsets.systemBars.asPaddingValues()
        val merged = androidx.compose.foundation.layout.PaddingValues(
            start = padding.calculateStartPadding(LayoutDirection.Ltr) +
                systemInsets.calculateStartPadding(LayoutDirection.Ltr),
            top = padding.calculateTopPadding() +
                (if (!showTopBar) systemInsets.calculateTopPadding() else 0.dp),
            end = padding.calculateEndPadding(LayoutDirection.Ltr) +
                systemInsets.calculateEndPadding(LayoutDirection.Ltr),
            bottom = tabBarTotal
        )
        Box(Modifier.fillMaxSize().nestedScroll(scrollConn)) {
            Column(Modifier.fillMaxSize()) {
                content(merged)
            }
            // ── v1.27.1：底栏做悬浮覆盖层，整条上下平移实现收放 ──────────
            // 平移量 = tabBarTotalPx × 滑出度，1 时整条在屏幕外。
            // 平移不改变布局尺寸，所以不存在「被压扁点不了」的中间态；
            // 滑出屏幕后自然点不到（那是不可见，不是可点区域错乱）。
            CompactTabBar(
                currentRoute, nav,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset {
                        IntOffset(
                            0,
                            (tabBarTotalPx * (if (barCollapsible) tabHide.value else 0f)).roundToInt()
                        )
                    }
            )
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
 * 底部导航栏（v1.22.1 抽出；v1.27.2 按**录屏实测**重做为悬浮胶囊）。
 *
 * ── 尺寸来源 ───────────────────────────────────────────────────────
 * 参考实现被加固（`dev.mocika.shield`），源码拿不到，所以改成**从录屏里量**。
 * 用户录屏 576×1280 ↔ 真机 1080×2400（系数 0.625 px→dp，density=3）。实测：
 *
 * | 项 | 录屏像素 | 换算 |
 * |---|---|---|
 * | 胶囊高 | 89（y 1168..1256） | **56dp** |
 * | 左/右边距 | 21 | **13dp** |
 * | 距屏幕底 | 24 | **15dp**（浮在手势条上方） |
 * | 圆角半径 | 24 | **15dp**（圆角矩形，不是全圆角胶囊） |
 * | 图标 ink | 26×23 / 29×29 | **约 22~24dp** |
 * | 文字单字 | 15~17 | **约 11sp** |
 * | 图标↔文字间距 | 15 | **约 5dp** |
 * | 三项中心 | x=112/288/466 | 等宽各 110dp |
 *
 * ── 与 v1.27.0/1.27.1 的两处结构性修正 ─────────────────────────────
 *  1. **通栏 → 悬浮胶囊**。原来是 `fillMaxWidth` 的白条贴屏幕底；
 *     实测参考是**左右各留 15dp、大圆角、带阴影、浮在内容之上**的胶囊。
 *  2. **竖排 → 横排**。实测参考是「图标 + 文字横排」。
 *     （v1.27.0 我按「字比图标小」的诉求改成竖排，方向是错的 ——
 *      参考本来就是横排，字只是比图标小一号。）
 *
 * 配色也从「每个 Tab 一个渐变色」回到参考实现的**单色**：
 * 选中 = `onSurface`（近黑，SemiBold），未选中 = `onSurfaceVariant`（灰）。
 *
 * ── 收放 ───────────────────────────────────────────────────────
 * ⚠️ v1.27.0 用 `height(52dp × expand)` 把栏连**点击区**一起压成 0 高，
 * 用户反馈「把导航栏搞小的点不了」；v1.27.1 改成整条平移滑出，修好了。
 * 本版继续沿用**平移**：栏自身尺寸恒定，收起 = 平移出屏幕外。
 * 本组件不管收放，只暴露 [modifier]，由 [MainScaffold] 统一驱动。
 */
@Composable
fun CompactTabBar(
    currentRoute: String,
    nav: NavController,
    modifier: Modifier = Modifier,
) {
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Surface(
        // 逐像素实测（视频 576×1280 ↔ 真机 1080×2400，1 视频px = 0.625dp）：
        //   胶囊 y 1168..1256 = 89px = **56dp**；左右边缘 x 21/557 = 边距 **13dp**；
        //   其上边缘的圆角轮廓：顶部 dx=24px→ 半径约 **15dp**（**不是**胶囊形/全圆角）。
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 13.dp,
                end = 13.dp,
                // 胶囊底边距屏幕底 24px = 15dp；系统导航栏更大时（三键导航）用系统值
                bottom = maxOf(15.dp, navBarBottom),
            ),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(15.dp),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
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
                    // 横排：图标 + 文字（与参考实现一致）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = if (selected) r.icon else r.iconIdle,
                            contentDescription = r.label,
                            tint = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = r.label,
                            fontSize = 11.sp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
