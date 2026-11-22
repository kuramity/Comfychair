package sh.hnet.comfychair.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.VideoUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Singleton that holds media state for generation screens.
 *
 * Supports two caching modes:
 * - Memory-first (default): Media stored in RAM, persisted to disk on app background.
 *   This eliminates frequent disk I/O during active generation.
 * - Disk-first: Media written directly to disk, read from disk when needed.
 *   This minimizes RAM usage for low-end devices with slow networks.
 *
 * Media files are scoped per-server using the format: {serverId}_{filename}
 */
object MediaStateHolder {

    private const val TAG = "MediaState"

    /** 编号参考图文件名里固定含有的片段，完整形式为 `{serverId}_itv_reference_{N}.png` */
    private const val ITV_REFERENCE_FILE_MARKER = "itv_reference_"

    // Current server ID for per-server file scoping
    // 生成流程在 OkHttp 回调线程写、落盘/裁剪在 IO 线程读：必须 volatile，否则可能长时间读到旧服务器
    @Volatile
    private var currentServerId: String? = null

    /**
     * Keys for different media types stored in the holder.
     * Base filenames are used - the server ID prefix is added dynamically.
     */
    sealed class MediaKey(val baseFilename: String) {
        // Image previews (final generated images displayed on screens)
        data object TtiPreview : MediaKey("tti_last_preview.png")
        data object ItiPreview : MediaKey("iti_last_preview.png")
        data object TtvPreview : MediaKey("ttv_last_preview.png")
        data object ItvPreview : MediaKey("itv_last_preview.png")

        // Source images (user-selected images for Image-to-X workflows)
        data object ItiSource : MediaKey("iti_last_source.png")
        data object ItvSource : MediaKey("itv_last_source.png")

        /**
         * 图生视频的编号参考图（乙类图位）。
         *
         * 编号取占位符 `reference_image_N` 里的 N（也是注入器接线用的那个编号），
         * 每个编号独立一档：删除某个图位只清掉它自己那一档，不会串到别的图位。
         */
        data class ItvReference(val index: Int) : MediaKey("itv_reference_$index.png")

        // Reference images for ITE (Image Editing) workflow
        data object IteReferenceImage1 : MediaKey("ite_reference_1.png")
        data object IteReferenceImage2 : MediaKey("ite_reference_2.png")

        // Generated videos (with prompt ID for uniqueness)
        data class TtvVideo(val promptId: String) : MediaKey("${VideoUtils.FilePrefix.TEXT_TO_VIDEO}$promptId.mp4")
        data class ItvVideo(val promptId: String) : MediaKey("${VideoUtils.FilePrefix.IMAGE_TO_VIDEO}$promptId.mp4")

        /**
         * Get the full filename including server ID prefix.
         */
        fun filename(serverId: String): String = "${serverId}_$baseFilename"
    }

    /**
     * Set the current server ID for per-server file scoping.
     * Should be called when connecting to a server.
     */
    fun setCurrentServerId(serverId: String?) {
        if (currentServerId != serverId) {
            DebugLogger.i(TAG, "Server ID changed: ${currentServerId?.take(8)} -> ${serverId?.take(8)}")
            currentServerId = serverId
            // 换了服务器就是换了媒体作用域：上一轮的「已就绪」不再代表当前服务器的内容，
            // 必须复位闩锁等新一轮恢复。调用方（MainContainerActivity.onCreate）紧接着就会启动
            // loadFromDisk / discoverVideoPromptIds —— 若没有后续恢复，界面会一直停在占位分支上。
            restoreLatch.resetForNewSession()
        }
    }

    /**
     * Get the current server ID.
     */
    fun getCurrentServerId(): String? = currentServerId

    // Caching mode - true for memory-first (default), false for disk-first
    // 从主线程写入、从 IO 协程读取，必须 volatile，否则后台线程可能长时间看不到模式切换
    @Volatile
    private var isMemoryFirstMode = true

    // In-memory storage (used in memory-first mode)
    private val bitmaps = ConcurrentHashMap<MediaKey, Bitmap>()
    private val videoBytes = ConcurrentHashMap<MediaKey, ByteArray>()

    /**
     * 每次写入取的全局版本号（见 [MediaPersistVersionOps]）。
     *
     * **进程内只增不回退**：evict / 清内存都不重置它。旧实现按 key 各自从 1 计数并在
     * evict / clearMemoryState 时删掉计数，于是「旧一轮写盘握着的版本 1」与
     * 「新一轮重新从 1 开始的版本」会撞车，条件删除 remove(key, 1) 命中新一轮的脏标记、
     * 把还没落盘的新内容清掉（5.2 修正）。
     */
    private val versionCounter = AtomicLong(0)

    /** key -> 尚未落盘的最新写入版本；写盘期间来过新版本时条件删除不命中，脏标记保留。 */
    private val dirtyVersions = ConcurrentHashMap<MediaKey, Long>()

    /**
     * 启动期媒体恢复是否已完成（[loadFromDisk] / [discoverVideoPromptIds] 结束时置 true）。
     *
     * 4.3 把主线程 `runBlocking { loadFromDisk() }` 改成异步后，界面需要知道「恢复到哪一步」：
     * 未就绪时先组合占位内容，等 [restored] 变 true 再组合导航图，屏幕初始化就不会读到空媒体。
     *
     * 5.2 起语义从「进程内一次性闩锁」改为「按本次恢复判断」（见 [MediaRestoreLatch]）：
     * 同一进程里第二次创建 MainContainerActivity（登出→重新登录、切换服务器）会重新开始一轮恢复，
     * 闩锁随之复位。否则 loadFromDisk 还在异步路上，界面就按上一轮留下的 true 组合了导航图，
     * 屏幕一次性读取媒体的路径（TextToImageViewModel.restoreLastGeneratedImage）读到的仍是空。
     */
    private val restoreLatch = MediaRestoreLatch()
    val restored: StateFlow<Boolean> get() = restoreLatch.restored

    /**
     * 同步声明「新一轮恢复开始」：把 [restored] 复位为 false。
     *
     * 必须由界面在组合之前调用（MainContainerActivity.onCreate 里 setContent 之前）：
     * 只靠 [loadFromDisk] 自己复位是不够的 —— 它是异步投递的，首帧组合可能先于它执行。
     * 调用后必须紧接着启动一轮恢复（[loadFromDisk] / [discoverVideoPromptIds]）。
     */
    fun beginRestoreSession() {
        DebugLogger.d(TAG, "beginRestoreSession: 复位媒体恢复闩锁，等待本轮恢复完成")
        restoreLatch.resetForNewSession()
    }

    /** 防抖落盘用的后台作用域与任务句柄（见 [schedulePersist]）。 */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistJob: Job? = null

    /**
     * 保护 [persistJob]「检查 + 赋值」原子性的锁（见 [schedulePersist]）。
     * schedulePersist 可能来自不同线程（UI 生命周期回调、落盘协程自身的补排），
     * 不加锁时两个线程可同时通过 isActive 检查、各起一个落盘协程并发写同一份文件。
     */
    private val persistLock = Any()

    /** 防抖窗口：这段时间内没有新的脏数据才真正写盘，把连续写入并成一次。 */
    private const val PERSIST_DEBOUNCE_MS = 800L

    /** 单个服务器在 filesDir 下的视频总量上限（512MB）：超出后从最旧的开始裁（见 [trimVideoFiles]）。 */
    private const val MAX_VIDEO_BYTES_PER_SERVER = 512L * 1024 * 1024

    /** 无论是否超出总量上限，最近这么多个视频一律保留。 */
    private const val KEEP_AT_LEAST = 3

    // Track current video prompt IDs for persistence
    // 跨线程读写（生成流程写、loadFromDisk / trimVideoFiles 读）：
    // trimVideoFiles 靠它决定「哪个视频不许删」，读到旧值就可能误删当前正在展示的视频
    @Volatile
    private var currentTtvPromptId: String? = null
    @Volatile
    private var currentItvPromptId: String? = null

