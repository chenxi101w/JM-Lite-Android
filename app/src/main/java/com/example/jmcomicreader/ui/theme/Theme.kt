package com.example.jmreader.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v6.0.0 MD3 设计系统全面重构：
 *  - Android 12+ 优先 Material You 动态取色，关闭动态取色时走三套预设风格
 *  - 预设风格：墨夜沉浸 / 纸感暖橘 / 霓虹紫夜（保留原配色，色值更对齐 MD3 tonal palette）
 *  - 完整 Typography 字号层级：Display / Headline / Title / Body / Label 共 9 档
 *  - 大圆角 Shapes：extra-small 6dp → extra-large 40dp，更现代更柔软
 *  - 新增 CompositionLocal<JmTints>：提供品牌渐变色 / 表面叠加色等派生色
 */

// ═══════════════════════════════════════════════════════════════
// ① 墨夜 · 沉浸漫读：暗色优先、纯黑 OLED 省电 + 朱砂红热血点缀
// ═══════════════════════════════════════════════════════════════
private object Ink {
    val light = lightColorScheme(
        primary = Color(0xFFB3261E),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDAD6),
        onPrimaryContainer = Color(0xFF410002),
        secondary = Color(0xFF775654),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDAD6),
        onSecondaryContainer = Color(0xFF2C1514),
        tertiary = Color(0xFF705C2E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFBDFA6),
        onTertiaryContainer = Color(0xFF251A00),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFBF8F6),
        onBackground = Color(0xFF201A19),
        surface = Color(0xFFFFFBFF),
        onSurface = Color(0xFF201A19),
        surfaceVariant = Color(0xFFF5DDDC),
        onSurfaceVariant = Color(0xFF534342),
        outline = Color(0xFF857371),
        outlineVariant = Color(0xFFD8C2C0),
        inverseSurface = Color(0xFF362F2E),
        inverseOnSurface = Color(0xFFFBEEEC),
        inversePrimary = Color(0xFFFFB4AB),
        scrim = Color(0xFF000000),
        surfaceTint = Color(0xFFB3261E)
    )
    val dark = darkColorScheme(
        primary = Color(0xFFFFB4AB),
        onPrimary = Color(0xFF690005),
        primaryContainer = Color(0xFF93000A),
        onPrimaryContainer = Color(0xFFFFDAD6),
        secondary = Color(0xFFE7BDB9),
        onSecondary = Color(0xFF442928),
        secondaryContainer = Color(0xFF5D3F3E),
        onSecondaryContainer = Color(0xFFFFDAD6),
        tertiary = Color(0xFFDEC48C),
        onTertiary = Color(0xFF3E2E04),
        tertiaryContainer = Color(0xFF574419),
        onTertiaryContainer = Color(0xFFFBDFA6),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0B0908),
        onBackground = Color(0xFFEDE0DE),
        surface = Color(0xFF15120F),
        onSurface = Color(0xFFEDE0DE),
        surfaceVariant = Color(0xFF534342),
        onSurfaceVariant = Color(0xFFD8C2C0),
        outline = Color(0xFFA08C8A),
        outlineVariant = Color(0xFF534342),
        inverseSurface = Color(0xFFEDE0DE),
        inverseOnSurface = Color(0xFF362F2E),
        inversePrimary = Color(0xFFB3261E),
        scrim = Color(0xFF000000),
        surfaceTint = Color(0xFFFFB4AB)
    )
}

