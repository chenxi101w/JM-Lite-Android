package com.example.jmreader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.jmreader.data.local.Book
import com.example.jmreader.ui.RemoteDecodedImage
import com.example.jmreader.ui.theme.JmShape
import com.example.jmreader.ui.theme.JmTone

/**
 * v6.0.0 MD3 漫画卡片网格：
 *  - 大圆角(24dp) Card + tonal elevation，阴影柔和不过度
 *  - 已看完/已下载/灰显 → **封面半透明蒙层**而非卡片整体灰化
 *  - 操作区：IconButton + tonal 容器色，替代手写 Surface 灰底
 *  - GridCells.Adaptive minSize 从 84dp → 108dp，卡片更显主体
 *  - 水平/垂直间距 12dp，呼吸感更足
 *  - 分区「已看完」分隔线使用 MD3 HorizontalDivider + 胶囊标签
 *
 * 保留所有原业务能力：下载/收藏态、多选、屏蔽角标、水平横滑、footer 槽。
 * 严禁改动数据库/业务逻辑。
 */
@Composable
fun ComicGrid(
    books: List<Book>,
    onBookClick: (Book) -> Unit,
    modifier: Modifier = Modifier,
    onBookLongClick: ((Book) -> Unit)? = null,
    horizontal: Boolean = false,
    columns: Int = 0,
    state: LazyGridState? = null,
    footer: (@Composable () -> Unit)? = null,
    /** 网格末尾的额外底部留白（dp），用于避开浮在上层的 FAB。0 = 不留。 */
    bottomSpacer: Dp = 0.dp,
    onDownload: ((Book) -> Unit)? = null,
    downloadingIds: Set<Int> = emptySet(),
    downloadedIds: Set<Int> = emptySet(),
    /** v1.28.1：卡片风格（设置项 card_style），透传给每张卡。clean / immersive */
    cardStyle: String = "clean",
    onDelete: ((Book) -> Unit)? = null,
    onFavorite: ((Book) -> Unit)? = null,
    favoriteIds: Set<Int> = emptySet(),
    selectionMode: Boolean = false,
    selectedIds: Set<Int> = emptySet(),
    finishedIds: Set<Int> = emptySet(),
    grayedIds: Set<Int> = emptySet(),
) {
    if (horizontal) {
        LazyRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            // v1.28.1：外边距 4dp → 20dp（照参考），封面更大、呼吸感更强
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
        ) {
            items(books, key = { it.id }, contentType = { "book" }) { book ->
                ComicCard(
                    book = book,
                    onClick = { onBookClick(book) },
                    cardStyle = cardStyle,
                    onLongClick = onBookLongClick?.let { cb -> { cb(book) } },
                    modifier = Modifier.width(124.dp),
                    onDownload = onDownload,
                    isDownloading = downloadingIds.contains(book.id),
                    isDownloaded = downloadedIds.contains(book.id),
                    onDelete = onDelete,
                    onFavorite = onFavorite,
                    isFavorite = favoriteIds.contains(book.id),
                    selectionMode = selectionMode,
                    isSelected = selectedIds.contains(book.id),
                    finished = finishedIds.contains(book.id),
                    grayed = grayedIds.contains(book.id),
                )
            }
            footer?.let { item { it() } }
        }
    } else {
        val (unfinished, finished) = remember(books, finishedIds) {
            books.partition { it.id !in finishedIds }
        }
        LazyVerticalGrid(
            state = state ?: rememberLazyGridState(),
            // v7.0.2：自适应分支由 Adaptive(minSize = 108.dp) 改为固定 3 列。
            // 原因：Adaptive 的列数是 floor((可用宽 + 间距) / (minSize + 间距))，
            // 108dp + 12dp 间距在常见的 360dp 屏（去掉页面左右 12dp 内边距后可用约 336dp）上
            // 算出来 floor(348 / 120) = 2 列 —— 卡片过宽、一屏信息量太少，用户明确要 3 列。
            // 仍保留 columns > 0 时按设置固定，只有「自适应」这一档被钉成 3 列。
            // v1.28.1：默认列数 3 → **2**（学参考实现的「精选流」观感，用户确认默认 2 列）。
            // 密度可在「设置 → 搜索 → 网格密度」切 2/3/4。
            columns = if (columns > 0) GridCells.Fixed(columns)
            else GridCells.Fixed(2),
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(unfinished, key = { it.id }, contentType = { "book" }) { book ->
                ComicCard(
                    book = book,
                    onClick = { onBookClick(book) },
                    onLongClick = onBookLongClick?.let { cb -> { cb(book) } },
                    modifier = Modifier.fillMaxWidth(),
                    onDownload = onDownload,
                    isDownloading = downloadingIds.contains(book.id),
                    isDownloaded = downloadedIds.contains(book.id),
                    onDelete = onDelete,
                    onFavorite = onFavorite,
                    isFavorite = favoriteIds.contains(book.id),
                    selectionMode = selectionMode,
                    isSelected = selectedIds.contains(book.id),
                    finished = finishedIds.contains(book.id),
                    grayed = grayedIds.contains(book.id),
                )
            }
            if (unfinished.isNotEmpty() && finished.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "finished_divider") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Surface(
                            shape = JmShape.pill,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Text(
                                "已看完",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 4.dp
                                )
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
            items(finished, key = { it.id }, contentType = { "book" }) { book ->
                ComicCard(
                    book = book,
                    onClick = { onBookClick(book) },
                    onLongClick = onBookLongClick?.let { cb -> { cb(book) } },
                    modifier = Modifier.fillMaxWidth(),
                    onDownload = onDownload,
                    isDownloading = downloadingIds.contains(book.id),
                    isDownloaded = downloadedIds.contains(book.id),
                    onDelete = onDelete,
                    onFavorite = onFavorite,
                    isFavorite = favoriteIds.contains(book.id),
                    selectionMode = selectionMode,
                    isSelected = selectedIds.contains(book.id),
                    finished = finishedIds.contains(book.id),
                    grayed = grayedIds.contains(book.id),
                )
            }
            footer?.let {
                item(span = { GridItemSpan(maxLineSpan) }, key = "grid_footer") { it() }
            }
            // v7.0.3：给悬浮按钮（FAB）留出底部空间。网格是 lazy 的，光在外层 Column 加
            // Spacer 没用 —— 吸底内容会被浮在上面的 FAB 压住（搜索结果的「加载更多」按钮
            // 右下角就点不到）。留白必须是网格内部的最后一个 item。
            if (bottomSpacer > 0.dp) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "grid_bottom_spacer") {
                    Spacer(Modifier.height(bottomSpacer))
                }
            }
        }
    }
}

