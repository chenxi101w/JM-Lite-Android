package com.example.jmreader.ui.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.jmreader.data.local.Book
import com.example.jmreader.ui.UiState
import com.example.jmreader.ui.components.ComicGrid
import com.example.jmreader.ui.components.PrettyLoader
import kotlinx.coroutines.launch

/**
 * 轻量版搜索（v1.0.0）：只保留**关键词搜索** + 结果网格（点卡片进详情 / 下载 / 加载更多）。
 * 删除：模式切换（作者/标签/JM号）、排序/时间筛选、搜索历史、屏蔽菜单、标签输入与逻辑。
 * 搜索参数固定：keyword 模式 + 最新排序 + 全部时间（Python 侧默认值）。
 */
@Composable
fun SearchBody(
    padding: androidx.compose.foundation.layout.PaddingValues,
    nav: NavController,
    vm: SearchViewModel,
    initialQuery: String? = null,
    initialMode: String = "keyword"
) {
    val state by vm.state.collectAsState()
    val keyword by vm.keyword.collectAsState()
    val downloadedIds by vm.downloadedIds.collectAsState()
    val downloadingIds by vm.downloadingIds.collectAsState()
    // v1.5.0：接上「设置 → 搜索 → 结果列数」（此前该配置读了但 UI 写死 columns=0，等于死配置）
    val gridColumns by vm.gridColumns.collectAsState()
    // v1.26.0：主页顶部分类快捷入口（内容就是关键词，点一下直接搜）
    val homeCategories by vm.homeCategories.collectAsState()
    val hasMore by vm.hasMore.collectAsState()
    // v1.2.0：区分「还没搜过」与「正在搜」——
    // VM 的初始 state 就是 UiState.Loading，此前一进搜索页就无谓地转圈（用户反馈）。
    val searched by vm.searched.collectAsState()
    // v1.28.0：首页改版新增
    val categoryBarMode by vm.categoryBarMode.collectAsState()
    val orderBy by vm.orderBy.collectAsState()
    val sortOptionsExpanded by vm.sortOptionsExpanded.collectAsState()

    // v1.1.8：搜索后收起输入法。
    // 两版尝试的经验（真机实测）：
    //   ① 只调 focusManager.clearFocus()          → 无效
    //   ② 再加 keyboardController?.hide()         → 对百度输入法仍无效
    //   ③ 直接走 Android 原生 IME 接口（本实现）  → 有效
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    // 诊断日志（v1.1.8）：确认是否执行、以及原生 IME 是否接受请求
    val hideIme = {
        focusManager.clearFocus()
        keyboardController?.hide()
        val immResult = runCatching {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }.getOrElse { "异常: ${it.message}" }
        android.util.Log.i(
            "JmSearch",
            "hideIme 执行：controller=${if (keyboardController != null) "非空" else "null"}，immResult=$immResult，token=${view.windowToken != null}"
        )
    }

    // 兜底（v1.1.8）：**仅在本次搜索动作之后**才收键盘。
    // ⚠️ 不能写成「state 变为 Success 就收」—— 从别的页面回到搜索页时，
    // VM 里仍是上次的 Success 状态，会立刻把键盘收掉，导致用户点输入框也弹不出来。
    var pendingHide by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (pendingHide && state is UiState.Success) {
            pendingHide = false
            hideIme()
        }
    }

    // 结果列表滚动位置：切页后由 VM 记录的位置恢复
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var navigating by remember { mutableStateOf(false) }
    DisposableEffect(gridState) {
        onDispose {
            vm.saveScroll(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        }
    }

    // 带参进来（详情页点作者/标签等）：自动填词并搜索
    LaunchedEffect(initialQuery, initialMode) {
        // v1.2.2：不再限制 `initialMode == "keyword"`。
        // 此前 author / tags 进来的关键词被直接丢弃（DiscoverScreen 还先把它置成了 null），
        // 于是「详情页点作者 / 点标签搜索」点了完全没反应。
        // Python 侧本就支持 keyword / author / tags / jmid，这里按传入 mode 原样搜索即可。
        if (!initialQuery.isNullOrBlank()) {
            vm.setKeyword(initialQuery)
            // v1.3.1：onSearch() 内部已调用 search() 真正发起抓取，
            // 此处不再重复调 search(initialQuery, initialMode)，否则会连发两次请求。
            vm.onSearch()
            gridState.requestScrollToItem(0)
        }
    }

    // ── v1.28.0：顶部区域整体重做（iOS 风格）─────────────────────────
    // 用户原话（这条需求曾因进程崩溃丢失，现已落盘 docs/需求/20261016_首页改版.md）：
    //   「加上分类搜索功能…咱们搞成一行，最新/最多喜欢/总排名/月排名/周排名等搞成
    //     一个小按钮放这一行最右边，搞成点一下就向下展开那种」
    //   「别再搞点搜索就蓝边的那种垃圾动画了…无感式跟界面融一起的设计很 ios」
    //   「分类栏分两种设置可改：①随搜索栏一起收拉 ②跟漫画一样常驻」
    //
    // 结构：
    //   [可收放区] iOS 胶囊搜索栏
    //              └─（mode=scroll 时）分类行：一行 = chips + 最右「排序」小按钮
    //                                    └─ 排序面板（点按钮向下展开）
    //   [常驻区]   （mode=stay 时）分类行 + 排序面板
    //   [内容]     结果网格
    //
    // 为什么这里能用**高度动画**收放（而底栏当年不行）：
    //   顶栏收起后下方网格是 weight(1f) 会自动填满，视觉上就是"内容长大一格"；
    //   不存在底栏那种「点击区被压成 0 却还停在原位」的问题（v1.27.0 的坑）。
    //
    // ⚠️ 分类行里 LazyRow 必须显式给 height —— 在 Row 中即使给了 weight(1f)，
    //    不给高度也会被量满并可能盖住兄弟节点吃掉点击（v1.27.0 搜索框 bug 同源）。
    val catInCollapsible = categoryBarMode != "stay"
    val sortOpts = listOf(
        "mr" to "最新",
        "mv" to "最多喜欢",
        "md" to "总排名",
        "mv_m" to "月排名",
        "mv_w" to "周排名",
    )
    val sortLabel = sortOpts.firstOrNull { it.first == orderBy }?.second ?: "最新"

    val SEARCH_ROW = 46f
    val CAT_ROW = 42f
    val PANEL_ROW = 44f
    val expandedDp = SEARCH_ROW +
        (if (catInCollapsible) CAT_ROW + (if (sortOptionsExpanded) PANEL_ROW else 0f) else 0f)

    val barHeight = remember { Animatable(expandedDp) }
    val barDir = remember { mutableIntStateOf(0) }
    // 无结果时强制常驻（没东西可滚，藏起来等于把搜索入口也弄没了）
    val forceShow = (state as? UiState.Success)?.data?.isNotEmpty() == false
    val barConn = remember(forceShow, expandedDp) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset, available: Offset, source: NestedScrollSource
            ): Offset {
                if (forceShow) return Offset.Zero
                val dy = consumed.y
                if (dy > 6f && barDir.intValue != 1) {
                    barDir.intValue = 1
                    // 收起时顺手收起排序面板，否则展开态会一直挂着
                    if (sortOptionsExpanded) vm.setSortOptionsExpanded(false)
                    scope.launch { barHeight.animateTo(0f, tween(180)) }
                } else if (dy < -6f && barDir.intValue != -1) {
                    barDir.intValue = -1
                    scope.launch { barHeight.animateTo(expandedDp, tween(180)) }
                }
                return Offset.Zero      // ⚠️ 不消费，列表照常滚
            }
        }
    }
    // 排序面板开合 / 分类栏模式变化 → 重算高度。
    // 「点一下向下展开」的动画就是这里：面板高度记进 expandedDp，
    // 外层 Box 高度从 A 动画到 A+PANEL_ROW，配合 clipToBounds 就是向下展开。
    LaunchedEffect(expandedDp, forceShow) {
        if (forceShow || barDir.intValue != 1) barHeight.animateTo(expandedDp, tween(180))
    }

    // 统一动作：用关键词发起一次搜索 + 收键盘 + 回到顶部
    val runSearch: (String) -> Unit = { kw ->
        vm.setKeyword(kw)
        vm.onSearch()
        pendingHide = true
        hideIme()
        scope.launch { gridState.requestScrollToItem(0) }
    }

    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .nestedScroll(barConn)
    ) {
        // ① 可收放区：搜索栏（含 scroll 模式下的分类行 + 排序面板）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(LocalDensity.current) { barHeight.value.toDp() })
                .clipToBounds()
        ) {
            Column(Modifier.fillMaxWidth()) {
                IosSearchField(
                    value = keyword,
                    onValueChange = { vm.setKeyword(it) },
                    onSearch = { kw ->
                        val t = kw.trim()
                        if (t.isNotBlank()) runSearch(t)
                    },
                )
                if (catInCollapsible) {
                    CategoryFilterRow(
                        categories = homeCategories,
                        keyword = keyword,
                        onPick = { cat ->
                            if (cat.isBlank()) vm.setKeyword("")   // 「全部」= 清空筛选
                            else if (keyword.trim() != cat) runSearch(cat)
                        },
                        sortLabel = sortLabel,
                        sortOpen = sortOptionsExpanded,
                        onToggleSort = { vm.setSortOptionsExpanded(!sortOptionsExpanded) },
                    )
                    if (sortOptionsExpanded) {
                        SortPanel(
                            options = sortOpts,
                            current = orderBy,
                            onPick = { v ->
                                vm.setSortOptionsExpanded(false)
                                if (orderBy != v) {
                                    vm.setOrderBy(v)
                                    if (searched && keyword.isNotBlank()) {
                                        vm.onSearch()
                                        scope.launch { gridState.requestScrollToItem(0) }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        // ② 常驻区：分类栏 mode=stay 时，分类行与排序面板不参与收放
        if (!catInCollapsible) {
            CategoryFilterRow(
                categories = homeCategories,
                keyword = keyword,
                onPick = { cat ->
                    if (cat.isBlank()) vm.setKeyword("")
                    else if (keyword.trim() != cat) runSearch(cat)
                },
                sortLabel = sortLabel,
                sortOpen = sortOptionsExpanded,
                onToggleSort = { vm.setSortOptionsExpanded(!sortOptionsExpanded) },
            )
            if (sortOptionsExpanded) {
                SortPanel(
                    options = sortOpts,
                    current = orderBy,
                    onPick = { v ->
                        vm.setSortOptionsExpanded(false)
                        if (orderBy != v) {
                            vm.setOrderBy(v)
                            if (searched && keyword.isNotBlank()) {
                                vm.onSearch()
                                scope.launch { gridState.requestScrollToItem(0) }
                            }
                        }
                    },
                )
            }
        }


        // 未搜索过：显示引导空态（而不是转圈）
        if (!searched) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "输入关键词开始搜索",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        } else when (val s = state) {
            UiState.Loading -> Column(
                modifier = Modifier.fillMaxWidth().padding(top = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) { PrettyLoader() }
            is UiState.Error -> Text(
                "错误: ${s.message}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp)
            )
            is UiState.Success -> ComicGrid(
                books = s.data,
                onBookClick = { book ->
                    if (!navigating) {
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
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                state = gridState,
                columns = gridColumns,
                onDownload = { vm.download(it) },
                downloadingIds = downloadingIds,
                downloadedIds = downloadedIds,
                footer = {
                    if (s.data.isNotEmpty()) {
                        if (hasMore) {
                            Button(onClick = { vm.loadMore() }, modifier = Modifier.fillMaxWidth()) {
                                Text("加载更多")
                            }
                        } else {
                            Text(
                                "已到底 · 共 ${s.data.size} 部",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            )
        }
    }
}
// ── v1.28.0 新增组件：iOS 风顶部区 ──────────────────────────────────

/**
 * iOS 风搜索栏。
 *
 * 为什么不用 `OutlinedTextField`：M3 的 OutlinedTextField 在获得焦点时会给一圈
 * **主题色描边**（默认蓝紫），用户原话「别再搞点搜索就蓝边的那种垃圾动画了」。
 * 这里改用 [BasicTextField] + 自绘 `decorationBox`：
 *   · 无描边、无 divider、无浮动 label；
 *   · 浅灰填充胶囊（`onSurface` 6% 透明度），与页面背景融合 —— iOS / Telegram 的做法；
 *   · 聚焦时**只有光标在闪**，不再有任何边框动画（"无感式"）；
 *   · 有内容时右侧出现清空按钮（iOS 习惯）。
 * 高度 46dp，其中胶囊本体 36dp（上下各 5dp 留白）。
 */
@Composable
private fun IosSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .padding(horizontal = 16.dp, vertical = 5.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(value) }),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
                Spacer(Modifier.width(7.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            "搜索关键词",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "清空",
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .clickable { onValueChange("") },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        },
    )
}

/**
 * 分类行：**只有一行** = 分类 chip（横向滚动） + 最右侧「排序」小按钮。
 * 用户要求把「最新/最多喜欢/总排名/月排名/周排名」收进这一行最右边的小按钮里，
 * 点一下**向下展开**（展开由调用方的 [SortPanel] + 高度动画完成）。
 */
@Composable
private fun CategoryFilterRow(
    categories: List<String>,
    keyword: String,
    onPick: (String) -> Unit,
    sortLabel: String,
    sortOpen: Boolean,
    onToggleSort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ⚠️ 显式 height：Row 内的 LazyRow 不给高度会被量满并吃掉兄弟节点点击
        LazyRow(
            modifier = Modifier
                .weight(1f)
                .height(32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 「全部」= 不过滤。「推荐」默认列表待接口支持（用户说"这个以后再说"）
            item { IosChip("全部", selected = keyword.isBlank()) { onPick("") } }
            items(categories.size) { i ->
                val cat = categories[i]
                IosChip(cat, selected = keyword.trim() == cat) { onPick(cat) }
            }
        }
        SortButton(sortLabel, sortOpen, onToggleSort)
    }
}

/** iOS 风胶囊 chip：无边框、无描边；未选中浅灰底，选中深色底 + 反白字。 */
@Composable
private fun IosChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 最右侧的「排序」小按钮：显示当前排序 + 上下箭头，点一下切换展开。 */
@Composable
private fun SortButton(label: String, open: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(start = 8.dp, end = 16.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(3.dp))
        Icon(
            imageVector = if (open) Icons.Filled.KeyboardArrowUp
            else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 向下展开的排序面板（横向可滚，避免小屏挤爆）。 */
@Composable
private fun SortPanel(
    options: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(start = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 16.dp),
    ) {
        items(options.size) { i ->
            val (value, label) = options[i]
            IosChip(label, selected = current == value) { onPick(value) }
        }
    }
}
