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
import sh.hnet.comfychair.lifecycle.AppLifecycleMonitor
import sh.hnet.comfychair.lifecycle.BackgroundDegradationPolicy
import sh.hnet.comfychair.model.GalleryItem
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryMetadataCache
import sh.hnet.comfychair.storage.GalleryTrashEntry
import sh.hnet.comfychair.storage.GalleryTrashStorage
import sh.hnet.comfychair.util.DebugLogger
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

/**
 * 「加载更早」第 `tapsDone + 1` 次要向服务端请求的 history 窗口大小。
 *
 * ## 为什么用"放宽 max_items 窗口"而不是任务书设想的 offset 步进（实测纠偏）
 *
 * 读码证据（F:\ComfyUI\execution.py:1423-1439，server.py:1138-1149 原样透传）：
 * `self.history` 是**按完成顺序追加**的 dict（task_done 里 `self.history[prompt_id] = …`，
 * 超 MAXIMUM_HISTORY_SIZE=10000 从**头部裁掉最旧的**），`get_history` 的循环
 * `for k in self.history: if i >= offset …` 于是**从最旧一端**数 offset 条再往后取；
 * 只有省略 offset（服务端置 -1）时才换算 `offset = len - max_items` 取**最新端**。
 * 响应是裸 dict、没有 total —— 客户端拿不到 len，就换算不出"从最新端再往更早一档"
 * 的 offset；硬用 `offset = 页数×100` 步进会拉到错误切片并**误判到底**：
 * 反例 L=150：首屏 = 位置 50..149（100 条，满页），offset=100 返回 100..149 ——
 * 全是已知条目且不足 100 条 → 按"页不满 = 到底"会停住，最旧的 0..49 永远拉不到。
 *
 * 放宽窗口则每一步都成立：响应永远是**最新 W 条**，索引合并（GalleryMetadataCache
 * .olderItemsNotKnown 同口径的条目去重）天然吃掉重叠部分，每档净增 ~100 条更早历史；
 * "返回条数 < 请求窗口"就是可靠的整段到底信号（见 [olderHistoryExhausted]）。
 * 重复下载的重叠部分受 [maxTaps] 上限约束（默认 5 档 → 最大窗口 600 条 history，
 * 与"服务端 history 是内存态、重启即清"的特性匹配：值得翻的更早段本就不多）。
 *
 * @param tapsDone 已完成的"加载更早"档数（0 = 只拉过首屏）
 * @param pageSize 每档放宽的条数 = 首屏单页常量（BackgroundDegradationPolicy.HISTORY_MAX_ITEMS，
 *                 其"单页上限 100"的语义不在本任务改动）
 * @param maxTaps 最大档数（防无限翻；首屏已含第 1 档窗口，故第 t 档窗口 = pageSize×(t+2)）
 * @return 下一次请求的窗口条数；null = 已达上限，不再提供"加载更早"
 */
internal fun olderWindowSize(tapsDone: Int, pageSize: Int, maxTaps: Int): Int? {
    if (tapsDone < 0 || pageSize <= 0 || maxTaps <= 0 || tapsDone >= maxTaps) return null
    return pageSize * (tapsDone + 2)
}

/**
 * 终止判定：服务端本页返回的 history 条数 **< 请求窗口** → 内存里整段历史都已在手，
 * 没有更早的了。（窗口取的是"最新 W 条"，返回不满 W 说明总条数就这么多。）
 */
