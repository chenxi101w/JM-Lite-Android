package com.example.jmreader.data.repository

/**
 * 算法策略注册表（P2-2/P2-3，3.0.0）：把搜索/推荐算法抽象成可枚举、可插拔的策略描述。
 * 设置页「搜索」从这里的 searchStrategies 读取全部策略并高亮当前生效项（4.13.0 起展示于搜索设置）。
 * 当前已实现的真实行为：classic（权重累加）、binary（命中优先，仅搜索生效）。其余为已登记、待实现的规划项。
 */
data class StrategyDescriptor(
    val id: String,
    val label: String,
    val desc: String
)

object AlgorithmRegistry {
    /** 搜索结果排序策略。 */
    val searchStrategies: List<StrategyDescriptor> = listOf(
        StrategyDescriptor(
            "classic",
            "经典加权",
            "喜欢标签权重累加（手动100 / 主喜欢90 / 微喜欢10），总分越高越靠前；无命中则保持原顺序。"
        ),
        StrategyDescriptor(
            "binary",
            "命中优先",
            "只看是否命中喜欢标签（命中=1，未命中=0），不区分权重高低；命中项统一排前。"
        ),
    )

    /** 推荐（主页兴趣）策略。当前仅 classic 一种。 */
    val recommendStrategies: List<StrategyDescriptor> = listOf(
        StrategyDescriptor(
            "classic",
            "经典加权",
            "按喜欢权重表逐标签搜索并合并，权重越高贡献结果越多；讨厌/屏蔽标签自动排除。"
        ),
    )

    fun searchStrategy(id: String): StrategyDescriptor =
        searchStrategies.firstOrNull { it.id == id } ?: searchStrategies.first()

    fun recommendStrategy(id: String): StrategyDescriptor =
        recommendStrategies.firstOrNull { it.id == id } ?: recommendStrategies.first()
}
