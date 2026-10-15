package com.example.jmreader

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import java.util.Calendar
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsControllerCompat
import com.example.jmreader.ui.AppNavHost
import com.example.jmreader.ui.JmViewModelFactory
import com.example.jmreader.ui.LocalShowHints
import com.example.jmreader.ui.ShowHintsState
import com.example.jmreader.ui.theme.JMComicTheme
import com.example.jmreader.ui.theme.ThemeState
import com.example.jmreader.ui.theme.themeBackground
import com.example.jmreader.util.AppIconManager

class MainActivity : ComponentActivity() {
    /** 通知点击起始路由（""=落地默认页，v6.1.0 起为书架；"library"=书架）。字段级状态，onNewIntent 可更新。 */
    private var navRoute by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ── v6.0.0 MD3：开启 edge-to-edge，让内容延伸到状态栏/导航栏下方 ──
        enableEdgeToEdge()

        navRoute = intent?.getStringExtra("nav_route") ?: ""
        val prefs = getSharedPreferences("jmcomic_prefs", Context.MODE_PRIVATE)
        // ── 从持久化配置初始化全局主题状态 ──
        ThemeState.theme = prefs.getString("theme", "system") ?: "system"
        ThemeState.themeStyle = prefs.getString("theme_style", "violet") ?: "violet"
        ThemeState.nightStart = prefs.getString("night_mode_start", "22:00") ?: "22:00"
        ThemeState.nightEnd = prefs.getString("night_mode_end", "06:00") ?: "06:00"
        // 动态取色：Android 12+ 默认开启，低版本强制关
        ThemeState.useDynamicColor = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            prefs.getBoolean("dynamic_color", false)
        } else {
            false
        }
        // 全局「显示提示说明」开关
        ShowHintsState.enabled = prefs.getBoolean("show_hints", true)

        runCatching { AppIconManager.applyStoredIcon(this) }

        val window = window
        fun syncWindow(dark: Boolean) {
            val bg = themeBackground(
                style = ThemeState.themeStyle,
                useDynamicColor = ThemeState.useDynamicColor,
                dark = dark,
                ctx = this@MainActivity
            )
            val argb = bg.toArgb()
            window.setBackgroundDrawable(ColorDrawable(argb))
            // edge-to-edge：状态栏与导航栏透明，由 Compose Scaffold 的 insets padding 处理内容避让
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }

        setContent {
            val dark = computeDarkNow()
            val style = ThemeState.themeStyle
            val dynColor = ThemeState.useDynamicColor
            syncWindow(dark)
            JMComicTheme(darkTheme = dark, style = style, useDynamicColor = dynColor) {
                DisposableEffect(dark, dynColor, style) { syncWindow(dark); onDispose { } }
                val repo = (application as JmComicApplication).repository
                val factory = remember { JmViewModelFactory(repo, applicationContext) }
                // v1.2.7：移除了启动时的通知权限请求。
                // 本 App 没有任何通知实现（全工程搜不到 NotificationManager/NotificationCompat），
                // 之前却在每次冷启动都弹一次「是否允许通知」——纯粹骚扰，且用户会疑惑为什么要这权限。
                LaunchedEffect(Unit) {
                    runCatching {
                        if (repo.getConfig("library_unfinished_first") == "1" &&
                            repo.getConfig("mig_v441_unfinished_first") == null
                        ) {
                            repo.setConfig("library_unfinished_first", "0")
                            repo.setConfig("mig_v441_unfinished_first", "1")
                        }
                    }
                }
                CompositionLocalProvider(LocalShowHints provides ShowHintsState.enabled) {
                    key(navRoute) {
                        AppNavHost(factory = factory, startRoute = navRoute)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("nav_route")?.let { route ->
            if (route != navRoute) navRoute = route
        }
    }

    private fun computeDarkNow(): Boolean {
        return when (ThemeState.theme) {
            "light" -> false
            "dark" -> true
            "auto" -> isInNightWindow(ThemeState.nightStart, ThemeState.nightEnd)
            else -> isSystemDark()
        }
    }

    private fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun isInNightWindow(start: String, end: String): Boolean {
        val (sh, sm) = parseHHMM(start)
        val (eh, em) = parseHHMM(end)
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMin = sh * 60 + sm
        val endMin = eh * 60 + em
        return if (startMin <= endMin) nowMin in startMin..endMin
        else nowMin >= startMin || nowMin <= endMin
    }

    private fun parseHHMM(s: String): Pair<Int, Int> {
        val parts = s.split(":").mapNotNull { it.toIntOrNull() }
        val h = (parts.getOrNull(0) ?: 0).coerceIn(0, 23)
        val m = (parts.getOrNull(1) ?: 0).coerceIn(0, 59)
        return h to m
    }
}
