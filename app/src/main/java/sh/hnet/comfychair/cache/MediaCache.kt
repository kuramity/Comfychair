package sh.hnet.comfychair.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.util.DebugLogger
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import kotlin.coroutines.resume

/**
 * Stable cache key for media items.
 * Uses promptId + filename to survive index changes during deletions.
 */
data class MediaCacheKey(
    val promptId: String,
    val filename: String
) {
    val keyString: String get() = "${promptId}_$filename"
    override fun toString(): String = keyString
}

/**
 * Cached video dimensions.
 * Used to avoid repeated MediaMetadataRetriever calls in VideoPlayer.
 */
data class VideoDimensions(
    val width: Int,
    val height: Int
)

/**
 * Prefetch priority levels - lower value = higher priority
 */
enum class PrefetchPriority(val value: Int) {
    ADJACENT(1),    // N±1 from current
    NEARBY(2),      // N±2 from current
    DISTANT(3)      // N±3 from current
}

/**
 * Identifies which screen is currently controlling cache priorities.
 * MediaViewer takes precedence when open.
 */
enum class ActiveView {
    GALLERY,
    MEDIA_VIEWER
}

/**
 * Prefetch request with priority ordering
 */
data class PrefetchRequest(
    val key: MediaCacheKey,
    val isVideo: Boolean,
    val subfolder: String,
    val type: String,
    val priority: PrefetchPriority,
    val timestamp: Long = System.currentTimeMillis()
) : Comparable<PrefetchRequest> {
    override fun compareTo(other: PrefetchRequest): Int {
        val priorityCompare = priority.value.compareTo(other.priority.value)
        return if (priorityCompare != 0) priorityCompare
        else timestamp.compareTo(other.timestamp)
    }
}

/**
 * Centralized media cache singleton for Gallery and MediaViewer.
 *
 * Supports two caching modes:
 * - Memory-first (default): In-memory caching with priority-based eviction and prefetching
 * - Disk-first: Write to disk cache, read from disk when needed (minimal RAM for low-end devices)
 *
 * Provides:
 * - Single bitmap cache for all images (thumbnails and full-size are the same)
 * - Priority-based eviction to protect items near current view position
 * - Smart prefetching with priority queue
 */
object MediaCache {

    private const val TAG = "MediaCache"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Accessor for shared client from ConnectionManager
    private val comfyUIClient: ComfyUIClient?
        get() = ConnectionManager.clientOrNull

    // Context for temp file operations (set once when first needed)
    private var applicationContext: Context? = null
    private var isPrefetchWorkersStarted = false

    // Caching mode - true for memory-first (default), false for disk-first
    private var isMemoryFirstMode = true

    // Memory budget: 1/3 of max heap for bitmaps (full-res images), 1/8 for video bytes
    // Bitmaps are large (4MB+ per 1024x1024 image), so we need generous cache for smooth scrolling
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val bitmapCacheSize = maxMemoryKb / 3
    private val videoCacheSize = maxMemoryKb / 8

    // Single bitmap cache for all images (full-size for both gallery and viewer)
    private val bitmapCache = PriorityLruCache<String, Bitmap>(
        maxSize = bitmapCacheSize,
        sizeOf = { _, bitmap -> bitmap.byteCount / 1024 }
    )

    // Video bytes cache (separate data type)
    private val videoCache = PriorityLruCache<String, ByteArray>(
        maxSize = videoCacheSize,
        sizeOf = { _, bytes -> bytes.size / 1024 }
    )

    /**
     * 缩略图专用的小缓存（预算约为原图缓存的 1/5）。
     *
     * 为什么与原图分开：列表里的缩略图只有几十 KB，原图动辄几 MB 到上百 MB。
     * 共用一个 LRU 时，只要用户点开一张大图，整屏的缩略图就被挤出去，
     * 滚回列表又要全部重拉 —— 这是"看过一张大图之后列表也变慢"的来源之一。
     */
    private val thumbnailCache = PriorityLruCache<String, Bitmap>(
        maxSize = (maxMemoryKb / 16).coerceAtLeast(8 * 1024),
        sizeOf = { _, bitmap -> bitmap.byteCount / 1024 }
    )

    /**
     * 拉原图时被判定为"超过下载上限"的条目。
     *
     * 记住它们是为了**不再去试原图**：这类失败重试多少次结果都一样，
     * 而不记的话每次点开都要先白探一次（服务端给了 Content-Length，所以是
     * "还没开始读就中止"，但连这个探测也不该反复做）。
     */
    private val oversizedKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 拉取服务端压缩预览的**并发上限**（v4.6.0，"约 10 张后加载不出来"的主修复之一）。
     *
     * 背景：LazyBitmap 是每个格子组合时各自发起 fetchThumbnail，**没有并发上限**；
     * OkHttp 同主机默认 5 并发，其余在内部排队；而服务端 preview 是现场压缩
     * （204MB 原图约 9 秒），排队 + 压缩叠加后，列表滚动时十几个请求成片挂起/超时。
     *
     * 这道门把**同时打到服务端的压缩请求**限制在 3 个（与预取队列的
     * [MAX_CONCURRENT_PREFETCH] 对齐）：后面的格子在本信号量上排队显示转圈
     * （用户可感知"在慢慢来"），而不是把服务端压垮后集体失败。
     */
    internal val thumbnailFetchPermits = Semaphore(permits = 3)

    /**
     * 服务器上已确认不存在的条目（/view 404）。
     *
     * 典型成因：电脑端清理了 output 目录，而 /history 的图库索引里还留着记录。
     * 这类条目重试多少次都是 404，记住它们可以：① 不再反复打服务器；
     * ② 让 UI 显示"文件不存在"而不是无限转圈。
     */
    private val missingKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 这个条目是否已确认服务器上没有（404） */
    fun isFileMissing(key: MediaCacheKey): Boolean = key.keyString in missingKeys

    /** 手动重试时清除"文件不存在"标记（例如电脑端把文件放回去了） */
    fun clearFileMissing(key: MediaCacheKey) {
        missingKeys.remove(key.keyString)
    }

    /**
     * 记录"服务器上已没有这个文件"（/view 404）。
     *
     * 只有 fetchImage 链路能把 404 单独分型出来（fetchVideo 不分），所以写入点都在图片链路上。
     * 记下之后：① fetchThumbnail / fetchViewerImage 在 [isFileMissing] 上短路，不再白打这趟请求；
     * ② 图库格子与查看器的"文件不存在"占位有了数据源。
     * 清除途径：手动重试（[clearFileMissing]）与下拉刷新（[clearForRefresh]）。
     */
    private fun markFileMissing(key: MediaCacheKey) {
        missingKeys.add(key.keyString)
    }

    // Disk cache directory name
    private const val DISK_CACHE_DIR = "gallery_cache"

    /** 缩略图磁盘缓存目录（独立于 gallery_cache：它是跨启动复用的小文件） */
    private const val THUMB_DISK_DIR = "gallery_thumb"

    /**
     * 缩略图磁盘缓存的总量上限（128 MB）。
     *
     * 必须有这一步。上一次"一张放大图拖慢整个图库"的事故里，磁盘缓存"只增不减"
     * 是原因之一；缩略图虽然单张只有几十 KB，但图库条目会一直长，没有上限迟早占满 cacheDir。
     * 到顶后按最后修改时间删最旧的（列表最上面的是最近生成/看过的，先删老的）。
     */
    private const val MAX_THUMB_DISK_BYTES = 128L * 1024 * 1024

    // ============================================================
    // 图库持久化缓存（v4.16.1，filesDir 双目录）
    // ============================================================

    /**
     * 图片统一档的磁盘缓存目录（`filesDir/gallery_store`）。
     *
     * **为什么不用 cacheDir**：cacheDir 在系统存储紧张时会被 Android 自动清空，
     * 这正是"重开 App 进图库、看过的图又从糊变清"的根因之一；filesDir 属于应用
     * 私有数据目录，系统不做自动清理，重开 App 直接命中、立刻清晰。
     * 与旧 cacheDir 两处目录（gallery_cache / gallery_thumb）完全分开：
     * 旧目录保留不动、不迁移、不删除（readCachedBytes / listCachedKeys 依赖旧路径推断），
     * 新逻辑只读写新目录。
     */
    private const val PERSIST_STORE_DIR = "gallery_store"

    /** 视频首帧缩略图的磁盘缓存目录（`filesDir/gallery_thumbs`；视频没有"统一档"概念，只有首帧） */
    private const val PERSIST_THUMB_DIR = "gallery_thumbs"

    /**
     * 图库统一档的解码长边上限（720）。
     *
     * 服务端 /view 不支持尺寸参数（实测六种参数全被忽略），只能客户端缩放：
     * 统一拉 `preview=jpeg;75`（同分辨率压缩，传输体积约为 PNG 的 1/7），
     * 客户端采样到长边 720、JPEG80 落盘。**只存一份 720**，不按列数存多档 ——
     * 2 列格子直接用、3/4 列显示时再降采样（见 galleryDisplayScaleForColumns），
     * 省磁盘且 4 列时底层数据仍优于 360 档。
     */
    const val GALLERY_DECODE_DIMENSION = 720

