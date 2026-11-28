package sh.hnet.comfychair.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
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
import sh.hnet.comfychair.model.GalleryItem
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.ExifOrientation
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Stable cache key for media items.
 *
 * 键由 **serverId + type + promptId + filename** 四段构成（P2-2）：
 * - [serverId]：MediaCache 是进程级单例，所有服务器共用同一份内存/磁盘缓存。
 *   不带服务器身份时，A 服务器与 B 服务器同名的产物会互相命中——典型的"换服务器后
 *   看到的还是上一台的图"。
 * - [type]：ComfyUI 的 `output` 与 `temp` 是两个独立目录，同名文件完全可能同时存在
 *   （例如 `ComfyUI_00001_.png`），只按 (promptId, filename) 会互撞。
 * - [promptId] + [filename]：在一次生成里区分具体产物（批量出图共用 promptId）。
 *
 * subfolder 没有进键：它参与 /view 的 URL 参数，但不参与产物身份；同一个
 * (serverId, type, promptId, filename) 在不同 subfolder 下重复出现不是 ComfyUI 的正常
 * 输出形态，为了不让键长度和磁盘文件名膨胀，这里只加 type + serverId。
 *
 * [keyString] 直接用作内存缓存键与磁盘文件名，编码规则见 [encodeKeyString] /
 * [parseKeyString]：新格式带 `v2_` 前缀、前三段转义 `_` 与 `%`，filename 原样放在最后，
 * 因此能被 [listCachedKeys] 无损反解回 key。旧版本写下的 `{promptId}_{filename}`
 * 仍按旧规则解析（serverId 为空、type 按 output），保证历史磁盘文件的恢复逻辑不中断。
 */
