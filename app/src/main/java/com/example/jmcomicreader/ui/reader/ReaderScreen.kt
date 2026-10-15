package com.example.jmreader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.example.jmreader.util.fileImageRequest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.distinctUntilChanged
import com.example.jmreader.ui.JmViewModelFactory
import com.example.jmreader.ui.Routes
import com.example.jmreader.ui.UiState
import com.example.jmreader.ui.components.ComicCard
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    factory: JmViewModelFactory,
    nav: NavController,
    bookId: Int,
    photoId: Int,
    chapterId: Int
) {
    val vm: ReaderViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsState()
    val chapters by vm.chapters.collectAsState()
    // pageMap（本地章节已解码图片路径 index → 本地文件路径）**不在此处 collect**：
    // 它随每页解码完成而更新，若在顶层订阅会让整屏（顶栏/底部按钮/信息卡）跟着重组。
    // v14 起下沉到 LazyColumn/Grid 的 item 内各自订阅，解码完一页只重组那一页。

    val listState = rememberLazyListState()
    val pps by vm.pagesPerScreen.collectAsState()
    val isLocal by vm.isLocal.collectAsState()
    val deleting by vm.deleting.collectAsState()
    val progressColorHex by vm.progressColor.collectAsState()
    val immersiveEnabled by vm.immersiveEnabled.collectAsState()
    val isFinished by vm.isFinished.collectAsState()
    val autoFinishScroll by vm.autoFinishScroll.collectAsState()
    val localDeleted by vm.localDeleted.collectAsState()
    val resumePage by vm.resumePage.collectAsState()
    // v1.2.0：每页宽高比（进章节时预读图片头得到），列表据此固定每项高度
    val ratios by vm.pageRatios.collectAsState()
    val book by vm.book.collectAsState()
    val isFavorite by vm.isFavorite.collectAsState()
    val downloading by vm.downloading.collectAsState()
    val blackDeletedHandle by vm.blackDeletedHandle.collectAsState()
    val scope = rememberCoroutineScope()
    // 滑轨颜色（高度/进度由 ReaderProgressRail 内部自持，v1.2.0 起不再提到此处）
    val railColor = remember(progressColorHex) {
        try { Color(android.graphics.Color.parseColor(progressColorHex)) }
        catch (_: Exception) { Color(0xFF7B6FE0) }
    }
    var showChapters by remember { mutableStateOf(false) }
    var overview by remember { mutableStateOf(false) }
    var jumpTo by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // 下载完成后的询问：「下载完成，是否删除这本漫画的本地内容？」（v5.3.0）
    var askDeleteAfterDownload by remember { mutableStateOf(false) }
    // 翻章标记（4.2.2 修复）：goTo 会销毁当前阅读器页面（nav.navigate + popUpTo inclusive），
    // 若 onDispose 因此触发 onReaderExit，会把「翻章」误判成「退出」→ 读完最后一章后翻上一章
    // 也会调度看完自动删除 → 60 秒后本地被删、你还在读上一章就白屏。
    // 切章时置位，onDispose 看到该标记则跳过自动删除调度；真正退出（返回/返回手势）才触发。
    var switchingChapter by remember { mutableStateOf(false) }
    // 沉浸式阅读：默认进入沉浸态（UI 隐藏），点击画面最上方区域唤起（v4.14.0）
    var immersive by remember { mutableStateOf(true) }

    LaunchedEffect(photoId) { vm.loadChapter(photoId, bookId, chapterId) }
    // v5.11.0：本地已删的漫画处理（设置 → 阅读 → 拉黑/删除的漫画 → auto_delete）：
    // auto_delete = 本地已删 → 退出阅读器。v1.17.0 拉黑下线后只剩「本地已删」这一支。
    LaunchedEffect(localDeleted, blackDeletedHandle) {
        if (blackDeletedHandle == "auto_delete" && localDeleted) {
            nav.popBackStack()
        }
    }
    // 标记条「知道了」关闭
    var markDismissed by remember { mutableStateOf(false) }
    // 下载完成浮条 10 秒后自动消失（不打扰阅读）
    LaunchedEffect(askDeleteAfterDownload) {
        if (askDeleteAfterDownload) { delay(10000); askDeleteAfterDownload = false }
    }
    // 退出阅读器（页面销毁）时：若开启「看完自动删除」且整本已看完，调度延迟删除本地内容（P0-2）。
    // 4.2.2：goTo 翻章也会销毁本页（nav.navigate + popUpTo inclusive），但那是「切章」不是「退出」——
    // 切章时 switchingChapter 已置位，跳过调度，避免读完最后一章翻上一章误删本地。
    DisposableEffect(Unit) { onDispose { if (!switchingChapter) vm.onReaderExit(bookId) } }
    LaunchedEffect(jumpTo) {
        jumpTo?.let { listState.scrollToItem(it); jumpTo = null }
    }
    // 恢复上次阅读页码（进入章节时若同章有进度则滚到上次位置）
    LaunchedEffect(state) {
        if (state is UiState.Success && resumePage > 0) {
            listState.scrollToItem(resumePage)
            vm.clearResumePage()
        }
    }
    // 沉浸式（v4.14.0 新交互）：默认沉浸态（顶栏/滑轨/底部隐藏）；
    // 点击画面最上方区域（120dp）唤起顶栏与底部按钮；唤起后开始滚动 2 秒自动回沉浸。
    var hideJob: Job? by remember { mutableStateOf(null) }
    fun scheduleHide() {
        hideJob?.cancel()
        hideJob = scope.launch { delay(2000); immersive = true }
    }
    LaunchedEffect(listState, immersiveEnabled) {
        if (!immersiveEnabled) { immersive = false; return@LaunchedEffect }
        immersive = true
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                // 顶栏已唤起时，一滚动就计时 2 秒后自动回沉浸（避免挡着看）
                if (scrolling && !immersive) scheduleHide()
            }
    }
    // 持续保存阅读页码（用于续读：再次进同一章恢复到上次位置，防抖 600ms）
    LaunchedEffect(state) {
        if (state !is UiState.Success) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .debounce(600)
            .collect { idx -> if (idx >= 0) vm.savePage(bookId, chapterId, idx) }
    }

    // 滚到底自动标记：本章读完 + 按设置自动标整本看完 / 读完自动删本章（v7）
    var chapterReadFired by remember(photoId, chapterId) { mutableStateOf(false) }
    LaunchedEffect(listState, state) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                val total = (state as? UiState.Success)?.data ?: 0
                if (total > 0 && last >= total - 1 && !chapterReadFired) {
                    chapterReadFired = true
                    // 只有最后一章才可能触发整本「看完」（4.2.1：避免读中间章节误判整本完成 → 自动删除）
                    val isLast = chapters.isNotEmpty() && chapters.last().id == chapterId
                    vm.onChapterRead(bookId, chapterId, total, last, isLast)
                }
            }
    }
    val currentIdx = chapters.indexOfFirst { it.id == chapterId }
    val prev = chapters.getOrNull(currentIdx - 1)
    val next = chapters.getOrNull(currentIdx + 1)

    fun goTo(chapterIdNext: Int) {
        switchingChapter = true
        nav.navigate(Routes.reader(bookId, chapterIdNext, chapterIdNext)) {
            popUpTo(Routes.READER) { inclusive = true }
        }
    }

    Scaffold(
        topBar = {
            if (!immersive) {
                TopAppBar(
                title = { Text("第 ${if (currentIdx >= 0) currentIdx + 1 else 0}/${chapters.size} 章") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = { showChapters = true }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "章节") }
                    IconButton(
                        onClick = { prev?.let { goTo(it.id) } },
                        enabled = prev != null
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一章") }
                    IconButton(
                        onClick = { next?.let { goTo(it.id) } },
                        enabled = next != null
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一章") }
                    // 溢出菜单：漫画详情 / 览图切换 / 删除本书（顶栏位置有限，收进菜单）
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("漫画详情") },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    nav.navigate(Routes.detail(bookId, local = isLocal))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (overview) "回到阅读" else "缩略图览") },
                                leadingIcon = { Icon(Icons.Filled.GridView, contentDescription = null) },
                                onClick = { showMenu = false; overview = !overview }
                            )
                            DropdownMenuItem(
                                text = { Text(if (deleting) "删除中…" else "删除本书") },
                                enabled = isLocal && !deleting,
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { showMenu = false; confirmDelete = true }
                            )
                        }
                    }
                }
            )
        }
        }
    ) { padding ->
        val totalPages = (state as? UiState.Success)?.data ?: 0

        // ── v1.2.3：提前预热「下一章」的前几张图 ──────────────────────────
        // 本 App 自 v5.0.18 起只支持阅读已下载章节，切章本身只是扫本地目录（几十毫秒），
        // 预取路径列表收益极小；真正会顿一下的是切过去之后**首屏图片的解码**。
        // 因此这里在滚动接近本章末尾时，把下一章前 3 张提前 enqueue 进 Coil 内存缓存，
        // 切章后首屏即可直接命中，无需等待。
        val appCtx = LocalContext.current
        val nextChapterId = remember(chapters, chapterId) {
            val i = chapters.indexOfFirst { it.id == chapterId }
            if (i >= 0 && i + 1 < chapters.size) chapters[i + 1].id else null
        }
        var nextWarmed by remember(nextChapterId) { mutableStateOf(false) }
        LaunchedEffect(totalPages) {
            if (totalPages <= 0) return@LaunchedEffect
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { idx ->
                    // ── v1.14.0：章内相邻页预载 ──────────────────────────
                    // 当前页 +1/+2 喂进 Coil 内存缓存：翻到下一页时直接命中内存，
                    // 解码不落在滚动帧上（LazyColumn 只保证组合，不保证解码时机）。
                    for (offset in 1..2) {
                        val n = idx + offset
                        if (n < totalPages) {
                            vm.pages.value[n]?.let { p ->
                                appCtx.imageLoader.enqueue(fileImageRequest(appCtx, p))
                            }
                        }
                    }
                    // ── v1.2.3：接近章末预热下一章前 3 张 ──
                    if (!nextWarmed && nextChapterId != null && idx >= totalPages - 2) {
                        nextWarmed = true   // 每章只预热一次
                        vm.peekNextChapterPaths(bookId, nextChapterId, limit = 3).forEach { p ->
                            appCtx.imageLoader.enqueue(
                                ImageRequest.Builder(appCtx).data(java.io.File(p)).build()
                            )
                        }
                    }
                }
        }

        // 注：进度值不再在此处读取 —— 直接读 listState.firstVisibleItemIndex 会让「翻一页」
        // 触发整个 content（顶栏/列表/底栏/信息卡）重组。现由 ReaderProgressRail 内部
        // 用 derivedStateOf 自持，只有页码真正变化才向下游发新值（v1.2.0）。
        // 沉浸式唤起（v4.14.0）：点击画面最上方 120dp 区域唤起顶栏/底部按钮（保持可见，滚动后 2 秒自动回隐）
        val topZonePx = with(LocalDensity.current) { 120.dp.toPx() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                // v1.1.2：去掉给滑轨预留的 8dp 右边距 —— 图片铺满整宽，进度滑轨改为浮层覆盖
                .padding(padding)
                .pointerInput(immersive) {
                    detectTapGestures { offset ->
                        if (immersive && offset.y <= topZonePx) {
                            immersive = false
                            hideJob?.cancel()   // 保持 UI 可见（与滚动自动回隐不同）
                        }
                    }
                }
        ) {
            when (val s = state) {
                UiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "正在加载本地章节…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is UiState.Error -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            s.message ?: "加载失败",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(onClick = { nav.navigate(Routes.detail(bookId, local = isLocal)) }) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("去详情页下载")
                        }
                    }
                }
                is UiState.Success -> {
                    val total = s.data
                    if (overview) {
                        // 缩略图览：一屏多页、自适应大小，点击直接定位到该页阅读（已解码页显示图，未解码先占位）
                        //
                        // v1.5.1 修复「多页挤在一起」：原先**写死** aspectRatio(0.75f)，
                        // 而每页真实比例在 vm.pageRatios（默认 0.72）——解码完成后比例突变，
                        // LazyGrid 会重算所有 item 高度 → **整屏跳位/重叠**。
                        // 现在与正常阅读模式一致：优先用真实比例，未知时才用默认值。
                        LazyVerticalGrid(
                            // pps=1 时用 Adaptive（按屏宽自适应，页够大才看得清）；
                            // pps>1 时 Fixed(pps) 精确按设置列数排，避免"挤在一起"。
                            columns = if (pps > 1) GridCells.Fixed(pps) else GridCells.Adaptive(minSize = 120.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(count = total, key = { it }, contentType = { "page" }) { idx ->
                                // v14：pageMap 订阅下沉到 item 内，解码完一页只重组该页
                                val pageMap by vm.pages.collectAsState()
                                val p = pageMap[idx]
                                // 关键：用与解码无关的「进入章节时预读的真实尺寸」，
                                // 占位与解码后完全一致 → 不会因解码完成而跳位。
                                val ratio = ratios[idx] ?: DEFAULT_PAGE_RATIO
                                val cell = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(ratio)
                                if (p != null) {
                                    AsyncImage(
                                        model = fileImageRequest(LocalContext.current, p),
                                        contentDescription = null,
                                        contentScale = ContentScale.FillWidth,
                                        modifier = cell.clickable { jumpTo = idx; overview = false }
                                    )
                                } else {
                                    // 未解码页：占位 + 点按定位（触发该页加载），尺寸与解码后一致
                                    Box(
                                        modifier = cell
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                            .clickable { jumpTo = idx; overview = false }
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            // v1.1.2：页间距 4dp → 0dp，逐页首尾相接（漫画页之间不该有空隙）
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            items(count = total, key = { it }, contentType = { "page" }) { idx ->
                                // v14：pageMap 订阅下沉到 item 内，解码完一页只重组该页
                                val pageMap by vm.pages.collectAsState()
                                val p = pageMap[idx]
                                // v1.2.0：比例取自「进入章节时预读的图片尺寸」，**与图片是否加载完成无关**。
                                // 修复：此前用 painter.intrinsicSize，图片加载完成后比例从占位 0.72 突变为真实值，
                                // 列表项高度随之变化 → LazyColumn 重算布局 → **往后滚动时位置被拽回去**（实测 bug）。
                                val ratio = ratios[idx] ?: DEFAULT_PAGE_RATIO
                                if (p != null) {
                                    AsyncImage(
                                        model = fileImageRequest(LocalContext.current, p),
                                        contentDescription = null,
                                        contentScale = ContentScale.FillWidth,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(ratio)
                                    )
                                } else {
                                    // 解码中的占位：用已知真实比例，高度与解码完成后完全一致
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(ratio)
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                    )
                                }
                            }
                            // 阅读器最底部（P0-4）：未看完显示短按钮「标已看完」；已看完隐藏按钮改显「已看完 ✓」
                            // 沉浸态（immersive）下整体隐藏，上滑唤起时显示
                            item {
                                if (!immersive) {
                                    // 标已看完按钮：仅在「自动看完」未勾选时显示（v4.14.0）——
                                    // 开了自动看完就无需手动标记，按钮隐藏；已看完状态以「已看完 ✓」文字呈现
                                    if (!isFinished && !autoFinishScroll) {
                                        Button(
                                            onClick = { vm.markFinished(bookId) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 24.dp, vertical = 20.dp),
                                            shape = RoundedCornerShape(24.dp)
                                        ) {
                                            Icon(Icons.Filled.CheckCircle, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text("标已看完")
                                        }
                                    } else if (isFinished) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 18.dp, bottom = 4.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "已看完 ✓",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    // 看完按钮下方一行：本地已删标识（v7.1.0 移除「不删」按钮——看完自动进回收站取代）
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (localDeleted) {
                                            Text(
                                                "已移入回收站",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                            // 漫画信息卡（v5.3.0）：阅读器底部，构成与搜索页卡片一致（封面 + 操作条 + 标题/作者），
                            // 操作条 = 下载键（已下载时显示为删除键）+ 收藏键；点卡进详情页。
                            item {
                                val b = book   // 局部变量：委托属性无法 smart cast，需先取到局部引用
                                if (!immersive && b != null) {
                                    ComicCard(
                                        book = b,
                                        onClick = { nav.navigate(Routes.detail(bookId, local = isLocal)) },
                                        onDownload = { b2 -> vm.download(b2.id) { ok -> if (ok) askDeleteAfterDownload = true } },
                                        onDelete = { confirmDelete = true },
                                        isDownloading = downloading,
                                        isDownloaded = isLocal,
                                        isFavorite = isFavorite,
                                        onFavorite = { vm.toggleFavorite(bookId) }
                                    )
                                    Spacer(Modifier.height(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            // 细进度滑轨（可点按 / 拖动定位页，颜色来自设置 reader_progress_color）
            // P0-5：常驻显示（沉浸态也保留细进度条）
            // v1.2.0 两处修正：
            //  ① 改走 ReaderProgressRail —— 这里原先留着一份**内联实现**，而下面同名函数
            //     从未被调用（死代码），导致"抽成独立 Composable 以隔离重组范围"（见该函数
            //     上方注释）其实并未生效：内联版直接读 listState.firstVisibleItemIndex，
            //     把翻页变成了整个 content 的重组源。
            //  ② 位置由右侧改到**左侧** —— 右侧留给正文滚动，避免手势互相抢占。
            ReaderProgressRail(
                listState = listState,
                totalPages = totalPages,
                railColor = railColor,
                onSeek = { idx -> scope.launch { listState.scrollToItem(idx) } },
                modifier = Modifier.align(Alignment.CenterStart)
            )
            // 顶部浮条（v5.11.0）：左上角 —— 下载完成询问 + 拉黑/删除标记，不打断阅读
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp, start = 8.dp)
                    .widthIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ① 下载完成：询问是否删除本地内容（轻提示，10 秒自动消失）
                AnimatedVisibility(
                    visible = askDeleteAfterDownload,
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it }
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shadowElevation = 4.dp
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                "已下载到书架，是否删除本地内容？",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.inverseOnSurface
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    enabled = !deleting,
                                    onClick = {
                                        askDeleteAfterDownload = false
                                        vm.deleteCurrent(bookId) { nav.popBackStack() }
                                    }
                                ) { Text("删除", color = MaterialTheme.colorScheme.inversePrimary) }
                                TextButton(onClick = { askDeleteAfterDownload = false }) {
                                    Text("保留", color = MaterialTheme.colorScheme.inverseOnSurface)
                                }
                            }
                        }
                    }
                }
                // ② 本地已删标记（mark 模式）→ 红条提示
                AnimatedVisibility(
                    visible = blackDeletedHandle == "mark" && !markDismissed && localDeleted,
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it }
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "该漫画本地内容已删除",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            TextButton(onClick = { markDismissed = true }) { Text("知道了") }
                        }
                    }
                }
            }
        }
    }

    // 删除本书确认
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        confirmDelete = false
                        vm.deleteCurrent(bookId) {
                            // 内容已删除，退出阅读器回上一页（书架/详情会自动刷新）
                            nav.popBackStack()
                        }
                    }
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
            title = { Text("删除这本漫画？") },
            text = { Text("将从本机删除该漫画的全部已下载内容，并清除书架记录、章节与阅读进度。此操作不可恢复。") }
        )
    }

    if (showChapters) {
        ModalBottomSheet(
            onDismissRequest = { showChapters = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            LazyColumn(modifier = Modifier.fillMaxHeight(0.7f)) {
                // v14：加 key 稳定项身份；删掉原 `chapters.indexOf(ch)`（O(n²) 且结果从未使用）
                itemsIndexed(chapters, key = { _, ch -> ch.id }) { _, ch ->
                    Text(
                        text = ch.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showChapters = false
                                goTo(ch.id)
                            }
                            .padding(16.dp),
                        color = if (ch.id == chapterId) MaterialTheme.colorScheme.primary else Color.Unspecified
                    )
                }
            }
        }
    }
}

