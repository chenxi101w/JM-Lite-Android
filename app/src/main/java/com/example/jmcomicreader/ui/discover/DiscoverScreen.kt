package com.example.jmreader.ui.discover

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.jmreader.ui.MainScaffold
import com.example.jmreader.ui.Routes
import com.example.jmreader.ui.search.SearchBody
import com.example.jmreader.ui.search.SearchViewModel
import com.example.jmreader.ui.topLevelRouteOf

/**
 * 「发现」页（v1.0.0 轻量版）：**只保留搜索**。
 * 删除：推荐流 / 订阅作者新作区块 / 搜索态-推荐态切换。进页即搜索框，回车搜索，
 * 结果网格点卡片进详情、可下载。返回键交给系统（无拦截）。
 *
 * 带参进来（详情页点作者/标签，历史遗留引用）时直接作为关键词搜索。
 */
@Composable
fun DiscoverScreen(
    nav: NavController,
    searchVm: SearchViewModel,
    initialQuery: String? = null,
    initialMode: String = "keyword"
) {
    MainScaffold(
        currentRoute = topLevelRouteOf(Routes.SEARCH),
        nav = nav,

    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            SearchBody(
                // v1.1.6：顶部间距 12dp → 2dp，搜索栏整体上移（原位置离状态栏偏空）；
                // 底部保持 12dp，避免结果列表贴住底部导航。
                padding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
                nav = nav,
                vm = searchVm,
                // v1.2.2：不再按 mode 丢弃 query ——
                // 此前非 keyword 模式一律传 null，导致「详情页点作者 / 标签搜索」点了没反应。
                initialQuery = initialQuery,
                initialMode = initialMode
            )
        }
    }
}