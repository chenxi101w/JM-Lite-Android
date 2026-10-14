package com.example.jmreader.data.repository

/**
 * 算法逻辑报告（P2-3/P3-2，3.0.0）：把「搜索/推荐是怎么算出来的」浓缩成一份可展示的数据。
 * 由 JmRepository.getLogicReport() 组合喜欢画像、权重、屏蔽、同义词、策略后产出，供逻辑查看页可视化。
 */
data class LogicReport(
    /** 全局总开关「智能关联拓展」是否开启。 */
    val smartAssociation: Boolean,
    /** 收藏样本数（喜欢正样本 = favorite==1 的漫画数），即喜欢基线规模。 */
    val sampleCount: Int,
    /** 手动喜欢条数。 */
    val manualCount: Int,
    /** 主喜欢 Top（标签, 权重），最多取前 12 条展示。 */
    val primaryTop: List<Pair<String, Int>>,
    /** 微喜欢 Top（标签, 权重），最多取前 12 条展示。 */
    val microTop: List<Pair<String, Int>>,
    /** 标题名称特征 Top（关键字分词, 出现次数），最多取前 12 条；与标签喜欢分开存放展示。 */
    val keywordTop: List<Pair<String, Int>>,
    /** 同义词/别名按类型计数（tag/author）。 */
    val aliasCounts: Map<String, Int>,
    /** 喜欢红线阈值：标签/名称特征需出现至少 N 次才计入。 */
    val minHits: Int,
    /** 当前搜索策略 id。 */
    val searchStrategy: String,
    /** 当前搜索策略名称（来自 AlgorithmRegistry）。 */
    val searchStrategyLabel: String,
    /** 当前推荐策略 id。 */
    val recommendStrategy: String,
    /** 当前推荐策略名称（来自 AlgorithmRegistry）。 */
    val recommendStrategyLabel: String
)
