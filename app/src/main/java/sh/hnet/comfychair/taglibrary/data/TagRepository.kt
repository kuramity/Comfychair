package sh.hnet.comfychair.taglibrary.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.taglibrary.search.HanVariantTable
import sh.hnet.comfychair.taglibrary.search.TagMemoryIndex
import sh.hnet.comfychair.taglibrary.search.TagSearcher
import sh.hnet.comfychair.taglibrary.search.TagSynonymTable

/**
 * 标签库仓库（单例）。
 *
 * 职责：
 * - 管理数据库生命周期（首次解压 → 只读打开）
 * - 构建内存索引（IO 线程，只构建一次）
 * - 对外提供 [TagSearcher] 与 [TagDao]
 *
 * 线程安全：初始化用 [initMutex] 串行化，重复调用直接复用已就绪实例。
 * 内存泄漏防护：协程作用域由调用方（ViewModel）持有，本类不持有 Context 引用。
 */
class TagRepository private constructor() {

    @Volatile
    private var searcher: TagSearcher? = null

    private val initMutex = Mutex()

    /**
     * 确保仓库就绪（解压 + 打开 + 建索引），应在 IO 线程调用。
     *
     * @return 成功返回搜索器；失败返回异常（上层展示重试入口）
     */
    suspend fun ensureReady(context: Context): Result<TagSearcher> {
        searcher?.let { return Result.success(it) }
        return initMutex.withLock {
            searcher?.let { return@withLock Result.success(it) }
            withContext(Dispatchers.IO) {
                TagDatabase.open(context).map { db ->
                    val dao = TagDao(db)
                    // 先装繁简/异体字表：内存索引构建时就要按简体归一化别名
                    HanVariantTable.ensureLoaded(context)
                    val index = TagMemoryIndex.build(dao)
                    TagSynonymTable.ensureLoaded(context)
                    TagSearcher(dao).also {
                        it.memoryIndex = index
                        searcher = it
                    }
                }
            }
        }
    }

    /** 当前是否已就绪（未就绪时 UI 显示加载态）。 */
    val isReady: Boolean get() = searcher != null

    /** 已就绪的搜索器（未就绪返回 null）。 */
    fun searcherOrNull(): TagSearcher? = searcher

    /** 释放数据库句柄（应用退出时可选调用）。 */
    fun release() {
        TagDatabase.close()
        searcher = null
        Log.i("TagRepository", "标签库资源已释放")
    }

    companion object {
        @Volatile
        private var instance: TagRepository? = null

        fun getInstance(): TagRepository =
            instance ?: synchronized(this) {
                instance ?: TagRepository().also { instance = it }
            }
    }
}
