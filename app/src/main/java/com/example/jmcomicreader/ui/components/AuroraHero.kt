package com.example.jmreader.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 关于页顶部「3D 渐变展示」主视觉（v1.19.0）。
 *
 * 设计目标：把原先「静态图标 + 名称」的关于页头部，换成一块**有生命力的展示卡**：
 *  - **流动的极光渐变**：多色线性渐变按相位旋转，颜色从主题色系取（换主题风格会跟着变）；
 *  - **3D 陀螺光环**：两层 sweep 渐变的圆盘做垂直压扁（scaleY≈0.42）后反向自转，
 *    在平面上模拟出立体圆环/全息盘的效果；
 *  - **浮动高光**：一个柔和的径向高光沿椭圆轨道游走，制造「玻璃反射」感；
 *  - **图标真 3D**：图标卡片走 graphicsLayer 的 rotationY/rotationX 加 cameraDistance，
 *    产生真实的透视翻转，并在表面扫过一道镜面高光。
 *
 * ⚠️ **性能约束（重要）**：所有动画相位都是 State，但**只在绘制阶段读取**
 * （Canvas 的 draw lambda 与 graphicsLayer 的 lambda 块内），
 * 因此每帧只触发 **draw 阶段失效**，不会引起重组、也不会触发 measure/layout。
 * 这是这个组件能在中低端机上满帧跑的关键 —— 后续改它时**不要把相位读进 @Composable 作用域**
 * （例如 `val p = phase.value` 提到外面再用），那会让整张卡每帧重组。
 */
@Composable
fun AuroraHeroCard(
    icon: Painter,
    title: String,
    subtitle: String,
    version: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "aurora")

    // 三个不同周期的相位，避免各层同步旋转产生「整块在转」的廉价感。
    val flow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing)),
        label = "flow"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing)),
        label = "spin"
    )
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5_200, easing = LinearEasing), RepeatMode.Reverse),
        label = "bob"
    )

    // 极光主色：主题色 + 两个固定紫调点缀（保证任何风格下都是「深浅紫」的高级感，而不是彩虹）。
    val cA = scheme.primary
    val cB = scheme.tertiary
    val cC = scheme.primaryContainer
    val cD = scheme.secondaryContainer
    val accent = Color(0xFF7C6BF0)
    val deep = Color(0xFF2A1E5C)

    // 图标 3D 摆动幅度（px）——按密度换算，保证不同屏幕视觉一致。
    val density = LocalDensity.current
    val camera = with(density) { 14.dp.toPx() }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = deep,                       // 兜底色：渐变没盖住的地方也不刺眼
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp)
                .clip(RoundedCornerShape(26.dp))
        ) {
            // ── ① 流动极光底：线性渐变整体绕中心旋转 ──
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val ang = flow * 2f * PI.toFloat()
                val r = kotlin.math.hypot(w, h) / 2f
                val cx = w / 2f
                val cy = h / 2f
                val brush = Brush.linearGradient(
                    colors = listOf(cA, accent, cB, cC, cD, cA),
                    start = Offset(cx + r * cos(ang), cy + r * sin(ang)),
                    end = Offset(cx - r * cos(ang), cy - r * sin(ang))
                )
                drawRect(brush = brush)
            }

            // ── ② 3D 陀螺光环：两层压扁的 sweep 圆盘反向自转 ──
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = size.height * 0.62f
                val a1 = spin * 360f
                // 内盘：顺时针
                rotate(degrees = a1, pivot = Offset(cx, cy)) {
                    scale(scaleX = 1f, scaleY = 0.42f, pivot = Offset(cx, cy)) {
                        drawCircle(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.55f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.28f),
                                    Color.Transparent
                                ),
                                center = Offset(cx, cy)
                            ),
                            radius = radius,
                            center = Offset(cx, cy),
                            style = Stroke(width = size.height * 0.055f)
                        )
                    }
                }
                // 外盘：逆时针，更细更淡，拉开层次
                rotate(degrees = -a1 * 0.6f, pivot = Offset(cx, cy)) {
                    scale(scaleX = 1f, scaleY = 0.30f, pivot = Offset(cx, cy)) {
                        drawCircle(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    cB.copy(alpha = 0.85f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.20f),
                                    Color.Transparent
                                ),
                                center = Offset(cx, cy)
                            ),
                            radius = radius * 1.22f,
                            center = Offset(cx, cy),
                            style = Stroke(width = size.height * 0.030f)
                        )
                    }
                }
            }

            // ── ③ 浮动高光：沿椭圆轨道游走的柔光，制造玻璃反射 ──
            Canvas(Modifier.fillMaxSize()) {
                val ang = flow * 2f * PI.toFloat()
                val cx = size.width / 2f + size.width * 0.30f * cos(ang)
                val cy = size.height / 2f + size.height * 0.20f * sin(ang * 1.3f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = size.height * 0.55f
                    ),
                    radius = size.height * 0.55f,
                    center = Offset(cx, cy)
                )
            }

            // ── ④ 前景：3D 翻转的图标 + 文案 ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        // 相位只在 graphicsLayer 的绘制 lambda 里读取 → 每帧仅重绘
                        .graphicsLayer {
                            cameraDistance = camera
                            scaleX = 1f
                            scaleY = 1f
                            transformOrigin = TransformOrigin.Center
                            // 竖向轻微摇摆 + 常态俯角：做成「悬浮全息盘」而不是纯平面图标
                            rotationY = sin(bob * 2f * PI.toFloat()) * 16f
                            rotationX = 8f + cos(bob * 2f * PI.toFloat()) * 4f
                        }
                        .clip(RoundedCornerShape(24.dp))
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(24.dp),
                        color = Color.White.copy(alpha = 0.14f)
                    ) {}
                    Image(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    )
                    // 图标表面扫过的镜面高光
                    Canvas(Modifier.fillMaxSize()) {
                        val x = (flow * 2f % 1f) * size.width * 2f - size.width * 0.5f
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.35f),
                                    Color.Transparent
                                ),
                                start = Offset(x - size.width * 0.35f, 0f),
                                end = Offset(x + size.width * 0.35f, size.height)
                            )
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                // 版本小药丸
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.18f)
                ) {
                    Text(
                        text = version,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}
