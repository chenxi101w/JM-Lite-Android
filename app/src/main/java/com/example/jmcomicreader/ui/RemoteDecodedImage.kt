package com.example.jmreader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/**
 * 远程图片组件（封面专用）。
 *
 * 封面图（book.coverUrl）是 JM 站点普通图片，**不需要** scramble 解码，
 * 因此直接用 Coil 从 URL 加载——不占用 Chaquopy Python 解释器（单 GIL），
 * 避免与搜索/下载抢 Python 时间片（此前走 Python 解码导致搜索时封面加载拖慢搜索）。
 *
 * Coil 自带内存/磁盘缓存，滚动回收后回看不重新联网。
 *
 * 性能注意：用 [AsyncImage] 而非 [coil.compose.SubcomposeAsyncImage]。
 * SubcomposeAsyncImage 在每张图「加载中→成功」的状态切换时会触发所在卡片子树重组，
 * 网格滚动时几十张封面同时加载会造成重组雪崩、明显掉帧；AsyncImage 由 Coil 内部处理
 * 占位/错误态、不触发外部重组，滚动更跟手。占位用纯色 ColorPainter 避免空白闪。
 */
@Composable
fun RemoteDecodedImage(
    imgUrl: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null
) {
    val phColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val placeholder = remember(phColor) { ColorPainter(phColor) }
    AsyncImage(
        model = imgUrl,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder
    )
}