@Immutable
private data class CardTokens(
    val selectedBg: Color,
    val selectedBorder: BorderStroke,
    val defaultBorder: BorderStroke?,
    val finishedBg: Color,
    val finishedFg: Color,
    val grayedBg: Color,
    val grayedFg: Color,
    val selectionOverlay: Color,
    val selectionCheck: Color,
    val actionFilled: Color,
    val actionFilledIcon: Color,
    val actionTonalBg: Color,
    val actionTonalFg: Color,
    val actionOutlinedBg: Color,
    val actionOutlinedFg: Color,
    val overlayRead: Color
)

@Composable
private fun rememberCardTokens(): CardTokens {
    val scheme = MaterialTheme.colorScheme
    val overlayRead = JmTone.current.coverOverlayRead
    return remember(scheme, overlayRead) {
        CardTokens(
            selectedBg = scheme.primaryContainer,
            selectedBorder = BorderStroke(2.dp, scheme.primary),
            defaultBorder = null,
            finishedBg = scheme.tertiaryContainer,
            finishedFg = scheme.onTertiaryContainer,
            grayedBg = scheme.surfaceVariant,
            grayedFg = scheme.onSurfaceVariant,
            selectionOverlay = scheme.surface.copy(alpha = 0.82f),
            selectionCheck = scheme.primary,
            actionFilled = scheme.primaryContainer,
            actionFilledIcon = scheme.onPrimaryContainer,
            actionTonalBg = scheme.surfaceColorAtElevation(3.dp).copy(alpha = 0.9f),
            actionTonalFg = scheme.onSurfaceVariant,
            actionOutlinedBg = scheme.surface.copy(alpha = 0.55f),
            actionOutlinedFg = scheme.onSurface,
            overlayRead = overlayRead
        )
    }
}