    /**
     * 图库持久化缓存的总量上限（512 MB，gallery_store 与 gallery_thumbs 合计）。
     *
     * 到顶后按最后修改时间删最旧的（与 trimThumbDisk 同思路，作用在新目录）。
     */
    private const val MAX_PERSIST_DISK_BYTES = 512L * 1024 * 1024

    /**
     * Set the caching mode.
     * @param enabled true for memory-first (default), false for disk-first
     */
    fun setMemoryFirstMode(enabled: Boolean) {
        if (isMemoryFirstMode == enabled) return  // No change

        // 清哪一层由 CacheModePolicy 决定：内存层归「内存优先」管，
        // 磁盘层（cacheDir/gallery_cache）是离线读取唯一的数据源，任何模式切换都不删。
        // 旧实现在切到内存优先时 deleteRecursively()，直接导致"开内存优先 → 离线无数据可读"。
        val layers = CacheModePolicy.layersToClear(enteringMemoryFirst = enabled)

        if (layers.memory) {
            // Switching TO disk-first: clear in-memory caches
            // Gallery content is fetched from server on-demand, no migration needed
            bitmapCache.evictAll()
            videoCache.evictAll()
            videoUriCache.clear()
            videoDimensionsCache.clear()
            prefetchQueue.clear()
            inProgressKeys.clear()
            completionCallbacks.clear()
        }

        if (layers.disk) {
            applicationContext?.let { context ->
                try {
                    getDiskCacheDir(context).deleteRecursively()
                } catch (_: Exception) {
                    // Ignore deletion failures
                }
            }
        }

        isMemoryFirstMode = enabled
    }

    /**
     * Check if memory-first mode is enabled.
     */
    fun isMemoryFirstMode(): Boolean = isMemoryFirstMode

    /**
     * Get the disk cache directory.
     */
    private fun getDiskCacheDir(context: Context): File {
        return File(context.cacheDir, DISK_CACHE_DIR).apply { mkdirs() }
    }

    /**
     * Get the disk cache file for a given key.
     */
    private fun getDiskCacheFile(context: Context, key: MediaCacheKey, isVideo: Boolean): File {
        val ext = if (isVideo) "mp4" else "png"
        return File(getDiskCacheDir(context), "${key.keyString}.$ext")
    }

