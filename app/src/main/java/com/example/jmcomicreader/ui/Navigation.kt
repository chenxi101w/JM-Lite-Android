package com.example.jmreader.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tune
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.jmreader.ui.detail.DetailScreen
import com.example.jmreader.ui.discover.DiscoverScreen
import com.example.jmreader.ui.downloads.DownloadsScreen
import com.example.jmreader.ui.library.LibraryScreen
import com.example.jmreader.ui.library.LibraryViewModel
import com.example.jmreader.ui.reader.ReaderScreen
import com.example.jmreader.ui.search.SearchViewModel
import com.example.jmreader.ui.settings.SettingsGroupScreen
import com.example.jmreader.ui.settings.SettingsScreen

/** 路由定义。 */
object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val DOWNLOADS = "downloads"
    const val SEARCH_ARGS = "search?q={q}&mode={mode}"
    const val DETAIL = "detail/{albumId}?local={local}"
    const val READER = "reader/{bookId}/{photoId}/{chapterId}"
    // v1.5.4：设置二级页（设置首页改为分类入口，点进去才是具体项）
    const val SETTINGS_SUB = "settings/{group}"
    const val SUB_APPEARANCE = "appearance"
    const val SUB_LIBRARY = "library"
    const val SUB_SEARCH = "search"
    const val SUB_READER = "reader"
    const val SUB_DOWNLOAD = "download"
    const val SUB_ABOUT = "about"

    fun detail(albumId: Int, local: Boolean = false) = "detail/$albumId?local=$local"
    fun reader(bookId: Int, photoId: Int, chapterId: Int) = "reader/$bookId/$photoId/$chapterId"

    /** 带关键词跳搜索页（历史遗留引用）。q 需转义，避免空格与 & 破坏路由。 */
    fun search(q: String, mode: String = "keyword") =
        "search?q=" + android.net.Uri.encode(q) + "&mode=" + mode
}

/**
 * 轻量版底部导航（v1.0.0）：书架 / 搜索 / 设置。
 *
 * v1.6.3：每个 Tab 配**专属主题色**（选中时图标与文字用该色），
 * 换掉原来千篇一律的 `Book/Search/Settings`：
 *  - 书架 `CollectionsBookmark`（书夹，比 `Book` 更像"收藏的书"）— 琥珀色
 *  - 搜索 `TravelExplore`（放大镜带把，比 `Search` 更活跃）— 青色
 *  - 设置 `Tune`（调节滑块，比 `Settings` 齿轮更"可调"）— 紫色
 */
enum class TopLevelRoute(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** 选中态图标/文字色（未选中统一用 onSurfaceVariant）。 */
    val tint: Color,
) {
    LIBRARY(Routes.LIBRARY, "书架", Icons.Filled.CollectionsBookmark, Color(0xFFF59E0B)),
    DISCOVER(Routes.SEARCH, "搜索", Icons.Filled.TravelExplore, Color(0xFF06B6D4)),
    SETTINGS(Routes.SETTINGS, "设置", Icons.Filled.Tune, Color(0xFF7C6BF0)),
}

