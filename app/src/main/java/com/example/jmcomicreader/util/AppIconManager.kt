package com.example.jmreader.util

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager

/**
 * 应用图标切换器（v5.0.x）。
 *
 * Android 8+ 桌面图标由 activity-alias 的 icon 属性决定。
 * 本类管理三个别名（default / alt1 / alt2），通过 PackageManager 启用选中的、禁用其余两个。
 * 切换后桌面图标通常会在几秒到几分钟内刷新，部分启动器需要重启桌面或等待缓存过期。
 */
object AppIconManager {

    /** 图标变体标识，与 AndroidManifest.xml 中的 activity-alias 名称一一对应。 */
    const val ICON_DEFAULT = "default"
    const val ICON_ALT1 = "alt1"
    const val ICON_ALT2 = "alt2"
    const val ICON_DOUBAO2 = "doubao2"
    const val ICON_DOUBAO3 = "doubao3"
    const val ICON_DOUBAO4 = "doubao4"

    private const val PREFS_NAME = "jmcomic_prefs"
    private const val KEY_APP_ICON = "app_icon"

    /** 全部 6 个 alias（默认 + alt1/alt2 + 豆包2~4），切换时启用目标、禁用其余。 */
    private val ICON_ALIASES = listOf(
        ".MainActivityDefault",
        ".MainActivityAlt1",
        ".MainActivityAlt2",
        ".MainActivityDoubao2",
        ".MainActivityDoubao3",
        ".MainActivityDoubao4"
    )

    /** 获取当前持久化的图标变体，未设置或已失效（如旧版本遗留 custom）返回 [ICON_DEFAULT]。 */
    fun getSelectedIcon(context: Context): String {
        val v = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_ICON, ICON_DEFAULT) ?: ICON_DEFAULT
        val known = listOf(ICON_DEFAULT, ICON_ALT1, ICON_ALT2, ICON_DOUBAO2, ICON_DOUBAO3, ICON_DOUBAO4)
        return if (v in known) v else ICON_DEFAULT
    }

    /**
     * 切换应用图标并持久化。
     * @param variant [ICON_DEFAULT] / [ICON_ALT1] / [ICON_ALT2] / [ICON_DOUBAO2]..[ICON_DOUBAO4]
     */
    fun setIcon(context: Context, variant: String) {
        val targetAlias = when (variant) {
            ICON_ALT1 -> ".MainActivityAlt1"
            ICON_ALT2 -> ".MainActivityAlt2"
            ICON_DOUBAO2 -> ".MainActivityDoubao2"
            ICON_DOUBAO3 -> ".MainActivityDoubao3"
            ICON_DOUBAO4 -> ".MainActivityDoubao4"
            else -> ".MainActivityDefault"
        }
        val pm = context.packageManager
        val pkg = context.packageName
        for (alias in ICON_ALIASES) {
            val component = ComponentName(pkg, pkg + alias)
            val newState = if (alias == targetAlias) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(
                component,
                newState,
                PackageManager.DONT_KILL_APP
            )
        }
        prefs(context).edit().putString(KEY_APP_ICON, variant).apply()
    }

    /**
     * 应用最近一次保存的图标设置（通常在 [MainActivity.onCreate] 调用，
     * 确保进程重启、覆盖安装等场景下图标状态与持久化一致）。
     *
     * 额外加入「自检恢复」：若检测到没有任何 alias 处于启用状态（launcher 状态错乱、
     * 覆盖安装残留等异常），强制恢复为默认图标，避免桌面无入口/无图标。
     */
    fun applyStoredIcon(context: Context) {
        val pm = context.packageManager
        val pkg = context.packageName
        val enabledCount = ICON_ALIASES.count { alias ->
            val state = pm.getComponentEnabledSetting(ComponentName(pkg, pkg + alias))
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        // 异常状态（0 个或全部被禁用）时，忽略持久化、强制恢复默认，保证桌面一定有图标
        val target = if (enabledCount == 1) getSelectedIcon(context) else ICON_DEFAULT
        setIcon(context, target)
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
