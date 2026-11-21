package sh.hnet.comfychair.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryMetadataCache
import sh.hnet.comfychair.storage.GalleryTrashEntry
import sh.hnet.comfychair.storage.GalleryTrashStorage
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.GalleryItem
import java.util.UUID

/**
 * Repository for managing gallery data with background preloading and caching.
 * This is a singleton that persists across activities.
 */
/** 视频扩展名（文件级私有：仓库与同文件的 GalleryHistoryParser 共用一份判定） */
private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm", ".gif", ".avi", ".mov")

/**
 * 把"回调式"网络接口适配成挂起函数，并保证**延续只 resume 一次**。
 *
 * P0（v4.16.13 闪退）：旧实现是 `suspendCoroutine { cont -> fetch { cont.resumeWith(...) } }`。
 * 回调若二次触发，第二次 resumeWith 抛 `IllegalStateException: Already resumed`，
 * 而它发生在 OkHttp 回调线程，调用方的 try/catch 根本捕不到 → 进程崩溃。
 *
 * 这里的 `isActive` 判定是最后一道闸：即便某个 client 的"只投递一次"守卫漏了，
 * 延续也不会被二次恢复（第二次投递被静默丢弃，只记日志）。
 *
 * 抽成 internal 顶层函数是为了 JVM 单测能直接"模拟回调被调用两次"并断言不抛异常。
 */
internal suspend fun <T> awaitCallbackOnce(fetch: ((T) -> Unit) -> Unit): T =
    suspendCancellableCoroutine { continuation ->
        fetch { value ->
            if (continuation.isActive) {
                continuation.resume(value)
            } else {
                DebugLogger.w("GalleryRepo", "忽略重复的异步回调投递（延续已恢复/已取消）")
            }
        }
    }

/**
 * 图库最终展示列表的**纯合成**（索引 + 磁盘缓存恢复 + 墓碑过滤）。
 *
 * R1 的落点：`indexItems == null`（索引读不出来）时**绝不能**直接返回空表 ——
 * 磁盘缓存里的图片文件往往还在，这时恢复出来的条目就是用户唯一能看到的图库。
 * 仓储层只负责"要不要保留上一次列表"的决定，合成规则全在这里，JVM 单测直接钉住。
 *
 * @param indexItems 索引合并结果；null = 索引读不出来（不是"索引是空表"）
 * @param cachedKeys 磁盘缓存里现存的条目键（MediaCache.listCachedKeys）
 * @param tombstones 墓碑（已在手机上删掉的条目，含旧版裸 promptId 语义）
 */
internal fun buildVisibleItems(
    indexItems: List<GalleryItem>?,
    cachedKeys: List<MediaCacheKey>,
    tombstones: Set<String>
): List<GalleryItem> =
    recoverFromCachedKeys(indexItems.orEmpty(), cachedKeys)
        .filterNot { GalleryMetadataCache.isDeleted(it, tombstones) }

/**
 * 从磁盘缓存键补回索引里缺失的条目（纯函数；Android 侧只负责把键列表读出来）。
 */
internal fun recoverFromCachedKeys(
    known: List<GalleryItem>,
    cachedKeys: List<MediaCacheKey>
): List<GalleryItem> {
    val knownKeys = known.mapTo(mutableSetOf()) {
        GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename)
    }
    val recovered = cachedKeys
        .filterNot {
            GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename) in knownKeys
        }
        // R2-N1：recovered 内部也要按条目去重。listCachedKeys 已按同一身份合并，
        // 这里再兜一层，保证不会把重复 (promptId, filename) 送进图库 LazyGrid
        // （它的 key 是该二元组，撞 key 会抛 IllegalArgumentException）。
        .distinctBy { GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename) }
        .map { key ->
            GalleryItem(
                promptId = key.promptId,
                filename = key.filename,
                subfolder = "",
                // 新键格式带 type：按写入时的目录恢复（output/temp）；旧格式解析为 output
                type = key.type,
                isVideo = VIDEO_EXTENSIONS.any { key.filename.lowercase().endsWith(it) },
                index = 0
            )
        }
    if (recovered.isNotEmpty()) {
        DebugLogger.i("GalleryRepo", "Recovered ${recovered.size} gallery item(s) from disk cache")
    }
    return known + recovered
}