    /**
     * 正在播放的视频（按 [MediaKey.baseFilename] 登记，见 [registerPlayingVideo]）。
     *
     * 裁剪原先只保护「每类最近一次 put / load 的 promptId」，但那不等于「正在播放的那个」：
     * 磁盘优先模式下 [getVideoUri] 直接返回 filesDir 里那个文件，用户接着生成下一个视频时
     * 旧 promptId 立刻被顶掉，正在播放的旧文件就可能被裁掉 —— 播放当场中断。
     * 现在由播放方在拿到 URI 时登记、切走 / 清空时注销，裁剪时整组跳过。
     */
    private val playingVideoBaseNames = ConcurrentHashMap.newKeySet<String>()

    /**
     * Set the caching mode.
     * @param enabled true for memory-first (default), false for disk-first
     * @param context Required for content migration when switching modes
     */
    fun setMemoryFirstMode(enabled: Boolean, context: Context? = null) {
        if (isMemoryFirstMode == enabled) return  // No change

        DebugLogger.i(TAG, "Switching to ${if (enabled) "memory-first" else "disk-first"} mode")
        // 先切模式、再做迁移：迁移已改为异步，窗口期内其他线程的读写必须已经按新模式分流
        isMemoryFirstMode = enabled

        if (enabled) {
            // Switching TO memory-first: load disk content into memory
            // 异步迁移（原先是主线程 runBlocking）：调用方（onCreate）立即返回，不卡启动
            context?.let { ctx ->
                val appContext = ctx.applicationContext
                persistScope.launch { loadFromDisk(appContext) }
                // Disk files remain as backup - don't clear them
            }
        } else {
            // Switching TO disk-first: persist memory content to disk first
            // 落盘是异步的，必须等它写完再清内存 —— 提前清会把还没写盘的内容直接丢掉
            val appContext = context?.applicationContext
            if (appContext == null) {
                clearMemoryState()
            } else {
                persistScope.launch {
                    persistToDisk(appContext)
                    // Now safe to clear in-memory caches
                    clearMemoryState()
                }
            }
        }
    }

    /**
     * 清空内存侧的内容与脏标记（模式切换 / [clearAll] 共用）。
     *
     * ⚠️ 只清内容与脏标记，**不动版本发号器**（见 [versionCounter]）：
     * 发号器是全局单调的，重置会让下一轮写入的版本与旧快照撞车（5.2）。
     */
    private fun clearMemoryState() {
        bitmaps.clear()
        videoBytes.clear()
        dirtyVersions.clear()
    }

    /**
     * Clear all disk cache files for the current server.
     */
    fun clearDiskCache(context: Context) {
        val serverId = currentServerId ?: return

        val allKeys = listOf(
            MediaKey.TtiPreview,
            MediaKey.ItiPreview,
            MediaKey.ItiSource,
            MediaKey.TtvPreview,
            MediaKey.ItvPreview,
            MediaKey.ItvSource,
            MediaKey.IteReferenceImage1,
            MediaKey.IteReferenceImage2
        ) + discoverItvReferenceKeys(context, serverId)

        for (key in allKeys) {
            try {
                val file = context.getFileStreamPath(key.filename(serverId))
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {
                // Ignore deletion failures
            }
        }

        // Also delete any video files for this server
        val prefix = "${serverId}_"
        try {
            context.filesDir.listFiles()?.filter { file ->
                file.name.startsWith(prefix) && (
                    file.name.contains(VideoUtils.FilePrefix.TEXT_TO_VIDEO) ||
                    file.name.contains(VideoUtils.FilePrefix.IMAGE_TO_VIDEO)
                )
            }?.forEach { file ->
                file.delete()
            }
        } catch (_: Exception) {
            // Ignore deletion failures
        }
    }

    /**
     * 枚举磁盘上本服务器已有的编号参考图 key。
     *
     * 图生视频的编号图位张数由工作流容量和用户增删共同决定，铺不出静态清单，
     * 只能按文件名扫。调用方（[clearDiskCache] / [loadFromDisk]）把它拼进自己的 key 清单里，
     * 漏掉任何一处都会表现为「图位重启后恢复不出来」或「清了缓存却删不掉」。
     */
    private fun discoverItvReferenceKeys(context: Context, serverId: String): List<MediaKey> {
        val prefix = "${serverId}_$ITV_REFERENCE_FILE_MARKER"
        val files = try {
            context.filesDir.listFiles()
        } catch (_: Exception) {
            null
        }
        return files.orEmpty().mapNotNull { file ->
            val name = file.name
            if (!name.startsWith(prefix) || !name.endsWith(".png")) return@mapNotNull null
            name.removePrefix(prefix).removeSuffix(".png").toIntOrNull()
                ?.let { MediaKey.ItvReference(it) }
        }
    }

    /**
     * Check if memory-first mode is enabled.
     */
    fun isMemoryFirstMode(): Boolean = isMemoryFirstMode

    /**
     * Store a bitmap. In memory-first mode, stores in RAM. In disk-first mode, writes to disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    /**
     * 预览图的边长上限。
     *
     * 这些落盘文件是「上次生成结果」的缓存，只用于手机上的预览。放大类工作流能产出上万像素的图，
     * 按原尺寸存下来（存的是**解码后位图的 PNG**）会有几十上百 MB，而它**每次冷启动都会被读回来** ——
     * 那是"重启 App 之后仍然卡"的直接原因：重启只清掉了内存，磁盘上那份巨大预览每次开机都重新吃一遍堆。
     * 2048 对手机预览足够，原始分辨率的图始终在服务器上（需要时可由查看器的下载链接取回）。
     */
    private val previewMaxDimension = 2048

    /**
     * 超过这个体积（约 32MB）的位图在进内存缓存前先降到预览尺寸。
     *
     * 生成结果可能是 6144 边（ARGB_8888 下约 151MB），常驻一份会让后续解码处处失败；
     * 调用方持有的原图不受影响（上传/显示仍用原对象）。
     */
    private const val LARGE_BITMAP_BYTES = 32 * 1024 * 1024

    /**
     * 视频字节驻留内存的体积上限（128MB）。
     *
     * 视频没有位图那样的"降采样"可用——要么整段进堆、要么不进。超长/高码率的视频
     * 动辄几百 MB，整段塞进 videoBytes 会让堆瞬间膨胀、后续解码处处 OOM；
     * 超限时改为只落盘不驻留（需要时走磁盘路径读出），与 LARGE_BITMAP_BYTES 同一目的。
     */
    private const val MAX_VIDEO_BYTES = 128L * 1024 * 1024

    /**
     * 落盘前把超大位图缩到预览尺寸；已经够小的原样返回（调用方据此判断要不要回收临时位图）。
     */
    private fun downscaleForPreview(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= previewMaxDimension) return bitmap
        val scale = previewMaxDimension.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /**
     * 按预览尺寸采样解码文件。
     *
     * 冷启动路径上这曾是在**主线程同步调用**的（MainContainerActivity.onCreate 里的 runBlocking），
     * 整幅解码一张几千像素的图会带来几十到几百 MB 的分配和肉眼可见的卡顿。
     * 4.3 起 loadFromDisk 移到后台协程，这里只在 IO 线程执行；采样仍然保留（省内存与解码时间）。
     */
    private fun decodeFileSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        if (longest <= previewMaxDimension) {
            return BitmapFactory.decodeFile(file.absolutePath)
        }
        var sampleSize = 1
        while (longest / sampleSize > previewMaxDimension) sampleSize *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    }

