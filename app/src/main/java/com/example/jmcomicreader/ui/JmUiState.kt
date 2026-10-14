package com.example.jmreader.ui

/**
 * 统一 UI 状态容器：取代各 ViewModel 散落的 _loading / _error / _data 三件套，
 * 让加载、成功、失败成为编译期可区分的状态，UI 用 when 穷尽处理，避免遗漏。
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
