package com.example.jmreader.ui.recycle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jmreader.data.repository.JmRepository
import com.example.jmreader.data.repository.RecycleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 回收站页 ViewModel（v1.15.0 自全量版 v7.1.x 移植）：恢复 / 彻底删除 / 清空 / 7 天超期扫描。 */
class RecycleViewModel(private val repo: JmRepository) : ViewModel() {

    /** 回收站列表项。 */
    val items: StateFlow<List<RecycleManager.TrashItem>> = repo.observeTrashed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 保留天数（7），页面显示「N 天后删除」。 */
    val retentionDays: Int = repo.recycleRetentionDays()

    /** 删除进行中（防重复点）。 */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        // 进入回收站页即扫描一次宽限超期项（7 天没再看 → 删本地内容）
        viewModelScope.launch { runCatching { repo.checkRecycleExpiry() } }
    }

    /** 宽限中的漫画：立即删除本地内容进回收站（提前触发 7 天机制）。 */
    fun deleteLocal(bookId: Int) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { repo.trashBook(bookId) }
            _busy.value = false
        }
    }

    /** 恢复：清回收站标记（仅本地已删的漫画，清标记后回到书架完分区，内容重新下载）。 */
    fun restore(bookId: Int) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { repo.restoreFromRecycle(bookId) }
            _busy.value = false
        }
    }

    /** 彻底删除（不可恢复）。 */
    fun deleteForever(bookId: Int) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { repo.deleteFromRecycle(bookId) }
            _busy.value = false
        }
    }

    /** 清空回收站：全部彻底删除。 */
    fun clearAll() {
        val ids = items.value.map { it.bookId }
        viewModelScope.launch {
            _busy.value = true
            for (id in ids) runCatching { repo.deleteFromRecycle(id) }
            _busy.value = false
        }
    }
}
