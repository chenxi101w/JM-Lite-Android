package com.example.jmreader.ui.filter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jmreader.data.local.Alias
import com.example.jmreader.data.repository.JmRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class FilterViewModel(private val repo: JmRepository) : ViewModel() {

    fun aliasesFor(type: String): Flow<List<Alias>> = repo.observeAliases(type)

    fun addAlias(type: String, alias: String, canonical: String) {
        viewModelScope.launch { repo.addAlias(type, alias, canonical) }
    }

    fun removeAlias(id: Int) {
        viewModelScope.launch { repo.removeAlias(id) }
    }

    /** 一键采纳内置同义词种子（tag 类）。 */
    fun adoptSeed() {
        viewModelScope.launch {
            try {
                // v1.4.0：种子数据从 Python 搬到 Kotlin（随 Chaquopy 一并移除）
                for ((canonical, aliases) in SEED_SYNONYMS_TAGS) {
                    for (alias in aliases) {
                        if (alias != canonical) repo.addAlias("tag", alias, canonical)
                    }
                }
            } catch (_: Exception) {
                // 种子采纳失败不阻断
            }
        }
    }
}

/**
 * 内置同义词种子（canonical 在前，其余为别名）—— v1.4.0 从 `jm_bridge.py` 搬到 Kotlin。
 *
 * 与 PC 版 `SEED_SYNONYMS` 对齐；随 Chaquopy 一并移除 Python 后，这里是唯一来源。
 */
private val SEED_SYNONYMS_TAGS: Map<String, List<String>> = mapOf(
    "巨乳" to listOf("爆乳", "巨乳", "大奶"),
    "JK" to listOf("女子高生", "女学生", "JK"),
    "单篇" to listOf("单话", "短篇", "单篇"),
    "中文" to listOf("汉化", "中文翻译", "中文"),
    "无修正" to listOf("无修", "无修正版", "无修正"),
    "性转" to listOf("性轉", "性转"),
    "调教" to listOf("调教", "凌辱", "调教系"),
)
