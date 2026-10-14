package com.example.jmreader.data.repository

/**
 * 首启自检报告（v7.3.0）：版本升级后第一次进入 App 时产生，
 * 由 MainActivity 观察 [com.example.jmreader.JmComicApplication.startupCheckReport] 弹窗展示。
 *
 * @param versionFrom 升级前的版本号（data_audit_version；0 = 全新安装）
 * @param versionTo   当前版本号（BuildConfig.VERSION_CODE）
 * @param autoFixed   本次自动修正的条目（版本修正点 + 数据体检自动补齐）
 * @param faults      识别出的故障（Python 桥接 / 数据一致性 / 存储目录）
 */
data class StartupCheckReport(
    val versionFrom: Int,
    val versionTo: Int,
    val autoFixed: List<String>,
    val faults: List<String>
)

/**
 * 版本数据修正点登记（v7.3.0）。
 *
 * 每次发版如有「升级后要自动修改的数据」，往 JmRepository.versionFixups 加一条即可：
 * 升级后首启自检会自动执行。`appliesFrom` 表示「从该版本起生效」——即升级前的
 * data_audit_version 小于它才需要跑；修完由自检自动登记进报告。
 *
 * 硬性要求：**修正点必须幂等**（重复执行无副作用），因为同一修正点可能被多次
 * 版本升级触发（如 145→147 与 146→147 都会跑 appliesFrom=146 的修正点）。
 */
data class VersionFixup(
    val appliesFrom: Int,
    val summary: String,
    val run: suspend (JmRepository) -> Unit
)