    /**
     * 扫一遍磁盘缓存目录，从文件名反推回缓存键。
     *
     * 命名规则是 `{promptId}_{filename}.{ext}`，而 promptId 是无下划线的 UUID，
     * 所以按**第一个下划线**切分即可还原（filename 自身可能含下划线，不能用最后一个）。
     *
     * 用途：图库索引曾经被"服务器历史为空"的那次刷新覆盖成空，而这些缓存文件都还在
     * 磁盘上 —— 靠它们能把已经丢掉的那部分图库补回来，条目重新下载原图也走得通。
     */
    fun listCachedKeys(context: Context): List<MediaCacheKey> {
        val files = getDiskCacheDir(context).listFiles() ?: return emptyList()
        return files.mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            val name = file.name.removeSuffix(".png").removeSuffix(".mp4")
            val separator = name.indexOf('_')
            if (separator <= 0 || separator == name.length - 1) return@mapNotNull null
            MediaCacheKey(
                promptId = name.substring(0, separator),
                filename = name.substring(separator + 1)
            )
        }
    }

    /**
     * Check if an item exists in disk cache.
     */
    private fun existsInDiskCache(context: Context, key: MediaCacheKey, isVideo: Boolean): Boolean {
        return getDiskCacheFile(context, key, isVideo).exists()
    }

    /**
     * Read a bitmap from disk cache.
     */
    private fun readBitmapFromDisk(context: Context, key: MediaCacheKey): Bitmap? {
        return try {
            val file = getDiskCacheFile(context, key, false)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Write a bitmap to disk cache.
     */
    private fun writeBitmapToDisk(context: Context, key: MediaCacheKey, bitmap: Bitmap) {
        try {
            val file = getDiskCacheFile(context, key, false)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (_: Exception) {
            // Ignore write failures
        }
    }

    /**
     * Read video bytes from disk cache.
     */
    private fun readVideoBytesFromDisk(context: Context, key: MediaCacheKey): ByteArray? {
        return try {
            val file = getDiskCacheFile(context, key, true)
            if (file.exists()) {
                file.readBytes()
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Write video bytes to disk cache.
     */
    private fun writeVideoToDisk(context: Context, key: MediaCacheKey, bytes: ByteArray) {
        try {
            val file = getDiskCacheFile(context, key, true)
            file.writeBytes(bytes)
        } catch (_: Exception) {
            // Ignore write failures
        }
    }

    /**
     * 读取磁盘缓存里的**原始字节**（图片为 PNG、视频为 MP4）。
     *
     * 只读：不联网、不写盘、不碰内存缓存，任何缓存模式下都能调。
     * 离线态查看器解析生成参数（正负提示词 / 种子）走这条路径——
     * 那份 `cacheDir/gallery_cache` 数据本来就是给离线读取留的。
     *
     * @return 文件不存在或读失败时返回 null
     */
    fun readCachedBytes(key: MediaCacheKey, isVideo: Boolean): ByteArray? {
        val context = applicationContext ?: return null
        return try {
            val file = getDiskCacheFile(context, key, isVideo)
            if (file.exists()) file.readBytes() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 离线时从磁盘缓存取缩略图/图片，纯读、不落盘。
     * 磁盘缓存是离线读取的数据源，与「内存优先」开关无关（见 [CacheModePolicy]）。
     */
    private fun readBitmapOnlyFromDisk(context: Context, key: MediaCacheKey, isVideo: Boolean): Bitmap? {
        return if (!isVideo) {
            readBitmapFromDisk(context, key)
        } else {
            val videoBytes = readVideoBytesFromDisk(context, key) ?: return null
            extractVideoData(key.keyString, videoBytes, context).second
        }
    }

    // Cache of video URIs (lightweight - just stores Uri references)
    private val videoUriCache = ConcurrentHashMap<String, Uri>()

    // Cache of video dimensions for VideoPlayer
    private val videoDimensionsCache = ConcurrentHashMap<String, VideoDimensions>()


    // Prefetching infrastructure
    private val prefetchQueue = PriorityBlockingQueue<PrefetchRequest>()
    private val inProgressKeys = ConcurrentHashMap.newKeySet<String>()

    private const val MAX_CONCURRENT_PREFETCH = 3

    // Active view tracking - MediaViewer takes precedence when open
    private var activeView = ActiveView.GALLERY

    /**
     * Set which view is currently active for priority management.
     * MediaViewer takes precedence when open.
     */
    fun setActiveView(view: ActiveView) {
        activeView = view
    }

    /**
     * Check if a specific view is currently active.
     */
    fun isActiveView(view: ActiveView): Boolean = activeView == view

    /**
     * Ensure prefetch workers are running.
     * Called when cache operations start that may need prefetching.
     */
    fun ensureInitialized(context: Context) {
        if (applicationContext == null) {
            applicationContext = context.applicationContext
        }
        if (!isPrefetchWorkersStarted) {
            startPrefetchWorkers()
            isPrefetchWorkersStarted = true
        }
    }

    // Bitmap operations

    /**
     * Get bitmap from cache (sync).
     */
    fun getBitmap(key: MediaCacheKey): Bitmap? {
        return bitmapCache.get(key.keyString)
    }

    /**
     * Put bitmap into cache with optional priority.
     */
    fun putBitmap(key: MediaCacheKey, bitmap: Bitmap, priority: Int = PriorityLruCache.PRIORITY_DEFAULT) {
        cacheFullBitmap(key.keyString, bitmap, priority)
    }

    /**
     * 取**已缓存**的视频字节（不触发下载；没命中返回 null，由调用方决定是否下载）。
     *
     * 查看器解析视频的生成参数需要完整字节（参数在文件尾的 moov box 里），而图库预取与
     * 缩略图提取往往已经把同一段视频取过一次——重复下载就是几十 MB。
     */
    fun getCachedVideoBytes(key: MediaCacheKey): ByteArray? = videoCache.get(key.keyString)

    /**
     * 全尺寸位图入内存缓存的统一闸门：超大图直接跳过。
     *
     * 服务端返回的原图可能是放大流的成品（几十 MB 级），一旦进缓存就会把整屏缩略图与
     * 相邻原图全部挤出去（bitmapCache 的预算只有 maxMemory/3）——这正是"看过一张大图
     * 之后列表也变慢"的由来。此前这个判断只做在查看器那一条路径上，其余入口都在裸存。
     * 缩略图（thumbnail 路径）体积小，仍直接走 bitmapCache.put。
     */
    private fun cacheFullBitmap(
        keyStr: String,
        bitmap: Bitmap,
        priority: Int = PriorityLruCache.PRIORITY_DEFAULT
    ) {
        if (bitmap.byteCount <= ComfyUIClient.CACHEABLE_BITMAP_BYTES) {
            bitmapCache.put(keyStr, bitmap, priority)
        } else {
            DebugLogger.d(TAG, "全尺寸图超限，不入内存缓存: $keyStr (${bitmap.byteCount / (1024 * 1024)}MB)")
        }
    }

    // Navigation priorities

    /**
     * Update bitmap priorities based on current navigation position.
     * Call this when user swipes to a new page in MediaViewer.
     *
     * @param currentIndex Index of the currently viewed item
     * @param allKeys All keys in viewing order
     */
    fun updateNavigationPriorities(currentIndex: Int, allKeys: List<MediaCacheKey>) {
        val priorityMap = mutableMapOf<String, Int>()

        for ((index, key) in allKeys.withIndex()) {
            val distance = kotlin.math.abs(index - currentIndex)
            val priority = when (distance) {
                0 -> PriorityLruCache.PRIORITY_CURRENT
                1 -> PriorityLruCache.PRIORITY_ADJACENT
                2 -> PriorityLruCache.PRIORITY_NEARBY
                3 -> PriorityLruCache.PRIORITY_DISTANT
                else -> PriorityLruCache.PRIORITY_DEFAULT
            }
            priorityMap[key.keyString] = priority
        }

        bitmapCache.updateAllPriorities(priorityMap)
    }

    /**
     * Update priorities based on gallery scroll position.
     * Uses a wider window than MediaViewer since more items are visible at once.
     *
     * @param firstVisibleIndex Index of first visible item in gallery grid
     * @param visibleItemCount Number of items currently visible on screen
     * @param allItems All gallery items for priority calculation
     * @param columnsInGrid Number of columns in the grid (for row-based calculations)
     */
    fun updateGalleryPosition(
        firstVisibleIndex: Int,
        visibleItemCount: Int,
        allItems: List<PrefetchItem>,
        columnsInGrid: Int = 2
    ) {
        if (allItems.isEmpty()) return

        val lastVisibleIndex = (firstVisibleIndex + visibleItemCount - 1).coerceAtMost(allItems.size - 1)
        val centerIndex = firstVisibleIndex + (visibleItemCount / 2)

        val priorityMap = mutableMapOf<String, Int>()

        for ((index, item) in allItems.withIndex()) {
            val distanceFromCenter = kotlin.math.abs(index - centerIndex)
            val rowDistance = distanceFromCenter / columnsInGrid

            val priority = when {
                // Visible items get highest priority
                index in firstVisibleIndex..lastVisibleIndex ->
                    PriorityLruCache.PRIORITY_CURRENT
                // ±1 row from visible
                rowDistance <= 1 -> PriorityLruCache.PRIORITY_ADJACENT
                // ±2 rows from visible
                rowDistance <= 2 -> PriorityLruCache.PRIORITY_NEARBY
                // ±3 rows from visible
                rowDistance <= 3 -> PriorityLruCache.PRIORITY_DISTANT
                else -> PriorityLruCache.PRIORITY_DEFAULT
            }
            priorityMap[item.key.keyString] = priority
        }

        bitmapCache.updateAllPriorities(priorityMap)

        // Trigger prefetch for items in buffer zone
        prefetchGalleryItems(firstVisibleIndex, visibleItemCount, columnsInGrid, allItems)
    }

    /**
     * Prefetch gallery items around visible area.
     */
    private fun prefetchGalleryItems(
        firstVisibleIndex: Int,
        visibleItemCount: Int,
        columnsInGrid: Int,
        allItems: List<PrefetchItem>
    ) {
        val bufferRows = 3
        val bufferItems = bufferRows * columnsInGrid

        val startIndex = (firstVisibleIndex - bufferItems).coerceAtLeast(0)
        val endIndex = (firstVisibleIndex + visibleItemCount + bufferItems).coerceAtMost(allItems.size)

        // Build set of keys that should be prefetched with their priorities
        val desiredPrefetches = mutableMapOf<String, Pair<PrefetchItem, PrefetchPriority>>()

        for (i in startIndex until endIndex) {
            val item = allItems[i]
            val keyStr = item.key.keyString

            // Skip if already cached
            if (isCached(item.key, item.isVideo)) continue
            // Skip if already in progress
            if (inProgressKeys.contains(keyStr)) continue

            val distanceFromFirst = kotlin.math.abs(i - firstVisibleIndex)
            val priority = when {
                i in firstVisibleIndex until (firstVisibleIndex + visibleItemCount) ->
                    PrefetchPriority.ADJACENT
                distanceFromFirst <= columnsInGrid * 2 -> PrefetchPriority.NEARBY
                else -> PrefetchPriority.DISTANT
            }

            desiredPrefetches[keyStr] = item to priority
        }

        // Merge approach: remove outdated requests, keep existing ones in range
        val iterator = prefetchQueue.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            val keyStr = request.key.keyString
            if (!desiredPrefetches.containsKey(keyStr)) {
                // Remove item no longer in desired range
                iterator.remove()
            } else {
                // Item already queued - remove from desired to avoid re-adding
                desiredPrefetches.remove(keyStr)
            }
        }

        // Add only new prefetch requests
        for ((_, itemAndPriority) in desiredPrefetches) {
            val (item, priority) = itemAndPriority
            prefetchQueue.offer(PrefetchRequest(
                key = item.key,
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type,
                priority = priority
            ))
        }
    }

    /**
     * Initial prefetch for gallery startup.
     * Fetches first N items with high priority.
     */
    fun initialPrefetch(items: List<PrefetchItem>) {
        for (item in items) {
            val keyStr = item.key.keyString
            if (!isCached(item.key, item.isVideo) && !inProgressKeys.contains(keyStr)) {
                prefetchQueue.offer(PrefetchRequest(
                    key = item.key,
                    isVideo = item.isVideo,
                    subfolder = item.subfolder,
                    type = item.type,
                    priority = PrefetchPriority.ADJACENT
                ))
            }
        }
    }

    /**
     * Fetch bitmap with caching.
     * Returns cached version immediately if available.
     * In disk-first mode, checks disk cache first then fetches from server and saves to disk.
     * 离线态（强制离线 / 自动离线）只读磁盘缓存，不发起任何网络请求。
     */
    suspend fun fetchBitmap(
        key: MediaCacheKey,
        isVideo: Boolean,
        subfolder: String,
        type: String,
        priority: Int = PriorityLruCache.PRIORITY_DEFAULT
    ): Bitmap? {
        val keyStr = key.keyString
        val context = applicationContext ?: return null
        val client = comfyUIClient  // May be null in offline mode
        // 离线态（用户强制离线 / 网络断开自动切换）**只读缓存，不发任何请求**。
        // 注意不能只看 client == null：离线态下 ConnectionManager 仍持有客户端
        // （看门狗探测要用它），只看它会走到网络上去。
        val offlineReadOnly = client == null || ConnectionManager.isOffline

        if (isMemoryFirstMode) {
            // Memory-first: check memory cache first
            bitmapCache.get(keyStr)?.let {
                DebugLogger.d(TAG, "fetchBitmap: $keyStr from memory cache -> HIT")
                return it
            }

            // 离线：回落到磁盘缓存（只读）。那份磁盘数据是离线读取的唯一数据源，
            // 与「内存优先」的内存缓存策略无关（见 CacheModePolicy）。
            if (offlineReadOnly) {
                return withContext(Dispatchers.IO) {
                    val bitmap = readBitmapOnlyFromDisk(context, key, isVideo)
                    bitmap?.let { cacheFullBitmap(keyStr, it) }
                    DebugLogger.d(TAG, "fetchBitmap: $keyStr offline -> disk cache ${if (bitmap != null) "HIT" else "MISS"}")
                    bitmap
                }
            }

            return withContext(Dispatchers.IO) {
                if (isVideo) {
                    fetchVideoBitmap(key, subfolder, type, client!!, context, priority)
                } else {
                    fetchImageBitmap(key, subfolder, type, client!!, priority)
                }
            }
        } else {
            // Disk-first: check disk cache first, fetch and save to disk if not found
            return withContext(Dispatchers.IO) {
                // Check disk cache first (works in offline mode!)
                if (!isVideo) {
                    readBitmapFromDisk(context, key)?.let {
                        DebugLogger.d(TAG, "fetchBitmap: $keyStr from disk cache -> HIT")
                        return@withContext it
                    }
                } else {
                    // For video, check if we have cached video and extract thumbnail
                    if (existsInDiskCache(context, key, true)) {
                        val videoBytes = readVideoBytesFromDisk(context, key)
                        if (videoBytes != null) {
                            DebugLogger.d(TAG, "fetchBitmap: $keyStr video from disk cache -> HIT")
                            val (_, thumbnail) = extractVideoData(keyStr, videoBytes, context)
                            return@withContext thumbnail
                        }
                    }
                }

                // 离线态：磁盘缓存就是全部内容，不得再去请求服务器
                if (offlineReadOnly) {
                    DebugLogger.d(TAG, "fetchBitmap: $keyStr -> offline, disk cache MISS")
                    return@withContext null
                }

                // Fetch from server
                DebugLogger.d(TAG, "fetchBitmap: $keyStr -> fetching from server")
                if (isVideo) {
                    fetchVideoBitmapDiskFirst(key, subfolder, type, client!!, context)
                } else {
                    fetchImageBitmapDiskFirst(key, subfolder, type, client!!, context)
                }
            }
        }
    }

    /**
     * 同步查缩略图内存缓存（列表格首次渲染时用，避免闪白）。
     */
    fun getThumbnail(key: MediaCacheKey): Bitmap? = thumbnailCache.get(key.keyString)

    /** 这个条目是否已被判定为「原图超过下载上限」 */
    fun isOversized(key: MediaCacheKey): Boolean = key.keyString in oversizedKeys

    /** 记录「原图超过下载上限」（拉原图返回 FILE_TOO_LARGE 时调用，之后不再重试原图） */
    fun markOversized(key: MediaCacheKey) {
        oversizedKeys.add(key.keyString)
    }

    /**
     * 取列表用的缩略图 —— **走服务端的压缩预览**，而不是原始文件。
     *
     * 这一点对本项目的意义很大：潜空间放大类工作流能产出 200 MB 级的 PNG，
     * 而服务端的 `/view?preview=` 会把它压成几 MB 的 JPEG（实测 204 MB → 7.9 MB，
     * 同分辨率、只是有损）。为了显示一格 180dp 的缩略图去传整个原图是这笔账里最亏的一环。
     *
     * 顺序：内存 → 磁盘 → 网络。磁盘这一层是必要的 —— 压缩是**服务端现场做**的
     * （那张 204 MB 的图要压约 9 秒），不缓存的话每次进图库都要再等一遍。
     *
     * @param isVideo 视频走原路径（preview 参数对 mp4 没有意义，它的缩略图本来就是解码首帧）
     */
    suspend fun fetchThumbnail(
        key: MediaCacheKey,
        isVideo: Boolean,
        subfolder: String,
        type: String
    ): Bitmap? {
        // v4.16.1 分流：持久化开关开启 → 走 filesDir 统一档链路（720 档 + 落盘 + 重开立即清晰）；
        // 关闭 → 走下方原逻辑（cacheDir + 512 档），两套互不读写对方目录。
        // 开关每次调用读一次（SharedPreferences 有内存缓存，读取极快）：设置变化即时生效。
        val appContext = applicationContext
        if (appContext != null && AppSettings.isGalleryPersistentCacheEnabled(appContext)) {
            return fetchThumbnailPersistent(key, isVideo, subfolder, type, appContext)
        }

        val keyStr = key.keyString
        thumbnailCache.get(keyStr)?.let { return it }

        // 已确认服务器上没有的条目不再打网络（重试也是 404）
        if (isFileMissing(key)) {
            DebugLogger.d(TAG, "fetchThumbnail: $keyStr known missing -> skip")
            return null
        }

        if (isVideo) {
            return fetchBitmap(key, true, subfolder, type)?.also { thumbnailCache.put(keyStr, it) }
        }

        val context = applicationContext
        val client = comfyUIClient
        val offlineReadOnly = client == null || ConnectionManager.isOffline

        if (context != null) {
            readThumbFromDisk(context, key)?.let {
                thumbnailCache.put(keyStr, it)
                DebugLogger.d(TAG, "fetchThumbnail: $keyStr disk -> HIT")
                return it
            }
        }
        if (context == null || offlineReadOnly || client == null) {
            DebugLogger.d(TAG, "fetchThumbnail: $keyStr -> offline/unavailable, MISS")
            return null
        }

        DebugLogger.d(TAG, "fetchThumbnail: $keyStr -> fetching compressed preview")
        val bitmap = withContext(Dispatchers.IO) {
            // 并发门：同时最多 3 个压缩请求打到服务端（见 thumbnailFetchPermits 的注释）。
            // 磁盘缓存读取不进门（快且不占服务端），只有真正的网络段受限。
            thumbnailFetchPermits.withPermit {
                suspendCancellableCoroutine { cont ->
                    client.fetchImage(
                        filename = key.filename,
                        subfolder = subfolder,
                        type = type,
                        maxDimension = ComfyUIClient.THUMBNAIL_DECODE_DIMENSION,
                        preview = ComfyUIClient.LIST_THUMBNAIL_PREVIEW,
                        useThumbnailClient = true
                    ) { result, failureType ->
                        if (result == null && failureType == ConnectionFailure.FILE_MISSING) {
                            markFileMissing(key)
                        }
                        if (cont.isActive) cont.resume(result)
                    }
                }
            }
        }
        if (bitmap != null) {
            thumbnailCache.put(keyStr, bitmap)
            withContext(Dispatchers.IO) { writeThumbToDisk(context, key, bitmap) }
        } else if (isFileMissing(key)) {
            // fetchImage 已把 404 记进 missingKeys —— 这里只需日志确认链路
            DebugLogger.i(TAG, "fetchThumbnail: $keyStr marked missing (404)")
        }
        return bitmap
    }

    /**
     * 图库列表缩略图的**持久化链路**（v4.16.1，[AppSettings.isGalleryPersistentCacheEnabled]
     * 开启时接管 [fetchThumbnail]）。
     *
     * 与旧链路（cacheDir + 512 档）的三点区别：
     * 1. 磁盘在 `filesDir/gallery_store`（图片统一档）与 `filesDir/gallery_thumbs`（视频首帧）
     *    —— 系统不做自动清理，重开 App 直接命中、立刻清晰；
     * 2. 图片统一档长边 720（[GALLERY_DECODE_DIMENSION]）：服务端 `/view` 不支持尺寸参数，
     *    客户端拉 `preview=jpeg;75` 后采样落盘；**只存一份**，显示时按列数降采样
     *    （2 列直接用，3/4 列走 galleryDisplayScaleForColumns）；
     * 3. **无条件落盘**：不再受「内存优先」开关控制（该开关保留原语义只管旧路径，
     *    见本类顶部 isMemoryFirstMode 的说明）。
     */
    private suspend fun fetchThumbnailPersistent(
        key: MediaCacheKey,
        isVideo: Boolean,
        subfolder: String,
        type: String,
        context: Context
    ): Bitmap? {
        val keyStr = key.keyString
        thumbnailCache.get(keyStr)?.let { return it }

        // 已确认服务器上没有的条目不再打网络（重试也是 404，与旧链路同一份 missingKeys）
        if (isFileMissing(key)) {
            DebugLogger.d(TAG, "fetchThumbnail(persist): $keyStr known missing -> skip")
            return null
        }

        // 磁盘层（filesDir 新目录）：图片读统一档，视频读首帧
        val diskFile = if (isVideo) persistThumbFile(context, key) else persistStoreFile(context, key)
        val cached = withContext(Dispatchers.IO) { readPersistBitmap(context, diskFile) }
        if (cached != null) {
            thumbnailCache.put(keyStr, cached)
            DebugLogger.d(TAG, "fetchThumbnail(persist): $keyStr disk -> HIT")
            return cached
        }

        // 离线（强制 / 自动）：持久化目录就是离线缩略图的数据源，不联网
        val client = comfyUIClient
        if (client == null || ConnectionManager.isOffline) {
            DebugLogger.d(TAG, "fetchThumbnail(persist): $keyStr -> offline, MISS")
            return null
        }

        if (isVideo) {
            // 视频没有"服务端压缩预览"概念：复用 fetchBitmap 的视频链路（拉字节 → 提取首帧），
            // 首帧等比缩到统一档长边后落盘 gallery_thumbs
            val frame = fetchBitmap(key, true, subfolder, type) ?: return null
            val scaled = withContext(Dispatchers.IO) {
                scaleBitmapToLongest(frame, GALLERY_DECODE_DIMENSION)
            }
            withContext(Dispatchers.IO) {
                persistBitmapToDisk(persistThumbFile(context, key), scaled)
                trimPersistDisk(context)
            }
            thumbnailCache.put(keyStr, scaled)
            return scaled
        }

        DebugLogger.d(TAG, "fetchThumbnail(persist): $keyStr -> fetching compressed preview")
        val bitmap = withContext(Dispatchers.IO) {
            // 并发门与旧链路共用（同为"服务端现场压缩"段，见 thumbnailFetchPermits 的注释）
            thumbnailFetchPermits.withPermit {
                suspendCancellableCoroutine { cont ->
                    client.fetchImage(
                        filename = key.filename,
                        subfolder = subfolder,
                        type = type,
                        maxDimension = GALLERY_DECODE_DIMENSION,
                        preview = ComfyUIClient.LIST_THUMBNAIL_PREVIEW,
                        useThumbnailClient = true
                    ) { result, failureType ->
                        if (result == null && failureType == ConnectionFailure.FILE_MISSING) {
                            markFileMissing(key)
                        }
                        if (cont.isActive) cont.resume(result)
                    }
                }
            }
        }
        if (bitmap != null) {
            thumbnailCache.put(keyStr, bitmap)
            withContext(Dispatchers.IO) {
                persistBitmapToDisk(persistStoreFile(context, key), bitmap)
                trimPersistDisk(context)
            }
        } else if (isFileMissing(key)) {
            DebugLogger.i(TAG, "fetchThumbnail(persist): $keyStr marked missing (404)")
        }
        return bitmap
    }

    /** 等比缩放到长边不超过 [maxLongest]（已在上限内时原样返回，不复制） */
    private fun scaleBitmapToLongest(bitmap: Bitmap, maxLongest: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxLongest || longest == 0) return bitmap
        val scale = maxLongest.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /** 统一档落盘：JPEG 质量 80（与旧缩略图落盘同质量档；失败不影响本次显示，下次重拉） */
    private fun persistBitmapToDisk(file: File, bitmap: Bitmap) {
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
        } catch (_: Exception) {
            // 忽略写失败
        }
    }

    /**
     * 查看器取图的结果。
     *
     * @param compressedFallback true 表示这张不是原图，而是"服务端压缩版"（原图超过下载上限时的降级）
     */
    data class ViewerImage(val bitmap: Bitmap?, val compressedFallback: Boolean)

    /**
     * 查看器用的取图：原图优先，**原图超限时自动降级到服务端压缩版**。
     *
     * 为什么要降级而不是直接失败：放大类工作流的输出可能 200 MB，手机上根本读不动，
     * 但"它长什么样"用户还是想看的 —— 服务端的 `preview` 是**同分辨率的有损压缩**
     * （实测 204 MB → 7.9 MB），画质够看、传输只要几 MB。真要原始画质时，
     * 界面会给一个"复制下载链接"的入口，让用户去浏览器拿原图（不经过 App 的内存与缓存）。
     *
     * 超限的条目会被[markOversized]记下来，之后不再去试原图 —— 那种失败重试多少次结果都一样。
     */
    suspend fun fetchViewerImage(
        key: MediaCacheKey,
        subfolder: String,
        type: String
    ): ViewerImage {
        val keyStr = key.keyString
        bitmapCache.get(keyStr)?.let { return ViewerImage(it, false) }

        val context = applicationContext
        val client = comfyUIClient

        // 离线：回落到磁盘缓存（与 fetchBitmap 同一口径，不发请求）。
        // context 为 null 时连磁盘都读不了，直接返回
        if (context == null) return ViewerImage(null, false)
        if (client == null || ConnectionManager.isOffline) {
            val fromDisk = withContext(Dispatchers.IO) {
                readBitmapOnlyFromDisk(context, key, false)
            }
            return ViewerImage(fromDisk, false)
        }

        // 已知超大：不再试原图（试也是白试一趟），直接用压缩版
        if (isOversized(key)) {
            DebugLogger.d(TAG, "fetchViewerImage: $keyStr known oversized -> compressed")
            return ViewerImage(fetchCompressed(client, key, subfolder, type), true)
        }

        val (bitmap, failure) = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                client.fetchImage(
                    filename = key.filename,
                    subfolder = subfolder,
                    type = type
                ) { result, failureType ->
                    if (cont.isActive) cont.resume(result to failureType)
                }
            }
        }
        if (bitmap != null) {
            // 大位图不进缓存（一张 6144 边的图约 151 MB，进缓存会把整屏的图都挤出去）——
            // 判断与说明统一收在 cacheFullBitmap 里
            cacheFullBitmap(keyStr, bitmap)
            return ViewerImage(bitmap, false)
        }
        if (failure == ConnectionFailure.FILE_MISSING) {
            // 404 记档：之后缩略图/查看器不再白打这趟请求，UI 也能显示"文件不存在"
            markFileMissing(key)
        }
        if (failure != ConnectionFailure.FILE_TOO_LARGE) {
            return ViewerImage(null, false)
        }

        // 原图超过下载上限：记下来（之后不再试原图），并降级到压缩版
        markOversized(key)
        DebugLogger.i(TAG, "Viewer image exceeded download limit, falling back to compressed preview")
        return ViewerImage(fetchCompressed(client, key, subfolder, type), true)
    }

    /** 取服务端压缩版（同分辨率、有损），用于大图降级 */
    private suspend fun fetchCompressed(
        client: ComfyUIClient,
        key: MediaCacheKey,
        subfolder: String,
        type: String
    ): Bitmap? = withContext(Dispatchers.IO) {
        // 与列表缩略图共用并发门与响应头超时：同为"服务端现场压缩"链路，
        // 排队 + 压缩叠加的无超时风险完全相同（见 thumbnailFetchPermits / thumbnailClient）
        thumbnailFetchPermits.withPermit {
            suspendCancellableCoroutine { cont ->
                client.fetchImage(
                    filename = key.filename,
                    subfolder = subfolder,
                    type = type,
                    preview = ComfyUIClient.FALLBACK_PREVIEW,
                    useThumbnailClient = true
                ) { result, failureType ->
                    // 压缩版请求打的是同一个文件：它 404 等于文件真没了，一并记档
                    if (result == null && failureType == ConnectionFailure.FILE_MISSING) {
                        markFileMissing(key)
                    }
                    if (cont.isActive) cont.resume(result)
                }
            }
        }
    }

    private fun getThumbDir(context: Context): File =
        File(context.cacheDir, THUMB_DISK_DIR).apply { mkdirs() }

    private fun thumbFile(context: Context, key: MediaCacheKey): File =
        File(getThumbDir(context), "${key.keyString}.jpg")

    /**
     * 读磁盘缩略图缓存，**带降采样**（v4.6.0 防御）。
     *
     * 磁盘上的 .jpg 是服务端压缩版落盘（同分辨率有损），放大类输出的预览即使压成
     * JPEG 也可能仍是几千像素的边长——无脑 decodeFile 会按原始尺寸展开成几十 MB 位图。
     * 列表格子最终只显示 ~180dp，长边 512 足够（与网络链路的 THUMBNAIL_DECODE_DIMENSION
     * 同一上限），这里同样按上限采样。
     */
    private fun readThumbFromDisk(context: Context, key: MediaCacheKey): Bitmap? = try {
        val file = thumbFile(context, key)
        if (file.isFile && file.length() > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) {
                null
            } else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = ComfyUIClient.computeSampleSize(longest, ComfyUIClient.THUMBNAIL_DECODE_DIMENSION)
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
            }
        } else null
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun writeThumbToDisk(context: Context, key: MediaCacheKey, bitmap: Bitmap) {
        try {
            FileOutputStream(thumbFile(context, key)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            trimThumbDisk(context)
        } catch (_: Exception) {
            // 缩略图写不进去不影响使用（下次会重拉）
        }
    }

    /** 缩略图磁盘缓存到顶后删最旧的（见 [MAX_THUMB_DISK_BYTES] 的说明） */
    private fun trimThumbDisk(context: Context) {
        try {
            val files = getThumbDir(context).listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_THUMB_DISK_BYTES) return
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_THUMB_DISK_BYTES) break
                val length = file.length()
                if (file.delete()) total -= length
            }
            DebugLogger.d(TAG, "Thumbnail disk cache trimmed to ${total / (1024 * 1024)} MB")
        } catch (_: Exception) {
            // 清理失败不影响使用
        }
    }

    /** 清空缩略图（用户清缓存时一并调用） */
    fun clearThumbnails(context: Context) {
        thumbnailCache.evictAll()
        try {
            getThumbDir(context).deleteRecursively()
        } catch (_: Exception) {
            // 忽略
        }
    }

    // ============================================================
    // 图库持久化缓存（filesDir 双目录，v4.16.1）
    // ============================================================

    /**
     * 淘汰候选的纯函数（便于单测）：文件按最后修改时间从旧到新排序，
     * 总量超过 [limitBytes] 时依次选最旧的删，直到回到上限以内。
     *
     * @param files (文件名, 大小字节) 候选列表（调用方先按 lastModified 升序排好）
     * @param totalBytes 当前总占用
     * @return 应删除的文件名（保持传入顺序 = 从最旧开始）
     */
    internal fun pickEvictionCandidates(
        files: List<Pair<String, Long>>,
        totalBytes: Long,
        limitBytes: Long
    ): List<String> {
        if (totalBytes <= limitBytes) return emptyList()
        val picked = mutableListOf<String>()
        var remaining = totalBytes
        for ((name, size) in files) {
            if (remaining <= limitBytes) break
            picked.add(name)
            remaining -= size
        }
        return picked
    }

    /** 图片统一档（720 长边 JPEG）的持久化目录与文件 */
    private fun persistStoreFile(context: Context, key: MediaCacheKey): File =
        File(File(context.filesDir, PERSIST_STORE_DIR).apply { mkdirs() }, "${key.keyString}.jpg")

    /** 视频首帧缩略图的持久化目录与文件 */
    private fun persistThumbFile(context: Context, key: MediaCacheKey): File =
        File(File(context.filesDir, PERSIST_THUMB_DIR).apply { mkdirs() }, "${key.keyString}.jpg")

    /**
     * 读持久化目录里的统一档位图，**带降采样防御**。
     *
     * 落盘前已采样到长边 720，正常文件解码后约 2 MB；但防御目录里出现异常大文件
     * （例如旧版本数据或手改），仍按 bounds 检查 + 采样解码，与 readThumbFromDisk 同模式。
     */
    private fun readPersistBitmap(context: Context, file: File): Bitmap? = try {
        if (file.isFile && file.length() > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) {
                null
            } else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = ComfyUIClient.computeSampleSize(longest, GALLERY_DECODE_DIMENSION)
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
            }
        } else null
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /**
     * 持久化目录（双目录合计）到顶后删最旧的，见 [MAX_PERSIST_DISK_BYTES]。
     */
    private fun trimPersistDisk(context: Context) {
        try {
            val storeDir = File(context.filesDir, PERSIST_STORE_DIR)
            val thumbDir = File(context.filesDir, PERSIST_THUMB_DIR)
            val allFiles = (storeDir.listFiles()?.map { it } ?: emptyList()) +
                (thumbDir.listFiles()?.map { it } ?: emptyList())
            if (allFiles.isEmpty()) return
            val total = allFiles.sumOf { it.length() }
            if (total <= MAX_PERSIST_DISK_BYTES) return
            // 按 lastModified 升序（最旧在前）交给纯函数挑选，再逐个删
            val ordered = allFiles.sortedBy { it.lastModified() }
                .map { it.name to it.length() }
            val toDelete = pickEvictionCandidates(ordered, total, MAX_PERSIST_DISK_BYTES)
            val deleted = allFiles.filter { it.name in toDelete.toSet() }.count { it.delete() }
            DebugLogger.d(TAG, "Persistent gallery cache trimmed: deleted $deleted files")
        } catch (_: Exception) {
            // 清理失败不影响使用
        }
    }

    /**
     * 删掉某个键在两个持久化目录里的全部档位文件（图片统一档 + 视频首帧）。
     * 删图连带清缓存（规格 B3）的磁盘部分；**只删新目录，旧 cacheDir 保持现状**。
     */
    private fun deletePersistFiles(context: Context, key: MediaCacheKey) {
        try {
            persistStoreFile(context, key).delete()
            persistThumbFile(context, key).delete()
        } catch (_: Exception) {
            // 忽略删除失败
        }
    }

    private suspend fun fetchImageBitmap(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        client: ComfyUIClient,
        priority: Int
    ): Bitmap? {
        val bitmap = suspendCancellableCoroutine { continuation ->
            client.fetchImage(key.filename, subfolder, type) { bmp, failureType ->
                if (bmp == null && failureType == ConnectionFailure.FILE_MISSING) {
                    markFileMissing(key)
                }
                continuation.resume(bmp)
            }
        }

        bitmap?.let {
            cacheFullBitmap(key.keyString, it, priority)
        }

        return bitmap
    }

    private suspend fun fetchVideoBitmap(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        client: ComfyUIClient,
        context: Context,
        priority: Int
    ): Bitmap? {
        val keyStr = key.keyString

        // Check if thumbnail is already cached - avoid redundant extraction
        bitmapCache.get(keyStr)?.let { return it }

        // Check if we have video bytes cached
        var videoBytes = videoCache.get(keyStr)

        if (videoBytes == null) {
            // Fetch video bytes
            videoBytes = suspendCancellableCoroutine { continuation ->
                client.fetchVideo(key.filename, subfolder, type) { bytes, _ ->
                    continuation.resume(bytes)
                }
            }

            // Cache the video bytes
            videoBytes?.let { videoCache.put(keyStr, it, PriorityLruCache.PRIORITY_DEFAULT) }
        }

        if (videoBytes == null) return null

        // Double-check bitmap cache (another thread might have extracted while we fetched)
        bitmapCache.get(keyStr)?.let { return it }

        // Extract and cache thumbnail and dimensions
        val (dimensions, thumbnail) = extractVideoData(keyStr, videoBytes, context)
        dimensions?.let { videoDimensionsCache[keyStr] = it }
        thumbnail?.let { bitmapCache.put(keyStr, it, priority) }

        return thumbnail
    }

    // Disk-first versions of fetch methods

    private suspend fun fetchImageBitmapDiskFirst(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        client: ComfyUIClient,
        context: Context
    ): Bitmap? {
        val bitmap = suspendCancellableCoroutine { continuation ->
            client.fetchImage(key.filename, subfolder, type) { bmp, failureType ->
                if (bmp == null && failureType == ConnectionFailure.FILE_MISSING) {
                    markFileMissing(key)
                }
                continuation.resume(bmp)
            }
        }

        // Write to disk cache (don't keep in memory)
        bitmap?.let { writeBitmapToDisk(context, key, it) }

        return bitmap
    }

    private suspend fun fetchVideoBitmapDiskFirst(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        client: ComfyUIClient,
        context: Context
    ): Bitmap? {
        val keyStr = key.keyString

        // Fetch video bytes
        val videoBytes = suspendCancellableCoroutine<ByteArray?> { continuation ->
            client.fetchVideo(key.filename, subfolder, type) { bytes, _ ->
                continuation.resume(bytes)
            }
        } ?: return null

        // Write video to disk cache
        writeVideoToDisk(context, key, videoBytes)

        // Extract and return thumbnail (don't cache dimensions in memory for disk-first)
        val (_, thumbnail) = extractVideoData(keyStr, videoBytes, context)
        return thumbnail
    }

    // Full-size image operations

    /**
     * Fetch full-size image with caching.
     * In disk-first mode, checks disk cache first then fetches from server and saves to disk.
     * 离线态（强制离线 / 自动离线）只读磁盘缓存，不发起任何网络请求。
     *
     * @param priority Cache priority level (default: PRIORITY_CURRENT for direct requests)
     */
    suspend fun fetchImage(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        priority: Int = PriorityLruCache.PRIORITY_CURRENT
    ): Bitmap? {
        val context = applicationContext ?: return null
        val client = comfyUIClient  // May be null in offline mode
        // 离线态只读缓存，不发请求（离线时客户端仍在，见 fetchBitmap 的说明）
        val offlineReadOnly = client == null || ConnectionManager.isOffline

        if (isMemoryFirstMode) {
            // Memory-first: check memory cache first
            bitmapCache.get(key.keyString)?.let {
                DebugLogger.d(TAG, "fetchImage: ${key.keyString} from memory cache -> HIT")
                return it
            }

            // 离线：回落到磁盘缓存（只读）
            if (offlineReadOnly) {
                return withContext(Dispatchers.IO) {
                    val bitmap = readBitmapFromDisk(context, key)
                    bitmap?.let { cacheFullBitmap(key.keyString, it, priority) }
                    DebugLogger.d(TAG, "fetchImage: ${key.keyString} offline -> disk cache ${if (bitmap != null) "HIT" else "MISS"}")
                    bitmap
                }
            }

            return withContext(Dispatchers.IO) {
                val bitmap = suspendCancellableCoroutine { continuation ->
                    client!!.fetchImage(key.filename, subfolder, type) { bmp, failureType ->
                        if (bmp == null && failureType == ConnectionFailure.FILE_MISSING) {
                            markFileMissing(key)
                        }
                        continuation.resume(bmp)
                    }
                }

                bitmap?.let { cacheFullBitmap(key.keyString, it, priority) }
                bitmap
            }
        } else {
            // Disk-first: check disk cache first, fetch and save to disk if not found
            return withContext(Dispatchers.IO) {
                readBitmapFromDisk(context, key)?.let {
                    DebugLogger.d(TAG, "fetchImage: ${key.keyString} from disk cache -> HIT")
                    return@withContext it
                }

                // 离线态：磁盘缓存就是全部内容，不得再去请求服务器
                if (offlineReadOnly) {
                    DebugLogger.d(TAG, "fetchImage: ${key.keyString} -> offline, disk cache MISS")
                    return@withContext null
                }

                DebugLogger.d(TAG, "fetchImage: ${key.keyString} -> fetching from server")
                val bitmap = suspendCancellableCoroutine { continuation ->
                    client!!.fetchImage(key.filename, subfolder, type) { bmp, failureType ->
                        if (bmp == null && failureType == ConnectionFailure.FILE_MISSING) {
                            markFileMissing(key)
                        }
                        continuation.resume(bmp)
                    }
                }

                bitmap?.let { writeBitmapToDisk(context, key, it) }
                bitmap
            }
        }
    }

    // Video operations

    /**
     * Get video bytes from memory cache (sync).
     */
    fun getVideoBytes(key: MediaCacheKey): ByteArray? {
        return videoCache.get(key.keyString)
    }

    /**
     * Fetch video bytes with caching.
     * In disk-first mode, checks disk cache first then fetches from server and saves to disk.
     * 离线态（强制离线 / 自动离线）只读磁盘缓存，不发起任何网络请求。
     */
    suspend fun fetchVideoBytes(
        key: MediaCacheKey,
        subfolder: String,
        type: String
    ): ByteArray? {
        val context = applicationContext ?: return null
        val client = comfyUIClient  // May be null in offline mode
        // 离线态只读缓存，不发请求（离线时客户端仍在，见 fetchBitmap 的说明）
        val offlineReadOnly = client == null || ConnectionManager.isOffline

        if (isMemoryFirstMode) {
            // Memory-first: check memory cache first
            videoCache.get(key.keyString)?.let {
                DebugLogger.d(TAG, "fetchVideoBytes: ${key.keyString} from memory cache -> HIT")
                return it
            }

            // 离线：回落到磁盘缓存（只读）
            if (offlineReadOnly) {
                return withContext(Dispatchers.IO) {
                    val bytes = readVideoBytesFromDisk(context, key)
                    bytes?.let { videoCache.put(key.keyString, it, PriorityLruCache.PRIORITY_DEFAULT) }
                    DebugLogger.d(TAG, "fetchVideoBytes: ${key.keyString} offline -> disk cache ${if (bytes != null) "HIT" else "MISS"}")
                    bytes
                }
            }

            return withContext(Dispatchers.IO) {
                val bytes = suspendCancellableCoroutine { continuation ->
                    client!!.fetchVideo(key.filename, subfolder, type) { videoBytes, _ ->
                        continuation.resume(videoBytes)
                    }
                }

                bytes?.let { videoCache.put(key.keyString, it, PriorityLruCache.PRIORITY_DEFAULT) }
                bytes
            }
        } else {
            // Disk-first: check disk cache first, fetch and save to disk if not found
            return withContext(Dispatchers.IO) {
                readVideoBytesFromDisk(context, key)?.let {
                    DebugLogger.d(TAG, "fetchVideoBytes: ${key.keyString} from disk cache -> HIT")
                    return@withContext it
                }

                // 离线态：磁盘缓存就是全部内容，不得再去请求服务器
                if (offlineReadOnly) {
                    DebugLogger.d(TAG, "fetchVideoBytes: ${key.keyString} -> offline, disk cache MISS")
                    return@withContext null
                }

                DebugLogger.d(TAG, "fetchVideoBytes: ${key.keyString} -> fetching from server")
                val bytes = suspendCancellableCoroutine { continuation ->
                    client!!.fetchVideo(key.filename, subfolder, type) { videoBytes, _ ->
                        continuation.resume(videoBytes)
                    }
                }

                bytes?.let { writeVideoToDisk(context, key, it) }
                bytes
            }
        }
    }

    /**
     * Get cached video URI if available (sync).
     * Returns null if URI not yet created - call getVideoUri to create it.
     */
    fun getCachedVideoUri(key: MediaCacheKey): Uri? {
        return videoUriCache[key.keyString]
    }

    /**
     * Get cached video dimensions if available (sync).
     * Used by VideoPlayer to avoid repeated MediaMetadataRetriever calls.
     */
    fun getVideoDimensions(key: MediaCacheKey): VideoDimensions? {
        return videoDimensionsCache[key.keyString]
    }

    /**
     * Get video URI for ExoPlayer playback.
     * In memory-first mode, creates a temp file from cached bytes.
     * In disk-first mode, returns URI directly to the cached file.
     * Returns null if bytes not cached - call fetchVideoBytes first.
     */
    suspend fun getVideoUri(key: MediaCacheKey, context: Context): Uri? {
        val keyStr = key.keyString

        if (isMemoryFirstMode) {
            // Memory-first: return cached URI if available
            videoUriCache[keyStr]?.let { return it }

            // 离线时内存里没有就回落到磁盘缓存（只读）：否则内存优先模式下离线视频永远播不出来。
            // 在线时不回落——内存未命中应当去服务器取，不能用可能过期的磁盘副本。
            val bytes = videoCache.get(keyStr) ?: if (ConnectionManager.isOffline) {
                withContext(Dispatchers.IO) {
                    readVideoBytesFromDisk(context, key)?.also {
                        videoCache.put(keyStr, it, PriorityLruCache.PRIORITY_DEFAULT)
                    }
                }
            } else null
            if (bytes == null) return null
            return withContext(Dispatchers.IO) {
                try {
                    // Create temp file for playback
                    val tempFile = File(context.cacheDir, "playback_${keyStr.hashCode()}.mp4")
                    tempFile.writeBytes(bytes)

                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        tempFile
                    )

                    // Cache the URI for future use
                    videoUriCache[keyStr] = uri
                    uri
                } catch (_: Exception) {
                    null
                }
            }
        } else {
            // Disk-first: return URI directly to the cached file
            return withContext(Dispatchers.IO) {
                try {
                    val file = getDiskCacheFile(context, key, true)
                    if (file.exists()) {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Fetch video and return URI for playback.
     * Caches bytes, extracts metadata, and creates temp file.
     */
    suspend fun fetchVideoUri(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        context: Context
    ): Uri? {
        val keyStr = key.keyString

        // Ensure bytes are cached
        val bytes = fetchVideoBytes(key, subfolder, type) ?: return null

        // Extract and cache dimensions/thumbnail if not already cached
        if (!videoDimensionsCache.containsKey(keyStr)) {
            withContext(Dispatchers.IO) {
                val (dimensions, thumbnail) = extractVideoData(keyStr, bytes, context)
                dimensions?.let { videoDimensionsCache[keyStr] = it }
                thumbnail?.let { bitmapCache.put(keyStr, it, PriorityLruCache.PRIORITY_DEFAULT) }
            }
        }

        return getVideoUri(key, context)
    }

    // Prefetching

    // Track completion callbacks for in-progress fetches
    private val completionCallbacks = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    /**
     * Request prefetching of adjacent items.
     * Call this when the user navigates to a new item.
     * Does not clear the queue - instead reprioritizes existing requests.
     */
    fun prefetchAround(
        currentIndex: Int,
        allItems: List<PrefetchItem>
    ) {
        // Build set of keys that should be prefetched with their priorities
        val desiredPrefetches = mutableMapOf<String, Pair<PrefetchItem, PrefetchPriority>>()

        val indicesToPrefetch = listOf(
            currentIndex - 1 to PrefetchPriority.ADJACENT,
            currentIndex + 1 to PrefetchPriority.ADJACENT,
            currentIndex - 2 to PrefetchPriority.NEARBY,
            currentIndex + 2 to PrefetchPriority.NEARBY,
            currentIndex - 3 to PrefetchPriority.DISTANT,
            currentIndex + 3 to PrefetchPriority.DISTANT
        )

        for ((index, priority) in indicesToPrefetch) {
            if (index in allItems.indices) {
                val item = allItems[index]
                val keyStr = item.key.keyString
                // Skip if already cached
                if (!isCached(item.key, item.isVideo)) {
                    desiredPrefetches[keyStr] = item to priority
                }
            }
        }

        // Remove outdated requests from queue (items no longer in desired range)
        val iterator = prefetchQueue.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            val keyStr = request.key.keyString
            if (!desiredPrefetches.containsKey(keyStr)) {
                iterator.remove()
            } else {
                // Item already queued - remove from desired to avoid re-adding
                desiredPrefetches.remove(keyStr)
            }
        }

        // Add new prefetch requests
        for ((keyStr, itemAndPriority) in desiredPrefetches) {
            val (item, priority) = itemAndPriority
            // Skip if already in progress
            if (inProgressKeys.contains(keyStr)) {
                continue
            }

            prefetchQueue.offer(PrefetchRequest(
                key = item.key,
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type,
                priority = priority
            ))
        }
    }

    /**
     * Check if a prefetch is currently in progress for the given key.
     */
    fun isPrefetchInProgress(key: MediaCacheKey): Boolean {
        return inProgressKeys.contains(key.keyString)
    }

    /**
     * Wait for an in-progress prefetch to complete.
     * Returns immediately if no prefetch is in progress.
     */
    suspend fun awaitPrefetchCompletion(key: MediaCacheKey) {
        val keyStr = key.keyString
        if (!inProgressKeys.contains(keyStr)) return

        suspendCancellableCoroutine { continuation ->
            val callbacks = completionCallbacks.getOrPut(keyStr) { mutableListOf() }
            val callback: () -> Unit = { continuation.resume(Unit) }
            synchronized(callbacks) {
                // Double-check in case it completed while we were setting up
                if (!inProgressKeys.contains(keyStr)) {
                    continuation.resume(Unit)
                } else {
                    callbacks.add(callback)
                }
            }

            continuation.invokeOnCancellation {
                synchronized(callbacks) {
                    callbacks.remove(callback)
                }
            }
        }
    }

    /**
     * Data class for prefetch item info
     */
    data class PrefetchItem(
        val key: MediaCacheKey,
        val isVideo: Boolean,
        val subfolder: String,
        val type: String
    )

    private fun isCached(key: MediaCacheKey, isVideo: Boolean): Boolean {
        if (isMemoryFirstMode) {
            // Memory-first: check in-memory cache
            val keyStr = key.keyString
            return if (isVideo) {
                videoCache.get(keyStr) != null
            } else {
                bitmapCache.get(keyStr) != null
            }
        } else {
            // Disk-first: check disk cache
            val context = applicationContext ?: return false
            return existsInDiskCache(context, key, isVideo)
        }
    }

    private fun startPrefetchWorkers() {
        repeat(MAX_CONCURRENT_PREFETCH) {
            scope.launch {
                while (isActive) {
                    val request = prefetchQueue.poll()

                    if (request != null) {
                        val keyStr = request.key.keyString

                        if (!isCached(request.key, request.isVideo) &&
                            inProgressKeys.add(keyStr)) {

                            try {
                                // Convert PrefetchPriority to PriorityLruCache priority
                                val cachePriority = when (request.priority) {
                                    PrefetchPriority.ADJACENT -> PriorityLruCache.PRIORITY_ADJACENT
                                    PrefetchPriority.NEARBY -> PriorityLruCache.PRIORITY_NEARBY
                                    PrefetchPriority.DISTANT -> PriorityLruCache.PRIORITY_DISTANT
                                }

                                if (request.isVideo) {
                                    // Fetch bytes and create URI so video is ready to play
                                    val context = applicationContext
                                    if (context != null) {
                                        fetchVideoUri(request.key, request.subfolder, request.type, context)
                                    } else {
                                        fetchVideoBytes(request.key, request.subfolder, request.type)
                                    }
                                } else {
                                    fetchImage(request.key, request.subfolder, request.type, cachePriority)
                                }
                            } finally {
                                inProgressKeys.remove(keyStr)
                                notifyPrefetchComplete(keyStr)
                            }
                        }
                    } else {
                        delay(100) // Wait before checking again
                    }
                }
            }
        }
    }

    /**
     * Notify all waiting callbacks that a prefetch has completed.
     */
    private fun notifyPrefetchComplete(keyStr: String) {
        val callbacks = completionCallbacks.remove(keyStr) ?: return
        synchronized(callbacks) {
            callbacks.forEach { it.invoke() }
        }
    }

    // Cache management

    /**
     * Remove item from all caches (after deletion).
     * In disk-first mode, also removes from disk cache.
     */
    fun evict(key: MediaCacheKey) {
        val keyStr = key.keyString
        bitmapCache.remove(keyStr)
        videoCache.remove(keyStr)
        videoUriCache.remove(keyStr)
        videoDimensionsCache.remove(keyStr)

        // v4.16.1 删图连带清（规格 B3）：缩略图内存缓存此前永不清（删图后列表缩略图仍挂着），
        // 与 filesDir 新目录里的统一档/首帧文件一起清掉。**旧 cacheDir 维持原状**（见下方注释）
        thumbnailCache.remove(keyStr)
        applicationContext?.let { context -> deletePersistFiles(context, key) }

        // In disk-first mode, also remove from disk
        if (!isMemoryFirstMode) {
            applicationContext?.let { context ->
                try {
                    getDiskCacheFile(context, key, false).delete()
                    getDiskCacheFile(context, key, true).delete()
                } catch (_: Exception) {
                    // Ignore deletion failures
                }
            }
        }
    }

    /**
     * Clear all caches (memory and disk).
     */
    fun clearAll() {
        bitmapCache.evictAll()
        videoCache.evictAll()
        videoUriCache.clear()
        videoDimensionsCache.clear()
        prefetchQueue.clear()
        inProgressKeys.clear()
        completionCallbacks.clear()

        // v4.16.1：缩略图内存缓存一并清（磁盘清了内存还挂着旧图，用户会以为"清缓存无效"）。
        // filesDir 新目录也一起清 —— 它与 gallery_cache 同属"图库媒体缓存"，用户在设置页
        // 点"清缓存"的预期就是全部重拉；旧 gallery_thumb 维持现状不清（历史行为，不动）
        thumbnailCache.evictAll()
        applicationContext?.let { context ->
            try {
                File(context.filesDir, PERSIST_STORE_DIR).deleteRecursively()
                File(context.filesDir, PERSIST_THUMB_DIR).deleteRecursively()
            } catch (_: Exception) {
                // Ignore deletion failures
            }
        }

        // Also clear disk cache
        applicationContext?.let { context ->
            try {
                getDiskCacheDir(context).deleteRecursively()
            } catch (_: Exception) {
                // Ignore deletion failures
            }
        }
    }

    /**
     * Clear bitmap and video thumbnail caches for refresh.
     * This forces thumbnails to be re-fetched from the server.
     * In disk-first mode, also clears the disk cache.
     */
    fun clearForRefresh() {
        bitmapCache.evictAll()
        videoCache.evictAll()
        videoDimensionsCache.clear()
        // 下拉刷新 = "重新向服务器要一遍"，这三样是本次会话内学到的"知识"，刷新即过期（H4）：
        // - thumbnailCache 不清 → 老缩略图直接命中内存，服务器上的变化永远看不见；
        // - missingKeys 不清 → 已确认 404 的条目在 fetchThumbnail/fetchViewerImage 里被短路，
        //   用户把文件放回服务器后下拉刷新也无法恢复；
        // - oversizedKeys 不清 → 同理，"超过下载上限"的判定是按当时的原图算的，
        //   服务器上换了文件之后旧判定不成立。
        // 三样必须一起清：只清缩略图不清标记，404 条目刷新后依旧加载失败（只清标记不清
        // 缩略图则更糟——内存里还有旧图可显示，用户会以为刷新根本没发生）。
        thumbnailCache.evictAll()
        missingKeys.clear()
        oversizedKeys.clear()
        prefetchQueue.clear()
        inProgressKeys.clear()
        completionCallbacks.clear()

        // In disk-first mode, also clear disk cache
        if (!isMemoryFirstMode) {
            applicationContext?.let { context ->
                try {
                    getDiskCacheDir(context).deleteRecursively()
                } catch (_: Exception) {
                    // Ignore deletion failures
                }
            }
        }
    }

    /**
     * Reset cache (on disconnect / server switch / logout).
     * Called by ConnectionManager (invalidateAll).
     * Only clears memory caches - disk cache is preserved for offline mode.
     * Note: isMemoryFirstMode is NOT reset - it's a user preference that persists across sessions.
     */
    fun reset() {
        // Only clear memory caches, NOT disk cache (needed for offline mode)
        bitmapCache.evictAll()
        videoCache.evictAll()
        videoUriCache.clear()
        videoDimensionsCache.clear()
        prefetchQueue.clear()
        inProgressKeys.clear()
        completionCallbacks.clear()

        // **不清 applicationContext**：它是进程级 application context（与进程同生命周期，
        // 不存在泄漏），而所有读取入口（fetchBitmap / fetchImage / fetchVideoBytes …）
        // 在它为 null 时直接 return null。
        //
        // 清掉它等于把"能不能读缓存"绑死在 Activity 重建上——因为补回它的地方只有
        // 三个 Activity 的 onCreate 与 loadFromOfflineCache()。而**不重建 Activity**
        // 的恢复路径（离线看门狗自动回在线、关闭强制离线）恰好都会走 connect()
        // → invalidateAll() → reset()：此后停留在画廊/查看器会一直空白，直到退出重进。
        // 恢复路径上的重建动作见 ConnectionManager.resumeOnlineInternal()。
        //
        // 预取 worker 同理：它们跑在进程级 scope 上、从不取消，所以这里也不能把
        // isPrefetchWorkersStarted 置 false（那只会让下次 ensureInitialized 再起一组重复 worker）。
        //
        // Don't reset isMemoryFirstMode - it's a user preference loaded from AppSettings
        // Don't delete disk cache - it's needed for offline mode
    }

    // Utilities

    /**
     * Extract video dimensions and thumbnail from video bytes.
     * Uses MediaMetadataRetriever which requires a file, so creates a temp file.
     * Returns Pair of (dimensions, thumbnail) - either may be null on error.
     *
     * @param keyStr Unique key string used for temp file naming to prevent race conditions
     * @param videoBytes The video bytes to extract from
     * @param context Android context for cache directory access
     */
    private fun extractVideoData(keyStr: String, videoBytes: ByteArray, context: Context): Pair<VideoDimensions?, Bitmap?> {
        // Use key hash + thread ID for unique temp filename to prevent race conditions
        @Suppress("DEPRECATION")
        val uniqueId = "${keyStr.hashCode()}_${Thread.currentThread().id}"
        val tempFile = File(context.cacheDir, "temp_meta_$uniqueId.mp4")
        var retriever: MediaMetadataRetriever? = null
        return try {
            tempFile.writeBytes(videoBytes)
            val r = MediaMetadataRetriever()
            retriever = r
            r.setDataSource(tempFile.absolutePath)
            val thumbnail = r.getFrameAtTime(0)
            val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: thumbnail?.width ?: 0
            val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: thumbnail?.height ?: 0
            Pair(VideoDimensions(width, height), thumbnail)
        } catch (_: Exception) {
            Pair(null, null)
        } finally {
            // native 资源必须在 finally 释放：setDataSource / getFrameAtTime / extractMetadata
            // 抛异常（下载字节被截断或损坏）时也要走这里，否则 retriever 泄漏
            runCatching { retriever?.release() }
            tempFile.delete()
        }
    }
}
