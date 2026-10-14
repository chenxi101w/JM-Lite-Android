package com.example.jmreader.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.jmreader.data.repository.JmRepository
import com.example.jmreader.ui.detail.DetailViewModel
import com.example.jmreader.ui.downloads.DownloadsViewModel
import com.example.jmreader.ui.filter.FilterViewModel
import com.example.jmreader.ui.home.HomeViewModel
import com.example.jmreader.ui.library.LibraryViewModel
import com.example.jmreader.ui.recycle.RecycleViewModel
import com.example.jmreader.ui.reader.ReaderViewModel
import com.example.jmreader.ui.search.SearchViewModel
import com.example.jmreader.ui.settings.SettingsViewModel

/** 统一 ViewModel 工厂：注入 JmRepository 与 Context（主题等需本地存储的 ViewModel 使用）。 */
class JmViewModelFactory(
    private val repo: JmRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(SearchViewModel::class.java) ->
                SearchViewModel(repo) as T
            modelClass.isAssignableFrom(DetailViewModel::class.java) ->
                DetailViewModel(repo) as T
            modelClass.isAssignableFrom(ReaderViewModel::class.java) ->
                ReaderViewModel(repo) as T
            modelClass.isAssignableFrom(LibraryViewModel::class.java) ->
                LibraryViewModel(repo) as T
            modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                HomeViewModel(repo) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(repo, context) as T
            modelClass.isAssignableFrom(RecycleViewModel::class.java) ->
                RecycleViewModel(repo) as T
            modelClass.isAssignableFrom(DownloadsViewModel::class.java) ->
                DownloadsViewModel(repo) as T
            modelClass.isAssignableFrom(FilterViewModel::class.java) ->
                FilterViewModel(repo) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
        }
    }
}