// ═══════════════════════════════════════════════════════════════
// ② 纸感 · 暖橘杂志：纸本杂志的温暖感
// ═══════════════════════════════════════════════════════════════
private object Paper {
    val light = lightColorScheme(
        primary = Color(0xFFBF4200),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDBCE),
        onPrimaryContainer = Color(0xFF3F1100),
        secondary = Color(0xFF9F4300),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDBC2),
        onSecondaryContainer = Color(0xFF341100),
        tertiary = Color(0xFF006874),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF9FF0FF),
        onTertiaryContainer = Color(0xFF001F24),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFFF8F5),
        onBackground = Color(0xFF251711),
        surface = Color(0xFFFFFBFF),
        onSurface = Color(0xFF251711),
        surfaceVariant = Color(0xFFF5DED3),
        onSurfaceVariant = Color(0xFF53443C),
        outline = Color(0xFF85746A),
        outlineVariant = Color(0xFFD8C2B8),
        inverseSurface = Color(0xFF3B2B24),
        inverseOnSurface = Color(0xFFFFEDE4),
        inversePrimary = Color(0xFFFFB596),
        scrim = Color(0xFF000000),
        surfaceTint = Color(0xFFBF4200)
    )
    val dark = darkColorScheme(
        primary = Color(0xFFFFB596),
        onPrimary = Color(0xFF662100),
        primaryContainer = Color(0xFF912E00),
        onPrimaryContainer = Color(0xFFFFDBCE),
        secondary = Color(0xFFFFB588),
        onSecondary = Color(0xFF552000),
        secondaryContainer = Color(0xFF7A3100),
        onSecondaryContainer = Color(0xFFFFDBC2),
        tertiary = Color(0xFF4FD8EB),
        onTertiary = Color(0xFF00363D),
        tertiaryContainer = Color(0xFF004F58),
        onTertiaryContainer = Color(0xFF9FF0FF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF100B09),
        onBackground = Color(0xFFF3DFD5),
        surface = Color(0xFF1C1410),
        onSurface = Color(0xFFF3DFD5),
        surfaceVariant = Color(0xFF53443C),
        onSurfaceVariant = Color(0xFFD8C2B8),
        outline = Color(0xFFA08E83),
        outlineVariant = Color(0xFF53443C),
        inverseSurface = Color(0xFFF3DFD5),
        inverseOnSurface = Color(0xFF3B2B24),
        inversePrimary = Color(0xFFBF4200),
        scrim = Color(0xFF000000),
        surfaceTint = Color(0xFFFFB596)
    )
}

// ═══════════════════════════════════════════════════════════════
// ③ 霓虹 · 紫夜渐变：深空 + 紫青霓虹
// ═══════════════════════════════════════════════════════════════
private object Neon {
    val light = lightColorScheme(
        primary = Color(0xFF5B4EE3),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE3DFFF),
        onPrimaryContainer = Color(0xFF16006B),
        secondary = Color(0xFF006878),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF9FEFFF),
        onSecondaryContainer = Color(0xFF001F25),
        tertiary = Color(0xFF984061),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFD9E2),
        onTertiaryContainer = Color(0xFF3E001D),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFFFBFF),
        onBackground = Color(0xFF1C1B1F),
        surface = Color(0xFFFFFBFF),
        onSurface = Color(0xFF1C1B1F),
        surfaceVariant = Color(0xFFE5E1EC),
        onSurfaceVariant = Color(0xFF47464F),
        outline = Color(0xFF77767F),
        outlineVariant = Color(0xFFC8C5D0),
        inverseSurface = Color(0xFF313033),
        inverseOnSurface = Color(0xFFF4EFF4),
        inversePrimary = Color(0xFFC5C0FF),
        scrim = Color(0xFF000000),
        surfaceTint = Color(0xFF5B4EE3)
    )
    val dark = darkColorScheme(
        primary = Color(0xFFC5C0FF),
        onPrimary = Color(0xFF2B1297),
        primaryContainer = Color(0xFF4234CA),
        onPrimaryContainer = Color(0xFFE3DFFF),
        secondary = Color(0xFF53D8F0),
        onSecondary = Color(0xFF00363F),
        secondaryContainer = Color(0xFF004E5B),
        onSecondaryContainer = Color(0xFF9FEFFF),
        tertiary = Color(0xFFFFB1C8),
        onTertiary = Color(0xFF5E1132),
        tertiaryContainer = Color(0xFF7D2949),
        onTertiaryContainer = Color(0xFFFFD9E2),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0C0B12),
        onBackground = Color(0xFFE6E1E6),
        surface = Color(0xFF14121A),
        onSurface = Color(0xFFE6E1E6),
        surfaceVariant = Color(0xFF47464F),
        onSurfaceVariant = Color(0xFFC8C5D0),
        outline = Color(0xFF918F9A),
        outlineVariant = Color(0xFF47464F),
        inverseSurface = Color(0xFFE6E1E6),
        inverseOnSurface = Color(0xFF313033),
        inversePrimary = Color(0xFF5B4EE3),
        scrim = Color(0xFF000000),
        surfaceTint = Color(0xFFC5C0FF)
    )
}