class GalleryRepository private constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Application context for accessing settings and cache
    private var applicationContext: Context? = null

    // Accessor for shared client from ConnectionManager
    private val comfyUIClient: ComfyUIClient?
        get() = ConnectionManager.clientOrNull

    /**
     * Initialize with application context.
     * Called when needed to access settings/cache.
     */
    fun initialize(context: Context) {
        if (applicationContext == null) {
            applicationContext = context.applicationContext
        }
    }

    // ============================================================
    // 「源已不存在」（/view 404）→ 内存 + 本地索引
    // ============================================================

    /**
     * 本次会话内已经发起过持久化写入的条目（`serverId\0identity`）。
     *
     * MediaCache 对重复 404 只会通知一次（[MediaCache.addSourceMissingListener] 的语义），
     * 这里再兜一层：用户显式重试失败 / 多页并发命中同一文件时，不重复写索引。
     * 写盘失败会把键移除，下次 404 还能再试。
     */
    private val pendingSourceMissingMarks =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        // MediaCache 只认识缓存键；把「404 已确认」转成条目标记 + 持久化，只能由仓储接住。
        // 一个进程只注册一次（仓储是单例）。
        MediaCache.addSourceMissingListener(::onSourceMissing)
    }

    /**
     * MediaCache 判定 /view 404 后的落库入口（可能在 OkHttp 回调线程）。
     *
     * 两件事：① 内存列表里的同一条目置 `sourceMissing = true`（角标立刻出现）；
     * ② 把标记写进本地索引 —— 重开 App / 刷新图库后仍是第三态，不再白等超时。
     * 服务器 /history 只删文件不删记录，因此这个标记只能本地记住。
     */
    private fun onSourceMissing(key: MediaCacheKey) {
        // 服务器身份优先取当前连接；当前无连接时退回键里带的 serverId
        // （离线查看缓存期间命中的 404 也要能落进对应服务器的索引）。
        val serverId = ConnectionManager.currentServerId
            ?: key.serverId.takeIf { it.isNotEmpty() }
            ?: return
        // 键里带着服务器身份：别的服务器的 404 不能标记当前列表里的同名文件
        if (key.serverId.isNotEmpty() && key.serverId != serverId) return

        val identity = GalleryMetadataCache.itemIdentityKey(key.promptId, key.filename)
        val markKey = "$serverId\u0000$identity"
        if (!pendingSourceMissingMarks.add(markKey)) return

        // 先更新内存列表（角标立刻出现），落盘失败也至少保证本次会话显示正确
        markVisibleItemMissing(identity)

        val context = applicationContext
        if (context == null) {
            // 进程上下文还没就绪：本次没法落盘，移出 pending 让后续 404 再试
            pendingSourceMissingMarks.remove(markKey)
            DebugLogger.w(TAG, "源已不存在标记暂无法落盘（无 Context）: ${key.filename}")
            return
        }

        scope.launch(Dispatchers.IO) {
            val ok = GalleryMetadataCache.markSourceMissing(context, serverId, setOf(identity))
            if (ok) {
                DebugLogger.i(TAG, "源已不存在（404）已写入索引: ${key.filename}")
            } else {
                // 索引没写成：本次会话的角标仍在（内存列表已标记），重启后可能再请求一次。
                // 移出 pending 集合，让后续 404 还能重试落盘。
                pendingSourceMissingMarks.remove(markKey)
                DebugLogger.e(TAG, "源已不存在标记未能落盘: ${key.filename}")
            }
        }
    }

    /** 把当前列表里的同一条目置为 sourceMissing（CAS 循环，避免并发刷新时互相覆盖）。 */
    private fun markVisibleItemMissing(identity: String) {
        while (true) {
            val current = _galleryItems.value
            val needsUpdate = current.any {
                GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename) == identity &&
                    !it.sourceMissing
            }
            if (!needsUpdate) return
            val updated = current.map {
                if (GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename) == identity &&
                    !it.sourceMissing
                ) {
                    it.copy(sourceMissing = true)
                } else {
                    it
                }
            }
            if (_galleryItems.compareAndSet(current, updated)) return
        }
    }

    /**
     * 用户显式重试「源已不存在」条目（查看器失败占位的重试按钮）。
     *
     * 清掉内存与索引里的标记后，下一次加载会重新向服务器请求一次：
     * 文件被放回去 → 恢复正常；仍然 404 → 重新标记（不会丢失第三态）。
     */
    fun clearSourceMissing(promptId: String, filename: String) {
        val identity = GalleryMetadataCache.itemIdentityKey(promptId, filename)
        val serverId = ConnectionManager.currentServerId ?: return
        pendingSourceMissingMarks.remove("$serverId\u0000$identity")

        while (true) {
            val current = _galleryItems.value
            val needsUpdate = current.any {
                GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename) == identity &&
                    it.sourceMissing
            }
            if (!needsUpdate) break
            val updated = current.map {
                if (GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename) == identity) {
                    it.copy(sourceMissing = false)
                } else {
                    it
                }
            }
            if (_galleryItems.compareAndSet(current, updated)) break
        }

        val context = applicationContext ?: return
        scope.launch(Dispatchers.IO) {
            GalleryMetadataCache.clearSourceMissing(context, serverId, setOf(identity))
        }
    }

    // Gallery data state
    private val _galleryItems = MutableStateFlow<List<GalleryItem>>(emptyList())
    val galleryItems: StateFlow<List<GalleryItem>> = _galleryItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Manual refresh triggered by user (pull-to-refresh) - shows indicator
    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    // Any refresh in progress (manual or background)
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastRefreshTime = MutableStateFlow(0L)
    val lastRefreshTime: StateFlow<Long> = _lastRefreshTime.asStateFlow()

    // Periodic refresh job
    private var periodicRefreshJob: Job? = null

    // Track if initial load has been done
    private var hasLoadedOnce = false

    // Track items being deleted to filter them from refresh results
    private val pendingDeletions = mutableSetOf<String>()

    companion object {
        private const val TAG = "GalleryRepo"

        @Volatile
        private var instance: GalleryRepository? = null

        private const val PERIODIC_REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        fun getInstance(): GalleryRepository {
            return instance ?: synchronized(this) {
                instance ?: GalleryRepository().also { instance = it }
            }
        }
    }

    /**
     * Start background preloading of gallery data.
     * Called after WebSocket connection is established, or when entering offline mode.
     */
    fun startBackgroundPreload() {
        val context = applicationContext
        // 离线态有两个来源：用户「强制离线」（持久化设置）与网络断开后的自动切换。
        // 只认设置项会让自动切过来的离线读不到缓存（画廊空白），所以两者都算。
        val isOffline = ConnectionManager.isOffline ||
            (context != null && AppSettings.isOfflineMode(context))

        // In online mode, require a client
        if (!isOffline && comfyUIClient == null) {
            return
        }

        if (_isLoading.value || _isRefreshing.value) {
            return
        }

        scope.launch {
            // Small delay to let UI settle after connection
            delay(500)
            loadGalleryInternal(isRefresh = false)
        }

        // Start periodic refresh only in online mode
        if (!isOffline) {
            startPeriodicRefresh()
        }
    }

    /**
     * Start periodic background refresh
     */
    private fun startPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = scope.launch {
            while (true) {
                delay(PERIODIC_REFRESH_INTERVAL_MS)
                if (comfyUIClient != null && !_isLoading.value && !_isRefreshing.value) {
                    loadGalleryInternal(isRefresh = true)
                }
            }
        }
    }

    /**
     * Stop periodic refresh (call when disconnecting)
     */
    fun stopPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    /**
     * Manual refresh triggered by user (pull-to-refresh).
     * Clears the thumbnail cache and fetches fresh data from server.
     *
     * @param onComplete Callback with success status (true if refresh succeeded)
     */
    fun manualRefresh(onComplete: (Boolean) -> Unit = {}) {
        if (_isLoading.value || _isRefreshing.value) {
            onComplete(false)
            return
        }

        scope.launch {
            _isManualRefreshing.value = true

            // Clear thumbnail cache to force re-fetch
            MediaCache.clearForRefresh()

            val success = loadGalleryInternal(isRefresh = true)

            _isManualRefreshing.value = false
            onComplete(success)
        }
    }

    /**
     * Background refresh (silent, no indicator).
     * Called after generation completes, periodically, or when returning from other screens.
     */
    fun refresh() {
        if (_isLoading.value || _isRefreshing.value) {
            DebugLogger.d(TAG, "Refresh skipped - already in progress (loading=${_isLoading.value}, refreshing=${_isRefreshing.value})")
            return
        }

        if (comfyUIClient == null) {
            DebugLogger.w(TAG, "Refresh skipped - no client available")
            return
        }

        DebugLogger.d(TAG, "Starting background refresh")
        scope.launch {
            loadGalleryInternal(isRefresh = true)
        }
    }

    /**
     * Load gallery data in background.
     * @return true if load succeeded, false otherwise
     */
    private suspend fun loadGalleryInternal(isRefresh: Boolean): Boolean {
        val context = applicationContext
        val serverId = ConnectionManager.currentServerId

        // Check if in offline mode - load from cache instead
        // （含自动切换过来的离线，此时 AppSettings 里的「强制离线」标志并未打开）
        if (ConnectionManager.isOffline || (context != null && AppSettings.isOfflineMode(context))) {
            return loadFromOfflineCache()
        }

        val client = comfyUIClient ?: run {
            return false
        }

        if (isRefresh) {
            _isRefreshing.value = true
        } else {
            _isLoading.value = true
        }

        try {
            val historyJson = withContext(Dispatchers.IO) {
                // P0：延续只能 resume 一次。旧实现用 suspendCoroutine + resumeWith，
                // OkHttp 回调二次触发（onResponse 里解析异常分支会再兜一次 callback(null)）
                // 就会抛 IllegalStateException: Already resumed —— 而且抛在 OkHttp 回调线程，
                // 外层 try/catch 根本捕不到，直接进程崩溃。现在两道防护：
                // ① 延续 isActive 判定；② client.fetchAllHistory 内部只投递一次。
                awaitCallbackOnce<JSONObject?> { deliver ->
                    client.fetchAllHistory { history -> deliver(history) }
                }
            }

            if (historyJson == null) {
                _isLoading.value = false
                _isRefreshing.value = false
                return false
            }

            // Get pending deletions snapshot
            val deletionsSnapshot: Set<String>
            synchronized(pendingDeletions) {
                deletionsSnapshot = pendingDeletions.toSet()
            }

            val serverItems = parseHistoryToGalleryItems(historyJson)

            // 本地索引是图库的底座：把服务器这次返回的**并进**索引，用合并结果当列表。
            //
            // 两条都不能少：
            // - 不能只用服务器返回的 —— ComfyUI 的 /history 是内存态，重启即清空，
            //   而图片文件仍在 output 目录里。"图库突然一张都没有、只剩新生成的图"
            //   就是这个原因（不是图片丢了，是索引丢了）。
            // - 不能把空的服务器结果写回索引 —— 合并语义保证索引只增不减，
            //   否则一次服务器重启就会把之前攒下的记录全冲掉，连离线退路一起断。
            //
            // 已在手机上删掉的条目（持久墓碑 + 本次待删）在最后统一滤掉。
            //
            // null 的语义：索引读不出来（不存在/解析失败）且服务器这次也没有条目。
            // 这时**仍然要走磁盘缓存恢复**（R1：图片文件还在本机，这是"图库加载不出
            // 任何图片"的最后一道补救）；只有"索引读不出 + 磁盘也一条都恢复不出来"时
            // 才保留上一次的列表 —— 绝不能把 null 当成"图库空了"清空界面。
            val visible: List<GalleryItem>? = if (context != null && serverId != null) {
                withContext(Dispatchers.IO) {
                    val tombstones = GalleryMetadataCache.loadTombstones(context, serverId) + deletionsSnapshot
                    val indexMerged = GalleryMetadataCache.mergeMetadata(context, serverId, serverItems)
                    val recovered = buildVisibleItems(
                        indexItems = indexMerged,
                        cachedKeys = MediaCache.listCachedKeys(context, ConnectionManager.currentServerId),
                        tombstones = tombstones
                    )
                    if (indexMerged == null && recovered.isEmpty()) {
                        DebugLogger.w(
                            TAG,
                            "索引读取失败且磁盘无缓存条目：保留上一次列表（${_galleryItems.value.size} 条），不清空"
                        )
                        null
                    } else {
                        if (indexMerged == null) {
                            DebugLogger.w(
                                TAG,
                                "索引读取失败，已用磁盘缓存恢复 ${recovered.size} 条（服务器本次 ${serverItems.size} 条）"
                            )
                        }
                        recovered
                    }
                }
            } else {
                serverItems.filterNot { GalleryMetadataCache.isDeleted(it, deletionsSnapshot) }
            }

            if (visible == null) {
                return false
            }

            // R2-N1 最终护栏：图库 LazyGrid 的 key 是 (promptId, filename)，
            // 列表里只要出现重复二元组就会抛 IllegalArgumentException。
            // 服务器解析、索引合并、磁盘恢复任一路带来重复都在这里收敛。
            // 口径与 GalleryMetadataCache.itemIdentityKey 完全一致（NUL 分隔，不是下划线）。
            val distinctVisible = visible.distinctBy {
                GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename)
            }

            val previousCount = _galleryItems.value.size
            _galleryItems.value = distinctVisible
            _lastRefreshTime.value = System.currentTimeMillis()
            hasLoadedOnce = true
            DebugLogger.d(
                TAG,
                "Gallery refresh complete: ${distinctVisible.size} items shown " +
                    "(was $previousCount, server reported ${serverItems.size})"
            )

            return true
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Gallery refresh failed: ${e.message}")
            return false
        } finally {
            _isLoading.value = false
            _isRefreshing.value = false
        }
    }

    /**
     * Load gallery data from offline cache.
     * @return true if cache was loaded successfully, false otherwise
     */
    private fun loadFromOfflineCache(): Boolean {
        val context = applicationContext ?: return false
        val serverId = ConnectionManager.currentServerId ?: return false

        // 带兜底：当前服务器没有索引时用最近更新的那份。换网络（热点、换网段）
        // 或改建服务器条目都会换 serverId，严格隔离会让攒下的图库凭空消失。
        val cachedItems = GalleryMetadataCache.loadMetadataWithFallback(context, serverId)
        if (cachedItems != null) {
            val tombstones = GalleryMetadataCache.loadTombstones(context, serverId)
            val visible = recoverFromDiskCache(context, cachedItems)
                .filterNot { GalleryMetadataCache.isDeleted(it, tombstones) }
                // R2-N1 最终护栏：离线路径同样按 (promptId, filename) 收敛，
                // 与图库 LazyGrid 的 key 口径完全一致（统一走 itemIdentityKey）。
                .distinctBy {
                    GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename)
                }
            _galleryItems.value = visible
            _lastRefreshTime.value = GalleryMetadataCache.getCacheTimestamp(context, serverId)
            hasLoadedOnce = true
            DebugLogger.d(TAG, "Gallery loaded from local index: ${visible.size} items (offline)")
            return true
        }

        DebugLogger.w(TAG, "No local gallery index available")
        return false
    }

    /**
     * 从磁盘缓存的文件名补回索引里缺失的条目（补救措施，不是常规路径）。
     *
     * 为什么需要它：图库索引曾经被"服务器历史为空"的那次刷新覆盖成空，
     * 而磁盘缓存里的图片/视频文件都还在。文件名是 `{promptId}_{filename}.ext`，
     * 反推出的条目拿当前服务器地址重新下载就能正常看原图 —— 已经丢掉的图库因此能补回大半。
     *
     * 反推不出的是 subfolder（按根目录）与 type（按 output）：绝大多数输出本来就在根目录；
     * 用户在服务器上用了子目录时，这类条目可能下载失败。
     * 所以它只做**补充**（不覆盖已有条目的信息），也**不写回索引文件**（不固化不可靠的反推结果）。
     */
    private fun recoverFromDiskCache(context: Context, known: List<GalleryItem>): List<GalleryItem> =
        recoverFromCachedKeys(
            known = known,
            cachedKeys = MediaCache.listCachedKeys(context, ConnectionManager.currentServerId)
        )

    /**
     * Check if gallery data is available (has been loaded at least once)
     */
    fun hasData(): Boolean = hasLoadedOnce && _galleryItems.value.isNotEmpty()

    /**
     * 从手机图库删除一个条目（G4 后语义 = **移入回收站**，阶段一，可恢复）。
     *
     * 历史沿革：改回收站之前这里是"只影响手机端的直接删除"（服务器不知情 → 必须落
     * 持久墓碑防复活）。回收站接管后墓碑语义原样保留（恢复 = 删墓碑），
     * 服务器文件的处理挪到了阶段二（[purgeTrashEntries]）。
     */
    suspend fun deleteItem(item: GalleryItem): Boolean =
        // L6：回收站/墓碑的落盘（原子写 + fsync + 写后读回）不能压在 Main 线程；
        // M2 的异常边界在 moveToTrash 内部（图库与查看器共用）。
        withContext(Dispatchers.IO) { moveToTrash(listOf(item)) }.isNotEmpty()

    /**
     * 从手机图库批量删除**选中的条目**（G4 后语义 = 逐条移入回收站）。
     *
     * 返回实际进入回收站的条目数。参数是条目而不是 promptId 集合（P1-19）：
     * 批量出图的 4 张共用同一个 promptId，按 promptId 删会"删一张、掉四张"。
     */
    suspend fun deleteItems(items: List<GalleryItem>): Int {
        if (items.isEmpty()) return 0
        return withContext(Dispatchers.IO) { moveToTrash(items) }.size
    }

    /**
     * 查看器删除入口（G4 后语义 = **移入回收站**，阶段一）。
     *
     * 保留旧函数名是因为媒体查看器手上是 `MediaViewerItem` 而不是 `GalleryItem`。
     * 粒度仍是**条目**（promptId + filename，P1-19）：同批出图共用 promptId，
     * 只按 promptId 会把整批一起删掉。
     */
    fun removeItemLocally(promptId: String, filename: String): Boolean {
        val target = _galleryItems.value.firstOrNull {
            it.promptId == promptId && it.filename == filename
        } ?: return false
        return moveToTrash(listOf(target)).isNotEmpty()
    }

    // ============================================================
    // G4（任务书 11.4）：回收站与两阶段删除
    // ============================================================
    //
    // 阶段一 = App 内删除：条目移入回收站（[moveToTrash]），列表消失但**本地媒体
    //         缓存保留**（可恢复、回收站里还能看缩略图），可逐条恢复回原位置。
    // 阶段二 = 回收站内删除（[purgeTrashEntries]）：联动删服务器文件
    //         （只走 DELETE /browser/files 三元组定位）+ 本地残留清理。
    //         服务器删除失败 → 条目**留在**回收站标记待重试；探测不到能力 →
    //         UNKNOWN 保持可再探。两阶段状态在 [GalleryTrashEntry] 上可观测。
    //
    // 回收站**没有**任何自动清空路径（硬约束 4）：条目只在用户显式恢复/删除/清空时消失。

    private val _trashEntries = MutableStateFlow<List<GalleryTrashEntry>>(emptyList())
    val trashEntries: StateFlow<List<GalleryTrashEntry>> = _trashEntries.asStateFlow()

    private var trashLoaded = false

    /** 首次访问时从磁盘加载回收站（幂等；purge/restore 内部会自触发，UI 打开回收站前也应调用一次）。 */
    fun ensureTrashLoaded() {
        if (trashLoaded) return
        val context = applicationContext ?: return
        _trashEntries.value = GalleryTrashStorage.loadAll(context)
        trashLoaded = true
    }

    /**
     * 阶段一：把条目移入回收站。
     *
     * 与旧的"直接删除"相比有两个刻意差别：
     * 1. **不 evict 媒体缓存** —— 本地资源保留才谈得上恢复；
     * 2. 返回回收站身份键 —— 查看器的撤销条 / 单测按它定位刚进站的那条。
     *
     * 幂等：同一 (serverId, promptId, filename) 已在回收站时不再重复进站，
     * 但仍保证列表与墓碑里有它（删两次不会出现两条记录）。
     */
    fun moveToTrash(items: List<GalleryItem>): List<String> {
        if (items.isEmpty()) return emptyList()
        return try {
            moveToTrashInternal(items)
        } catch (e: Exception) {
            // M2：删除入口的最终边界（图库与查看器共用本方法）。数据层内部已逐处
            // try/catch，这里再兜一层，任何漏网异常都不会把调用方协程炸成进程崩溃；
            // 记完整栈（可从"调试日志"导出），返回空 = 未进站，调用方必须报失败。
            DebugLogger.e(TAG, "移入回收站异常（已拦截，未崩溃）: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    private fun moveToTrashInternal(items: List<GalleryItem>): List<String> {
        ensureTrashLoaded()
        val serverId = ConnectionManager.currentServerId.orEmpty()
        val now = System.currentTimeMillis()
        val fresh = items.map { item ->
            GalleryTrashEntry(
                id = UUID.randomUUID().toString(),
                serverId = serverId,
                item = item,
                deletedAt = now
            )
        }
        val previousEntries = _trashEntries.value
        val merged = dedupeTrash(previousEntries, fresh) { it.identityKey }
        // R2：先落盘再动内存。回收站写失败时**取消这次删除**——否则墓碑（下发在
        // removeFromGallery）会让图片从图库消失，而回收站里没有记录，用户永远恢复不了。
        // context 为空 = 无法落盘，按失败处理（不能把「没写」当成「写成功」）。
        val persisted = applicationContext?.let { GalleryTrashStorage.saveAll(it, merged) } ?: false
        if (!persisted) {
            _trashEntries.value = previousEntries
            DebugLogger.e(TAG, "回收站落盘失败，已取消本次移入回收站（列表/墓碑保持不动）")
            return emptyList()
        }
        _trashEntries.value = merged
        // 列表、墓碑立刻生效；evictMedia = false 是"可恢复"的前提
        removeFromGallery(
            keys = items.mapTo(mutableSetOf()) {
                GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename)
            },
            items = items,
            evictMedia = false
        )
        val identities = items.mapTo(mutableSetOf()) {
            "$serverId\u0000${it.promptId}\u0000${it.filename}"
        }
        return merged.filter { it.identityKey in identities }.map { it.identityKey }
    }

    /**
     * 从回收站恢复一个条目（阶段一回退）。
     *
     * 三处一起回滚，缺一不可：墓碑（显示层过滤）、会话墓碑（刷新窗口期拦截）、
     * 列表内存（界面立刻可见）。索引记录在阶段一从未删过，所以恢复后
     * **列表顺序与元数据天然一致**（原 index 随条目一起带回）。
     */
    fun restoreFromTrash(identityKey: String): Boolean {
        ensureTrashLoaded()
        val entry = _trashEntries.value.firstOrNull { it.identityKey == identityKey } ?: return false
        val remaining = _trashEntries.value.filterNot { it.identityKey == identityKey }
        val key = GalleryMetadataCache.itemIdentityKey(entry.item.promptId, entry.item.filename)

        // R2：顺序改为"先删墓碑（恢复意图落盘）→ 再落盘回收站剩余条目 → 最后动内存"。
        // 墓碑删不掉就整体取消：条目还在回收站，用户可重试，图库保持原样。
        val context = applicationContext
        val tombstonesCleared = context?.let {
            GalleryMetadataCache.removeTombstones(it, entry.serverId, setOf(key))
        } ?: false
        if (!tombstonesCleared) {
            DebugLogger.e(TAG, "墓碑清理失败，取消本次恢复（条目仍在回收站）: ${entry.item.filename}")
            return false
        }
        synchronized(pendingDeletions) { pendingDeletions.remove(key) }

        val persisted = context?.let { GalleryTrashStorage.saveAll(it, remaining) } ?: false
        if (persisted) {
            _trashEntries.value = remaining
        } else {
            // 墓碑已清 = 图库侧的恢复已经生效；回收站记录没能删掉，重启后这条会重复出现，
            // 用户可再清一次。内存保持与磁盘一致（条目仍显示在回收站），不静默。
            DebugLogger.e(
                TAG,
                "回收站记录未能落盘（恢复已生效，该条目可能在回收站重复出现）: ${entry.item.filename}"
            )
        }

        val items = _galleryItems.value.toMutableList()
        if (items.none { it.promptId == entry.item.promptId && it.filename == entry.item.filename }) {
            // 按保存的 index 找回原位置：列表里第一个"排得比它晚"的条目之前。
            // 期间进了新图的话位置会顺移，但它仍在同一时间段的邻居之间 —— 这就是
            // "恢复至原位置（列表顺序与元数据一致）"在活列表上的可实现语义。
            val insertAt = items.indexOfFirst { it.index > entry.item.index }
                .let { if (it >= 0) it else items.size }
            items.add(insertAt, entry.item)
            _galleryItems.value = items
        }
        DebugLogger.i(TAG, "回收站恢复: ${entry.item.filename}")
        return true
    }

    /** 一次"彻底删除"批处理的分路由结果（UI 据此说清楚每一类条目发生了什么）。 */
    data class TrashPurgeSummary(
        /** 服务器确认删除（或已不存在）且本机残留已清理 */
        val removedWithServer: Int,
        /** 服务器不支持删除接口：只清了本机记录（服务器文件保留，必须提示） */
        val removedLocalOnly: Int,
        /** 服务器删除失败/探测不通，已标记待重试、留在回收站 */
        val queuedForRetry: Int,
        /** 属于其它服务器的条目：未动，连上那台后再处理 */
        val keptForOtherServer: Int
    ) {
        val touched: Int get() = removedWithServer + removedLocalOnly
    }

    /**
     * 阶段二：回收站内"彻底删除"（服务器联动 + 本地残留清理）。
     *
     * 硬约束的代码化：
     * - 服务器删除**只**经 [ComfyUIClient.deleteBrowserFile]，定位符先过
     *   [toBrowserFolderType] 白名单与 [isLocatorSafe] 守卫，拼不出也发不出路径；
     * - 能力必须先探测（[capabilityOf]），UNKNOWN 不发删除请求、下次可再探；
     * - 删除请求失败/超时 → [ServerDeleteAttempt.RETRYABLE] → 条目保留 + 标记
     *   pendingRetry，**本地一个文件都不清**，绝不谎报成功；
     * - 405（能力判断过期，插件被卸载等）→ 改缓存为 UNSUPPORTED，走本机清理并说明。
     *
     * 首次启用的清单确认由 UI 层在进入本函数之前完成（确认状态经 AppSettings 持久化）。
     */
    fun purgeTrashEntries(identities: List<String>, onResult: (TrashPurgeSummary) -> Unit) {
        ensureTrashLoaded()
        scope.launch {
            var removedWithServer = 0
            var removedLocalOnly = 0
            var queuedForRetry = 0
            var keptForOtherServer = 0
            var unsupportedAnnounced = false

            val context = applicationContext
            val currentServerId = ConnectionManager.currentServerId.orEmpty()
            val client = comfyUIClient

            // 待处理条目快照：执行期间的状态变更都基于这份列表
            val targets = _trashEntries.value.filter { it.identityKey in identities.toSet() }

            for (entry in targets) {
                val tombstoneKey =
                    GalleryMetadataCache.itemIdentityKey(entry.item.promptId, entry.item.filename)
                val onCurrentServer = entry.serverId == currentServerId
                // 路由决策全走纯函数 planPurgeRoute（能力 × 归属 × 在线三因子矩阵，
                // 单测 GalleryTrashLogicTest 钉住），这里只执行路由结果 ——
                // 分支判定不留第二份，杜绝"改了一处漏一处"的谎报空间
                val capability = if (onCurrentServer && client != null) {
                    capabilityOf(client, entry.serverId)
                } else {
                    ServerDeleteCapability.UNKNOWN
                }
                when (
                    planPurgeRoute(
                        capability = capability,
                        entryOnCurrentServer = onCurrentServer,
                        clientAvailable = client != null
                    )
                ) {
                    PurgeRoute.KEEP_OTHER_SERVER -> {
                        // 别家服务器的条目：原样保留，不动这台服务器的一切东西
                        keptForOtherServer++
                    }
                    PurgeRoute.KEEP_FOR_RETRY -> {
                        // 离线 / 探测不通：说不清服务器侧现状 → 标待重试、条目保留，
                        // 本地一个字节都不清（硬约束 3）
                        queuedForRetry++
                        markEntry(
                            entry.identityKey, true,
                            if (client == null) "离线，未发起服务器删除"
                            else "无法连接服务器，未能确认删除能力"
                        )
                    }
                    PurgeRoute.LOCAL_ONLY -> {
                        // 服务器无删除接口：降级只清本机记录并明确提示（不报错、不假装删了）
                        removedLocalOnly++
                        if (!unsupportedAnnounced) {
                            unsupportedAnnounced = true
                            DebugLogger.w(TAG, "服务器 ${entry.serverId} 无 browser 删除插件，回收站按本机清理")
                        }
                        finishLocalCleanup(context, currentServerId, entry, tombstoneKey)
                    }
                    PurgeRoute.SERVER_DELETE -> {
                        val activeClient = client
                        if (activeClient == null) {
                            // 防御位：纯函数保证走到这里时 client 非空，理论不可达
                            queuedForRetry++
                            markEntry(entry.identityKey, true, "客户端不可用")
                        } else {
                        val folderType = toBrowserFolderType(entry.item.type)
                        if (folderType == null || !isLocatorSafe(entry.item.filename, entry.item.subfolder)) {
                            // 定位符不合法（文件名里带路径分隔等异常数据）：宁可不删。
                            // 标待重试并留证据，绝不把可疑字符串送进服务端的路径拼接。
                            queuedForRetry++
                            markEntry(entry.identityKey, true, "条目定位符异常，未发起服务器删除")
                            continue
                        }
                        val (code, networkFailed) = activeClient.deleteBrowserFileAwait(
                            entry.item.filename, entry.item.subfolder, folderType
                        )
                            when (classifyDeleteAttempt(code, networkFailed)) {
                                ServerDeleteAttempt.REMOVED, ServerDeleteAttempt.ALREADY_GONE -> {
                                    // 2xx = 服务器确认删除；404 = 文件本就不在了（幂等收敛，
                                    // 能力已由探测确认在位，这个 404 来自插件的"未找到文件"）
                                    removedWithServer++
                                    finishLocalCleanup(context, currentServerId, entry, tombstoneKey)
                                }
                                ServerDeleteAttempt.UNSUPPORTED_FALLBACK -> {
                                    removedLocalOnly++
                                    capabilityCache[entry.serverId] = ServerDeleteCapability.UNSUPPORTED
                                    finishLocalCleanup(context, currentServerId, entry, tombstoneKey)
                                }
                                ServerDeleteAttempt.RETRYABLE -> {
                                    queuedForRetry++
                                    markEntry(
                                        entry.identityKey, true,
                                        if (networkFailed) "网络失败" else "服务器返回 HTTP $code"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            onResult(
                TrashPurgeSummary(
                    removedWithServer = removedWithServer,
                    removedLocalOnly = removedLocalOnly,
                    queuedForRetry = queuedForRetry,
                    keptForOtherServer = keptForOtherServer
                )
            )
        }
    }

    /** 阶段二成功 / 降级路径的"本地残留清理"：媒体缓存 + 索引记录。墓碑**保留**（防历史复活）。 */
    private fun finishLocalCleanup(
        context: Context?,
        serverId: String,
        entry: GalleryTrashEntry,
        tombstoneKey: String
    ) {
        // serverId 显式给键：条目属于哪台服务器，就清哪台服务器的缓存文件
        MediaCache.evict(MediaCacheKey.of(entry.item.promptId, entry.item.filename, entry.item.type, serverId))
        if (context != null) {
            GalleryMetadataCache.removeFromIndex(context, serverId, setOf(tombstoneKey))
        }
        _trashEntries.value = _trashEntries.value.filterNot { it.identityKey == entry.identityKey }
        val persisted = applicationContext?.let { GalleryTrashStorage.saveAll(it, _trashEntries.value) } ?: false
        if (!persisted) {
            // 服务器文件已删、回收站记录没删掉：下次启动这条会重新出现，可再清一次
            // （服务器侧是幂等 404）。这里必须明确记失败，不能让人以为已经清理干净。
            DebugLogger.e(TAG, "回收站清理结果未能落盘（条目可能在下次启动重现）: ${entry.item.filename}")
        }
    }

    /** 更新单条回收站记录的重试标记并落盘（条目**留在**回收站，列表不减）。 */
    private fun markEntry(identityKey: String, pendingRetry: Boolean, error: String?) {
        _trashEntries.value = _trashEntries.value.map {
            if (it.identityKey == identityKey) it.copy(pendingRetry = pendingRetry, lastError = error) else it
        }
        val persisted = applicationContext?.let { GalleryTrashStorage.saveAll(it, _trashEntries.value) } ?: false
        if (!persisted) {
            DebugLogger.e(TAG, "回收站待重试标记未能落盘（重启后仍会重新探测）: $identityKey")
        }
    }

    // ---- 服务器删除能力缓存（会话内，UNKNOWN 不缓存、下次必再探） ----

    private val capabilityCache = mutableMapOf<String, ServerDeleteCapability>()
    private val capabilityMutex = Mutex()

    private suspend fun capabilityOf(client: ComfyUIClient, serverId: String): ServerDeleteCapability {
        capabilityCache[serverId]?.let { return it }
        return capabilityMutex.withLock {
            capabilityCache[serverId]?.let { return it }
            val (code, networkFailed) = client.probeBrowserDeleteSupportAwait()
            classifyProbe(code, networkFailed).also {
                // UNKNOWN（网络失败 / 5xx / 认证抖动）不落缓存：保持"下次再探"的重试能力，
                // 探明 SUPPORTED / UNSUPPORTED 这两种确定结论才缓存
                if (it != ServerDeleteCapability.UNKNOWN) capabilityCache[serverId] = it
            }
        }
    }

    /** 强制重探（"服务器不支持"但用户装好插件后想刷新结论时用）。 */
    suspend fun refreshCapability(serverId: String): ServerDeleteCapability {
        val client = comfyUIClient ?: return ServerDeleteCapability.UNKNOWN
        capabilityCache.remove(serverId)
        return capabilityOf(client, serverId)
    }

    /**
     * 手机端移除的共同实现：内存列表、持久墓碑、媒体缓存三处一起处理。
     *
     * 三处缺一不可：
     * - 内存列表 → 界面立刻响应；
     * - **持久墓碑**（磁盘）→ 换网络、重启 App 之后服务器历史也不会把它带回来；
     * - **会话墓碑**（[pendingDeletions]）→ 堵住"刷新已拿到服务器响应、还没写回列表"这个
     *   窗口期：那次刷新的合并结果会绕过磁盘墓碑，得靠内存这份兜住。
     *   它只在会话内存活，进程结束由磁盘那份接管，因此不会无限增长。
     *
     * @param keys 条目身份键（[GalleryMetadataCache.itemIdentityKey]）
     * @param evictMedia 是否连带清除媒体缓存。阶段一（移入回收站）传 **false**：
     *                   本地资源保留才能恢复；阶段二确认删除后再由
     *                   [finishLocalCleanup] 显式清残留。旧的"直接删除"路径仍传 true。
     */
    private fun removeFromGallery(keys: Set<String>, items: List<GalleryItem>, evictMedia: Boolean) {
        if (keys.isEmpty()) return

        synchronized(pendingDeletions) {
            pendingDeletions.addAll(keys)
        }

        _galleryItems.value = _galleryItems.value.filterNot {
            GalleryMetadataCache.isDeleted(it, keys)
        }

        val context = applicationContext
        val serverId = ConnectionManager.currentServerId
        if (context != null && serverId != null) {
            // R2：墓碑写失败 = 本次删除没有持久化（重启后图片会重新出现）。
            // 回收站侧已先行落盘，所以用户仍能从回收站恢复/再次删除，不构成数据丢失；
            // 但必须明确记 error，不能沉默。
            val tombstonePersisted = GalleryMetadataCache.addTombstones(context, serverId, keys)
            if (!tombstonePersisted) {
                DebugLogger.e(TAG, "删除墓碑未能落盘，重启后这批图片可能重新出现: ${keys.size} 个键")
            }
        } else {
            DebugLogger.w(TAG, "Deletion tombstones not persisted (no context/serverId)")
        }

        if (evictMedia) {
            items.forEach {
                MediaCache.evict(MediaCacheKey.of(it.promptId, it.filename, it.type))
            }
        }
    }

    /**
     * Clear cached data (for logout/disconnect)
     * Note: Uses MediaCache.reset() to preserve disk cache for offline mode.
     * User can still clear disk cache manually via Settings → Clear Cache.
     */
    fun clearCache() {
        DebugLogger.i(TAG, "Clearing cache")
        _galleryItems.value = emptyList()
        _lastRefreshTime.value = 0L
        hasLoadedOnce = false
        synchronized(pendingDeletions) {
            pendingDeletions.clear()
        }
        stopPeriodicRefresh()
        // Clear media cache (preserves disk cache for offline mode)
        MediaCache.reset()
    }

    /**
     * Reset repository state (for logout/disconnect).
     * Called by ConnectionManager when disconnecting.
     */
    fun reset() {
        DebugLogger.i(TAG, "Resetting repository")
        clearCache()
    }

    /**
     * Parse history JSON to gallery items.
     * Does NOT fetch bitmaps - those are loaded lazily via MediaCache.
     */
    private fun parseHistoryToGalleryItems(historyJson: JSONObject): List<GalleryItem> {
        return GalleryHistoryParser.parse(historyJson)
    }

    // ---- G4：ComfyUIClient 回调式网络接口的挂起适配（回调语义原样透传，不改判定） ----

    /**
     * @return (HTTP 码 or null, 是否网络失败)
     *
     * L1：走 [awaitCallbackOnce]（延续 isActive 判定），与 fetchAllHistory 同款防护。
     * 能力探测/删除文件的回调若二次投递，无条件 resume 会抛 Already resumed 并炸在
     * OkHttp 线程 —— 这个适配器是那两条 client 回调的唯一入口，必须同样有闸。
     */
    private suspend fun ComfyUIClient.probeBrowserDeleteSupportAwait(): Pair<Int?, Boolean> =
        awaitCallbackOnce { deliver ->
            probeBrowserDeleteSupport { code, networkFailed -> deliver(Pair(code, networkFailed)) }
        }

    /** @return (HTTP 码 or null, 是否网络失败)，防护同 [probeBrowserDeleteSupportAwait] */
    private suspend fun ComfyUIClient.deleteBrowserFileAwait(
        filename: String,
        folderPath: String,
        folderType: String
    ): Pair<Int?, Boolean> =
        awaitCallbackOnce { deliver ->
            deleteBrowserFile(filename, folderPath, folderType) { code, networkFailed ->
                deliver(Pair(code, networkFailed))
            }
        }
}

/**
 * /history → 图库条目的纯解析（抽成 object 以便 JVM 单测直接覆盖，不依赖网络与仓库状态）。
 */
internal object GalleryHistoryParser {

    /**
     * 解析整份 /history 响应。
     *
     * 3D 识别规则（v4.3.0 新增）：ComfyUI 的 3D 节点把 glb 写在**独立字段 `3d`** 里，
     * 与预览图的 `images` 常在**同一 promptId 的不同 node**（IDOL 实测：
     * node 914 出 glb、node 915 出 share.image.png）。所以在 promptId 层先跨 node
     * 收集 `3d` 产物，再把它配给该 prompt 下的图片条目 —— 条目于是带上 3D 角标、
     * 点击直达 3D 查看器；若该 prompt 只有 glb 没有预览图，glb 自身也成为条目
     * （无缩略图，由卡片用 3D 图标兜底）。
     */
    fun parse(historyJson: JSONObject): List<GalleryItem> {
        val items = mutableListOf<GalleryItem>()
        var index = 0

        val promptIds = historyJson.keys()
        while (promptIds.hasNext()) {
            val promptId = promptIds.next()
            val promptHistory = historyJson.optJSONObject(promptId) ?: continue
            val outputs = promptHistory.optJSONObject("outputs") ?: continue

            // 第一遍：跨 node 收集该 prompt 的 3D 产物（多个时优先 .glb 文件）
            var threeD: ThreeDResultHolder.Result? = null
            var glbFallback: ThreeDResultHolder.Result? = null
            val nodeIds = outputs.keys()
            while (nodeIds.hasNext()) {
                val nodeOutput = outputs.optJSONObject(nodeIds.next()) ?: continue
                val arr = nodeOutput.optJSONArray("3d") ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val filename = o.optString("filename", "")
                    if (filename.isBlank()) continue
                    val r = ThreeDResultHolder.Result(
                        filename = filename,
                        subfolder = o.optString("subfolder", ""),
                        type = o.optString("type", "output")
                    )
                    if (filename.lowercase().endsWith(".glb") && glbFallback == null) {
                        glbFallback = r
                    }
                    if (threeD == null) threeD = r
                }
            }
            threeD = glbFallback ?: threeD

            // 第二遍：常规视频/图片条目，图片条目带上同 prompt 的 3D 产物
            var mediaCount = 0
            val nodeIds2 = outputs.keys()
            while (nodeIds2.hasNext()) {
                val nodeOutput = outputs.optJSONObject(nodeIds2.next()) ?: continue

                // Check for videos first
                val videos = nodeOutput.optJSONArray("videos")
                    ?: nodeOutput.optJSONArray("gifs")

                if (videos != null && videos.length() > 0) {
                    for (i in 0 until videos.length()) {
                        val videoInfo = videos.optJSONObject(i) ?: continue
                        val filename = videoInfo.optString("filename", "")
                        if (filename.isEmpty()) continue

                        val subfolder = videoInfo.optString("subfolder", "")
                        val type = videoInfo.optString("type", "output")

                        items.add(GalleryItem(
                            promptId = promptId,
                            filename = filename,
                            subfolder = subfolder,
                            type = type,
                            isVideo = true,
                            index = index++
                        ))
                        mediaCount++
                    }
                }

                // Check for images
                val images = nodeOutput.optJSONArray("images")
                if (images != null && images.length() > 0) {
                    for (i in 0 until images.length()) {
                        val imageInfo = images.optJSONObject(i) ?: continue
                        val filename = imageInfo.optString("filename", "")
                        if (filename.isEmpty()) continue

                        // Skip if it's actually a video
                        if (VIDEO_EXTENSIONS.any { filename.lowercase().endsWith(it) }) {
                            val subfolder = imageInfo.optString("subfolder", "")
                            val type = imageInfo.optString("type", "output")

                            items.add(GalleryItem(
                                promptId = promptId,
                                filename = filename,
                                subfolder = subfolder,
                                type = type,
                                isVideo = true,
                                index = index++
                            ))
                            mediaCount++
                            continue
                        }

                        val subfolder = imageInfo.optString("subfolder", "")
                        val type = imageInfo.optString("type", "output")

                        items.add(GalleryItem(
                            promptId = promptId,
                            filename = filename,
                            subfolder = subfolder,
                            type = type,
                            isVideo = false,
                            index = index++,
                            threeD = threeD
                        ))
                        mediaCount++
                    }
                }
            }

            // 只有 3D 产物、没有预览图的产出：glb 自身作为条目（卡片用 3D 图标兜底）
            if (mediaCount == 0 && threeD != null) {
                items.add(GalleryItem(
                    promptId = promptId,
                    filename = threeD.filename,
                    subfolder = threeD.subfolder,
                    type = threeD.type,
                    isVideo = false,
                    index = index++,
                    threeD = threeD
                ))
            }
        }

        // Sort by index descending (newest first)
        return items.sortedByDescending { it.index }
    }
}
