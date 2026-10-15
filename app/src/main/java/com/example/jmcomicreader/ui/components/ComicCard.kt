package com.example.jmreader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
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
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
        ) {
            items(books, key = { it.id }, contentType = { "book" }) { book ->
                ComicCard(
                    book = book,
                    onClick = { onBookClick(book) },
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
            columns = if (columns > 0) GridCells.Fixed(columns)
            else GridCells.Fixed(3),
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
) {
    val tk = rememberCardTokens()
    val shape: Shape = JmShape.comicCard

    // 选中态 = 主色描边 + 主色容器背景
    val cardContainer = if (isSelected) tk.selectedBg
    else MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    val cardBorder = if (isSelected) tk.selectedBorder else tk.defaultBorder

    Card(
        modifier = modifier
            // 灰显（hideMode=gray 已下载隐藏模式）整卡淡化，保留可读性
            .alpha(if (grayed) 0.6f else 1f)
            .then(if (cardBorder != null) Modifier.border(cardBorder, shape) else Modifier)
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = cardContainer),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp,
            pressedElevation = 3.dp
        )
    ) {
        Column {
            // ── 封面区（主体） ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
            ) {
                RemoteDecodedImage(
                    imgUrl = book.coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    contentDescription = book.title
                )
                // ① 已看完 / 已下载灰显 → 半透明蒙层 + 底部渐隐
                CoverOverlay(
                    show = finished || grayed,
                    overlayColor = if (grayed) tk.grayedBg.copy(alpha = 0.72f)
                    else tk.overlayRead
                )
                // ② 已下载 → 右下角下载完成标记
                if (grayed) {
                    BadgePill(
                        text = "已下载",
                        containerColor = tk.grayedBg,
                        contentColor = tk.grayedFg,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                    )
                }
                // ③ 已看完 → 左下角 tertiary 标记
                if (finished) {
                    BadgePill(
                        text = "已看完",
                        containerColor = tk.finishedBg,
                        contentColor = tk.finishedFg,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                    )
                }
                // ⑤ 多选态：左上角勾选圆钮
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
            }
            // ── 操作条：左下载 + 右收藏（IconButton + tonal/填充色） ──
            ComicActionBar(
                isDownloading = isDownloading,
                isDownloaded = isDownloaded,
                onDownload = onDownload?.let { cb -> { cb(book) } },
                onDelete = onDelete?.let { cb -> { cb(book) } },
                isFavorite = isFavorite,
                onFavorite = onFavorite?.let { cb -> { cb(book) } },
                selectionMode = selectionMode
            )
            // ── 标题与作者 ──
            Column(
                modifier = Modifier.padding(
                    start = 8.dp,
                    end = 8.dp,
                    bottom = 8.dp,
                    top = 2.dp
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    minLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