internal fun olderHistoryExhausted(pageEntryCount: Int, requestedWindow: Int): Boolean =
    pageEntryCount < requestedWindow

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
            // 索引里没有该条目（仅磁盘缓存恢复、从未写进索引的条目）时补记一条最小记录：
            // 旧路径 markSourceMissing 会直接放弃，标记只在内存列表里、重启即丢（R3-2）。
            // 字段口径与 recoverFromCachedKeys 的缓存恢复条目一致（subfolder 空、按扩展名判视频）
            val recoveredRecord = GalleryItem(
                promptId = key.promptId,
                filename = key.filename,
                subfolder = "",
                type = key.type,
                isVideo = VIDEO_EXTENSIONS.any { key.filename.lowercase().endsWith(it) },
                index = 0
            )
            val ok = GalleryMetadataCache.markSourceMissingOrInsert(context, serverId, recoveredRecord)
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

    // ============================================================
    // 「加载更早」分页状态（会话内；clearCache 复位）
    // ============================================================
    //
    // 与首屏/刷新链路完全解耦：这里只多发一次"窗口放宽一档"的 /history，
    // 结果照旧走索引合并 + 同一套可见列表合成（buildVisibleFromIndex），
    // 下拉刷新（manualRefresh）与周期刷新（startPeriodicRefresh）的代码路径不动。

    /** 已完成的"加载更早"档数（窗口大小由它推，见 [olderWindowSize]） */
    private var olderTapsDone = 0

    /** 服务端历史已确认拉尽（页不满窗口 / 净新增为 0），或明确不满足分页前提 */
    private var olderExhausted = false

    /** 在途闸：AtomicBoolean CAS，防按钮双击/回填与手动翻页并发出两笔同一窗口的请求 */
    private val olderInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _canLoadOlder = MutableStateFlow(false)
    val canLoadOlder: StateFlow<Boolean> = _canLoadOlder.asStateFlow()

    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()

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

        /**
         * 「加载更早」每档放宽的 history 条数：与首屏单页常量同值。
         * 注意**不修改** BackgroundDegradationPolicy.HISTORY_MAX_ITEMS 本身
         * （BackgroundDegradationPolicyTest:171 钉着它 = 首屏单页上限 100 的语义）。
         */
        internal const val OLDER_WINDOW_STEP_ITEMS =
            BackgroundDegradationPolicy.HISTORY_MAX_ITEMS

        /** 「加载更早」最大档数（5 档 → 最大窗口 600 条 history，防无限翻） */
        internal const val MAX_OLDER_WINDOW_TAPS = 5

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
                // v4.18.x 后台省流量（首要元凶修复）：后台完全停掉周期刷新 ——
                // 旧行为是后台每 5 分钟全量拉一次 /history（每条带完整 prompt JSON，
                // 估算 150~860 MB/天），正是安全管家报「后台耗流量」的主犯。
                // 现在只有前台才发请求；回前台 / 收到任务完成事件的拉取由调用方另起。
                if (!BackgroundDegradationPolicy.shouldPeriodicHistoryRefresh(
                        AppLifecycleMonitor.visibility.value
                    )
                ) {
                    continue
                }
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
                    // v4.18.x：带 max_items 上限（服务端支持 ?max_items=N）。全量响应每条
                    // 含完整 prompt JSON，历史攒多后一次可达数十 MB；上限外的旧条目由
                    // 本地索引合并兜底（mergeMetadata 只增不减），不会从图库消失。
                    client.fetchAllHistory(maxItems = BackgroundDegradationPolicy.HISTORY_MAX_ITEMS) { history ->
                        deliver(history)
                    }
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
            // 两条都不能少（口径详见 buildVisibleFromIndex 的 KDoc）：
            // - 不能只用服务器返回的 —— ComfyUI 的 /history 是内存态，重启即清空，
            //   而图片文件仍在 output 目录里。"图库突然一张都没有、只剩新生成的图"
            //   就是这个原因（不是图片丢了，是索引丢了）。
            // - 不能把空的服务器结果写回索引 —— 合并语义保证索引只增不减，
            //   否则一次服务器重启就会把之前攒下的记录全冲掉，连离线退路一起断。
            //
            // 合成规则（墓碑过滤 + 磁盘恢复 + R1「null 不清空」+ R2-N1 去重护栏）
            // 与"加载更早"分页共用 buildVisibleFromIndex 这一份实现，不留第二套。
            val distinctVisible: List<GalleryItem>? = if (context != null && serverId != null) {
                val indexMerged = withContext(Dispatchers.IO) {
                    GalleryMetadataCache.mergeMetadata(context, serverId, serverItems)
                }
                buildVisibleFromIndex(context, serverId, indexMerged, deletionsSnapshot)
            } else {
                serverItems
                    .filterNot { GalleryMetadataCache.isDeleted(it, deletionsSnapshot) }
                    .distinctBy { GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename) }
            }

            if (distinctVisible == null) {
                return false
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

            // ============================================================
            // 「加载更早」分页基线 + 新用户首访自动回填（config-audit-report 事项2）
            // ============================================================
            //
            // 首屏窗口 = HISTORY_MAX_ITEMS 条 **history**（服务端按 prompt 条目计数，
            // 一次批量出图 4 张只占 1 条 —— 分页算术全部按条目数走，
            // 理由与"为什么不是 offset 步进"见顶层 olderWindowSize 的 KDoc）。
            // 满页 = 服务端可能还有更早的；不满 = 整段内存历史已在手里。
            val serverHistoryCount = historyJson.length()
            olderExhausted =
                serverHistoryCount < BackgroundDegradationPolicy.HISTORY_MAX_ITEMS
            _canLoadOlder.value = !olderExhausted &&
                olderWindowSize(olderTapsDone, OLDER_WINDOW_STEP_ITEMS, MAX_OLDER_WINDOW_TAPS) != null

            // 首次进图库若索引为空 → 自动回填一页更早数据（报告建议的性价比做法）。
            // 判据「展示条数 == 本次服务器条目数」= 索引对合并的净贡献为零 → 索引是空表
            //（全新用户）。老用户索引攒有更早条目，合并结果必然多于首屏条目数，条件不成立
            // → 不发多余请求，既有链路零影响。回填会推进 olderTapsDone，本会话后续加载
            // 靠 olderTapsDone == 0 再也不会二次触发。
            // 回填在**本协程内**执行：此刻 _isLoading/_isRefreshing 尚未复位（finally
            // 还没跑），周期刷新与下拉刷新天然进不来，两笔 /history 不会并发写列表。
            if (olderTapsDone == 0 &&
                context != null && serverId != null &&
                serverItems.isNotEmpty() && !olderExhausted &&
                distinctVisible.size == serverItems.size
            ) {
                DebugLogger.i(TAG, "索引为空的首次加载：自动回填一页更早历史")
                loadOlderInternal(context, serverId)
            }

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
     * 索引合并结果 → 最终展示列表。**首屏/刷新与「加载更早」分页共用这一份合成**，
     * 墓碑过滤、磁盘恢复、R1「null 不清空」、R2-N1 去重护栏都不留第二处实现。
     *
     * 三条规则的口径（原内联版逐条保留）：
     * - 已在手机上删掉的条目（持久墓碑 + 本次待删快照）在最后统一滤掉；
     * - null 的语义：索引读不出来（不存在/解析失败）且服务器本次也没有条目时
     *   返回 null —— 调用方**必须保留上一次列表**，绝不能把 null 当成"图库空了"
     *   清空界面（R1：磁盘缓存恢复出的条目是这时用户唯一能看到的图库）；
     * - 图库 LazyGrid 的 key 是 (promptId, filename)，重复二元组会抛
     *   IllegalArgumentException = 闪退，所以这里统一 distinctBy 收敛（R2-N1）。
     */
    private suspend fun buildVisibleFromIndex(
        context: Context,
        serverId: String,
        indexMerged: List<GalleryItem>?,
        deletionsSnapshot: Set<String>
    ): List<GalleryItem>? = withContext(Dispatchers.IO) {
        val tombstones = GalleryMetadataCache.loadTombstones(context, serverId) + deletionsSnapshot
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
                DebugLogger.w(TAG, "索引读取失败，已用磁盘缓存恢复 ${recovered.size} 条")
            }
            recovered.distinctBy { GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename) }
        }
    }

    /**
     * 「加载更早」按钮入口：向服务端把 history 窗口放宽一档重拉，把更早的条目
     * 追加进列表尾部（"新的在前"，更早的必然垫底 —— 顺序不会错乱）。
     *
     * 失败静默：按钮留在原地可再点，不发 toast（翻页失败不该打断浏览，
     * 且 canLoadOlder 未被消耗，重试无额外条件）。
     * 与首屏/刷新互斥：那两条链路在途时分页结果会和它们的列表回写竞争。
     */
    fun loadOlderItems(onComplete: (Boolean) -> Unit = {}) {
        if (!canLoadOlder.value) {
            onComplete(false)
            return
        }
        if (_isLoading.value || _isRefreshing.value) {
            onComplete(false)
            return
        }
        if (!olderInFlight.compareAndSet(false, true)) {
            onComplete(false)
            return
        }
        scope.launch {
            try {
                val ctx = applicationContext
                val sid = ConnectionManager.currentServerId
                onComplete(if (ctx != null && sid != null) loadOlderInternal(ctx, sid) else false)
            } finally {
                olderInFlight.set(false)
            }
        }
    }

    /**
     * 分页主体。两个入口：[loadOlderItems]（按钮，olderInFlight 已占位）与
     * loadGalleryInternal 的首访回填（**不经过** olderInFlight —— 它跑在加载协程内、
     * _isLoading/_isRefreshing 未复位时，由那两个标志互斥）。
     */
    private suspend fun loadOlderInternal(context: Context, serverId: String): Boolean {
        val client = comfyUIClient ?: return false
        // 离线态（含自动切换）没有可翻页的服务端；索引里已翻到的更早条目照常可见
        if (ConnectionManager.isOffline || AppSettings.isOfflineMode(context)) return false

        val window = olderWindowSize(olderTapsDone, OLDER_WINDOW_STEP_ITEMS, MAX_OLDER_WINDOW_TAPS)
            ?: run {
                olderExhausted = true
                _canLoadOlder.value = false
                return false
            }

        _isLoadingOlder.value = true
        try {
            val historyJson = withContext(Dispatchers.IO) {
                awaitCallbackOnce<JSONObject?> { deliver ->
                    // 只放宽 max_items 窗口，**offset 恒不传** —— 传了服务端会从最旧一端
                    // 跳过条数（execution.py:1423-1439），拿到的不是"首屏再往更早"而是
                    // 别处切片；理由见 olderWindowSize 的 KDoc。
                    client.fetchAllHistory(maxItems = window) { history -> deliver(history) }
                }
            } ?: return false

            val deletionsSnapshot: Set<String>
            synchronized(pendingDeletions) { deletionsSnapshot = pendingDeletions.toSet() }

            val olderItems = parseHistoryToGalleryItems(historyJson)

            // 净新增按**当前展示列表**判定：整页全是熟面孔 → 更早的已经都在索引里
            //（索引条目全部来自服务端页面，窗口档位单调放宽 → 再往更早也不会出新的；
            // 保守起见这里只终止本会话翻页，不弄丢任何数据）。
            val knownKeys = _galleryItems.value.mapTo(mutableSetOf()) {
                GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename)
            }
            val netNew = olderItems.count {
                GalleryMetadataCache.itemIdentityKey(it.promptId, it.filename) !in knownKeys
            }

            val mergedIndex = withContext(Dispatchers.IO) {
                GalleryMetadataCache.mergeOlderMetadata(context, serverId, olderItems)
            }
            if (mergedIndex == null) {
                // 索引读不出来：分页**完全不动列表**（区别于首屏路径的 R1 —— 首屏的
                // 服务器数据能撑起新列表，更早页撑不起，硬换会把当前可见图库缩水）。
                DebugLogger.w(TAG, "索引读取失败，跳过本次加载更早（保留现有 ${_galleryItems.value.size} 条）")
                return false
            }

            val visible = buildVisibleFromIndex(context, serverId, mergedIndex, deletionsSnapshot)
                ?: return false

            if (olderHistoryExhausted(historyJson.length(), window) || netNew == 0) {
                olderExhausted = true
            }
            olderTapsDone++

            _galleryItems.value = visible
            hasLoadedOnce = true
            _canLoadOlder.value = !olderExhausted &&
                olderWindowSize(olderTapsDone, OLDER_WINDOW_STEP_ITEMS, MAX_OLDER_WINDOW_TAPS) != null
            DebugLogger.i(
                TAG,
                "加载更早完成: 窗口 $window 条 history，净新增 $netNew 条，展示 ${visible.size} 条（已翻 $olderTapsDone 档）"
            )
            return true
        } catch (e: Exception) {
            DebugLogger.w(TAG, "加载更早失败: ${e.message}")
            return false
        } finally {
            _isLoadingOlder.value = false
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
            // 离线态没有可翻页的服务端：不显示"加载更早"（索引里已翻到的更早条目照常可见）
            _canLoadOlder.value = false
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

    /**
     * _trashEntries「读快照 → 计算 → 全量覆盖写盘 → 回写」的互斥锁。
     *
     * 四个写入口（moveToTrashInternal / restoreFromTrash / finishLocalCleanup / markEntry）
     * 调用线程不同（部分 IO、部分主线程），无锁时后写方会用旧快照覆盖先写方，
     * 刚进回收站的条目会从磁盘消失（图库与回收站都找不到）。锁内只做磁盘 IO，
     * 不含 suspend 调用（四个入口均为普通函数）。
     */
    private val trashEntriesLock = Any()

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
        // 读快照 → 计算 → 全量覆盖写盘 → 回写整段持锁；失败路径非局部返回，成功把 merged 带出去
        val merged = synchronized(trashEntriesLock) {
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
            merged
        }
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
        // 从读到回写整段持锁（含墓碑清理）：中途出锁的话，另一个写入口可能在这段
        // 间隙用旧快照全量覆盖写盘，把刚进站的条目从磁盘上抹掉。
        val entry = synchronized(trashEntriesLock) {
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
            entry
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
        /** [removedWithServer] 的子集：删除时返回 404，文件在服务器上本就不在了
         *  （多半已在电脑端删除）—— 照常收尾本地，UI 需单独提示 */
        val alreadyGone: Int,
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
     * - 服务器删除**只**经 [ComfyUIClient.deleteBrowserFile]，子目录先过
     *   [normalizeFolderPath]（Windows 反斜杠形态统一成正斜杠，校验与发送同一个值），
     *   再过 [toBrowserFolderType] 白名单（未知 type 兜底 outputs 并记日志）与
     *   [isLocatorSafe] 守卫，拼不出也发不出路径；
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
            var alreadyGone = 0
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
                        if (folderType.fellBackToOutputs) {
                            // type 缺失/不认识：图库只展示 outputs 目录的产出，兜底按 outputs 根处理；
                            // 不静默 —— 调试日志带原值（原值永不进请求体）
                            DebugLogger.w(
                                TAG,
                                "回收站条目 type 无法识别（原值：\"${entry.item.type}\"），" +
                                    "按 outputs 根删除: ${entry.item.filename}"
                            )
                        }
                        // Windows 下的 ComfyUI 返回 "anima\hires" 反斜杠子目录：校验与发送前先
                        // 统一成正斜杠，两处用同一个归一化值，杜绝"校验过却发原值"或"合法却被拦"
                        val folderPath = normalizeFolderPath(entry.item.subfolder)
                        if (!isLocatorSafe(entry.item.filename, folderPath)) {
                            // 归一化后定位符仍非法（filename 含路径分隔 / `..` / 绝对前缀）：宁可不删，
                            // 不发请求、条目留在回收站 —— 安全行为不变；文案给用户可执行的指引，
                            // 不再出现"条目定位符异常"这类内部术语
                            queuedForRetry++
                            markEntry(
                                entry.identityKey, true,
                                "文件名含非法字符，无法自动删除；请在电脑端手动删除后重试"
                            )
                            continue
                        }
                        val (code, networkFailed) = activeClient.deleteBrowserFileAwait(
                            entry.item.filename, folderPath, folderType.value
                        )
                            when (val attempt = classifyDeleteAttempt(code, networkFailed)) {
                                ServerDeleteAttempt.REMOVED, ServerDeleteAttempt.ALREADY_GONE -> {
                                    // 2xx = 服务器确认删除；404 = 文件本就不在了（幂等收敛，
                                    // 能力已由探测确认在位，这个 404 来自插件的"未找到文件"，
                                    // 通常是电脑端已删 —— 照常收尾本地，摘要里单独提示用户）
                                    removedWithServer++
                                    if (attempt == ServerDeleteAttempt.ALREADY_GONE) alreadyGone++
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
                    alreadyGone = alreadyGone,
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
        synchronized(trashEntriesLock) {
            _trashEntries.value = _trashEntries.value.filterNot { it.identityKey == entry.identityKey }
            val persisted = applicationContext?.let { GalleryTrashStorage.saveAll(it, _trashEntries.value) } ?: false
            if (!persisted) {
                // 服务器文件已删、回收站记录没删掉：下次启动这条会重新出现，可再清一次
                // （服务器侧是幂等 404）。这里必须明确记失败，不能让人以为已经清理干净。
                DebugLogger.e(TAG, "回收站清理结果未能落盘（条目可能在下次启动重现）: ${entry.item.filename}")
            }
        }
    }

    /** 更新单条回收站记录的重试标记并落盘（条目**留在**回收站，列表不减）。 */
    private fun markEntry(identityKey: String, pendingRetry: Boolean, error: String?) {
        synchronized(trashEntriesLock) {
            _trashEntries.value = _trashEntries.value.map {
                if (it.identityKey == identityKey) it.copy(pendingRetry = pendingRetry, lastError = error) else it
            }
            val persisted = applicationContext?.let { GalleryTrashStorage.saveAll(it, _trashEntries.value) } ?: false
            if (!persisted) {
                DebugLogger.e(TAG, "回收站待重试标记未能落盘（重启后仍会重新探测）: $identityKey")
            }
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
        // 「加载更早」分页状态随列表一起复位：断连/换服务器后重走首屏加载，
        // 由那一次的"满页判据"重新决定能否翻页（olderTapsDone 归零 → 窗口重新从一档起）
        olderTapsDone = 0
        olderExhausted = false
        _canLoadOlder.value = false
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