    fun putBitmap(key: MediaKey, bitmap: Bitmap, context: Context? = null) {
        val serverId = currentServerId
        if (serverId == null) {
            DebugLogger.w(TAG, "putBitmap SKIPPED - currentServerId is null (key: ${key.baseFilename})")
            return
        }

        DebugLogger.d(TAG, "putBitmap: ${key.baseFilename} (server: ${serverId.take(8)}..., mode: ${if (isMemoryFirstMode) "memory" else "disk"})")

        if (isMemoryFirstMode) {
            // Memory-first: store in memory, mark as dirty for later persistence
            // 超大位图先降到预览尺寸再常驻（见 LARGE_BITMAP_BYTES）；原图仍由调用方持有
            val stored = if (bitmap.byteCount > LARGE_BITMAP_BYTES) downscaleForPreview(bitmap) else bitmap
            bitmaps[key] = stored
            // 先存内容、后记版本：反过来落盘线程可能拿着新版本号读到上一份内容
            val version = MediaPersistVersionOps.nextVersion(versionCounter)
            MediaPersistVersionOps.markDirty(dirtyVersions, key, version)
            context?.let { schedulePersist(it) }
            DebugLogger.d(TAG, "putBitmap: stored in memory, marked dirty v$version (dirty count: ${dirtyVersions.size})")
        } else {
            // Disk-first: write to disk immediately, don't store in memory
            // 「禁用媒体缓存」打开：写前跳过（不落盘、不写临时文件），内存与调用方持有的原图不受影响
            if (context != null && AppSettings.isMediaCacheDisabled(context)) {
                DebugLogger.d(TAG, "putBitmap: media cache disabled, disk write SKIPPED (key: ${key.baseFilename})")
                return
            }
            context?.let { ctx ->
                try {
                    // 落盘前缩到预览尺寸：原尺寸存下来会让每次冷启动都多解码几十 MB（见 previewMaxDimension）
                    val preview = downscaleForPreview(bitmap)
                    ctx.openFileOutput(key.filename(serverId), Context.MODE_PRIVATE).use { out ->
                        preview.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    if (preview !== bitmap) preview.recycle()
                    DebugLogger.d(TAG, "putBitmap: written to disk")
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "putBitmap: disk write FAILED - ${e.message}")
                }
            } ?: DebugLogger.w(TAG, "putBitmap: context is null, cannot write to disk")
        }
    }

    /**
     * 各图片媒体键最近一次对应的 promptId。
     *
     * 生成结果有两条取图链路（完成事件的即时取图 + 屏幕侧收到事件后的取图），
     * 两边都会落到同一个媒体键上。这个映射让后到的一方能够确认"缓存里这张就是那张图"，
     * 从而跳过重复的下载与解码；没有匹配记录时一律照常下载，不会误用旧图。
     */
    private val imagePromptIds = ConcurrentHashMap<MediaKey, String>()

    /** 记下某个图片媒体键当前对应的 promptId（见 [imageMatchesPromptId]）。 */
    fun markImagePromptId(key: MediaKey, promptId: String) {
        imagePromptIds[key] = promptId
    }

    /** 该媒体键缓存的内容是否正是这个 promptId 的图。 */
    fun imageMatchesPromptId(key: MediaKey, promptId: String): Boolean =
        imagePromptIds[key] == promptId

