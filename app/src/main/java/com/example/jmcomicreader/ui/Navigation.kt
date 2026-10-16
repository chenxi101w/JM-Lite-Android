package com.example.jmreader.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.jmreader.ui.detail.DetailScreen
import com.example.jmreader.ui.detail.DetailViewModel
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
 * 轻量版底部导航：书架 / 搜索 / 设置。
 *
 * v1.27.0（照抄参考实现 `jm-comic-android` 的 CompactBottomBar 逐项对齐）：
 *  - 图标换成**市面最常见**的三个：`Bookmarks` / `Search` / `Settings`；
 *  - 配色从 v1.6.3 的「三色跳脱」（琥珀/青/紫）改为**相邻色相渐变**（橙/青蓝/紫），
 *    远看仍分得清、近看是一套；图标用 `Brush` 走渐变，文字取渐变深色端。
 */
enum class TopLevelRoute(
    val route: String,
    val label: String,
    /** 选中态图标（实心）。 */
    val icon: ImageVector,
    /** 未选中态图标（线性）—— v1.22.1 起按参考实现的做法：选中/未选中换图标字形，
     *  这样「当前在哪一栏」不靠指示器动画也能一眼看出。 */
    val iconIdle: ImageVector,
    /** v1.27.2：去掉「每个 Tab 一个色」的方案。
     *  实测参考实现（录屏量像素）的选中态是**纯黑图标 + 黑色加粗文字**，
     *  未选中是灰色，没有任何渐变/背景药丸/指示器。跟随参考 -> 这里不再存颜色，
     *  由 CompactTabBar 统一取 `onSurface`（黑）/`onSurfaceVariant`（灰）。 */
) {
    // v1.28.0：原「搜索」页升为「首页」并提到第一位（用户：「咱们搜索页改成首页（第一页）」）。
    // 图标跟着语义走：搜索 -> 首页用 Home（与参考实现的「首页」一致）。
    DISCOVER(Routes.SEARCH, "首页", Icons.Filled.Home, Icons.Outlined.Home),
    LIBRARY(Routes.LIBRARY, "书架", Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks),
    SETTINGS(Routes.SETTINGS, "设置", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/**
 * 该路由是否属于某个顶级 Tab（底部导航三选一）。
 *
 * 复用 [topLevelRouteOf] 的归一化 —— 它已经把 `search/{q}/{mode}` 这类子路由归到
 * `search`，所以「书架 → 带参搜索页」也会被正确认成 Tab 切换（底部栏本来就把那里
 * 高亮成「搜索」Tab，观感一致）。**不要再另立一份 Tab 路由清单**，否则两处会漂移。
 */
private fun isTopLevelTab(route: String?): Boolean {
    val normalized = topLevelRouteOf(route)
    return normalized == Routes.LIBRARY || normalized == Routes.SEARCH || normalized == Routes.SETTINGS
}

/**
 * 是否属于「顶级 Tab 之间互切」——只有这种情况关掉转场（瞬时到位）。
 * 其余（Tab → 详情/阅读器/设置二级页）照常走 push/pop 动画。
 */
private fun androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.isTabSwitch(): Boolean =
    isTopLevelTab(initialState.destination.route) && isTopLevelTab(targetState.destination.route)

@Composable
fun AppNavHost(factory: JmViewModelFactory, startRoute: String = Routes.SEARCH) {
    val nav = rememberNavController()
    // 搜索页 ViewModel 提升到 Activity 作用域：切底部导航时不被销毁，保留结果/输入/滚动位置。
    val searchVm: SearchViewModel = viewModel(factory = factory)
    // 书架 ViewModel 同样提升到 NavHost 作用域：切走再回不重建、不重扫列表。
    val libraryVm: LibraryViewModel = viewModel(factory = factory)
    // v1.23.0：详情页 VM 也提升到 NavHost 作用域 —— 它内部有「同一本不重拉」缓存，
    // 但页面作用域的 VM 一离开就销毁，从详情页切 Tab 再回来会重新转圈拉一遍。
    val detailVm: DetailViewModel = viewModel(factory = factory)
    // ── v1.22.0：转场改为参考实现那套「iOS 风格 push/pop」───────────────
    // 对照 1976764/jm-comic-android 的 AppNavigation.kt（用户说的「人家」）：
    //   enter：新页从右侧**整屏滑入** 250ms；
    //   exit ：当前页向左移 **1/4 屏** 250ms（视差，制造纵深）；
    //   popExit：当前页向右整屏滑出 + 渐隐 220ms；popEnter：上一页淡入 220ms。
    // 关键差别（v1.19.0 我做错的地方）：
    //   ① 我用的是 fade + scale，方向感弱；滑动的方向本身就在告诉用户「进了下一层」；
    //   ② **人家的底部 Tab 切换是完全不做动画的**（`when(selectedTab)` 直接换内容，瞬时到位），
    //      而我给所有路由套了同一套淡入缩放 —— 每次切 Tab 都要等 200ms，主观就是「慢半拍」。
    // 所以这里加了 isTabSwitch() 判断：**顶级 Tab 互切 → None（瞬时）**，
    // 其余（进详情/阅读器/设置二级页）才走滑动。
    NavHost(
        navController = nav,
        startDestination = startRoute.takeIf { it.isNotBlank() } ?: Routes.SEARCH,
        // v1.24.0：默认改成**淡入淡出**。原来默认就是横滑（层级下钻那套），
        // 结果连「详情 → 搜索结果」「点 Tab」这种平级跳转也横滑 —— 用户反馈
        //「切哪个都是左翻页」。按 M3 选型（见 Knowledge Base/Animation/03）：
        // **横滑只用于层级下钻**，平级关系用 fade through。真正需要横滑的
        // 详情页在下面单独覆盖。
        enterTransition = {
            if (isTabSwitch()) EnterTransition.None
            else fadeIn(tween(190))
        },
        exitTransition = {
            if (isTabSwitch()) ExitTransition.None
            else fadeOut(tween(170))
        },
        popEnterTransition = {
            if (isTabSwitch()) EnterTransition.None else fadeIn(tween(220))
        },
        popExitTransition = {
            if (isTabSwitch()) ExitTransition.None
            else slideOutHorizontally(tween(220)) { full -> full } + fadeOut(tween(220))
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
            ),
            // v1.24.0：**只有详情页保留横滑**（它是真正的"进入下一层"）。
            // 新页右侧整屏滑入 250ms，旧页左移 1/4 屏做视差；
            // 返回时当前页向右滑出并渐隐 220ms，上一页淡入。
            enterTransition = { slideInHorizontally(tween(250)) { full -> full } },
            exitTransition = { slideOutHorizontally(tween(250)) { full -> -full / 4 } },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = {
                slideOutHorizontally(tween(220)) { full -> full } + fadeOut(tween(220))
            }
        ) {
            val albumId = it.arguments?.getInt("albumId") ?: 0
            val local = it.arguments?.getBoolean("local") ?: false
            DetailScreen(nav = nav, vm = detailVm, albumId = albumId, local = local)
        }
        composable(
            Routes.READER,
            // v1.24.0：全屏看图**不要位移** —— 横向滑动会让画面晃眼，纯淡入。
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