// ═══════════════════════════════════════════════════════════════
// ④ 暮紫 · 极简分层（v1.16.0，参考 YOMII 的克制分层哲学 × 紫色主色）
//    特点：surface 灰阶精细分级（Lowest→Highest 逐级加深）、主色低饱和沉稳紫、
//    亮色底是微紫灰白而非纯白、暗色底近黑微紫——分层全靠 1~6% 的亮度差撑层次
// ═══════════════════════════════════════════════════════════════
private object Violet {
    val light = lightColorScheme(
        primary = Color(0xFF5B3FA8),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE9E2FA),
        onPrimaryContainer = Color(0xFF241457),
        secondary = Color(0xFF62597B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE9E4F5),
        onSecondaryContainer = Color(0xFF1E1A33),
        tertiary = Color(0xFF6E6486),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE5DFF2),
        onTertiaryContainer = Color(0xFF241D38),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF8F7FB),
        onBackground = Color(0xFF1A1822),
        surface = Color(0xFFFCFCFE),
        onSurface = Color(0xFF1A1822),
        surfaceVariant = Color(0xFFECEAF3),
        onSurfaceVariant = Color(0xFF5C5868),
        outline = Color(0xFF8B8798),
        outlineVariant = Color(0xFFD9D6E4),
        surfaceDim = Color(0xFFE2E0EA),
        surfaceBright = Color(0xFFFCFCFE),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF8F7FB),
        surfaceContainer = Color(0xFFF3F2F8),
        surfaceContainerHigh = Color(0xFFEEEDF4),
        surfaceContainerHighest = Color(0xFFE9E8F0),
        inverseSurface = Color(0xFF2F2D3A),
        inverseOnSurface = Color(0xFFF1EFF7),
        inversePrimary = Color(0xFFC9BCFF),
        scrim = Color(0xFF000000),
        surfaceTint = Color(0xFF5B3FA8)
    )
    val dark = darkColorScheme(
        primary = Color(0xFFC9BCFF),
        onPrimary = Color(0xFF2E1A6B),
        primaryContainer = Color(0xFF442E8C),
        onPrimaryContainer = Color(0xFFE9E2FA),
        secondary = Color(0xFFBFB8D4),
        onSecondary = Color(0xFF2C2740),
        secondaryContainer = Color(0xFF383352),
        onSecondaryContainer = Color(0xFFDDD8EE),
        tertiary = Color(0xFFA99BC8),
        onTertiary = Color(0xFF332A4A),
        tertiaryContainer = Color(0xFF3D3654),
        onTertiaryContainer = Color(0xFFDDD8EE),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0F0F14),
        onBackground = Color(0xFFF2F1F7),
        surface = Color(0xFF16161C),
        onSurface = Color(0xFFF2F1F7),
        surfaceVariant = Color(0xFF2B2935),
        onSurfaceVariant = Color(0xFFC3C0D0),
        outline = Color(0xFF4A4757),
        outlineVariant = Color(0xFF2B2935),
        surfaceDim = Color(0xFF0F0F14),
        surfaceBright = Color(0xFF2A2A32),
        surfaceContainerLowest = Color(0xFF0C0C10),
        surfaceContainerLow = Color(0xFF14141A),
        surfaceContainer = Color(0xFF191920),
        surfaceContainerHigh = Color(0xFF1F1F26),
        surfaceContainerHighest = Color(0xFF25252C),
        inverseSurface = Color(0xFFF2F1F7),
        inverseOnSurface = Color(0xFF2F2D3A),
        inversePrimary = Color(0xFF5B3FA8),
        scrim = Color(0xFF000000),
        surfaceTint = Color(0xFFC9BCFF)
    )
}

// ═══════════════════════════════════════════════════════════════
// 形状体系（MD3 规范升级）：更圆润柔顺，大圆角 + 微变
// ═══════════════════════════════════════════════════════════════
private val JmShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(40.dp)
)

/** 项目内统一使用的形状常量（卡片 / 药丸 / 底部弹层 / chip）。 */
object JmShape {
    /** 漫画卡片：大圆角封面 + 圆润边角 */
    val comicCard: Shape = RoundedCornerShape(24.dp)
    /** 封面内嵌小部件（角标 / 药丸标记） */
    val cornerBadge: Shape = RoundedCornerShape(14.dp)
    /** 标准胶囊形（chip / FAB / 按钮容器） */
    val pill: Shape = RoundedCornerShape(50)
    /** 底部 ModalBottomSheet 顶圆角 */
    val sheet: Shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    /** 搜索框圆角 */
    val searchBar: Shape = RoundedCornerShape(28.dp)
}