/**
 * 阅读器右侧细进度滑轨（点按/拖动定位页，颜色取自设置 reader_progress_color）。
 *
 * v14 性能修复：此前进度值直接在 Scaffold content 里读 `listState.firstVisibleItemIndex`，
 * 该读取把「翻一页」变成了整个 content 的重组源 —— 顶栏、LazyColumn、底部按钮、信息卡全部重跑。
 * 现在：①抽成独立 Composable 隔离重组范围；②derivedStateOf 保证只有页码真正变化才向下游发新值。
 */
@Composable
private fun ReaderProgressRail(
    listState: LazyListState,
    totalPages: Int,
    railColor: Color,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 滑轨像素高度（点击定位用）——随组件内聚，不再由外层持有
    var railHeight by remember { mutableIntStateOf(0) }
    val progress by remember(listState, totalPages) {
        derivedStateOf {
            if (totalPages > 0) {
                ((listState.firstVisibleItemIndex + 1).coerceAtMost(totalPages)).toFloat() / totalPages
            } else 0f
        }
    }
    // 按住/拖动中：用于视觉加粗反馈
    var active by remember { mutableStateOf(false) }

    // 触点 y → 页索引
    val seekAt: (Float) -> Unit = { y ->
        if (railHeight > 0 && totalPages > 0) {
            val frac = (y / railHeight).coerceIn(0f, 1f)
            onSeek((frac * (totalPages - 1)).roundToInt())
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            // v1.2.0：触摸热区 6dp → 28dp（**视觉仍保持细条**）。
            // 原先热区与视觉同宽（6dp），手指几乎不可能精准按住 —— 用户反馈「不好碰到」。
            // 28dp ≈ 屏宽的 2.6%，既好按又不过多侵占阅读区。
            .width(28.dp)
            .onSizeChanged { railHeight = it.height }
            // 拖动定位：按住即跳转，拖动实时跟随（长漫画快速翻页用）
            .pointerInput(totalPages) {
                detectDragGestures(
                    onDragStart = { offset ->
                        active = true
                        seekAt(offset.y)
                    },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false }
                ) { change, _ ->
                    change.consume()
                    seekAt(change.position.y)
                }
            }
            // 点按定位（短按即跳，不移动的情况下由它接管）
            .pointerInput(totalPages) {
                detectTapGestures(onPress = { offset ->
                    active = true
                    seekAt(offset.y)
                    tryAwaitRelease()
                    active = false
                })
            },
        contentAlignment = Alignment.CenterStart   // v1.2.0：细条贴左边缘（整体已移到左侧）
    ) {
        // 可视滑轨：按住/拖动时加粗到 10dp，作为触摸反馈
        val railWidth = if (active) 10.dp else 6.dp
        Box(
            modifier = Modifier
                .width(railWidth)
                .fillMaxHeight()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    RoundedCornerShape(5.dp)
                )
        ) {
            // 进度填充（顶部 → 底部）
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(progress)
                    .background(railColor, RoundedCornerShape(5.dp))
            )
        }
    }
}

/**
 * 单页宽高比兜底值（v1.1.2）。
 * 仅在图片尚未解码、拿不到真实尺寸时用于占位，避免「高度 0 → 突然撑开」造成滚动跳动。
 * 取值参考常见漫画页 702×1000 ≈ 0.702。
 */
private const val DEFAULT_PAGE_RATIO = 0.72f