@Composable
fun ComicCard(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onDownload: ((Book) -> Unit)? = null,
    isDownloading: Boolean = false,
    isDownloaded: Boolean = false,
    onDelete: ((Book) -> Unit)? = null,
    onFavorite: ((Book) -> Unit)? = null,
    isFavorite: Boolean = false,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    finished: Boolean = false,
    grayed: Boolean = false,
    /**
     * v1.28.1：卡片风格（设置项 card_style）。
     *  - `clean`     **干净优先**（默认，照参考实现）：没有卡片容器/边框/阴影，
     *                封面 + 两行标题 + 一行作者，直接铺在页面底上。
     *  - `immersive` **大图流**：封面底部加渐变遮罩，标题压在封面上，封面下只留一行作者。
     */
    cardStyle: String = "clean",
) {
    val tk = rememberCardTokens()
    val immersive = cardStyle == "immersive"
    val coverRadius = if (immersive) 16.dp else 12.dp

    Column(
        modifier = modifier
            // 灰显（hideMode=gray 已下载隐藏模式）整卡淡化，保留可读性
            .alpha(if (grayed) 0.6f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        // ── 封面区 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(coverRadius))
        ) {
            RemoteDecodedImage(
                imgUrl = book.coverUrl,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                contentDescription = book.title
            )
            // 已看完 / 已下载灰显 → 半透明蒙层
            CoverOverlay(
                show = finished || grayed,
                overlayColor = if (grayed) tk.grayedBg.copy(alpha = 0.72f) else tk.overlayRead
            )
            // 大图流：底部渐变遮罩，保证压在封面上的标题可读
            if (immersive) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.5f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.78f)
                            )
                        )
                )
            }
            // ── 封面角上的透明方形小按钮（v1.28.1 用户要求）──
            // 「收藏和下载放漫画左上右上，透明正方形同时小一点」：
            // 收藏 → 左上；下载 → 右上。**无底色、无描边**，只有图标本身；
            // 触摸区 28dp（比原来的 tonal 按钮小一圈），图标 17dp，方形圆角 6dp。
            // 多选态下让位给勾选圆钮，避免同角叠两层。
            if (!selectionMode && onFavorite != null) {
                CornerIconButton(
                    icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "取消收藏" else "收藏",
                    active = isFavorite,
                    align = Alignment.TopStart,
                ) { onFavorite(book) }
            }
            if (!selectionMode && (onDownload != null || (isDownloaded && onDelete != null))) {
                CornerIconButton(
                    icon = if (isDownloaded) Icons.Filled.Download else Icons.Outlined.Download,
                    contentDescription = if (isDownloaded) "删除下载" else "下载",
                    active = isDownloaded,
                    align = Alignment.TopEnd,
                    enabled = !isDownloading,
                ) {
                    if (isDownloaded) onDelete?.invoke(book) else onDownload?.invoke(book)
                }
            }
            // 多选态：左上角勾选圆钮
            if (selectionMode) {
                Surface(
                    shape = CircleShape,
                    color = tk.selectionOverlay,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(26.dp)
                        .then(
                            if (isSelected) Modifier.border(
                                1.5.dp,
                                tk.selectionCheck,
                                CircleShape
                            ) else Modifier
                        )
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        AnimatedVisibility(
                            visible = isSelected,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "已选中",
                                tint = tk.selectionCheck,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(5.dp)
                            )
                        }
                    }
                }
            }
            // 状态标记：收敛成右下角一个小圆点（原来是一整条胶囊角标，太吵）
            if (finished || isDownloaded) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(6.dp)
                        .background(
                            if (isDownloaded) tk.grayedFg else tk.finishedFg,
                            CircleShape
                        )
                )
            }
            // 大图流：标题压封面底部
            if (immersive) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, end = 8.dp, bottom = 16.dp)
                )
            }
        }
        // ── 文字区 ──
        Column(
            modifier = Modifier.padding(
                start = if (immersive) 2.dp else 2.dp,
                end = 2.dp,
                top = 6.dp,
                bottom = 6.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (!immersive) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    // minLines = 2：保证同一行卡片等高，网格不会参差
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (book.author.isNotBlank()) {
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 封面角上的**透明方形小按钮**。
 *
 * 用户要求：「收藏和下载放漫画左上右上，**透明正方形**同时**小一点**」。
 * 所以这里**不画任何底色/描边**，只有图标本身；触摸区 28dp（方形、圆角 6dp），
 * 图标 17dp —— 比原来那排 tonal `IconButton`（40dp 触摸区 + 圆形填充）小一圈。
 *
 * 状态色：未激活 = 白（在封面图上可读），激活 = 主题色（已收藏/已下载一眼能看出）。
 */
@Composable
private fun BoxScope.CornerIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    align: Alignment,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(align)
            .padding(4.dp)
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(17.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.92f)
        )
    }
}

