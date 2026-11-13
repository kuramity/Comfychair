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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryMetadataCache
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.GalleryItem

/**
 * Repository for managing gallery data with background preloading and caching.
 * This is a singleton that persists across activities.
 */
/** 视频扩展名（文件级私有：仓库与同文件的 GalleryHistoryParser 共用一份判定） */
private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm", ".gif", ".avi", ".mov")

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
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchAllHistory { history ->
                        continuation.resumeWith(Result.success(history))
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
            // 两条都不能少：
            // - 不能只用服务器返回的 —— ComfyUI 的 /history 是内存态，重启即清空，
            //   而图片文件仍在 output 目录里。"图库突然一张都没有、只剩新生成的图"
            //   就是这个原因（不是图片丢了，是索引丢了）。
            // - 不能把空的服务器结果写回索引 —— 合并语义保证索引只增不减，
            //   否则一次服务器重启就会把之前攒下的记录全冲掉，连离线退路一起断。
            //
            // 已在手机上删掉的条目（持久墓碑 + 本次待删）在最后统一滤掉。
            val visible = if (context != null && serverId != null) {
                withContext(Dispatchers.IO) {
                    val tombstones = GalleryMetadataCache.loadTombstones(context, serverId) + deletionsSnapshot
                    recoverFromDiskCache(
                        context,
                        GalleryMetadataCache.mergeMetadata(context, serverId, serverItems)
                    ).filterNot { it.promptId in tombstones }
                }
            } else {
                serverItems.filterNot { it.promptId in deletionsSnapshot }
            }

            val previousCount = _galleryItems.value.size
            _galleryItems.value = visible
            _lastRefreshTime.value = System.currentTimeMillis()
            hasLoadedOnce = true
            DebugLogger.d(
                TAG,
                "Gallery refresh complete: ${visible.size} items shown " +
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
            val visible = recoverFromDiskCache(context, cachedItems).filterNot { it.promptId in tombstones }
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
    private fun recoverFromDiskCache(context: Context, known: List<GalleryItem>): List<GalleryItem> {
        val knownKeys = known.mapTo(mutableSetOf()) { "${it.promptId}\u0000${it.filename}" }
        val recovered = MediaCache.listCachedKeys(context)
            .filterNot { "${it.promptId}\u0000${it.filename}" in knownKeys }
            .map { key ->
                GalleryItem(
                    promptId = key.promptId,
                    filename = key.filename,
                    subfolder = "",
                    type = "output",
                    isVideo = VIDEO_EXTENSIONS.any { key.filename.lowercase().endsWith(it) },
                    index = 0
                )
            }
        if (recovered.isNotEmpty()) {
            DebugLogger.i(TAG, "Recovered ${recovered.size} gallery item(s) from disk cache")
        }
        return known + recovered
    }

    /**
     * Check if gallery data is available (has been loaded at least once)
     */
    fun hasData(): Boolean = hasLoadedOnce && _galleryItems.value.isNotEmpty()

    /**
     * 按 promptId 从手机图库移除。
     *
     * 供媒体查看器使用 —— 它手上是 `MediaViewerItem` 而不是 `GalleryItem`，
     * 但删除语义与 [deleteItem] 完全相同：只影响手机端，并落下删除墓碑
     * （否则下次刷新服务器历史时这条记录会复活）。
     */
    fun removeItemLocally(promptId: String) {
        val items = _galleryItems.value.filter { it.promptId == promptId }
        removeFromGallery(setOf(promptId), items)
    }

    /**
     * 从手机图库移除一个条目。
     *
     * **只影响手机端**：服务器的历史记录与 output 目录里的文件都不动（这是明确的产品选择）。
     * 正因为服务器不知情，这里必须落一份持久墓碑 —— 否则下次刷新服务器历史时，
     * 这条记录会原样复活（用户会觉得"删了又回来了"）。
     */
    suspend fun deleteItem(item: GalleryItem): Boolean {
        removeFromGallery(setOf(item.promptId), listOf(item))
        return true
    }

    /**
     * 从手机图库批量移除。
     *
     * 返回实际移除的条目数（promptId 数）。语义与单条删除一致：不动服务器。
     */
    suspend fun deleteItems(promptIds: Set<String>): Int {
        if (promptIds.isEmpty()) return 0
        val removedItems = _galleryItems.value.filter { it.promptId in promptIds }
        removeFromGallery(promptIds, removedItems)
        return promptIds.size
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
     */
    private fun removeFromGallery(promptIds: Set<String>, items: List<GalleryItem>) {
        if (promptIds.isEmpty()) return

        synchronized(pendingDeletions) {
            pendingDeletions.addAll(promptIds)
        }

        _galleryItems.value = _galleryItems.value.filterNot { it.promptId in promptIds }

        val context = applicationContext
        val serverId = ConnectionManager.currentServerId
        if (context != null && serverId != null) {
            GalleryMetadataCache.addTombstones(context, serverId, promptIds)
        } else {
            DebugLogger.w(TAG, "Deletion tombstones not persisted (no context/serverId)")
        }

        items.forEach { MediaCache.evict(MediaCacheKey(it.promptId, it.filename)) }
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