    /**
     * Get a bitmap. In memory-first mode, returns from RAM. In disk-first mode, reads from disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun getBitmap(key: MediaKey, context: Context? = null): Bitmap? {
        val serverId = currentServerId
        if (serverId == null) {
            DebugLogger.w(TAG, "getBitmap SKIPPED - currentServerId is null (key: ${key.baseFilename})")
            return null
        }

        return if (isMemoryFirstMode) {
            // Memory-first: return from RAM
            val bitmap = bitmaps[key]
            DebugLogger.d(TAG, "getBitmap: ${key.baseFilename} from memory -> ${if (bitmap != null) "FOUND" else "NOT FOUND"}")
            bitmap
        } else {
            // Disk-first: read from disk each time
            context?.let { ctx ->
                try {
                    val file = ctx.getFileStreamPath(key.filename(serverId))
                    if (file.exists()) {
                        val bitmap = decodeFileSampled(file)
                        DebugLogger.d(TAG, "getBitmap: ${key.baseFilename} from disk -> ${if (bitmap != null) "FOUND" else "DECODE FAILED"}")
                        bitmap
                    } else {
                        DebugLogger.d(TAG, "getBitmap: ${key.baseFilename} from disk -> FILE NOT FOUND")
                        null
                    }
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "getBitmap: ${key.baseFilename} disk read FAILED - ${e.message}")
                    null
                }
            }
        }
    }

    /**
     * Store video bytes. In memory-first mode, stores in RAM. In disk-first mode, writes to disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun putVideoBytes(key: MediaKey, bytes: ByteArray, context: Context? = null) {
        val serverId = currentServerId ?: return

        // Track prompt ID for persistence
        when (key) {
            is MediaKey.TtvVideo -> currentTtvPromptId = key.promptId
            is MediaKey.ItvVideo -> currentItvPromptId = key.promptId
            else -> { }
        }

        if (isMemoryFirstMode) {
            // 超过内存上限的视频不驻留：只写盘、不进 videoBytes（视频整段无法缩小，
            // 只能整段跳过，见 MAX_VIDEO_BYTES）。放在"驱逐同类型旧视频"之前——
            // 新内容没进内存时，不该把还驻留着、可能尚未落盘的旧视频逐出。
            if (bytes.size > MAX_VIDEO_BYTES) {
                DebugLogger.w(
                    TAG,
                    "视频超出内存上限 ${MAX_VIDEO_BYTES / (1024 * 1024)} MB，已跳过内存驻留" +
                        "（key=${key.baseFilename}, size=${bytes.size}）"
                )
                context?.let { ctx ->
                    try {
                        // 落盘与磁盘优先分支同一写法，写完同样按总量上限裁一次旧视频
                        File(ctx.filesDir, key.filename(serverId)).writeBytes(bytes)
                        trimVideoFiles(ctx, serverId)
                    } catch (_: Exception) {
                        // Ignore write failures
                    }
                }
                return
            }
            // Memory-first: store in memory, mark as dirty for later persistence
            // 同类型只保留最新一份：视频键带 promptId，每生成一个视频就是新键，而旧键
            // 没有任何删除点（evict 按 key 精确匹配）——留着就是每个视频几十 MB 常驻不退。
            videoBytes.keys.filter { existing ->
                (existing is MediaKey.TtvVideo && key is MediaKey.TtvVideo) ||
                    (existing is MediaKey.ItvVideo && key is MediaKey.ItvVideo)
            }.forEach {
                videoBytes.remove(it)
                // 内容已丢，脏标记一并清掉（免得下一轮白跑一趟写盘）。
                // 版本发号器不动：它是全局单调的，回退会让旧快照的条件删除命中新内容（5.2）。
                dirtyVersions.remove(it)
            }
            videoBytes[key] = bytes
            // 与 putBitmap 同一约定：先存内容、后记版本（见 MediaPersistVersionOps）
            val version = MediaPersistVersionOps.nextVersion(versionCounter)
            MediaPersistVersionOps.markDirty(dirtyVersions, key, version)
            context?.let { schedulePersist(it) }
        } else {
            // Disk-first: write to disk immediately, don't store in memory
            // 「禁用媒体缓存」打开：写前跳过（与 putBitmap 的磁盘优先路径同一约定）
            if (context != null && AppSettings.isMediaCacheDisabled(context)) {
                DebugLogger.d(TAG, "putVideoBytes: media cache disabled, disk write SKIPPED (key: ${key.baseFilename})")
                return
            }
            context?.let { ctx ->
                try {
                    File(ctx.filesDir, key.filename(serverId)).writeBytes(bytes)
                    // 写盘成功后立刻按总量上限裁一次旧视频（5.1：磁盘侧原先没有任何删除点）
                    trimVideoFiles(ctx, serverId)
                } catch (_: Exception) {
                    // Ignore write failures
                }
            }
        }
    }

    /**
     * Get video bytes. In memory-first mode, returns from RAM. In disk-first mode, reads from disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun getVideoBytes(key: MediaKey, context: Context? = null): ByteArray? {
        val serverId = currentServerId ?: return null

        return if (isMemoryFirstMode) {
            // Memory-first: return from RAM
            videoBytes[key]
        } else {
            // Disk-first: read from disk each time
            context?.let { ctx ->
                try {
                    val file = File(ctx.filesDir, key.filename(serverId))
                    if (file.exists()) {
                        file.readBytes()
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Get a video URI for ExoPlayer playback.
     * In memory-first mode, creates a temp file from in-memory bytes.
     * In disk-first mode, returns URI directly to the stored file.
     */
    suspend fun getVideoUri(context: Context, key: MediaKey): Uri? {
        val serverId = currentServerId ?: return null

        return withContext(Dispatchers.IO) {
            try {
                if (isMemoryFirstMode) {
                    // Memory-first: create temp file from in-memory bytes
                    val bytes = videoBytes[key] ?: return@withContext null
                    // 专属前缀：与 MediaCache 落在同一目录、名字却是 playback_<hash>.mp4 的
                    // 播放临时文件彻底隔开（见策略注释的 N3 跨模块删除回归）。
                    // 仍放 cacheDir 根目录：设置页「清除缓存」等既有清扫按根目录扫 .mp4，
                    // 换子目录会让这些临时文件躲过清扫。
                    val tempFile = File(context.cacheDir, MediaPlaybackTempPolicy.fileNameFor(key.baseFilename))
                    // 写新文件前先清掉上一轮留下的临时文件（m8）：旧实现只写不删，
                    // 连续看不同视频会在 cacheDir 里一直累积到系统自己清。
                    // 保留本次这份与播放登记中的（可能正被 ExoPlayer 读着）。
                    // 注意传的是 base 名：策略内部翻译成临时文件名再比对（见策略注释）
                    MediaPlaybackTempPolicy.cleanupDirectory(
                        cacheDir = context.cacheDir,
                        keepName = tempFile.name,
                        playingBaseNames = playingVideoBaseNames
                    )
                    tempFile.writeBytes(bytes)
                    // P2-12：播放方就在本进程（ExoPlayer / MediaMetadataRetriever），
                    // file:// 足够，不需要 FileProvider。这样 file_paths.xml 才能把
                    // 整个 cacheDir/filesDir 从可共享根里去掉（分享一律走 ShareFileStore）。
                    Uri.fromFile(tempFile)
                } else {
                    // Disk-first: return URI to the stored file directly
                    val file = File(context.filesDir, key.filename(serverId))
                    if (file.exists()) {
                        Uri.fromFile(file)
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 播放方登记：这个视频正在播放，裁剪时必须保留（见 [playingVideoBaseNames]）。
     *
     * 只对视频键生效；图片键没有对应的磁盘裁剪路径。
     */
    fun registerPlayingVideo(key: MediaKey) {
        if (key.isVideoKey()) {
            playingVideoBaseNames += key.baseFilename
            DebugLogger.d(TAG, "registerPlayingVideo: ${key.baseFilename}（裁剪保护 +1，共 ${playingVideoBaseNames.size}）")
        }
    }

    /** 播放方注销：不再播放这个视频，之后按常规策略裁剪即可。 */
    fun unregisterPlayingVideo(key: MediaKey) {
        if (key.isVideoKey()) {
            playingVideoBaseNames -= key.baseFilename
            DebugLogger.d(TAG, "unregisterPlayingVideo: ${key.baseFilename}（裁剪保护 -1，剩 ${playingVideoBaseNames.size}）")
        }
    }

    /**
     * 按总量上限裁剪本服务器的旧视频文件（5.1：磁盘侧原先没有任何删除点）。
     *
     * 每次生成的视频都是一个新文件（`{serverId}_..._{promptId}.mp4`），内存里同类型只留最新一份，
     * 磁盘却只增不减，单个服务器攒到几个 GB 很常见，而旧视频几乎不会再被打开。
     * 规则见 [VideoTrimPolicy]：最近 [KEEP_AT_LEAST] 个一律保留，其余在总量超过
     * [MAX_VIDEO_BYTES_PER_SERVER] 时从最旧的开始逐个删除，直到不再超限。
     *
     * 永不删除：每类当前展示的视频（[currentTtvPromptId] / [currentItvPromptId]）
     * 与播放方登记过的正在播放的视频（[registerPlayingVideo]）。
     *
     * 调用点（都在 IO 上）：
     * - putVideoBytes 磁盘优先模式写完新视频后；
     * - persistToDisk 写完视频后；
     * - 启动期补裁一次（[scheduleStartupTrim]，由 [loadFromDiskInternal] /
     *   [discoverVideoPromptIds] 投递，不阻塞首帧）——
     *   只在「写入新视频」时裁的话，历史视频堆积但此后再不生成的设备永远等不到裁剪。
     */
    private fun trimVideoFiles(context: Context, serverId: String) {
        try {
            val prefix = "${serverId}_"
            val files = context.filesDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".mp4") }
                ?: return
            if (files.isEmpty()) return

            // 受保护名单按 base 文件名维护，服务器前缀由 VideoTrimPolicy.planForServer 统一补：
            // - 当前展示的视频：内存优先模式下播放用的是 cacheDir 临时文件，
            //   但 filesDir 这份是重启后的恢复来源；磁盘优先模式下播放的就是这个文件
            // - 播放方登记的正在播放的视频：可能是上一轮的 promptId，只靠「最近一次」保护不住
            val protectedBaseNames = mutableSetOf<String>()
            currentTtvPromptId?.let { protectedBaseNames += MediaKey.TtvVideo(it).baseFilename }
            currentItvPromptId?.let { protectedBaseNames += MediaKey.ItvVideo(it).baseFilename }
            protectedBaseNames += playingVideoBaseNames

            val infos = files.map { VideoFileInfo(it.name, it.length(), it.lastModified()) }
            val victims = VideoTrimPolicy.planForServer(
                files = infos,
                serverId = serverId,
                protectedBaseNames = protectedBaseNames,
                maxTotalBytes = MAX_VIDEO_BYTES_PER_SERVER,
                keepAtLeast = KEEP_AT_LEAST
            )
            if (victims.isEmpty()) return

            var freedBytes = 0L
            for (name in victims) {
                val file = File(context.filesDir, name)
                val size = file.length()
                if (file.delete()) {
                    freedBytes += size
                    DebugLogger.d(TAG, "trimVideoFiles: 删除旧视频 ${file.name}")
                }
            }
            DebugLogger.i(TAG, "trimVideoFiles: 删除 ${victims.size} 个旧视频，释放 ${freedBytes / (1024 * 1024)}MB")
        } catch (e: Exception) {
            // 裁剪失败不影响主流程：下次写盘还会再试一次
            DebugLogger.w(TAG, "trimVideoFiles FAILED - ${e.message}")
        }
    }

    /**
     * 启动期补裁一次（5.2）：裁剪原先只在「写入一个新视频」时触发，
     * 已有大量历史视频、此后再不生成的设备永远等不到那次写入。
     *
     * 投递到应用级 IO 作用域后**立即返回**：两条启动路径都必须在裁剪之前就放行首帧
     * （内存优先靠 [loadFromDisk] 的 finally 置就绪、磁盘优先靠 [discoverVideoPromptIds] 同步置就绪）。
     * 视频积压多时枚举 + 逐个删除要几百毫秒，挡在就绪信号前面就是可见的启动卡顿。
     * 「禁用媒体缓存」时不裁（连 SharedPreferences 的读取一起挪到 IO），避免替用户删历史文件。
     */
    private fun scheduleStartupTrim(context: Context, serverId: String) {
        val appContext = context.applicationContext
        persistScope.launch {
            withContext(Dispatchers.IO) {
                if (AppSettings.isMediaCacheDisabled(appContext)) {
                    DebugLogger.d(TAG, "scheduleStartupTrim: media cache disabled, startup trim SKIPPED")
                    return@withContext
                }
                trimVideoFiles(appContext, serverId)
            }
        }
    }

    /**
     * 防抖落盘：脏数据出现后延迟一小段再异步写盘。
     *
     * 以前只在 onStop 里 `runBlocking { persistToDisk() }` —— 那是主线程同步等待全量 PNG
     * 压缩与视频写盘，退到后台时会卡几百毫秒到数秒。现在预览一更新就顺手异步落盘，
     * onStop 的兜底调用通常已无脏数据（几乎不耗时）；4.3 起连这次兜底也改成投递到
     * 应用级 IO 作用域（见 [persistInBackground]），onStop 不再阻塞。
     */
    private fun schedulePersist(context: Context) {
        // 「禁用媒体缓存」打开：不启动防抖落盘（写前检查，脏数据留在内存，用户当场仍能看到）
        if (AppSettings.isMediaCacheDisabled(context)) {
            DebugLogger.d(TAG, "schedulePersist: media cache disabled, persist SKIPPED (dirty: ${dirtyVersions.size})")
            return
        }
        // 「检查 + 赋值」必须原子（persistLock）：不加锁时两个线程可同时通过
        // isActive 检查、各 launch 一个落盘协程，两个协程并发写同一份磁盘文件
        // （内容交错损坏）。launch 的创建与赋值放进锁内，协程体本身仍在锁外执行。
        synchronized(persistLock) {
            if (persistJob?.isActive == true) return
            persistJob = persistScope.launch {
                delay(PERSIST_DEBOUNCE_MS)
                persistToDisk(context.applicationContext)
                // 落盘期间可能又攒了新的脏数据（连续出图/连续换图），补排一次。
                // 必须先把句柄清掉，否则下面的 schedulePersist 会因为"上一个任务还活着"而直接返回。
                persistJob = null
                if (dirtyVersions.isNotEmpty()) {
                    schedulePersist(context.applicationContext)
                }
            }
        }
    }

    /**
     * 把落盘任务投递到应用级 IO 作用域后**立即返回**。
     *
     * onStop 用这个入口：以前是 `runBlocking { persistToDisk() }` —— 主线程同步等全量 PNG 压缩
     * 与视频写盘，退到后台会卡几百毫秒到数秒。现在 onStop 不再阻塞。
     * 代价：进程若在写完前被杀会丢这一轮脏数据；但预览本来就有 800ms 防抖落盘兜底
     * （见 [schedulePersist]），onStop 时通常已经没有脏数据。
     */
    fun persistInBackground(context: Context) {
        val appContext = context.applicationContext
        persistScope.launch { persistToDisk(appContext) }
    }

    /** 视频键（带 promptId 的两种）与图片键共用一套版本账本，落盘时按类型分流。 */
    private fun MediaKey.isVideoKey(): Boolean = this is MediaKey.TtvVideo || this is MediaKey.ItvVideo

    suspend fun persistToDisk(context: Context) {
        val serverId = currentServerId
        if (serverId == null) {
            DebugLogger.w(TAG, "persistToDisk SKIPPED - currentServerId is null! (dirty: ${dirtyVersions.size})")
            return
        }

        // 快照 (key, version)：写盘期间同一 key 若再被写入，它的脏版本会被推进，
        // 本轮的条件删除就不会命中（见 MediaPersistVersionOps），新内容留到下一轮
        val pending = MediaPersistVersionOps.snapshot(dirtyVersions)
        if (pending.isEmpty()) {
            DebugLogger.d(TAG, "persistToDisk: nothing to persist")
            return
        }
        val bitmapsToPersist = pending.filter { !it.first.isVideoKey() }
        val videosToPersist = pending.filter { it.first.isVideoKey() }
        DebugLogger.i(
            TAG,
            "persistToDisk START (server: ${serverId.take(8)}..., dirty: ${bitmapsToPersist.size} bitmaps, ${videosToPersist.size} videos)"
        )

        withContext(Dispatchers.IO) {
            // Launch all writes in parallel for maximum speed
            val bitmapJobs = bitmapsToPersist.map { (key, version) ->
                async {
                    val bitmap = bitmaps[key]
                    if (bitmap == null) {
                        // 内容已被 evict：按版本条件移脏标记，别把写盘期间新登记的版本误清掉
                        MediaPersistVersionOps.clearIfStillDirty(dirtyVersions, key, version)
                        DebugLogger.w(TAG, "persistToDisk: bitmap ${key.baseFilename} is dirty but not in memory!")
                        return@async false
                    }
                    try {
                        val preview = downscaleForPreview(bitmap)
                        context.openFileOutput(key.filename(serverId), Context.MODE_PRIVATE).use { out ->
                            preview.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        if (preview !== bitmap) preview.recycle()
                        val cleared = MediaPersistVersionOps.clearIfStillDirty(dirtyVersions, key, version)
                        DebugLogger.d(
                            TAG,
                            "persistToDisk: ${key.baseFilename} -> OK" +
                                if (cleared) "" else "（写盘期间来了新版本，脏标记保留待下一轮）"
                        )
                        true
                    } catch (e: Exception) {
                        DebugLogger.e(TAG, "persistToDisk: ${key.baseFilename} -> FAILED: ${e.message}")
                        false
                    }
                }
            }

            val videoJobs = videosToPersist.map { (key, version) ->
                async {
                    val bytes = videoBytes[key]
                    if (bytes == null) {
                        // 同 bitmap 分支：按版本条件移脏标记
                        MediaPersistVersionOps.clearIfStillDirty(dirtyVersions, key, version)
                        DebugLogger.w(TAG, "persistToDisk: video ${key.baseFilename} is dirty but not in memory!")
                        return@async false
                    }
                    try {
                        File(context.filesDir, key.filename(serverId)).writeBytes(bytes)
                        val cleared = MediaPersistVersionOps.clearIfStillDirty(dirtyVersions, key, version)
                        DebugLogger.d(
                            TAG,
                            "persistToDisk: ${key.baseFilename} -> OK" +
                                if (cleared) "" else "（写盘期间来了新版本，脏标记保留待下一轮）"
                        )
                        true
                    } catch (e: Exception) {
                        DebugLogger.e(TAG, "persistToDisk: ${key.baseFilename} -> FAILED: ${e.message}")
                        false
                    }
                }
            }

            // Wait for all writes to complete
            val bitmapResults = bitmapJobs.awaitAll()
            val videoResults = videoJobs.awaitAll()

            val bitmapSuccess = bitmapResults.count { it }
            val bitmapFailed = bitmapResults.count { !it }
            val videoSuccess = videoResults.count { it }
            val videoFailed = videoResults.count { !it }

            DebugLogger.i(TAG, "persistToDisk DONE (bitmaps: $bitmapSuccess OK, $bitmapFailed failed; videos: $videoSuccess OK, $videoFailed failed)")

            // 视频写完后再按总量上限裁一次（内存优先模式的主体写入路径；
            // 磁盘优先模式在 putVideoBytes 写完时就裁，见那里的 trimVideoFiles）。
            // 必须在 IO 块内：SettingsViewModel.setMemoryFirstCache 会从主线程直接调 persistToDisk，
            // 放到块外就等于在主线程序列 filesDir、删文件（5.2 修正）。
            if (videosToPersist.isNotEmpty()) {
                trimVideoFiles(context, serverId)
            }
        }
    }

    /**
     * Load persisted media from disk into memory for the current server.
     * Called from MainContainerActivity.onCreate()（4.3 起改为异步调用，不再主线程 runBlocking）。
     *
     * 本轮开始时先复位闩锁、结束时（成功、失败、无服务器都算）置 true ——
     * 界面据此从占位内容切到导航图，屏幕初始化就不会读到空媒体。
     * 置 true 只认**本轮令牌**：期间若又开了新一轮（[beginRestoreSession] / 换服务器），
     * 这次迟到的完成不会把新一轮误标成已就绪（见 [MediaRestoreLatch]）。
     *
     * 令牌同时兼作「写回许可」：换服务器 / 又开一轮之后，本方法会在下一次校验点停手，
     * 不把旧服务器的媒体写进当前档位（校验点见 [loadFromDiskInternal]）。
     */
    suspend fun loadFromDisk(context: Context) {
        val token = restoreLatch.beginRestore()
        try {
            loadFromDiskInternal(context, token)
        } finally {
            restoreLatch.markRestored(token)
        }
    }

    private suspend fun loadFromDiskInternal(context: Context, token: Long) {
        val serverId = currentServerId
        if (serverId == null) {
            DebugLogger.w(TAG, "loadFromDisk SKIPPED - currentServerId is null!")
            return
        }

        DebugLogger.i(TAG, "loadFromDisk START (server: ${serverId.take(8)}...)")

        withContext(Dispatchers.IO) {
            // Load bitmap files
            val bitmapKeys = listOf(
                MediaKey.TtiPreview,
                MediaKey.ItiPreview,
                MediaKey.ItiSource,
                MediaKey.TtvPreview,
                MediaKey.ItvPreview,
                MediaKey.ItvSource,
                MediaKey.IteReferenceImage1,
                MediaKey.IteReferenceImage2
            ) + discoverItvReferenceKeys(context, serverId)

            var bitmapsLoaded = 0
            var bitmapsNotFound = 0
            for (key in bitmapKeys) {
                // 本轮已被作废（换服务器 / 又开了一轮）：立刻停手。
                // 注意 clearAll 不会走到这里 —— 它不作废令牌，为的是让本轮把数据写完（见 settleReady）。
                if (!restoreLatch.isCurrent(token)) {
                    DebugLogger.w(TAG, "loadFromDisk ABORTED - 本轮恢复已被更新的一轮取代")
                    return@withContext
                }
                try {
                    val file = context.getFileStreamPath(key.filename(serverId))
                    if (file.exists()) {
                        val bitmap = decodeFileSampled(file)
                        if (bitmap != null) {
                            bitmaps[key] = bitmap
                            bitmapsLoaded++
                            DebugLogger.d(TAG, "loadFromDisk: ${key.baseFilename} -> LOADED")
                            // NOT dirty - already on disk
                        } else {
                            DebugLogger.w(TAG, "loadFromDisk: ${key.baseFilename} -> DECODE FAILED")
                        }
                    } else {
                        bitmapsNotFound++
                    }
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "loadFromDisk: ${key.baseFilename} -> ERROR: ${e.message}")
                }
            }

            // 视频回填同样要过令牌校验：这两个回调会写 videoBytes 与 current promptId，
            // 旧服务器的值写进去会直接顶掉当前服务器刚要展示的视频
            if (!restoreLatch.isCurrent(token)) {
                DebugLogger.w(TAG, "loadFromDisk ABORTED - 本轮恢复已被更新的一轮取代（跳过视频回填）")
                return@withContext
            }

            // Load latest video files for this server
            val serverPrefix = "${serverId}_"
            loadLatestVideoFile(context, serverPrefix, VideoUtils.FilePrefix.TEXT_TO_VIDEO) { promptId, bytes ->
                val key = MediaKey.TtvVideo(promptId)
                videoBytes[key] = bytes
                currentTtvPromptId = promptId
                DebugLogger.d(TAG, "loadFromDisk: TTV video -> LOADED (promptId: ${promptId.take(8)}...)")
            }

            loadLatestVideoFile(context, serverPrefix, VideoUtils.FilePrefix.IMAGE_TO_VIDEO) { promptId, bytes ->
                val key = MediaKey.ItvVideo(promptId)
                videoBytes[key] = bytes
                currentItvPromptId = promptId
                DebugLogger.d(TAG, "loadFromDisk: ITV video -> LOADED (promptId: ${promptId.take(8)}...)")
            }

            DebugLogger.i(TAG, "loadFromDisk DONE (loaded: $bitmapsLoaded bitmaps, not found: $bitmapsNotFound)")

            // 启动期补裁一次（5.2）：见 scheduleStartupTrim。
            // 这里只投递、不等待 —— 若在本 IO 块里同步裁，裁剪结束前本方法不返回，
            // loadFromDisk 的 finally 也就不会 markRestored，视频积压多时会拖住首帧。
            scheduleStartupTrim(context, serverId)
        }
    }

    private fun loadLatestVideoFile(
        context: Context,
        serverPrefix: String,
        videoPrefix: String,
        onLoaded: (promptId: String, bytes: ByteArray) -> Unit
    ) {
        try {
            val fullPrefix = "$serverPrefix$videoPrefix"
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith(fullPrefix) && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { file ->
                    val promptId = file.name
                        .removePrefix(fullPrefix)
                        .removeSuffix(".mp4")
                    // 冷启动读入同样过内存上限：超限文件整段 readBytes 会把堆顶爆，
                    // 跳过读入也不回调（不驻留内存），需要时走 getVideoBytes 的磁盘路径读出。
                    if (file.length() > MAX_VIDEO_BYTES) {
                        DebugLogger.w(
                            TAG,
                            "视频超出内存上限 ${MAX_VIDEO_BYTES / (1024 * 1024)} MB，已跳过冷启动读入" +
                                "（key=${file.name}, size=${file.length()}）"
                        )
                        return@let
                    }
                    val bytes = file.readBytes()
                    onLoaded(promptId, bytes)
                }
        } catch (_: Exception) {
            // Ignore load failures
        }
    }

    /**
     * Remove a specific media item from memory.
     */
    fun evict(key: MediaKey) {
        bitmaps.remove(key)
        videoBytes.remove(key)
        // 只清脏标记，**不清版本发号器**：回退版本会让旧一轮写盘快照的条件删除
        // 命中下一次写入的脏标记，把未落盘的新内容清掉（5.2，见 [versionCounter]）
        dirtyVersions.remove(key)

        // Clear prompt ID tracking
        when (key) {
            is MediaKey.TtvVideo -> if (key.promptId == currentTtvPromptId) currentTtvPromptId = null
            is MediaKey.ItvVideo -> if (key.promptId == currentItvPromptId) currentItvPromptId = null
            else -> { }
        }
    }

    /**
     * Remove a specific media item from memory AND delete from disk.
     * Use this when the item should not be restored on app restart.
     */
    suspend fun evictAndDeleteFromDisk(context: Context, key: MediaKey) {
        val serverId = currentServerId ?: return
        evict(key)
        withContext(Dispatchers.IO) {
            try {
                val file = context.getFileStreamPath(key.filename(serverId))
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {
                // Ignore deletion failures
            }
        }
    }

    /**
     * Clear all media from memory.
     * Called during cache clearing.
     *
     * 恢复闩锁的处理分两种（见 [MediaRestoreLatch.settleReady]）：
     * - 没有恢复在跑：直接收敛到「就绪」—— 清空之后没有可等待的内容，
     *   置未就绪会让界面停在占位分支上**永远**等（清缓存、断线重连之后都不会再启动一轮恢复）；
     * - 有恢复在跑（启动恢复途中恰逢 connect()→invalidateAll→clearAll）：**保持未就绪**，
     *   等那一轮自己完成。清空只清了内存，它马上还会把数据写回内存；此刻置就绪，
     *   界面会先按空媒体组合导航图，而屏幕只读一次媒体、不会补刷。
     *   这一轮也不作废（[MediaRestoreLatch.isCurrent] 仍为真），否则没人再来置就绪。
     */
    fun clearAll() {
        DebugLogger.i(TAG, "Clearing all media from memory")
        clearMemoryState()
        currentTtvPromptId = null
        currentItvPromptId = null
        restoreLatch.settleReady()
    }

    /**
     * Get the current Text-to-Video prompt ID (if any video is loaded).
     */
    fun getCurrentTtvPromptId(): String? = currentTtvPromptId

    /**
     * Get the current Image-to-Video prompt ID (if any video is loaded).
     */
    fun getCurrentItvPromptId(): String? = currentItvPromptId

    /**
     * Clear the current Text-to-Video prompt ID tracking.
     * This prevents video restoration on subsequent screen initializations.
     */
    fun clearCurrentTtvPromptId() {
        currentTtvPromptId = null
    }

    /**
     * Clear the current Image-to-Video prompt ID tracking.
     * This prevents video restoration on subsequent screen initializations.
     */
    fun clearCurrentItvPromptId() {
        currentItvPromptId = null
    }

    /**
     * Discover video prompt IDs from disk without loading bytes into memory.
     * Used in disk-first mode to enable video restoration on app restart.
     * In disk-first mode, videos are read on-demand, but we still need to know
     * which promptIds have saved videos.
     *
     * 这是磁盘优先模式的「本轮恢复」入口：调用同步完成，闩锁在同一帧内从
     * 未就绪切到就绪（见 [MediaRestoreLatch]），界面不会看到占位闪一下。
     */
    fun discoverVideoPromptIds(context: Context) {
        val token = restoreLatch.beginRestore()
        val serverId = currentServerId ?: run {
            DebugLogger.w(TAG, "discoverVideoPromptIds SKIPPED - currentServerId is null!")
            // 没有服务器就没有可恢复的媒体；同样置就绪，别让界面停在占位内容上
            restoreLatch.markRestored(token)
            return
        }
        val serverPrefix = "${serverId}_"

        // Discover TTV video
        try {
            val fullPrefix = "$serverPrefix${VideoUtils.FilePrefix.TEXT_TO_VIDEO}"
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith(fullPrefix) && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { file ->
                    currentTtvPromptId = file.name
                        .removePrefix(fullPrefix)
                        .removeSuffix(".mp4")
                }
        } catch (_: Exception) {
            // Ignore discovery failures
        }

        // Discover ITV video
        try {
            val fullPrefix = "$serverPrefix${VideoUtils.FilePrefix.IMAGE_TO_VIDEO}"
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith(fullPrefix) && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { file ->
                    currentItvPromptId = file.name
                        .removePrefix(fullPrefix)
                        .removeSuffix(".mp4")
                }
        } catch (_: Exception) {
            // Ignore discovery failures
        }

        // 磁盘优先模式下这就是启动期媒体恢复的最后一步：字节按需读取，这里只需要 promptId。
        // 先置就绪，别让历史视频的裁剪（下面的 IO 任务）挡住首帧。
        restoreLatch.markRestored(token)

        // 启动期补裁一次（5.2）：这条路径是磁盘优先模式唯一的启动入口，
        // 不在这里补的话，「已有大量历史视频但此后再不生成」的设备永远不会被裁。
        // 与内存优先路径走同一个投递入口（scheduleStartupTrim）：本方法在 onCreate 是同步跑的，
        // 枚举 + 删除必须挪到 IO，也不能挡在 markRestored 后面。
        scheduleStartupTrim(context, serverId)
    }

    /**
     * Check if a bitmap exists. In memory-first mode, checks RAM. In disk-first mode, checks disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun hasBitmap(key: MediaKey, context: Context? = null): Boolean {
        val serverId = currentServerId ?: return false

        return if (isMemoryFirstMode) {
            bitmaps.containsKey(key)
        } else {
            context?.let { ctx ->
                try {
                    ctx.getFileStreamPath(key.filename(serverId)).exists()
                } catch (e: Exception) {
                    false
                }
            } ?: false
        }
    }

    /**
     * Check if video bytes exist. In memory-first mode, checks RAM. In disk-first mode, checks disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun hasVideoBytes(key: MediaKey, context: Context? = null): Boolean {
        val serverId = currentServerId ?: return false

        return if (isMemoryFirstMode) {
            videoBytes.containsKey(key)
        } else {
            context?.let { ctx ->
                try {
                    File(ctx.filesDir, key.filename(serverId)).exists()
                } catch (e: Exception) {
                    false
                }
            } ?: false
        }
    }

    /**
     * Cleanup orphaned media files that don't belong to any registered server.
     * Call this after deleting a server or during app cleanup.
     *
     * 判据见 [MediaOrphanFilePolicy]：**已知 serverId 白名单（精确前缀比对）+ 已知媒体文件名模式**
     * 双条件同时成立才删。旧实现只看「下划线前是 36 字符含 `-`」，既可能误删形状巧合的非媒体
     * 文件，又永远清不掉 serverId 不是 UUID 形状的孤儿（m9）。
     *
     * @param context Application context
     * @param validServerIds List of server IDs that should be kept
     * @return 实际删掉的文件数（供维护流程汇总日志用；失败时返回已删的部分，绝不抛异常）
     */
    fun cleanupOrphanedMediaFiles(context: Context, validServerIds: Set<String>): Int {
        DebugLogger.i(TAG, "Cleaning up orphaned media files")
        return try {
            val deleted = MediaOrphanFilePolicy.cleanupDirectory(context.filesDir, validServerIds)
            for (name in deleted) {
                DebugLogger.d(TAG, "Deleting orphaned file: $name")
            }
            deleted.size
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to cleanup orphaned files: ${e.message}")
            0
        }
    }
}

/**
 * 内存态播放临时文件的清理判据（m8，见 [MediaStateHolder.getVideoUri]）。
 *
 * 内存优先模式每次播放都会在 cacheDir 写一份临时 mp4，而旧实现**没有任何删除点**：
 * 看得越多，cacheDir 里攒得越多，直到系统清缓存。这里在写新文件前清一遍，但有两类必须留：
 * - 本次要用的那份（[keepName]，下一行就要 writeBytes）；
 * - 播放登记表里的（[MediaStateHolder.registerPlayingVideo]，可能正被 ExoPlayer 读着，
 *   删掉播放会当场中断）。
 *
 * 清理是尽力而为：目录不可读、单个删除失败都只跳过，绝不挡住播放。
 *
 * ⚠️ 两个命名空间不能混（m8 复核修正）：播放登记表存的是 **base 文件名**
 * （`last_generated_video_<id>.mp4`，见 [MediaStateHolder.registerPlayingVideo]），
 * 磁盘上是 **临时文件名**。本 API 的入参一律收 base 名，内部经 [playingTempNames]
 * 翻译后再比对；直接把磁盘名喂进来会因 hash 不同而永不命中。
 *
 * ⚠️ 跨模块隔离（m8 复核修正 · N3）：[sh.hnet.comfychair.cache.MediaCache] 的播放临时文件
 * 也叫 `playback_<hash>.mp4` 且直接落在 cacheDir 根目录。若本策略按「`playback_` 前缀 +
 * `.mp4` 后缀」扫 cacheDir 根，就会把 MediaCache 的文件一起删掉；而 MediaCache 把 URI
 * 缓存在 `videoUriCache` 里、命中即返回、不校验文件是否还在，也没有失效点。可复现链路：
 * 图库看视频（写临时文件 + 缓存 URI）→ 进生成页播视频（触发本策略清理）→ 回图库重播，
 * 拿到指向已删除文件的旧 URI，播放器打不开且不会自愈。
 * 因此本模块的临时文件改用独立前缀 [PREFIX]（`state_playback_`），**不再使用裸
 * `playback_`**：清理判据以整个前缀匹配，MediaCache 的 `playback_<hash>.mp4` 永远不满足
 * 条件，删除范围彻底限定在本模块自己的产出内。
 * 仍然留在 cacheDir 根目录（不换子目录）是有意的：设置页「清除缓存」等既有清扫按根目录
 * 扫 `.mp4` 文件，换成子目录反而会让这些临时文件躲过清扫。
 *
 * 遗留文件：本次修正前写下的 `playback_<hash>.mp4` 无法与 MediaCache 的文件区分，
 * **一律不删**（宁可留几个旧文件，也不冒误删正在用的文件的风险），交给系统缓存回收。
 */
internal object MediaPlaybackTempPolicy {

    /**
     * 专属文件前缀。刻意不再用裸 `playback_`：MediaCache 的播放临时文件正是
     * `playback_<hash>.mp4`（MediaCache.kt:1577），两者前缀必须错开，
     * 否则本策略的清理会跨模块误删。
     */
    const val PREFIX = "state_playback_"
    const val SUFFIX = ".mp4"

    /** `state_playback_<hash>.mp4`：hash 用 [String.hashCode]。 */
    fun fileNameFor(baseFilename: String): String = "$PREFIX${baseFilename.hashCode()}$SUFFIX"

    /** 把「正在播放的 base 文件名」翻译成磁盘上的临时文件名（两者命名空间不同，见类注释）。 */
    fun playingTempNames(playingBaseNames: Set<String>): Set<String> =
        if (playingBaseNames.isEmpty()) emptySet() else playingBaseNames.mapTo(HashSet()) { fileNameFor(it) }

    /**
     * 这个文件名是否该删：**本模块前缀** + **不是本次要用的** + **不在播放登记里**。
     *
     * [playingTempNames] 必须是 [playingTempNames] 翻译过的临时文件名集合
     * ——磁盘名对磁盘名比对，不能拿 base 名直接比。
     * 前缀 [PREFIX] 是跨模块隔离的唯一依据：cacheDir 根目录里还有 MediaCache 的
     * `playback_*.mp4` 与 `temp_meta_*`，前缀不匹配就必须原样保留。
     */
    fun shouldDelete(name: String, keepName: String, playingTempNames: Set<String>): Boolean =
        name.startsWith(PREFIX) &&
            name.endsWith(SUFFIX) &&
            name != keepName &&
            name !in playingTempNames

    /**
     * 在 [cacheDir] 上执行一次清理，返回实际删掉的文件名。
     *
     * @param playingBaseNames 正在播放的视频 **base 文件名**集合（生产端直接传
     *   [MediaStateHolder] 的注册表；内部负责翻译成磁盘文件名）
     *
     * 生产路径传 `context.cacheDir`；单测传临时目录（不碰真实 cacheDir）。
     * 目录不存在时直接返回空清单、不抛异常。
     */
    fun cleanupDirectory(cacheDir: File, keepName: String, playingBaseNames: Set<String>): List<String> {
        val deleted = mutableListOf<String>()
        val files = try {
            cacheDir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return deleted
        val protectedTempNames = playingTempNames(playingBaseNames)
        for (file in files) {
            if (!file.isFile) continue
            val name = file.name
            if (!shouldDelete(name, keepName, protectedTempNames)) continue
            if (file.delete()) deleted += name
        }
        return deleted
    }
}

/**
 * 孤儿媒体文件的删除判据（m9，见 [MediaStateHolder.cleanupOrphanedMediaFiles]）。
 *
 * 旧实现只认「第一个下划线前是 36 字符且含 `-`」就删，两个漏洞：
 * 1. **误删**：任何恰好长成 UUID 形状开头的文件都会被当成孤儿媒体删掉，
 *    与它是不是本模块产出的媒体毫无关系；
 * 2. **漏清**：serverId 不是 UUID 形状时（导入的旧配置、自定义 ID）永远进不了这个分支，
 *    孤儿文件永远留着。
 *
 * 现在改成双条件 —— 文件名必须**同时**满足：
 * - serverId 部分**不在**已知白名单里（在的话是活服务器的文件，一律留）；
 * - 去掉 serverId 后剩下的 base 名**属于本模块能产出的已知媒体模式**。
 * 未知形状一律不删（fail-closed）：宁可留垃圾，也不能误删用户数据。
 *
 * 白名单按「完整前缀 + 下划线」精确比对，不对 serverId 做任何 UUID 形状假设；
 * 反过来，切出 serverId 用「已知 base 名后缀」定位边界，所以 serverId 含几个下划线都不影响。
 */
internal object MediaOrphanFilePolicy {

    /** 本模块写进 filesDir 的固定 base 名（对照 [MediaStateHolder.MediaKey]）。 */
    private val FIXED_BASE_NAMES = setOf(
        "tti_last_preview.png",
        "iti_last_preview.png",
        "ttv_last_preview.png",
        "itv_last_preview.png",
        "iti_last_source.png",
        "itv_last_source.png",
        "ite_reference_1.png",
        "ite_reference_2.png"
    )

    /** 编号参考图：`itv_reference_<N>.png`。 */
    private const val ITV_REFERENCE_PREFIX = "itv_reference_"

    /** 视频文件名：`<prefix><promptId>.mp4`（前缀取自 VideoUtils.FilePrefix）。 */
    private const val VIDEO_SUFFIX = ".mp4"
    private val VIDEO_PREFIXES = listOf(
        "last_generated_video_",
        "image_to_video_last_generated_"
    )

    /**
     * 去掉 serverId 前缀后的 base 名是不是已知媒体名。
     *
     * 固定名直接比对；编号参考图要求编号是整数；视频要求 promptId 非空。
     */
    fun isKnownMediaBaseName(baseName: String): Boolean {
        if (baseName in FIXED_BASE_NAMES) return true
        if (baseName.startsWith(ITV_REFERENCE_PREFIX) && baseName.endsWith(".png")) {
            return baseName
                .removePrefix(ITV_REFERENCE_PREFIX)
                .removeSuffix(".png")
                .toIntOrNull() != null
        }
        if (baseName.endsWith(VIDEO_SUFFIX)) {
            return VIDEO_PREFIXES.any { prefix ->
                baseName.startsWith(prefix) &&
                    baseName.removePrefix(prefix).removeSuffix(VIDEO_SUFFIX).isNotEmpty()
            }
        }
        return false
    }

    /**
     * 把 `{serverId}_{baseName}` 拆成 (serverId, baseName)；形状不在已知媒体模式里时返回 null。
     *
     * 用「已知 base 名后缀」定位边界，而不是按第一个下划线切 —— serverId 自身可能含 `_`。
     */
    private fun splitServerIdAndBase(fileName: String): Pair<String, String>? {
        // 固定名：`<serverId>_<fixed>`
        for (base in FIXED_BASE_NAMES) {
            val marker = "_$base"
            if (fileName.endsWith(marker) && fileName.length > marker.length) {
                return fileName.removeSuffix(marker) to base
            }
        }
        // 编号参考图：`<serverId>_itv_reference_<N>.png`
        if (fileName.endsWith(".png")) {
            val marker = "_$ITV_REFERENCE_PREFIX"
            val index = fileName.lastIndexOf(marker)
            if (index > 0) {
                val base = fileName.substring(index + 1)
                if (isKnownMediaBaseName(base)) return fileName.substring(0, index) to base
            }
        }
        // 视频：`<serverId>_<videoPrefix><promptId>.mp4`
        if (fileName.endsWith(VIDEO_SUFFIX)) {
            for (prefix in VIDEO_PREFIXES) {
                val marker = "_$prefix"
                val index = fileName.lastIndexOf(marker)
                if (index > 0) {
                    val base = fileName.substring(index + 1)
                    if (isKnownMediaBaseName(base)) return fileName.substring(0, index) to base
                }
            }
        }
        return null
    }

    /**
     * 这个文件名是否该被当作「已删服务器的孤儿媒体」删掉。
     *
     * @param validServerIds 仍然存在的服务器 ID（精确比对，不做 UUID 形状假设）
     */
    fun shouldDelete(fileName: String, validServerIds: Set<String>): Boolean {
        // 活服务器的文件一律保留：按「完整 serverId + 下划线」比对，
        // 不用形状猜 serverId（否则含下划线的自定义 ID 会被切错、误删）
        if (validServerIds.any { fileName.startsWith("${it}_") }) return false
        // 形状不是「已知 serverId 前缀 + 已知媒体 base 名」→ 不删（fail-closed）
        val (serverId, _) = splitServerIdAndBase(fileName) ?: return false
        return serverId.isNotEmpty()
    }

    /**
     * 在 [directory] 上执行一次孤儿清理，返回实际删掉的文件名。
     *
     * 生产路径传 `context.filesDir`；单测传临时目录（不碰真实 filesDir）。
     * 单个文件删除失败只跳过，不影响其余文件。
     */
    fun cleanupDirectory(directory: File, validServerIds: Set<String>): List<String> {
        val deleted = mutableListOf<String>()
        val files = try {
            directory.listFiles()
        } catch (_: Exception) {
            null
        } ?: return deleted
        for (file in files) {
            if (!file.isFile) continue
            val name = file.name
            if (!shouldDelete(name, validServerIds)) continue
            if (file.delete()) deleted += name
        }
        return deleted
    }
}