/**
 * 封面叠加层：半透明蒙层（整块）+ 底部 25% 渐深，让角标/文字始终可读。
 */
@Composable
private fun BoxScope.CoverOverlay(show: Boolean, overlayColor: Color) {
    AnimatedVisibility(
        visible = show,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.matchParentSize()
    ) {
        Box(
            Modifier
                .background(overlayColor)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.25f)
                        ),
                        startY = Float.POSITIVE_INFINITY * 0.75f
                    )
                )
        )
    }
}

/** 通用角标胶囊：圆角 14dp、紧凑 padding、labelMedium 字体。 */
@Composable
private fun BadgePill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = JmShape.cornerBadge,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(
                horizontal = 8.dp,
                vertical = 4.dp
            )
        )
    }
}

/**
 * 封面下方操作条：
 *  下载键：未下载=tonal(半透明)+描边轮廓；下载中=转圈；已下载=filled；已下载且可删=filled+删除
 *  收藏键：未收藏=tonal；已收藏=primary filled（♥实心）
 *  使用 IconButton(tonal/filled) 而非手写 Surface，保持 MD3 一致性。
 */
@Composable
fun ComicActionBar(
    isDownloading: Boolean = false,
    isDownloaded: Boolean = false,
    onDownload: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    selectionMode: Boolean = false
) {
    val tk = rememberCardTokens()

    // v1.2.3：整条在「没有任何可显示操作」时**直接不渲染**。
    // 此前左/右无内容时会各塞一个 32dp Spacer，整条固定占约 40dp（32 + 上下 4dp），
    // 而书架里绝大多数书未下载（无下载/删除按钮）、且未接收藏回调 ——
    // 这段空白正好夹在封面与标题之间（用户反馈「作品名和封面中间的留白」）。
    val showLeft =
        (onDownload != null || (isDownloaded && onDelete != null)) && !selectionMode
    val showRight = onFavorite != null && !selectionMode
    if (!showLeft && !showRight) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp),   // 4 → 1dp：有按钮时也更紧凑
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左：下载 / 删除
        if (showLeft) {
            when {
                isDownloading ->
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(6.dp)
                            .size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                isDownloaded && onDelete != null ->
                    IconButton(
                        onClick = { onDelete() },
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = tk.actionFilled,
                            contentColor = tk.actionFilledIcon
                        )
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除下载",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                isDownloaded ->
                    IconButton(
                        onClick = { /* 标识用，不可点击 */ },
                        modifier = Modifier.size(32.dp),
                        enabled = false,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = tk.actionFilled,
                            contentColor = tk.actionFilledIcon,
                            disabledContainerColor = tk.actionFilled,
                            disabledContentColor = tk.actionFilledIcon
                        )
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = "已下载",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                else ->
                    OutlinedIconButton(
                        onClick = { onDownload?.invoke() },
                        modifier = Modifier.size(32.dp),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant
                        ),
                        colors = IconButtonDefaults.outlinedIconButtonColors(
                            containerColor = tk.actionOutlinedBg,
                            contentColor = tk.actionOutlinedFg
                        )
                    ) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = "下载",
                            modifier = Modifier.size(16.dp)
                        )
                    }
            }
        } else {
            Spacer(Modifier.size(32.dp))
        }

        // 右：收藏（v1.2.3：改用 showRight，顺带补上原先漏掉的 !selectionMode 判断）
        if (showRight) {
            if (isFavorite) {
                IconButton(
                    onClick = { onFavorite() },
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = "取消收藏",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                OutlinedIconButton(
                    onClick = { onFavorite() },
                    modifier = Modifier.size(32.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        containerColor = tk.actionOutlinedBg,
                        contentColor = tk.actionOutlinedFg
                    )
                ) {
                    Icon(
                        Icons.Filled.FavoriteBorder,
                        contentDescription = "收藏",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else {
            Spacer(Modifier.size(32.dp))
        }
    }
}