data class MediaCacheKey(
    val promptId: String,
    val filename: String,
    val type: String,
    val serverId: String
) {
    val keyString: String get() = encodeKeyString(this)

    override fun toString(): String = keyString

    companion object {
        /** 新键格式前缀：promptId 是 UUID，不会以 `v2_` 开头，与旧格式天然区分。 */
        private const val NEW_KEY_PREFIX = "v2_"

        /** serverId 缺失（旧格式文件 / 未连接）时的占位符；不能用空串——编码后会出现连续下划线。 */
        private const val NO_SERVER = "-"

        /** 旧格式 `{promptId}_{filename}` 的默认产物类型。 */
        const val DEFAULT_TYPE = "output"

        /**
         * 生产路径统一入口：serverId 取**当前连接服务器**。
         *
         * 离线态下 `currentServerId` 仍是上次连上的那台（见 ConnectionManager 的
         * OfflineConnected），所以离线读缓存时键仍能对上。
         */
        fun of(
            promptId: String,
            filename: String,
            type: String,
            serverId: String? = ConnectionManager.currentServerId
        ): MediaCacheKey = MediaCacheKey(
            promptId = promptId,
            filename = filename,
            type = normalizeType(type),
            serverId = serverId.orEmpty()
        )

        /** type 为空时按 ComfyUI 默认的 output 处理（/history 里个别节点的 type 字段缺失）。 */
        internal fun normalizeType(type: String): String =
            type.trim().ifBlank { DEFAULT_TYPE }

        /** 前三段里可能出现的 `_` / `%` 转义，保证 `split('_', limit = 4)` 可以无损反解。 */
        private fun escapePart(value: String): String =
            value.replace("%", "%25").replace("_", "%5F")

        private fun unescapePart(value: String): String =
            value.replace("%5F", "_").replace("%25", "%")

        internal fun encodeKeyString(key: MediaCacheKey): String {
            val server = key.serverId.ifEmpty { NO_SERVER }
            return NEW_KEY_PREFIX +
                escapePart(server) + "_" +
                escapePart(normalizeType(key.type)) + "_" +
                escapePart(key.promptId) + "_" +
                key.filename
        }

        /**
         * 从磁盘文件名（已去掉扩展名）反解缓存键。
         *
         * 新格式：`v2_{serverId}_{type}_{promptId}_{filename}`（filename 允许含下划线）。
         * 旧格式：`{promptId}_{filename}`（promptId 是无下划线的 UUID，按第一个下划线切）。
         *
         * 解析失败返回 null，由调用方跳过该文件。
         */
        internal fun parseKeyString(fileNameStem: String): MediaCacheKey? {
            if (fileNameStem.startsWith(NEW_KEY_PREFIX)) {
                val rest = fileNameStem.substring(NEW_KEY_PREFIX.length)
                val parts = rest.split('_', limit = 4)
                if (parts.size < 4) return null
                val serverId = unescapePart(parts[0]).let { if (it == NO_SERVER) "" else it }
                val type = normalizeType(unescapePart(parts[1]))
                val promptId = unescapePart(parts[2])
                val filename = parts[3]
                if (promptId.isEmpty() || filename.isEmpty()) return null
                return MediaCacheKey(promptId, filename, type, serverId)
            }

            val separator = fileNameStem.indexOf('_')
            if (separator <= 0 || separator == fileNameStem.length - 1) return null
            return MediaCacheKey(
                promptId = fileNameStem.substring(0, separator),
                filename = fileNameStem.substring(separator + 1),
                type = DEFAULT_TYPE,
                serverId = ""
            )
        }
    }
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
 *
 * @param thumbnailOnly true = 只暖**缩略图**档（图库网格用）；false = 拉原图（查看器用）。
 *   这一位不能省：同一个队列同时服务两个诉求，网格只显示 720 档，按原图预取是
 *   纯浪费（3 路 6144 px 下载 + 解码会在滑动时和滚动抢 CPU/带宽，见 P2 执行书 4.2）。
 */
data class PrefetchRequest(
    val key: MediaCacheKey,
    val isVideo: Boolean,
    val subfolder: String,
    val type: String,
    val priority: PrefetchPriority,
    val thumbnailOnly: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) : Comparable<PrefetchRequest> {
    override fun compareTo(other: PrefetchRequest): Int {
        val priorityCompare = priority.value.compareTo(other.priority.value)
        return if (priorityCompare != 0) priorityCompare
        else timestamp.compareTo(other.timestamp)
    }
}

/**
 * GalleryItem → 缓存键（B1 自 viewmodel.GalleryItem.toCacheKey() 外移；原成员 KDoc 的
 * 「type 与 serverId 进键防串台」语义见 [MediaCacheKey] 类级 KDoc 的 P2-2 记载）。
 * 放 cache 包而非 model：MediaCacheKey 是缓存域概念，model 层不得依赖 cache。
 */
fun GalleryItem.toCacheKey(): MediaCacheKey = MediaCacheKey.of(promptId, filename, type)

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

    /** 缩略图内存预算占堆的比例分母（P2-C：1/8，旧值 1/16） */
    private const val THUMBNAIL_HEAP_DIVISOR = 8

    /** 缩略图内存预算地板（KB）：小堆设备也不至于只放得下 4 张（P2-C，旧值 8 MB） */
    private const val THUMBNAIL_MIN_CACHE_KB = 16 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Accessor for shared client from ConnectionManager
    // B4.2-A：类型收窄到 MediaGateway 窄接口 —— 缓存层只依赖 fetchImage/fetchVideo/viewUrl
    // 三个签名，不再看见连接层客户端的全部内部（离线闸门保持原位，行为零变化）
    private val comfyUIClient: MediaGateway?
        get() = ConnectionManager.clientOrNull

    // Context for temp file operations (set once when first needed)
    // 多个入口（两个 Activity 的 onCreate 与 ConnectionManager 两处）会在主线程/协程线程
    // 首次调用 ensureInitialized：读侧必须 volatile，否则可能长时间读到 null 而跳过缓存读写
    @Volatile
    private var applicationContext: Context? = null

    /**
     * 预取 worker 组是否已启动。
     *
     * 必须用 CAS 而不是 `if (!flag) { start(); flag = true }`：ensureInitialized 的调用点
     * 分散在主线程（Activity.onCreate）与协程线程（ConnectionManager），check-then-act 会让
     * 两个线程同时走进 startPrefetchWorkers，起两组 worker 抢同一个 [prefetchQueue]
     * ——重复下载、[inProgressKeys] 互相打架。
     * 进程内一次性：[reset] 刻意不复位（见那里的注释），否则每次断线重连都多一组 worker。
     */
    private val isPrefetchWorkersStarted = AtomicBoolean(false)

    // Caching mode - true for memory-first (default), false for disk-first
    // 主线程写（MainContainerActivity.onCreate / ConnectionManager），
    // 读取发生在 Dispatchers.Default 的预取 worker 与各 suspend 入口（见 fetchBitmap /
    // getVideoUri 等处的 if (isMemoryFirstMode)）：必须 volatile，否则 worker 可能长时间
    // 按旧模式分流（用户已切磁盘优先，预取仍走内存/临时文件路径）
    @Volatile
    private var isMemoryFirstMode = true

    // Memory budget: 1/3 of max heap for bitmaps (full-res images), 1/8 for video bytes
    // Bitmaps are large (4MB+ per 1024x1024 image), so we need generous cache for smooth scrolling
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val bitmapCacheSize = maxMemoryKb / 3
    private val videoCacheSize = maxMemoryKb / 8

    /**
     * 缩略图内存预算（P2-C）：堆的 1/8，下限 [THUMBNAIL_MIN_CACHE_KB]。
     *
     * 以 [maxMemoryKb] 为基准而不是写死张数：720 档单张字节数与图片来源有关
     * （方图 2.07 MB，竖长图按长边等比更小），按堆比例算对两种图都成立。
     * 256 MB 堆 → 32 MB（≈15 张），512 MB 堆 → 64 MB（≈30 张）；
     * 128 MB 的小堆设备被地板托到 16 MB（≈8 张，仍比旧值 8 MB 翻一倍）。
     */
    private val thumbnailCacheSize = (maxMemoryKb / THUMBNAIL_HEAP_DIVISOR)
        .coerceAtLeast(THUMBNAIL_MIN_CACHE_KB)

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
     * 缩略图专用的小缓存（预算约为原图缓存的 1/3）。
     *
     * 为什么与原图分开：列表里的缩略图只有几十 KB ~ 2 MB，原图动辄几 MB 到上百 MB。
     * 共用一个 LRU 时，只要用户点开一张大图，整屏的缩略图就被挤出去，
     * 滚回列表又要全部重拉 —— 这是"看过一张大图之后列表也变慢"的来源之一。
     *
     * P2-C：预算从 `heap/16` 提到 [thumbnailCacheSize]（`heap/8`，下限
     * [THUMBNAIL_MIN_CACHE_KB]）。旧值在 256 MB 堆上只有 16 MB ——
     * 720 档方图 `720×720×4B ≈ 2.07 MB/张`，约 8 张，2 列一屏都不够；
     * 滚过一屏再滚回来就整屏落盘重解码（每次 bounds + 采样两次 BitmapFactory），
     * 期间每格再闪一帧转圈 —— 这是"发粘"的第二条根因。
     * 提高后 256 MB 堆 ≈ 32 MB ≈ 15 张、512 MB 堆 ≈ 64 MB ≈ 30 张；
     * 超出预算时仍由 [PriorityLruCache] 按 LRU 淘汰（缩略图统一 PRIORITY_DEFAULT，
     * 淘汰顺序就是"最久未使用"）。
     */
    private val thumbnailCache = PriorityLruCache<String, Bitmap>(
        maxSize = thumbnailCacheSize,
        sizeOf = { _, bitmap -> bitmap.byteCount / 1024 }
    )

    /**
     * 同一 key 正在飞的缩略图请求（单飞闸门，见 [fetchThumbnail]）。
     *
     * 网格格子的组合与预取 worker 是两条独立的调用链，会同时请求同一张缩略图；
     * 没有这张表时两趟都去抢 3 个网络许可、服务端把同一张图压两遍（现场压缩很贵）。
     */
    private val thumbnailInFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

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

    /**
     * 「源已不存在」的观察者（图库仓储用它把 404 落进索引）。
     *
     * MediaCache 只知道缓存键、不知道 GalleryItem 与本地索引；仓储在构造时注册监听，
     * 收到 [MediaCacheKey] 后找到对应条目、置 `sourceMissing = true` 并持久化。
     * 用 CopyOnWriteArrayList：注册发生在初始化，通知发生在 OkHttp 回调线程，读多写零。
     */
    private val sourceMissingListeners =
        java.util.concurrent.CopyOnWriteArrayList<(MediaCacheKey) -> Unit>()

    /** 注册「/view 404」观察者（幂等：重复注册同一实例不会通知两次）。 */
    fun addSourceMissingListener(listener: (MediaCacheKey) -> Unit) {
        sourceMissingListeners.addIfAbsent(listener)
    }

    /** 注销观察者（目前只有单例仓储注册，保留给测试/生命周期清理）。 */
    fun removeSourceMissingListener(listener: (MediaCacheKey) -> Unit) {
        sourceMissingListeners.remove(listener)
    }

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
     * ② 图库格子与查看器的"文件不存在"占位有了数据源；③ 通知仓储把标记持久化进索引，
     * 重开 App / 刷新图库后仍然是第三态（不会重新白等超时）。
     *
     * 同一键只在**首次**判定时通知：重复 404 不反复写索引。
     */
    private fun markFileMissing(key: MediaCacheKey) {
        if (!missingKeys.add(key.keyString)) return
        for (listener in sourceMissingListeners) {
            try {
                listener(key)
            } catch (e: Exception) {
                // 观察者（仓储/索引写盘）出错不能反过来打断加载链路
                DebugLogger.w(TAG, "sourceMissing listener failed: ${e.message}")
            }
        }
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
            // P2-3：切到磁盘优先后不再使用播放临时文件，已写下的那批一并清掉
            applicationContext?.let { context -> clearPlaybackTempFiles(context) }
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

    /** 旧版本的缓存文件名：`{promptId}_{filename}.{ext}`（那时键还没有 type/serverId）。 */
    private fun legacyCacheFileName(key: MediaCacheKey, ext: String): String =
        MediaCacheFileNames.legacyName(key.promptId, key.filename, ext)

    /**
     * 读取磁盘缓存时的文件名解析（F1：升级兼容，规则见 [MediaCacheFileNames]）。
     *
     * 新键格式带 `v2_{serverId}_{type}_` 前缀，目录名却没变；如果只按新名字找，
     * 升级后既有的全部磁盘缓存都会读不到。因此：新名字命中直接用；否则回退旧名字，
     * 旧文件命中后尽力改名成新名字（懒迁移），失败就继续用旧路径读。
     *
     * 只用于**读**路径；写入一律用 [getDiskCacheFile] 的新名字。
     */
    private fun readableCacheFile(target: File, legacyName: String): File? =
        MediaCacheFileNames.resolveReadable(target, legacyName)

    /** 旧 cacheDir/gallery_cache 的读取解析（图片 png / 视频 mp4） */
    private fun resolveDiskCacheFile(context: Context, key: MediaCacheKey, isVideo: Boolean): File? {
        val ext = if (isVideo) "mp4" else "png"
        return readableCacheFile(
            target = getDiskCacheFile(context, key, isVideo),
            legacyName = legacyCacheFileName(key, ext)
        )
    }

    /** 旧 cacheDir/gallery_thumb 的读取解析（缩略图 jpg） */
    private fun resolveThumbFile(context: Context, key: MediaCacheKey): File? =
        readableCacheFile(
            target = thumbFile(context, key),
            legacyName = legacyCacheFileName(key, "jpg")
        )

    /** filesDir/gallery_store（图片）与 gallery_thumbs（视频首帧）的读取解析 */
    private fun resolvePersistFile(context: Context, key: MediaCacheKey, isVideo: Boolean): File? {
        val target = if (isVideo) persistThumbFile(context, key) else persistStoreFile(context, key)
        return readableCacheFile(target = target, legacyName = legacyCacheFileName(key, "jpg"))
    }

    /**
     * 扫一遍全部磁盘缓存目录，从文件名反推回缓存键（P2-23）。
     *
     * 为什么要扫四个目录：图库的持久化版本（v4.16.1）把图片统一档写在
     * `filesDir/gallery_store`、视频首帧写在 `filesDir/gallery_thumbs`，而旧版
     * 缓存还在 `cacheDir/gallery_cache` 与 `cacheDir/gallery_thumb`。修复前只扫
     * `gallery_cache`，一旦图库索引丢失，最值钱的 720 档持久化图片反而补不回来。
     *
     * 各目录的命名规则一致（`{keyString}.{扩展名}`），只是扩展名不同：
     * - gallery_cache：`.png`（图）/ `.mp4`（视频）
     * - gallery_thumb / gallery_store / gallery_thumbs：`.jpg`
     *
     * 键的格式见 [MediaCacheKey.parseKeyString]：新格式 `v2_{serverId}_{type}_{promptId}_{filename}`，
     * 旧格式 `{promptId}_{filename}`。两种都认。
     *
     * @param serverId 非空时只返回**这台服务器**（以及旧格式无法判定的文件）的键。
     *   不按服务器过滤会把 B 服务器的缓存恢复进 A 服务器的图库，点开就是 404
     *   —— 与 P2-2 要解决的跨服务器串数据是同一类问题。
     */
    fun listCachedKeys(context: Context, serverId: String? = null): List<MediaCacheKey> {
        val dirs = listOf(
            getDiskCacheDir(context),
            getThumbDir(context),
            File(context.filesDir, PERSIST_STORE_DIR),
            File(context.filesDir, PERSIST_THUMB_DIR)
        )
        val result = LinkedHashMap<String, MediaCacheKey>()
        val extensions = listOf(".png", ".mp4", ".jpg", ".jpeg", ".webp")
        for (dir in dirs) {
            val files = try {
                dir.listFiles()
            } catch (_: Exception) {
                null
            } ?: continue
            for (file in files) {
                if (!file.isFile) continue
                val name = file.name
                val ext = extensions.firstOrNull { name.endsWith(it, ignoreCase = true) } ?: continue
                val stem = name.dropLast(ext.length)
                val key = MediaCacheKey.parseKeyString(stem) ?: continue
                if (serverId != null && key.serverId.isNotEmpty() && key.serverId != serverId) continue
                result.putIfAbsent(key.keyString, key)
            }
        }
        // R2-N1：同一条目可能同时有旧名文件（serverId 空）与新名文件（v2_…），
        // keyString 不同但媒体身份相同；必须按 (promptId, filename) 去重，
        // 否则 GalleryRepository 会生成重复 GalleryItem，图库 LazyGrid 的
        // `${promptId}_${filename}` key 撞车会直接抛异常。
        return MediaCacheFileNames.dedupeByIdentity(result.values.toList())
    }

    /**
     * Check if an item exists in disk cache.
     */
    private fun existsInDiskCache(context: Context, key: MediaCacheKey, isVideo: Boolean): Boolean {
        return resolveDiskCacheFile(context, key, isVideo) != null
    }

    /**
     * Read a bitmap from disk cache.
     *
     * 解码后按 EXIF 方向摆正（P1-26）：缓存目录里可能有历史版本直接落盘的 JPEG 原图，
     * BitmapFactory 不读 Orientation，带方向的照片会躺倒。
     *
     * 文件名解析走 [resolveDiskCacheFile]（F1：新键名未命中时回退旧版本文件名）。
     */
    private fun readBitmapFromDisk(context: Context, key: MediaCacheKey): Bitmap? {
        return try {
            val file = resolveDiskCacheFile(context, key, false) ?: return null
            BitmapFactory.decodeFile(file.absolutePath)
                ?.let { ExifOrientation.applyFromFile(file, it) }
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
            resolveDiskCacheFile(context, key, true)?.readBytes()
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
            resolveDiskCacheFile(context, key, isVideo)?.readBytes()
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

    /**
     * 「源已不存在」条目本机副本的清晰度档位（保存文案按实际档位选，R3-3）。
     */
    enum class LocalImageQuality {
        /** 全尺寸副本（内存原图缓存 / 旧磁盘原图缓存）—— 保存的就是原图 */
        FULL_SIZE,

        /** 低清副本（720 统一档 / 旧缩略图）—— 保存的不是原图 */
        LOW_RES
    }

    /**
     * 取本机已有的图片副本（纯本地：内存 → 持久化 720 档 → 旧磁盘缓存 → 旧缩略图）。
     *
     * 专门给「源已不存在」条目用：服务器文件没了，保存/兜底展示只能用本机缓存。
     * 顺序按清晰度优先（原图缓存 > 720 统一档 > 512 缩略图），全部不命中返回 null
     * —— 调用方据此提示「原图已被删除，且本机无缓存」并禁用保存。
     */
    fun getLocalImage(key: MediaCacheKey): Bitmap? = getLocalImageWithQuality(key)?.first

    /**
     * [getLocalImage] 的带档位标注版：位图与查找顺序完全一致，另标出命中的档位。
     *
     * 给「源已不存在」条目的保存路径选文案用：命中 [LocalImageQuality.FULL_SIZE]
     * （查看器看过的原图还在内存缓存 / 升级前旧磁盘原图）时保存的就是原图，
     * 提示不能再写「已保存低清版」。
     */
    fun getLocalImageWithQuality(key: MediaCacheKey): Pair<Bitmap, LocalImageQuality>? {
        val keyStr = key.keyString
        bitmapCache.get(keyStr)?.let { return it to LocalImageQuality.FULL_SIZE }
        val context = applicationContext
        if (context == null) {
            return thumbnailCache.get(keyStr)?.let { it to LocalImageQuality.LOW_RES }
        }
        thumbnailCache.get(keyStr)?.let { return it to LocalImageQuality.LOW_RES }
        // filesDir 持久化统一档（720 长边 JPEG，v4.16.1）
        readPersistBitmap(context, resolvePersistFile(context, key, false))
            ?.let { return it to LocalImageQuality.LOW_RES }
        // 旧 cacheDir 原图 / 旧缩略图（升级兼容）：前者是升级前缓存的全尺寸原图
        readBitmapFromDisk(context, key)?.let { return it to LocalImageQuality.FULL_SIZE }
        return readThumbFromDisk(context, key)?.let { it to LocalImageQuality.LOW_RES }
    }

    /**
     * 本机是否还有这个图片条目的任何副本（不触发网络、不触发解码）。
     *
     * 只做文件存在性判断，供 UI 决定「源已不存在 + 本机无缓存」时是否禁用保存。
     */
    fun hasLocalImage(key: MediaCacheKey, isVideo: Boolean = false): Boolean {
        val keyStr = key.keyString
        if (isVideo) {
            val context = applicationContext ?: return false
            // 与图片分支同口径只查文件存在性：readCachedBytes 会把整段视频字节读进
            // 内存，而这里只需要「在不在」（saveEnabled 在主线程组合期反复调用）
            return videoCache.get(keyStr) != null || resolveDiskCacheFile(context, key, true) != null
        }
        if (bitmapCache.get(keyStr) != null || thumbnailCache.get(keyStr) != null) return true
        val context = applicationContext ?: return false
        return resolvePersistFile(context, key, false) != null ||
            resolveDiskCacheFile(context, key, false) != null ||
            resolveThumbFile(context, key) != null
    }

    /**
     * 取本机已有的视频首帧（纯本地：内存 → filesDir/gallery_thumbs → 旧 cacheDir 视频字节抽帧）。
     *
     * 「源已不存在」的视频条目展示第三态用：原文件已删，只能显示本机缓存的首帧。
     */
    fun getLocalVideoFrame(key: MediaCacheKey): Bitmap? {
        val keyStr = key.keyString
        bitmapCache.get(keyStr)?.let { return it }
        thumbnailCache.get(keyStr)?.let { return it }
        val context = applicationContext ?: return null
        readPersistBitmap(context, resolvePersistFile(context, key, true))?.let { return it }
        return readBitmapOnlyFromDisk(context, key, true)?.also { bits ->
            // 抽帧结果进内存缓存，避免同一格反复解码
            bitmapCache.put(keyStr, bits)
        }
    }

    // Cache of video URIs (lightweight - just stores Uri references)
    private val videoUriCache = ConcurrentHashMap<String, Uri>()

    /**
     * 正在播放的视频 key（F7，见 [registerPlayingVideo]）。
     *
     * 播放临时文件清理必须跳过这些 key：极端时序下视频 A 已 prepare、
     * ExoPlayer 还没真正打开文件，此时为视频 B 生成 URI 会触发清理 ——
     * 没有保护就会把 A 的文件删掉，播放失败且不会自愈。
     * 与 MediaStateHolder.playingVideoBaseNames 同一设计，但这里存的是 **keyString**。
     */
    private val playingVideoKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 登记"这个视频正在播放"（由 VideoPlayer 在组合期注册，页面销毁时注销）。 */
    fun registerPlayingVideo(key: MediaCacheKey) {
        playingVideoKeys.add(key.keyString)
    }

    /** 注销播放登记；之后清理可以正常回收它的临时文件。 */
    fun unregisterPlayingVideo(key: MediaCacheKey) {
        playingVideoKeys.remove(key.keyString)
    }

    /** 正在播放的 keyString → 磁盘临时文件名集合（清理保护的判据）。 */
    private fun protectedPlaybackTempNames(): Set<String> =
        synchronized(playingVideoKeys) {
            playingVideoKeys.mapTo(HashSet()) { MediaCachePlaybackTempPolicy.fileNameFor(it) }
        }

    // Cache of video dimensions for VideoPlayer
    private val videoDimensionsCache = ConcurrentHashMap<String, VideoDimensions>()


    // Prefetching infrastructure
    private val prefetchQueue = PriorityBlockingQueue<PrefetchRequest>()
    private val inProgressKeys = ConcurrentHashMap.newKeySet<String>()

    private const val MAX_CONCURRENT_PREFETCH = 3

    // Active view tracking - MediaViewer takes precedence when open
    // 写方在界面线程（GalleryScreen 切换图库/查看器）；读取走公开访问器 isActiveView，
    // 任何线程都可调用，与 isMemoryFirstMode 同一判定标准加 volatile，
    // 免得将来有人从 worker 里读优先级判定时踩可见性坑
    @Volatile
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
        // 只有抢到 false -> true 的线程启动 worker 组（见 [isPrefetchWorkersStarted]）；
        // 并发调用的其余线程直接返回，不会起第二组
        if (isPrefetchWorkersStarted.compareAndSet(false, true)) {
            startPrefetchWorkers()
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
     * 取本机已有的视频字节（内存 → 旧磁盘缓存），不联网。
     * 「源已不存在」条目的保存/分享走这条；没有缓存返回 null。
     */
    fun getLocalVideoBytes(key: MediaCacheKey): ByteArray? =
        videoCache.get(key.keyString) ?: readCachedBytes(key, isVideo = true)

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
     * @param thumbnailOnly true（默认）= 网格只暖缩略图档（P2-B）。原图不在这里预取 ——
     *   格子显示的就是 720 档，为它去拉 6144 px 原图是纯浪费；原图只在点开查看器时需要，
     *   由 [prefetchOriginal] / [prefetchAround] 负责。
     */
    fun updateGalleryPosition(
        firstVisibleIndex: Int,
        visibleItemCount: Int,
        allItems: List<PrefetchItem>,
        columnsInGrid: Int = 2,
        thumbnailOnly: Boolean = true
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
        prefetchGalleryItems(
            firstVisibleIndex = firstVisibleIndex,
            visibleItemCount = visibleItemCount,
            columnsInGrid = columnsInGrid,
            allItems = allItems,
            thumbnailOnly = thumbnailOnly
        )
    }

    /**
     * Prefetch gallery items around visible area.
     *
     * 可见项本身不排（格子组合时自己会取图，排进来只会让同一张图有两趟请求排队）：
     * 只暖**可见范围之外**的一圈缓冲，这才是滑动时提前就位的部分。
     */
    private fun prefetchGalleryItems(
        firstVisibleIndex: Int,
        visibleItemCount: Int,
        columnsInGrid: Int,
        allItems: List<PrefetchItem>,
        thumbnailOnly: Boolean
    ) {
        val bufferRows = 3
        val bufferItems = bufferRows * columnsInGrid

        val startIndex = (firstVisibleIndex - bufferItems).coerceAtLeast(0)
        val endIndex = (firstVisibleIndex + visibleItemCount + bufferItems).coerceAtMost(allItems.size)
        // 布局还没量出来时（visibleItemCount = 0）不做"排除可见项"处理，
        // 否则第一次上报会把首屏整片漏掉
        val visibleEnd = if (visibleItemCount > 0) firstVisibleIndex + visibleItemCount else startIndex

        // Build set of keys that should be prefetched with their priorities
        val desiredPrefetches = mutableMapOf<String, Pair<PrefetchItem, PrefetchPriority>>()

        for (i in startIndex until endIndex) {
            val item = allItems[i]
            // 源已不存在：不预取（重试也是 404）
            if (item.sourceMissing) continue
            // 可见项自己会取，跳过（见本函数注释）
            if (i in firstVisibleIndex until visibleEnd) continue
            val keyStr = item.key.keyString

            // Skip if already cached（口径由 thumbnailOnly 决定，见 isCached）
            if (isCached(item.key, item.isVideo, thumbnailOnly)) continue
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
        // 只清**同类**任务：缩略图队列由网格管，原图任务由查看器（prefetchAround /
        // prefetchOriginal）管；网格这次上报不该顺手把用户刚点开那张的原图任务删掉
        val iterator = prefetchQueue.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (request.thumbnailOnly != thumbnailOnly) continue
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
                priority = priority,
                thumbnailOnly = thumbnailOnly
            ))
        }
    }

    /**
     * Initial prefetch for gallery startup.
     * Fetches first N items with high priority.
     *
     * P2-B：默认只暖缩略图（[thumbnailOnly] = true）。首屏这 24 张原本按原图拉，
     * 是冷启动后第一次滑动最主要的卡顿源。
     */
    fun initialPrefetch(items: List<PrefetchItem>, thumbnailOnly: Boolean = true) {
        for (item in items) {
            // 源已不存在：不预取（重试也是 404）
            if (item.sourceMissing) continue
            val keyStr = item.key.keyString
            if (!isCached(item.key, item.isVideo, thumbnailOnly) && !inProgressKeys.contains(keyStr)) {
                prefetchQueue.offer(PrefetchRequest(
                    key = item.key,
                    isVideo = item.isVideo,
                    subfolder = item.subfolder,
                    type = item.type,
                    priority = PrefetchPriority.ADJACENT,
                    thumbnailOnly = thumbnailOnly
                ))
            }
        }
    }

    /**
     * 点开查看器前，把**这一张**的原图排进预取队列（P2-B 的配套）。
     *
     * 为什么需要：网格预取改成缩略图之后，查看器首帧不再有"原图已在缓存"的待遇。
     * 从点击到查看器真正显示之间还有 PixelCopy 截图 + 挖空 + startActivity，
     * 这段空档用来提前发起原图拉取，命中则由 worker 直接跳过。
     * 尽力而为：查看器自己也会按 [prefetchAround] 补 ±1~±3 张，这里只负责被点开那张。
     */
    fun prefetchOriginal(item: PrefetchItem) {
        if (item.sourceMissing) return
        val keyStr = item.key.keyString
        if (isCached(item.key, item.isVideo, thumbnailOnly = false)) return
        if (inProgressKeys.contains(keyStr)) return
        prefetchQueue.offer(PrefetchRequest(
            key = item.key,
            isVideo = item.isVideo,
            subfolder = item.subfolder,
            type = item.type,
            priority = PrefetchPriority.ADJACENT,
            thumbnailOnly = false
        ))
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
        priority: Int = PriorityLruCache.PRIORITY_DEFAULT,
        /** true = 只读本机缓存，绝不发网络请求（「源已不存在」条目走这条） */
        localOnly: Boolean = false
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

            // 离线：只读本机缓存，不发请求。与 missing/localOnly 分支同口径走
            // [getLocalImage]（覆盖 filesDir 720 持久化档 + 旧 cacheDir 原图/缩略图），
            // 只读旧磁盘目录会让"本机有 720 档"的条目在离线查看器里空白。
            if (offlineReadOnly) {
                return withContext(Dispatchers.IO) {
                    val bitmap = if (isVideo) {
                        getLocalVideoFrame(key)
                    } else {
                        getLocalImage(key)
                    }
                    bitmap?.let { cacheFullBitmap(keyStr, it) }
                    DebugLogger.d(TAG, "fetchBitmap: $keyStr offline -> local cache ${if (bitmap != null) "HIT" else "MISS"}")
                    bitmap
                }
            }

            // 本会话已确认 404（源已不存在）：不再打网络，只用本机缓存。
            // 手动重试与下拉刷新会清掉 missingKeys，届时允许再探一次。
            if (isFileMissing(key)) {
                return withContext(Dispatchers.IO) {
                    val local = if (isVideo) {
                        getLocalVideoFrame(key)
                    } else {
                        getLocalImage(key)
                    }
                    local?.let { cacheFullBitmap(keyStr, it) }
                    DebugLogger.d(TAG, "fetchBitmap: $keyStr known missing -> local ${if (local != null) "HIT" else "MISS"}")
                    local
                }
            }

            // 「源已不存在」：只用本机缓存（原图/720 档/缩略图任一命中即可），不发请求
            if (localOnly) {
                return withContext(Dispatchers.IO) {
                    val bitmap = if (isVideo) {
                        getLocalVideoFrame(key)
                    } else {
                        getLocalImage(key)
                    }
                    bitmap?.let { cacheFullBitmap(keyStr, it) }
                    DebugLogger.d(TAG, "fetchBitmap: $keyStr local-only -> ${if (bitmap != null) "HIT" else "MISS"}")
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

                // 本会话已确认 404（源已不存在）：磁盘优先也只用本机缓存
                if (isFileMissing(key)) {
                    return@withContext if (isVideo) {
                        getLocalVideoFrame(key)
                    } else {
                        getLocalImage(key)
                    }
                }

                // 「源已不存在」：磁盘优先模式也先把 720 档/缩略图算进本地缓存，再决定是否联网
                if (localOnly) {
                    val local = if (isVideo) getLocalVideoFrame(key) else getLocalImage(key)
                    DebugLogger.d(TAG, "fetchBitmap: $keyStr local-only -> ${if (local != null) "HIT" else "MISS"}")
                    return@withContext local
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
        type: String,
        /** true = 只读本机缓存，绝不发网络请求（「源已不存在」条目走这条） */
        localOnly: Boolean = false
    ): Bitmap? {
        val keyStr = key.keyString
        // ① 内存命中不进门（列表格首次组合时走的就是这一条）
        // 第三态只读本机：内存里已有原图就直接用（省掉一次落盘读 + 解码）
        if (localOnly) bitmapCache.get(keyStr)?.let { return it }
        thumbnailCache.get(keyStr)?.let { return it }

        // ② 单飞（P2-B 配套）：同一 key 只允许一趟在途。
        // 格子组合与预取 worker 会**同时**请求同一张缩略图（预取刚排上、格子已经渲染），
        // 两趟都去抢那 3 个网络许可，服务端还要把同一张图现场压两遍 ——
        // preview 是现场压缩，204 MB 的图单次约 9 秒，重复一次就是双倍。
        // 后来者直接等第一个人的结果：缓存键相同，拿到的位图完全一致。
        // localOnly 不进门：它只读本机（几十毫秒），没有等待别人的理由 ——
        // 让它去等同 key 的网络请求等于把「源已不存在」的格子挂住最多一个压缩周期。
        if (!localOnly) {
            thumbnailInFlight[keyStr]?.let { return it.await() }
            val gate = CompletableDeferred<Bitmap?>()
            thumbnailInFlight.putIfAbsent(keyStr, gate)?.let { return it.await() }
            try {
                return fetchThumbnailUnshared(key, isVideo, subfolder, type, localOnly)
                    .also { gate.complete(it) }
            } finally {
                // 取消/异常时也要唤醒等待者，否则它们会永远挂在这个 key 上
                if (!gate.isCompleted) gate.complete(null)
                thumbnailInFlight.remove(keyStr, gate)
            }
        }
        return fetchThumbnailUnshared(key, isVideo, subfolder, type, localOnly)
    }

    /**
     * [fetchThumbnail] 的实际实现（内存命中与单飞已在调用方挡过）。
     */
    private suspend fun fetchThumbnailUnshared(
        key: MediaCacheKey,
        isVideo: Boolean,
        subfolder: String,
        type: String,
        localOnly: Boolean
    ): Bitmap? {
        // v4.16.1 分流：持久化开关开启 → 走 filesDir 统一档链路（720 档 + 落盘 + 重开立即清晰）；
        // 关闭 → 走下方原逻辑（cacheDir + 512 档），两套互不读写对方目录。
        // 开关每次调用读一次（SharedPreferences 有内存缓存，读取极快）：设置变化即时生效。
        val appContext = applicationContext
        if (appContext != null && AppSettings.isGalleryPersistentCacheEnabled(appContext)) {
            return fetchThumbnailPersistent(key, isVideo, subfolder, type, appContext, localOnly)
        }

        val keyStr = key.keyString
        if (isVideo) {
            return fetchBitmap(key, true, subfolder, type, localOnly = localOnly)
                ?.also { thumbnailCache.put(keyStr, it) }
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
        // 已确认服务器上没有（404）或「源已不存在」：不再打网络。
        // 但**必须放在磁盘读之后**，且旧 thumb 未命中时再查一遍完整本机副本
        // （旧 gallery_cache 原图 / 持久化目录），避免 UI 判"无缓存"而保存却成功。
        if (isFileMissing(key) || localOnly) {
            val legacy = withContext(Dispatchers.IO) { getLocalImage(key) }
            if (legacy != null) {
                thumbnailCache.put(keyStr, legacy)
                return legacy
            }
            DebugLogger.d(TAG, "fetchThumbnail: $keyStr local-only -> MISS (skip network)")
            return null
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
        context: Context,
        localOnly: Boolean = false
    ): Bitmap? {
        val keyStr = key.keyString
        if (localOnly) bitmapCache.get(keyStr)?.let { return it }
        thumbnailCache.get(keyStr)?.let { return it }

        // 磁盘层（filesDir 新目录）：图片读统一档，视频读首帧。
        // F1：新键名未命中时回退旧版本 `{promptId}_{filename}.jpg`，避免升级后旧缓存全部失效。
        val cached = withContext(Dispatchers.IO) {
            readPersistBitmap(context, resolvePersistFile(context, key, isVideo))
        }
        if (cached != null) {
            thumbnailCache.put(keyStr, cached)
            DebugLogger.d(TAG, "fetchThumbnail(persist): $keyStr disk -> HIT")
            return cached
        }

        // 已确认 404 / 第三态：持久化目录没有就回退旧 cacheDir 副本。
        // 老用户升级后可能只剩 gallery_cache / gallery_thumb 里的图；不能因为新目录没有
        // 就显示"本机无缓存"——保存路径 getLocalImage 明明还能取到，UI 与保存能力必须一致。
        if (isFileMissing(key) || localOnly) {
            val legacy = withContext(Dispatchers.IO) {
                if (isVideo) getLocalVideoFrame(key) else getLocalImage(key)
            }
            if (legacy != null) {
                thumbnailCache.put(keyStr, legacy)
                DebugLogger.d(TAG, "fetchThumbnail(persist): $keyStr local fallback -> HIT")
                return legacy
            }
            DebugLogger.d(TAG, "fetchThumbnail(persist): $keyStr local-only -> MISS (skip network)")
            return null
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
            val frame = fetchBitmap(key, true, subfolder, type, localOnly = localOnly) ?: return null
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
        type: String,
        /** true = 只读本机缓存，绝不发网络请求（「源已不存在」条目走这条） */
        localOnly: Boolean = false
    ): ViewerImage {
        val keyStr = key.keyString
        bitmapCache.get(keyStr)?.let { return ViewerImage(it, false) }

        val context = applicationContext
        val client = comfyUIClient

        // 离线：回落到本机缓存（与 fetchBitmap / localOnly 同口径走 [getLocalImage]，
        // 覆盖 filesDir 720 持久化档 + 旧 cacheDir），不发请求。
        // context 为 null 时连磁盘都读不了，直接返回
        if (context == null) return ViewerImage(null, false)
        if (client == null || ConnectionManager.isOffline) {
            val fromLocal = withContext(Dispatchers.IO) {
                getLocalImage(key)
            }
            return ViewerImage(fromLocal, false)
        }

        // 「源已不存在」：原图已被确认删除（404 已持久化进索引），不再请求 ——
        // 直接用本机缓存（720 档/旧磁盘原图），避免每次进查看器都白等一次超时。
        if (localOnly) {
            val local = withContext(Dispatchers.IO) { getLocalImage(key) }
            local?.let { cacheFullBitmap(keyStr, it) }
            DebugLogger.d(TAG, "fetchViewerImage: $keyStr local-only -> ${if (local != null) "HIT" else "MISS"}")
            return ViewerImage(local, false)
        }

        // 本会话已确认 404：不再请求原图，直接用本机缓存（手动重试/下拉刷新会清标记）
        if (isFileMissing(key)) {
            val local = withContext(Dispatchers.IO) { getLocalImage(key) }
            local?.let { cacheFullBitmap(keyStr, it) }
            DebugLogger.d(TAG, "fetchViewerImage: $keyStr known missing -> local ${if (local != null) "HIT" else "MISS"}")
            return ViewerImage(local, false)
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
            // 404 记档：之后缩略图/查看器不再白打这趟请求，仓储据此把标记持久化进索引
            markFileMissing(key)
            // 源已不存在：回落到本机缓存（720 档/旧磁盘原图），让界面直接进入第三态
            // ——清晰的低清副本 + 「已删除」角标，而不是一次失败占位后让用户反复重试。
            val local = withContext(Dispatchers.IO) { getLocalImage(key) }
            local?.let { cacheFullBitmap(keyStr, it) }
            return ViewerImage(local, false)
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
        client: MediaGateway,
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
        // F1：新键名未命中时回退旧版本 `{promptId}_{filename}.jpg`
        val file = resolveThumbFile(context, key)
        if (file != null && file.isFile && file.length() > 0) {
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
    private fun readPersistBitmap(context: Context, file: File?): Bitmap? = try {
        if (file != null && file.isFile && file.length() > 0) {
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
            // F1：旧版本命名的文件也要删，否则删图后旧的 `{promptId}_{filename}.jpg`
            // 仍会被读路径的兼容回退重新读出来（"删了又回来"）。
            File(context.filesDir, PERSIST_STORE_DIR).let {
                File(it, legacyCacheFileName(key, "jpg")).delete()
            }
            File(context.filesDir, PERSIST_THUMB_DIR).let {
                File(it, legacyCacheFileName(key, "jpg")).delete()
            }
        } catch (_: Exception) {
            // 忽略删除失败
        }
    }

    /** 删掉某个键的播放临时文件（P2-3，见 [MediaCachePlaybackTempPolicy]）。 */
    private fun deletePlaybackTempFile(context: Context, keyStr: String) {
        try {
            File(context.cacheDir, MediaCachePlaybackTempPolicy.fileNameFor(keyStr)).delete()
        } catch (_: Exception) {
            // 忽略删除失败
        }
    }

    /** 删掉本模块在 cacheDir 留下的全部播放临时文件（只认 `playback_` 前缀）。 */
    private fun clearPlaybackTempFiles(context: Context) {
        try {
            MediaCachePlaybackTempPolicy.cleanupDirectory(context.cacheDir, keepName = "")
        } catch (_: Exception) {
            // 忽略删除失败
        }
    }

    private suspend fun fetchImageBitmap(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        client: MediaGateway,
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

        if (bitmap != null) {
            cacheFullBitmap(key.keyString, bitmap, priority)
            return bitmap
        }

        // 404 = 源已不存在：不回 null，回落到本机缓存（720 档/旧磁盘），
        // 让界面进入第三态（清晰的低清副本 + 「已删除」角标），而不是黑屏/失败图标。
        if (isFileMissing(key)) {
            return withContext(Dispatchers.IO) { getLocalImage(key) }?.also {
                cacheFullBitmap(key.keyString, it, priority)
            }
        }

        return null
    }

    private suspend fun fetchVideoBitmap(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        client: MediaGateway,
        context: Context,
        priority: Int
    ): Bitmap? {
        val keyStr = key.keyString

        // Check if thumbnail is already cached - avoid redundant extraction
        bitmapCache.get(keyStr)?.let { return it }

        // 已经下载过的字节优先用本地字节抽帧：不再多打一次网络
        videoCache.get(keyStr)?.let { bytes ->
            val (dimensions, thumbnail) = extractVideoData(keyStr, bytes, context)
            dimensions?.let { videoDimensionsCache[keyStr] = it }
            thumbnail?.let { bitmapCache.put(keyStr, it, priority) }
            return thumbnail
        }

        // P2-5：先让 MediaMetadataRetriever 直接读远程 URL 抽一帧。
        // 修复前为了列表里的一个首帧会把整段视频（常见几十 MB）下载下来再解码，
        // 内存优先模式下这是纯浪费 —— 播放时还会再下一次。
        val remote = extractVideoDataFromUrl(
            url = client.viewUrl(key.filename, subfolder, type),
            keyStr = keyStr
        )
        if (remote != null) {
            val (dimensions, thumbnail) = remote
            dimensions?.let { videoDimensionsCache[keyStr] = it }
            thumbnail?.let { bitmapCache.put(keyStr, it, priority) }
            return thumbnail
        }

        // 回退：远程抽帧失败（服务器要求 Basic/Bearer/Cookie 认证，或自签证书只有
        // OkHttp 的自定义 TrustManager 认得，而 MediaMetadataRetriever 走系统 TLS），
        // 仍按原逻辑整段下载后用临时文件抽帧。
        // Check if we have video bytes cached
        var videoBytes = videoCache.get(keyStr)

        if (videoBytes == null) {
            // Fetch video bytes
            videoBytes = suspendCancellableCoroutine { continuation ->
                client.fetchVideo(key.filename, subfolder, type) { bytes, failureType ->
                    // 404 = 视频源已不存在：与图片链路同一份 missingKeys / 第三态标记
                    if (bytes == null && failureType == ConnectionFailure.FILE_MISSING) {
                        markFileMissing(key)
                    }
                    continuation.resume(bytes)
                }
            }

            // Cache the video bytes
            videoBytes?.let { videoCache.put(keyStr, it, PriorityLruCache.PRIORITY_DEFAULT) }
        }

        if (videoBytes == null) {
            // 源已不存在：回落到本机缓存的首帧，让格子/查看器进入第三态
            return if (isFileMissing(key)) getLocalVideoFrame(key) else null
        }

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
        client: MediaGateway,
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
        if (bitmap != null) {
            writeBitmapToDisk(context, key, bitmap)
            return bitmap
        }

        // 404 = 源已不存在：回落到本机缓存（720 档/旧磁盘），让界面进入第三态
        if (isFileMissing(key)) {
            return getLocalImage(key)
        }

        return null
    }

    private suspend fun fetchVideoBitmapDiskFirst(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        client: MediaGateway,
        context: Context
    ): Bitmap? {
        val keyStr = key.keyString

        // P2-5：磁盘优先模式同样先远程抽帧。修复前这里必然 client.fetchVideo 整段下载
        // 再 writeVideoToDisk —— 用户关掉「内存优先缓存」后，图库每格的视频首帧都要
        // 先拉几十 MB，与"只有播放才整段下载"的目标不符。
        // 远程抽帧成功时不落视频字节：真需要播放时 fetchVideoBytes 会按磁盘优先链路
        // 下载并缓存，下载时机推迟到播放。
        val remote = extractVideoDataFromUrl(
            url = client.viewUrl(key.filename, subfolder, type),
            keyStr = keyStr
        )
        if (remote != null) {
            // 与原实现保持一致：磁盘优先不把尺寸放进内存缓存
            return remote.second
        }

        // 回退：远程抽帧失败（认证 / 自签证书 / 协议不支持）时按原链路整段下载并落盘
        val videoBytes = suspendCancellableCoroutine<ByteArray?> { continuation ->
            client.fetchVideo(key.filename, subfolder, type) { bytes, failureType ->
                if (bytes == null && failureType == ConnectionFailure.FILE_MISSING) {
                    markFileMissing(key)
                }
                continuation.resume(bytes)
            }
        } ?: return if (isFileMissing(key)) getLocalVideoFrame(key) else null

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

            // 离线：只读本机缓存（与 missing 分支同口径走 [getLocalImage]，覆盖
            // filesDir 720 持久化档 + 旧 cacheDir），不发请求
            if (offlineReadOnly) {
                return withContext(Dispatchers.IO) {
                    val bitmap = getLocalImage(key)
                    bitmap?.let { cacheFullBitmap(key.keyString, it, priority) }
                    DebugLogger.d(TAG, "fetchImage: ${key.keyString} offline -> local cache ${if (bitmap != null) "HIT" else "MISS"}")
                    bitmap
                }
            }

            // 本会话已确认 404：保存/分享直接用本机缓存，不再白打一次请求
            if (isFileMissing(key)) {
                return withContext(Dispatchers.IO) {
                    getLocalImage(key)?.also { cacheFullBitmap(key.keyString, it, priority) }
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

                if (bitmap != null) {
                    cacheFullBitmap(key.keyString, bitmap, priority)
                    return@withContext bitmap
                }
                // 404 = 源已不存在：保存/分享等调用方拿到的是本机低清副本，而不是 null
                // （界面据此走"已保存低清版"，而不是一句没有原因的保存失败）。
                if (isFileMissing(key)) {
                    return@withContext getLocalImage(key)?.also {
                        cacheFullBitmap(key.keyString, it, priority)
                    }
                }
                null
            }
        } else {
            // Disk-first: check disk cache first, fetch and save to disk if not found
            return withContext(Dispatchers.IO) {
                readBitmapFromDisk(context, key)?.let {
                    DebugLogger.d(TAG, "fetchImage: ${key.keyString} from disk cache -> HIT")
                    return@withContext it
                }

                // 本会话已确认 404：保存/分享直接用本机缓存
                if (isFileMissing(key)) {
                    return@withContext getLocalImage(key)
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

                if (bitmap != null) {
                    writeBitmapToDisk(context, key, bitmap)
                    return@withContext bitmap
                }
                if (isFileMissing(key)) {
                    return@withContext getLocalImage(key)
                }
                null
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
        type: String,
        /** true = 只读本机缓存，绝不发网络请求（「源已不存在」的视频条目走这条） */
        localOnly: Boolean = false
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

            // 离线 / 已确认 404 / 第三态：只读磁盘缓存，不发请求
            if (offlineReadOnly || localOnly || isFileMissing(key)) {
                return withContext(Dispatchers.IO) {
                    val bytes = readVideoBytesFromDisk(context, key)
                    bytes?.let { videoCache.put(key.keyString, it, PriorityLruCache.PRIORITY_DEFAULT) }
                    DebugLogger.d(TAG, "fetchVideoBytes: ${key.keyString} local-only -> disk cache ${if (bytes != null) "HIT" else "MISS"}")
                    bytes
                }
            }

            return withContext(Dispatchers.IO) {
                val bytes = suspendCancellableCoroutine { continuation ->
                    client!!.fetchVideo(key.filename, subfolder, type) { videoBytes, failureType ->
                        if (videoBytes == null && failureType == ConnectionFailure.FILE_MISSING) {
                            markFileMissing(key)
                        }
                        continuation.resume(videoBytes)
                    }
                }

                bytes?.let { videoCache.put(key.keyString, it, PriorityLruCache.PRIORITY_DEFAULT) }
                // 404：回落到本机磁盘字节（源已不存在，但本机可能还留着）
                bytes ?: if (isFileMissing(key)) readVideoBytesFromDisk(context, key) else null
            }
        } else {
            // Disk-first: check disk cache first, fetch and save to disk if not found
            return withContext(Dispatchers.IO) {
                readVideoBytesFromDisk(context, key)?.let {
                    DebugLogger.d(TAG, "fetchVideoBytes: ${key.keyString} from disk cache -> HIT")
                    return@withContext it
                }

                // 离线态 / 已确认 404 / 第三态：磁盘缓存就是全部内容，不得再去请求服务器
                if (offlineReadOnly || localOnly || isFileMissing(key)) {
                    DebugLogger.d(TAG, "fetchVideoBytes: ${key.keyString} -> local-only, disk cache MISS")
                    return@withContext null
                }

                DebugLogger.d(TAG, "fetchVideoBytes: ${key.keyString} -> fetching from server")
                val bytes = suspendCancellableCoroutine { continuation ->
                    client!!.fetchVideo(key.filename, subfolder, type) { videoBytes, failureType ->
                        if (videoBytes == null && failureType == ConnectionFailure.FILE_MISSING) {
                            markFileMissing(key)
                        }
                        continuation.resume(videoBytes)
                    }
                }

                bytes?.let { writeVideoToDisk(context, key, it) }
                bytes ?: if (isFileMissing(key)) readVideoBytesFromDisk(context, key) else null
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
                    val tempFile = File(context.cacheDir, MediaCachePlaybackTempPolicy.fileNameFor(keyStr))

                    // P2-3：写新文件前清掉上一轮留下的同前缀临时文件（旧实现只写不删，
                    // 连续看视频会把 cacheDir 一直堆到系统回收）。保留本次这份；
                    // `state_playback_*`（MediaStateHolder）与这里前缀不重叠，不会被误删。
                    // F7：正在播放的视频（VideoPlayer 已登记）同样保留 —— 视频 A 已 prepare
                    // 但 ExoPlayer 尚未打开文件时，为 B 生成 URI 不能删掉 A 的文件。
                    // 被删文件的 URI 一并从缓存里摘掉，否则 getVideoUri 会返回指向已删文件的死 URI。
                    val deleted = MediaCachePlaybackTempPolicy.cleanupDirectory(
                        cacheDir = context.cacheDir,
                        keepName = tempFile.name,
                        protectedNames = protectedPlaybackTempNames()
                    )
                    if (deleted.isNotEmpty()) {
                        val deletedNames = deleted.toHashSet()
                        val staleKeys = videoUriCache.filterValues {
                            it.lastPathSegment in deletedNames
                        }.keys.toList()
                        videoUriCache.keys.removeAll(staleKeys.toSet())
                        DebugLogger.d(TAG, "清理播放临时文件 ${deleted.size} 个（保留 ${tempFile.name}）")
                    }

                    tempFile.writeBytes(bytes)

                    // P2-12：播放方就在本进程（ExoPlayer），file:// 足够，不需要 FileProvider。
                    // 只有这样 file_paths.xml 才能把整个 cacheDir 从可共享根里去掉；
                    // 对外分享一律走 util/ShareFileStore.kt（cacheDir/share/）。
                    val uri = Uri.fromFile(tempFile)

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
                    // F1：新键名未命中时回退旧版本 `{promptId}_{filename}.mp4`
                    val file = resolveDiskCacheFile(context, key, true)
                    if (file != null && file.exists()) {
                        // P2-12：播放 URI 用 file://（进程内播放），不经过 FileProvider
                        Uri.fromFile(file)
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
        context: Context,
        /** true = 只读本机缓存，绝不发网络请求（「源已不存在」的视频条目走这条） */
        localOnly: Boolean = false
    ): Uri? {
        val keyStr = key.keyString

        // Ensure bytes are cached
        val bytes = fetchVideoBytes(key, subfolder, type, localOnly) ?: return null

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
                // 源已不存在：预取原图没有意义（只会再拿一个 404）
                if (item.sourceMissing) continue
                val keyStr = item.key.keyString
                // Skip if already cached
                if (!isCached(item.key, item.isVideo)) {
                    desiredPrefetches[keyStr] = item to priority
                }
            }
        }

        // Remove outdated requests from queue (items no longer in desired range)
        // 两处例外不能删：
        // - 缩略图任务（网格那一队）不归查看器管，删了会把滚回图库前的预热全作废；
        // - 当前这一张的原图任务（[prefetchOriginal] 在点击时排的）也不删：查看器首帧就靠它，
        //   desiredPrefetches 只含 ±1~±3，当前项不在其中。
        val currentKeyStr = allItems.getOrNull(currentIndex)?.key?.keyString
        val iterator = prefetchQueue.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (request.thumbnailOnly) continue
            val keyStr = request.key.keyString
            if (keyStr == currentKeyStr) continue
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
                priority = priority,
                thumbnailOnly = false
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
        val type: String,
        /**
         * 源已不存在（/view 404 已持久化）→ 预取直接跳过。
         *
         * 预取拉的是**原图**；对已删除的条目再拉只会换回一次 404，
         * 既浪费请求也让"每次进图库都要重新等一次"的观感继续存在。
         */
        val sourceMissing: Boolean = false
    )

    /**
     * 这个条目对**当前这条预取诉求**来说是否已经有货。
     *
     * @param thumbnailOnly true = 问"网格能显示了吗"：缩略图内存命中或 720 档磁盘命中
     *   都算已缓存，不再排原图任务（P2-B 第二条：修复前只认原图缓存，刚看过的格子
     *   缩略图明明在内存里，仍会再排一次 6144 px 的原图拉取）。
     *   false = 问"查看器能直接显示原图了吗"：只有原图缓存命中才算，缩略图不算 ——
     *   否则查看器会把 720 档当原图直接用，打开就是糊的。
     *
     * 磁盘分支的文件存在性判断（stat ×3）只在 [updateGalleryPosition] 的
     * Dispatchers.Default 上下文里跑到，不占主线程（见 GalleryScreen 的 P2-D 注释）。
     */
    private fun isCached(
        key: MediaCacheKey,
        isVideo: Boolean,
        thumbnailOnly: Boolean = false
    ): Boolean {
        val keyStr = key.keyString
        if (thumbnailOnly && thumbnailCache.get(keyStr) != null) return true
        if (isMemoryFirstMode) {
            // Memory-first: check in-memory cache
            return if (isVideo) {
                videoCache.get(keyStr) != null
            } else {
                bitmapCache.get(keyStr) != null
            }
        } else {
            // Disk-first: check disk cache
            val context = applicationContext ?: return false
            if (thumbnailOnly) {
                // 磁盘优先模式下图库读的是 720 统一档（filesDir/gallery_store）与旧缩略图目录，
                // 不能只看 gallery_cache 的原图：那里没有，网格照样能立刻出图
                return resolvePersistFile(context, key, isVideo) != null ||
                    resolveThumbFile(context, key) != null ||
                    existsInDiskCache(context, key, isVideo)
            }
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

                        if (!isCached(request.key, request.isVideo, request.thumbnailOnly) &&
                            inProgressKeys.add(keyStr)) {

                            try {
                                // Convert PrefetchPriority to PriorityLruCache priority
                                val cachePriority = when (request.priority) {
                                    PrefetchPriority.ADJACENT -> PriorityLruCache.PRIORITY_ADJACENT
                                    PrefetchPriority.NEARBY -> PriorityLruCache.PRIORITY_NEARBY
                                    PrefetchPriority.DISTANT -> PriorityLruCache.PRIORITY_DISTANT
                                }

                                // P2-B：两种任务分两条链路 ——
                                // 缩略图（网格）= 服务端压缩预览 + 720 采样解码，几十 KB~2 MB；
                                // 原图（查看器）= 6144 px / 48 MB 上限，只在真的要看全屏时才拉。
                                // 修复前一律走 fetchImage，等于给每格预览都按原图付费。
                                if (request.thumbnailOnly) {
                                    fetchThumbnail(
                                        key = request.key,
                                        isVideo = request.isVideo,
                                        subfolder = request.subfolder,
                                        type = request.type
                                    )
                                } else if (request.isVideo) {
                                    // P2-3：预取只拉字节，**不再预生成播放临时文件**。
                                    // 修复前这里走 fetchVideoUri → getVideoUri，会给每个预取到的
                                    // 视频都在 cacheDir 写一份 playback_*.mp4，而预取窗口有 3 个方向，
                                    // 光滑动列表就能攒出一堆永远不播的临时文件。真正要播放时
                                    // getVideoUri 会从内存里的字节按需创建。
                                    fetchVideoBytes(request.key, request.subfolder, request.type)
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
        playingVideoKeys.remove(keyStr)

        // v4.16.1 删图连带清（规格 B3）：缩略图内存缓存此前永不清（删图后列表缩略图仍挂着），
        // 与 filesDir 新目录里的统一档/首帧文件一起清掉。**旧 cacheDir 维持原状**（见下方注释）
        thumbnailCache.remove(keyStr)
        applicationContext?.let { context ->
            deletePersistFiles(context, key)
            // P2-3：删条目时把它的播放临时文件一并删掉（否则永远留在 cacheDir）
            deletePlaybackTempFile(context, keyStr)
        }

        // In disk-first mode, also remove from disk
        if (!isMemoryFirstMode) {
            applicationContext?.let { context ->
                try {
                    getDiskCacheFile(context, key, false).delete()
                    getDiskCacheFile(context, key, true).delete()
                    // F1：旧名字的文件也删，否则读路径的兼容回退会把删掉的条目读回来
                    val dir = getDiskCacheDir(context)
                    File(dir, legacyCacheFileName(key, "png")).delete()
                    File(dir, legacyCacheFileName(key, "mp4")).delete()
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
        playingVideoKeys.clear()
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
            // P2-3：播放临时文件也是"缓存"，用户点清缓存时必须一并清掉
            clearPlaybackTempFiles(context)
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
        playingVideoKeys.clear()
        // 断线/切服务器即会话知识失效，与 clearForRefresh 口径一致：
        // missingKeys（404 短路标记）与 oversizedKeys（超限判定）都是"针对当时那台服务器"
        // 学到的，换服务器后沿用旧标记会把同名键的文件误判成 404/超限。
        missingKeys.clear()
        oversizedKeys.clear()
        prefetchQueue.clear()
        inProgressKeys.clear()
        completionCallbacks.clear()

        // P2-3：断线/切服务器/退出登录时，内存里的 URI 已清空；对应的播放临时文件
        // 也一并删掉（它们是本模块自己的产物，不属于离线读取所需的磁盘缓存）。
        applicationContext?.let { context -> clearPlaybackTempFiles(context) }

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

    /**
     * 直接从远程 URL 抽视频首帧与尺寸（P2-5）。
     *
     * MediaMetadataRetriever 支持 `setDataSource(http/https URL)`，由它自己按需读取
     * 容器头部与首帧附近的数据，**不需要把整段视频下载到本地**。
     *
     * 失败（返回 null）的典型原因，调用方必须回退到整段下载：
     * - 服务器要求 Basic/Bearer/Cookie 认证：这个 API 无法附加请求头；
     * - 自签证书：应用的自定义 TrustManager 只装在 OkHttp 上，系统 TLS 不认；
     * - 服务端 Range/头部行为不符合 MMR 预期、文件损坏等。
     *
     * @return (尺寸, 首帧)；URL 为空、抽取失败或首帧为空时返回 null
     */
    private fun extractVideoDataFromUrl(
        url: String?,
        keyStr: String
    ): Pair<VideoDimensions?, Bitmap?>? {
        if (url == null) return null
        var retriever: MediaMetadataRetriever? = null
        return try {
            val r = MediaMetadataRetriever()
            retriever = r
            r.setDataSource(url)
            val thumbnail = r.getFrameAtTime(0) ?: return null
            val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: thumbnail.width
            val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: thumbnail.height
            DebugLogger.d(TAG, "fetchVideoBitmap: $keyStr 远程抽帧成功 ${width}x$height")
            Pair(VideoDimensions(width, height), thumbnail)
        } catch (_: Exception) {
            // 认证 / TLS / 协议不支持：交给调用方回退整段下载
            DebugLogger.d(TAG, "fetchVideoBitmap: $keyStr 远程抽帧失败，回退整段下载")
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            runCatching { retriever?.release() }
        }
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
