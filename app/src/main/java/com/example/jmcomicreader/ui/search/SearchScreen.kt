package com.example.jmreader.ui.search

import android.widget.Toast
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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
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
    val hasMore by vm.hasMore.collectAsState()
    // v1.2.0：区分「还没搜过」与「正在搜」——
    // VM 的初始 state 就是 UiState.Loading，此前一进搜索页就无谓地转圈（用户反馈）。
    val searched by vm.searched.collectAsState()

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

    // ── v1.25.0：搜索栏随滚动收放 ──────────────────────────────────
    // 需求：「搜索页的搜索栏改成跟人家一样，上滑一下就自动出来」。
    // 与底部栏/悬浮球同一套做法：内容下滑 → 收起，上滑 → 展开（只认方向、不跟手）。
    // 用**真实高度动画**（外层 Box 裁切）而不是 graphicsLayer 平移 —— 平移会在收起后
    // 留下一块空白高度，看起来像「上面少了一块」。网格是 weight(1f)，
    // 搜索栏收起时它自动填满，视觉上就是内容长大一格。
    val barDp = 52f
    val barHeight = remember { Animatable(barDp) }
    val barDir = remember { mutableIntStateOf(0) }
    // 无结果时强制常驻（没东西可滚，藏起来等于把搜索入口也弄没了）
    val forceShow = (state as? UiState.Success)?.data?.isNotEmpty() == false
    val barConn = remember(forceShow) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset, available: Offset, source: NestedScrollSource
            ): Offset {
                if (forceShow) return Offset.Zero
                val dy = consumed.y
                if (dy > 6f && barDir.intValue != 1) {
                    barDir.intValue = 1
                    scope.launch { barHeight.animateTo(0f, tween(180)) }
                } else if (dy < -6f && barDir.intValue != -1) {
                    barDir.intValue = -1
                    scope.launch { barHeight.animateTo(barDp, tween(180)) }
                }
                return Offset.Zero      // ⚠️ 不消费，列表照常滚
            }
        }
    }
    LaunchedEffect(forceShow) {
        if (forceShow && barHeight.value != barDp) barHeight.animateTo(barDp, tween(180))
    }

    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .nestedScroll(barConn)
    ) {
        // 搜索输入框（回车即搜）
        // v1.1.9：去掉浮动 label（"搜索关键词（标题 / 标签）"这行小字），
        // 改用占位提示 + 压缩高度 + 胶囊圆角 —— 顶部不再显厚重，输入后文字自动消失。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(LocalDensity.current) { barHeight.value.toDp() })
                .clipToBounds()
        ) {
        OutlinedTextField(
            value = keyword,
            onValueChange = { vm.setKeyword(it) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            placeholder = {
                Text(
                    "搜索关键词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = {
                    val kw = keyword.trim()
                    if (kw.isBlank()) return@KeyboardActions
                    vm.setKeyword(kw)
                    // v1.3.2：onSearch() 内部已按当前条件调用 search() 真正发起抓取，
                    // 此处**不可**再调 vm.search(kw, "keyword") ——
                    // 那会造成两次并发搜索，`searchJob?.cancel()` 互相打断，
                    // 其中一次失败 → 页面顶部闪出一行红色「搜索失败」后又显示结果。
                    vm.onSearch()
                    // v1.1.8：立刻收起输入法，让结果区域占满视野
                    // （另置 pendingHide：结果返回后再收一次，覆盖异步路径）
                    pendingHide = true
                    hideIme()
                    scope.launch { gridState.requestScrollToItem(0) }
                }
            )
        )

        }   // ← 搜索栏高度动画 Box 结束

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