@Composable
fun AppNavHost(factory: JmViewModelFactory, startRoute: String = Routes.LIBRARY) {
    val nav = rememberNavController()
    // 搜索页 ViewModel 提升到 Activity 作用域：切底部导航时不被销毁，保留结果/输入/滚动位置。
    val searchVm: SearchViewModel = viewModel(factory = factory)
    // 书架 ViewModel 同样提升到 NavHost 作用域：切走再回不重建、不重扫列表。
    val libraryVm: LibraryViewModel = viewModel(factory = factory)
    // ── v1.19.0：页面转场 ────────────────────────────────────────────
    // 之前 NavHost **没配任何转场**，走的是 navigation-compose 的默认值 ——
    // 700ms 纯交叉淡入淡出：两屏同时合成、且慢，观感就是「糊」+「拖」。
    // 现在统一为「180~240ms 淡入 + 轻微缩放」，节奏接近系统级转场：
    //   · enter：淡入 + 0.94→1 放大（容器变换感，比纯淡入更「有方向」）；
    //   · exit ：淡出 + 1→1.03 微放大（让退出的那屏「退到后面去」，不抢焦点）。
    // 时长压到 200ms 上下是**流畅度的关键**：动画越短，用户越感觉「立刻到了」，
    // 且两屏同时合成的窗口更小（Compose 冷启动一屏的开销被摊薄）。
    // 阅读器单独走纯淡入 —— 全屏看图时任何位移/缩放都会让人觉得画面在抖。
    val fastOut = tween<Float>(150, easing = FastOutLinearInEasing)
    NavHost(
        navController = nav,
        startDestination = startRoute.takeIf { it.isNotBlank() } ?: Routes.LIBRARY,
        enterTransition = {
            fadeIn(tween(190)) + scaleIn(
                initialScale = 0.94f,
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            )
        },
        exitTransition = {
            fadeOut(fastOut) + scaleOut(
                targetScale = 1.03f,
                animationSpec = tween(190, easing = FastOutLinearInEasing)
            )
        },
        popEnterTransition = {
            fadeIn(tween(190)) + scaleIn(
                initialScale = 0.96f,
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            )
        },
        popExitTransition = {
            fadeOut(fastOut) + scaleOut(
                targetScale = 1.03f,
                animationSpec = tween(190, easing = FastOutLinearInEasing)
            )
        }
    ) {
        // 「搜索」页（轻量版发现页 = 纯搜索）：无参 = 底部 Tab 进入；带 q/mode = 历史引用跳转
        composable(Routes.SEARCH) {
            DiscoverScreen(nav = nav, searchVm = searchVm)
        }
        composable(
            route = Routes.SEARCH_ARGS,
            arguments = listOf(
                navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("mode") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) {
            val q = it.arguments?.getString("q")
            val mode = it.arguments?.getString("mode") ?: "keyword"
            DiscoverScreen(nav = nav, searchVm = searchVm, initialQuery = q, initialMode = mode)
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(nav = nav, factory = factory, vm = libraryVm)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(nav = nav, factory = factory)
        }
        // v1.5.4：设置二级页
        composable(
            route = Routes.SETTINGS_SUB,
            arguments = listOf(navArgument("group") { type = NavType.StringType })
        ) { entry ->
            SettingsGroupScreen(
                group = entry.arguments?.getString("group") ?: Routes.SUB_ABOUT,
                nav = nav,
                factory = factory
            )
        }
        composable(Routes.DOWNLOADS) {
            DownloadsScreen(nav = nav, factory = factory)
        }
        composable(
            Routes.DETAIL,
            arguments = listOf(
                navArgument("albumId") { type = NavType.IntType },
                navArgument("local") { type = NavType.BoolType; defaultValue = false }
            )
        ) {
            val albumId = it.arguments?.getInt("albumId") ?: 0
            val local = it.arguments?.getBoolean("local") ?: false
            DetailScreen(nav = nav, factory = factory, albumId = albumId, local = local)
        }
        composable(
            Routes.READER,
            enterTransition = { fadeIn(tween(160)) },
            exitTransition = { fadeOut(tween(140)) },
            popEnterTransition = { fadeIn(tween(160)) },
            popExitTransition = { fadeOut(tween(140)) },
            arguments = listOf(
                navArgument("bookId") { type = NavType.IntType },
                navArgument("photoId") { type = NavType.IntType },
                navArgument("chapterId") { type = NavType.IntType }
            )
        ) {
            val bookId = it.arguments?.getInt("bookId") ?: 0
            val photoId = it.arguments?.getInt("photoId") ?: 0
            val chapterId = it.arguments?.getInt("chapterId") ?: 0
            ReaderScreen(factory = factory, nav = nav, bookId = bookId, photoId = photoId, chapterId = chapterId)
        }
    }
}