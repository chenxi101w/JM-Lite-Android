package com.example.jmreader.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 全局可观察主题状态：
 * MainActivity 与 SettingsViewModel 共享的单一状态源，主题切换即时生效。
 *
 * 持久化仍走 SharedPreferences，启动时由 MainActivity 从 prefs 读取初始化本状态。
 *
 * v6.0.0 (MD3 重构)：
 *  - 新增 [useDynamicColor]：Android 12+ 动态取色（Material You）开关
 *  - 主题风格（ink/paper/neon）在关闭动态取色时生效，开启动态取色时自动忽略
 *  - 与亮度模式（system/light/dark/auto）正交组合
 */
object ThemeState {
    /** 主题模式：system / light / dark / auto。 */
    var theme by mutableStateOf("system")

    /** 主题风格：violet 暮紫极简（默认）/ ink 墨夜 / paper 纸感 / neon 霓虹。动态取色开启时被忽略。 */
    var themeStyle by mutableStateOf("violet")

    /** 定时夜间模式时间段（theme=auto 时生效）。 */
    var nightStart by mutableStateOf("22:00")
    var nightEnd by mutableStateOf("06:00")

    /** Android 12+ 动态取色（Material You）。true 时忽略 themeStyle，跟随壁纸取色。v1.16.0 默认改 false：让暮紫主题默认可见。 */
    var useDynamicColor by mutableStateOf(false)
}