// ═══════════════════════════════════════════════════════════════
// Typography 字号层级（完整 MD3 9 档，行高放松，视觉更透气）
// ═══════════════════════════════════════════════════════════════
private val JmTypography = Typography(
    // Display：超大标题 / 启动页品牌
    displayLarge = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 45.sp,
        lineHeight = 52.sp
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 36.sp,
        lineHeight = 44.sp
    ),
    // Headline：页面大标题 / 详情页漫画名
    headlineLarge = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 32.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    // Title：卡片标题 / 分区标题 / 列表项大标题
    titleLarge = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    // Body：正文 / 描述 / 章节列表
    bodyLarge = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    // Label：角标 / 提示 / chip 文案 / 按钮文字
    labelLarge = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.5.sp
    )
)

// ═══════════════════════════════════════════════════════════════
// 额外派生色：品牌渐变 / 封面遮罩 / 品牌色集合
// ═══════════════════════════════════════════════════════════════
@Immutable
data class JmTints(
    /** 封面「已下载/已看完」半透明蒙层颜色 */
    val coverOverlayRead: Color,
    /** 角标默认背景（surface 变体色的高透明度版） */
    val badgeSurface: Color,
    /** 漫画卡片按下时的 ripple 叠加色（按 MD3 要求：primary 色值 12%） */
    val pressOverlay: Color,
    /** 详情页头图大渐变遮罩（底部 → 透明） */
    val heroGradientBottom: Color
)

private val LocalJmTints = compositionLocalOf {
    JmTints(
        coverOverlayRead = Color(0x99000000),
        badgeSurface = Color(0x66FFFFFF),
        pressOverlay = Color(0x1F000000),
        heroGradientBottom = Color(0xCC000000)
    )
}

object JmTone {
    val current: JmTints
        @Composable
        @ReadOnlyComposable
        get() = LocalJmTints.current
}

/** 从当前 theme colorScheme 派生 JmTints（remember 缓存）。 */
@Composable
private fun rememberJmTints(dark: Boolean): JmTints {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme, dark) {
        val scrim = if (dark) Color(0x88000000) else Color(0x66000000)
        JmTints(
            coverOverlayRead = scrim,
            badgeSurface = scheme.surface.copy(alpha = 0.72f),
            pressOverlay = scheme.primary.copy(alpha = 0.12f),
            heroGradientBottom = scheme.surface.copy(alpha = 0.92f)
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// 取色逻辑：动态取色（Android 12+）优先，否则走三套预设风格
// ═══════════════════════════════════════════════════════════════

/** 主题风格标识：ink 墨夜 / paper 纸感 / neon 霓虹。 */
val ThemeStyles = listOf(
    "ink" to "墨夜沉浸",
    "paper" to "纸感暖橘",
    "neon" to "霓虹紫夜",
    "violet" to "暮紫极简"
)

/** 按风格 + 明暗取预设配色（仅当动态取色关闭时使用）。 */
fun themeScheme(style: String, dark: Boolean) = when (style) {
    "paper" -> if (dark) Paper.dark else Paper.light
    "neon" -> if (dark) Neon.dark else Neon.light
    "violet" -> if (dark) Violet.dark else Violet.light
    else -> if (dark) Ink.dark else Ink.light
}

/** 窗口背景色（与 background 同步，供 MainActivity 消除黑窗闪动）。 */
fun themeBackground(style: String, useDynamicColor: Boolean, dark: Boolean, ctx: Context): Color {
    return if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(ctx).background else dynamicLightColorScheme(ctx).background
    } else {
        themeScheme(style, dark).background
    }
}

@Composable
fun JMComicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    style: String = "violet",
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        else -> themeScheme(style, darkTheme)
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = JmShapes,
        typography = JmTypography
    ) {
        // 派生色紧随 colorScheme，放在 MaterialTheme 内部以拿到最新 scheme
        val tints = rememberJmTints(darkTheme)
        CompositionLocalProvider(LocalJmTints provides tints, content = content)
    }
}
