package com.example.jmreader.data.repository

import com.example.jmreader.data.local.AppDatabase
import com.example.jmreader.data.local.Book
import com.example.jmreader.data.local.Category
import com.example.jmreader.data.local.ComicCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 分类域：书架分类（多级树）与漫画↔分类多对多关联、一键分类。
 * 从 JmRepository 拆出。
 */
class CategoryManager(
    db: AppDatabase
) {
    private val categoryDao = db.categoryDao()
    private val comicCategoryDao = db.comicCategoryDao()
    private val bookDao = db.bookDao()

    fun observeCategories(): Flow<List<Category>> = categoryDao.observeAll()

    fun observeAllComicCategories(): Flow<List<ComicCategory>> = comicCategoryDao.observeAll()

    fun observeComicCategories(comicJmId: Int): Flow<List<Int>> = comicCategoryDao.observeForComic(comicJmId)

    suspend fun createCategory(name: String, parentId: Int? = null): Long =
        categoryDao.insert(Category(name = name.trim(), parentId = parentId))

    suspend fun renameCategory(id: Int, name: String): Boolean {
        val c = categoryDao.get(id) ?: return false
        categoryDao.update(c.copy(name = name.trim()))
        return true
    }

    suspend fun moveCategory(id: Int, parentId: Int?): Boolean {
        val c = categoryDao.get(id) ?: return false
        categoryDao.update(c.copy(parentId = parentId))
        return true
    }

    /** 删除分类：先把子分类提升为其父级，再删自身（避免误删子树）。 */
    suspend fun deleteCategory(id: Int): Boolean {
        val target = categoryDao.get(id) ?: return false
        categoryDao.observeAll().first()
            .filter { it.parentId == id }
            .forEach { categoryDao.update(it.copy(parentId = target.parentId)) }
        // 清理该分类下的漫画关联，避免 comic_categories 留下孤儿记录
        comicCategoryDao.deleteForCategory(id)
        categoryDao.deleteById(id)
        return true
    }

    /**
     * 删除分类并把它下面的漫画全部并入目标分类（漫画原有的其他分类保留）。
     * 与 [deleteCategory] 不同，此函数会先把漫画关联转移到 targetCatId，再删分类，
     * 因此不会留下孤儿记录，也不会丢失漫画归属。子分类同样提升为被删分类的父级。
     * 若 targetCatId == id 或分类不存在则返回 false。
     */
    suspend fun deleteCategoryWithReassign(catId: Int, targetCatId: Int): Boolean {
        if (catId == targetCatId) return false
        val target = categoryDao.get(catId) ?: return false
        // 1. 把该分类下所有漫画加入目标分类（保留其它分类，并移除被删分类自身）
        val comicIds = comicCategoryDao.getComicsForCategory(catId)
        for (comicId in comicIds) {
            val others = comicCategoryDao.getForComic(comicId).toMutableList()
            others.remove(catId)                 // 移除被删分类自身的关联
            if (targetCatId !in others) others.add(targetCatId)
            comicCategoryDao.deleteForComic(comicId)
            comicCategoryDao.insertAll(others.map { ComicCategory(comicId, it) })
        }
        // 2. 子分类提升为被删分类的父级（与 deleteCategory 一致）
        categoryDao.observeAll().first()
            .filter { it.parentId == catId }
            .forEach { categoryDao.update(it.copy(parentId = target.parentId)) }
        // 3. 删除分类（漫画关联已转移，无需 deleteForCategory）
        categoryDao.deleteById(catId)
        return true
    }

    suspend fun setComicCategories(comicJmId: Int, categoryIds: List<Int>) {
        comicCategoryDao.deleteForComic(comicJmId)
        if (categoryIds.isNotEmpty()) {
            comicCategoryDao.insertAll(categoryIds.map { ComicCategory(comicJmId, it) })
        }
    }

    suspend fun getComicCategories(comicJmId: Int): List<Int> =
        comicCategoryDao.getForComic(comicJmId)

    /** 已归入任意分类的漫画 id 集合。 */
    private suspend fun getCategorizedComicIds(): Set<Int> =
        comicCategoryDao.observeAll().first().map { it.comicJmId }.toSet()

    /** 默认分类（sentinel=-1）包含的漫画 id：已下载但无任何分类。 */
    suspend fun getDefaultCategoryBookIds(): Set<Int> {
        val downloaded = bookDao.observeAll().first().filter { it.savedDir.isNotBlank() }.map { it.id }.toSet()
        return downloaded - getCategorizedComicIds()
    }

    /** 某分类下的漫画 id（categoryId=-1 表示默认分类）。 */
    suspend fun getBookIdsInCategory(categoryId: Int): List<Int> =
        if (categoryId == -1) getDefaultCategoryBookIds().toList() else comicCategoryDao.getComicsForCategory(categoryId)

}
