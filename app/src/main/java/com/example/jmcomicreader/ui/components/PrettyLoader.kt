package com.example.jmreader.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 加载指示器（v1.2.0，替代 M3 默认 CircularProgressIndicator）。
 *
 * 相比默认转圈：
 *  1. 保留一圈低对比度**轨道**，与扫掠光弧形成层次，而不是单根孤零零的弧；
 *  2. 光弧用 **sweepGradient 渐变**收尾（透明 → 主题色），末端更自然；
 *  3. 旋转匀速、弧长**来回摆动**，两者节奏不同 → 有呼吸感而非机械打转；
 *  4. 圆头线帽，线条更柔和。
 */
@Composable
fun PrettyLoader(
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)

    val transition = rememberInfiniteTransition(label = "pretty-loader")
    // 旋转角：匀速转
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1150, easing = LinearEasing)),
        label = "angle",
    )
    // 弧长占比：来回摆动 → 呼吸感
    val sweep by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.78f,
        animationSpec = infiniteRepeatable(
            tween(950, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "sweep",
    )

    Canvas(modifier = modifier.size(size)) {
        val stroke = this.size.minDimension / 11f
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)

        // 低对比度轨道
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )

        // 扫掠光弧（渐变 + 圆头）
        rotate(degrees = angle) {
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        primary.copy(alpha = 0f),
                        primary.copy(alpha = 0.35f),
                        primary,
                    ),
                ),
                startAngle = 0f,
                sweepAngle = sweep * 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
