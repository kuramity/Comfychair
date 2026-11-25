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
 * 关闭互斥（方案 A）：[release] 只**请求**关闭，物理关闭由 [TagDatabase] 的引用计数门
 * 推迟到最后一个在途查询离开；建索引期间本类额外持一个引用，并在写 `searcher` 前
 * 检查关闭门，因此「close 与在途查询重叠」既不会崩进程，也不会把断柄句柄固化进单例。
 * 内存泄漏防护：协程作用域由调用方（ViewModel）持有，本类不持有 Context 引用。
 */
class TagRepository private constructor() {

    @Volatile
    private var searcher: TagSearcher? = null

    private val initMutex = Mutex()

    init {
        // 句柄一旦被物理关闭，缓存的 searcher 立即作废：performSearch 首行
        // `searcherOrNull() ?: return` 就会早退，不会拿陈旧句柄去查库。
        // 这一行把「searcher 的生命周期」变成「句柄生命周期」的子集，
        // 使毒化单例在结构上不可能发生（诊断报告第三节的最后一个缺口）。
        TagDatabase.onHandleClosed = { searcher = null }
    }

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
                    // 整个建索引过程（两条大游标，实测 3-12 秒）额外持一个引用：
                    // 期间到达的 close 请求只会置标志，不会把句柄从正在跑的游标底下抽走，
                    // 从而也消掉了「游标两次 fillWindow 之间被 close」的静默缺数据形态。
                    if (!TagDatabase.acquire()) {
                        // open 刚成功、这里却被拒：说明这之间有人 close。放弃本次初始化，
                        // 交给 Result.failure → UI 重试提示（排空是毫秒级，重试即可成功）。
                        throw IllegalStateException("标签库在打开后立刻被关闭，请重试")
                    }
                    try {
                        val dao = TagDao(db)
                        // 先装繁简/异体字表：内存索引构建时就要按简体归一化别名
                        HanVariantTable.ensureLoaded(context)
                        val index = TagMemoryIndex.build(dao)
                        TagSynonymTable.ensureLoaded(context)
                        val candidate = TagSearcher(dao)
                        // 构建期间收到过关闭请求（现在是挂起状态、等我们退出计数）：
                        // 绝不能把这个马上就要断柄的 searcher 写进单例。抛出去 → 变成
                        // Result.failure（Kotlin Result.map 会捕获 transform 的异常）。
                        if (TagDatabase.isClosePending) {
                            throw IllegalStateException("标签库在索引构建期间被关闭，请重试")
                        }
                        candidate.memoryIndex = index
                        searcher = candidate
                        candidate
                    } finally {
                        TagDatabase.release()
                    }
                }
            }
        }
    }

    /** 当前是否已就绪（未就绪时 UI 显示加载态）。 */
    val isReady: Boolean get() = searcher != null

    /** 已就绪的搜索器（未就绪返回 null）。 */
    fun searcherOrNull(): TagSearcher? = searcher

    /**
     * 释放数据库句柄（应用退出时可选调用）。
     *
     * 方案 A：这里只是**请求**关闭。仍有查询在途时物理关闭会被推迟到最后一个查询离开，
     * 本方法不阻塞，因此 `TagLibraryViewModel.retryInitialize` 可以在主线程调用。
     * `searcher` 立即置 null（与修复前一致），新搜索因此在首行早退；
     * 句柄真正关闭时 [TagDatabase.onHandleClosed] 会再兜底作废一次。
     */
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
