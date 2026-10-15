package com.example.jmreader.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * 关于页顶部展示卡（v1.21.0 按参考实现重做）。
 *
 * **这一版的两处「照搬」**（对照 1976764/jm-comic-android 的 AboutScreen，即用户说的「人家」）：
 *  1. **模糊色块漂移背景** —— 用户夸的「3D 渐变」真身：从 12 色调色板随机取 3 个颜色，
 *     各画一个大圆（径向渐变：中心实色 → 30% → 透明），六个**独立周期**的 X/Y 位移动画
 *     （8~13 秒、FastOutSlowIn、Reverse），整体套 `blur`。所以它是**慢慢流动的柔光**，
 *     既不是静态渐变，也不是我上一版那套「旋转极光 + 陀螺光环」。
 *  2. **转速 20°/秒**（一圈 18 秒），对齐人家的 `rotation-per-second="20deg"`；
 *     我上一版 7.2 秒/圈（50°/s）快了 2.5 倍，观感「着急」。
 *
 * **必须知道的天花板**：人家的 3D 物体是 **WebView + `<model-viewer>` 加载真 GLB 模型**
 * （`assets/models/JM.glb`，`auto-rotate`，`exposure=1.2`，`shadow-intensity=0`）——
 * 有**真实体积**与**真实环境反射**。我们这里是**平面位图绕 Y 轴旋转**，
 * 观感像一张有厚度的贴纸在转台上转，不是真三维模型。
 * 要完全一致必须引入 GLB（方案对比见 `docs/升级日志/v1.21.0.md`）。
 *
 * ⚠️ **性能约束（改前必读）**：所有动画相位只在**绘制阶段**读取
 * （`Canvas` 的 draw lambda、`graphicsLayer` 的 lambda、`drawWithContent` 的 lambda），
 * 每帧只让 draw 阶段失效，**不重组、不 measure/layout**。
 * 绝不能把相位读进 @Composable 作用域（如 `val a = turn.value` 提到外面用），
 * 那会让整张卡每帧重组，中低端机立刻掉帧。
 */
private val BlobColors = listOf(
    Color(0xFF7C3AED), // 紫
    Color(0xFF8B5CF6),
    Color(0xFF6366F1), // 靛蓝
    Color(0xFF3B82F6), // 蓝
    Color(0xFF06B6D4), // 青
    Color(0xFF14B8A6), // 青绿
    Color(0xFF22C55E), // 绿
    Color(0xFFEAB308), // 黄
    Color(0xFFF97316), // 橙
    Color(0xFFEF4444), // 红
    Color(0xFFEC4899), // 粉
    Color(0xFFD946EF)  // 紫红
)

/** 感知亮度：决定深色主题下是否压暗色块（避免整块糊成荧光）。 */
private fun Color.luma(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
fun AuroraHeroCard(
    icon: Painter,
    title: String,
    subtitle: String,
    version: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "hero")

    /** 图标转台相位：20°/秒 → 一圈 18 秒。 */
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing)),
        label = "turn"
    )

    // 六个独立相位：三个色块各有 X/Y，周期互不相同 → 交错流动，而不是整体平移
    val x1 by transition.animateFloat(-60f, 60f, infiniteRepeatable(tween(8_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "x1")
    val y1 by transition.animateFloat(-45f, 50f, infiniteRepeatable(tween(10_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y1")
    val x2 by transition.animateFloat(70f, -70f, infiniteRepeatable(tween(12_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "x2")
    val y2 by transition.animateFloat(35f, -55f, infiniteRepeatable(tween(9_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y2")
    val x3 by transition.animateFloat(30f, -50f, infiniteRepeatable(tween(11_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "x3")
    val y3 by transition.animateFloat(80f, -70f, infiniteRepeatable(tween(13_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y3")

    // 每次进页面随机取 3 色（与参考实现同做法）
    val colors = remember { BlobColors.shuffled().take(3) }
    val dim = if (scheme.surface.luma() > 0.5f) 1f else 0.7f

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = scheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(206.dp)
                .clip(RoundedCornerShape(26.dp))
        ) {
            // ── ① 模糊色块漂移背景 ──
            Box(
                modifier = Modifier.fillMaxSize().blur(60.dp),   // API<31 是 no-op；径向渐变本身已足够柔
                contentAlignment = Alignment.Center
            ) {
                val blobs = listOf(
                    Triple(colors[0], x1, y1),
                    Triple(colors[1], x2, y2),
                    Triple(colors[2], x3, y3)
                )
                blobs.forEachIndexed { i, (c, ox, oy) ->
                    val d = when (i) { 0 -> 300.dp; 1 -> 260.dp; else -> 190.dp }
                    Box(
                        modifier = Modifier
                            .size(d)
                            .offset(x = ox.dp, y = oy.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        c.copy(alpha = 0.85f * dim),
                                        c.copy(alpha = 0.30f * dim),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                }
            }

            // ── ② 转台图标（反光 + 倒影 + 接触阴影）+ 文案 ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val camera = with(LocalDensity.current) { 14.dp.toPx() }
                val tile = 84.dp

                Box(modifier = Modifier.size(tile), contentAlignment = Alignment.Center) {
                    // 接触阴影：随转动侧过去而变窄 —— 人眼判断「在三维里转」的关键线索
                    Canvas(Modifier.fillMaxSize()) {
                        val f = abs(cos(turn * 2f * PI.toFloat()))
                        val w = size.width * (0.50f + 0.44f * f)
                        val h = size.height * 0.15f
                        drawOval(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.34f), Color.Transparent),
                                center = Offset(size.width / 2f, size.height * 0.94f),
                                radius = w * 0.6f
                            ),
                            topLeft = Offset((size.width - w) / 2f, size.height * 0.94f - h / 2f),
                            size = GeoSize(w, h)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(tile)
                            .graphicsLayer {
                                cameraDistance = camera * 1.15f
                                transformOrigin = TransformOrigin.Center
                                rotationY = turn * 360f
                                rotationX = 5f
                            }
                            .clip(RoundedCornerShape(22.dp))
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(22.dp),
                            color = scheme.surface.copy(alpha = 0.22f)
                        ) {}
                        Image(
                            painter = icon,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().padding(6.dp)
                        )
                        // 反光：高光带与转动同相 → 像一束固定光源扫过转动的表面
                        Canvas(Modifier.fillMaxSize()) {
                            val x = ((turn * 2f) % 1f) * size.width * 2f - size.width * 0.5f
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.06f),
                                        Color.White.copy(alpha = 0.55f),
                                        Color.White.copy(alpha = 0.06f),
                                        Color.Transparent
                                    ),
                                    start = Offset(x - size.width * 0.42f, 0f),
                                    end = Offset(x + size.width * 0.42f, size.height)
                                )
                            )
                        }
                    }
                }

                // 底部镜像倒影：向下渐隐（DstIn 遮罩），与主体同步转动
                Box(
                    modifier = Modifier
                        .size(tile)
                        .height(tile * 0.5f)
                        .clipToBounds()
                ) {
                    Image(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(tile)
                            .graphicsLayer {
                                cameraDistance = camera * 1.15f
                                transformOrigin = TransformOrigin.Center
                                rotationY = turn * 360f
                                scaleY = -1f
                            }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.38f),
                                            Color.Transparent
                                        )
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                            .padding(6.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))
                // 文案改用主题色：背景已从「深紫卡」改成主题表面色，白字会看不见
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = scheme.primary.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = version,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}
