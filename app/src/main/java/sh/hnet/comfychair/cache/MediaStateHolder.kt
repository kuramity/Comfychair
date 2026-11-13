package sh.hnet.comfychair.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.VideoUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
        }
    }

    /**
     * Get the current server ID.
     */
    fun getCurrentServerId(): String? = currentServerId

    // Caching mode - true for memory-first (default), false for disk-first
    private var isMemoryFirstMode = true

    // In-memory storage (used in memory-first mode)
    private val bitmaps = ConcurrentHashMap<MediaKey, Bitmap>()
    private val videoBytes = ConcurrentHashMap<MediaKey, ByteArray>()

    // Dirty flags - tracks what needs to be persisted to disk (memory-first mode only)
    private val dirtyBitmaps = ConcurrentHashMap.newKeySet<MediaKey>()
    private val dirtyVideos = ConcurrentHashMap.newKeySet<MediaKey>()

    /** 防抖落盘用的后台作用域与任务句柄（见 [schedulePersist]）。 */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistJob: Job? = null

    /** 防抖窗口：这段时间内没有新的脏数据才真正写盘，把连续写入并成一次。 */
    private const val PERSIST_DEBOUNCE_MS = 800L

    // Track current video prompt IDs for persistence
    private var currentTtvPromptId: String? = null
    private var currentItvPromptId: String? = null

    /**
     * Set the caching mode.
     * @param enabled true for memory-first (default), false for disk-first
     * @param context Required for content migration when switching modes
     */
    fun setMemoryFirstMode(enabled: Boolean, context: Context? = null) {
        if (isMemoryFirstMode == enabled) return  // No change

        DebugLogger.i(TAG, "Switching to ${if (enabled) "memory-first" else "disk-first"} mode")
        if (enabled) {
            // Switching TO memory-first: load disk content into memory
            context?.let { ctx ->
                runBlocking {
                    loadFromDisk(ctx)
                }
                // Disk files remain as backup - don't clear them
            }
        } else {
            // Switching TO disk-first: persist memory content to disk first
            context?.let { ctx ->
                runBlocking {
                    persistToDisk(ctx)
                }
            }
            // Now safe to clear in-memory caches
            bitmaps.clear()
            videoBytes.clear()
            dirtyBitmaps.clear()
            dirtyVideos.clear()
        }

        isMemoryFirstMode = enabled
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
     * 冷启动路径上这是**主线程同步调用**（MainContainerActivity.onCreate 里的 runBlocking），
     * 整幅解码一张几千像素的图会带来几十到几百 MB 的分配和肉眼可见的卡顿。
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
            dirtyBitmaps.add(key)
            context?.let { schedulePersist(it) }
            DebugLogger.d(TAG, "putBitmap: stored in memory, marked dirty (dirty count: ${dirtyBitmaps.size})")
        } else {
            // Disk-first: write to disk immediately, don't store in memory
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
            // Memory-first: store in memory, mark as dirty for later persistence
            // 同类型只保留最新一份：视频键带 promptId，每生成一个视频就是新键，而旧键
            // 没有任何删除点（evict 按 key 精确匹配）——留着就是每个视频几十 MB 常驻不退。
            videoBytes.keys.filter { existing ->
                (existing is MediaKey.TtvVideo && key is MediaKey.TtvVideo) ||
                    (existing is MediaKey.ItvVideo && key is MediaKey.ItvVideo)
            }.forEach { videoBytes.remove(it) }
            videoBytes[key] = bytes
            dirtyVideos.add(key)
            context?.let { schedulePersist(it) }
        } else {
            // Disk-first: write to disk immediately, don't store in memory
            context?.let { ctx ->
                try {
                    File(ctx.filesDir, key.filename(serverId)).writeBytes(bytes)
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
                    val tempFile = File(context.cacheDir, "playback_${key.baseFilename.hashCode()}.mp4")
                    tempFile.writeBytes(bytes)
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        tempFile
                    )
                } else {
                    // Disk-first: return URI to the stored file directly
                    val file = File(context.filesDir, key.filename(serverId))
                    if (file.exists()) {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Persist all dirty items to disk using parallel writes for speed.
     * Called from MainContainerActivity.onStop().
     */
    /**
     * 防抖落盘：脏数据出现后延迟一小段再异步写盘。
     *
     * 以前只在 onStop 里 `runBlocking { persistToDisk() }` —— 那是主线程同步等待全量 PNG
     * 压缩与视频写盘，退到后台时会卡几百毫秒到数秒。现在预览一更新就顺手异步落盘，
     * onStop 的兜底调用通常已无脏数据（几乎不耗时），"进程被杀前已落盘"的语义保持不变。
     */
    private fun schedulePersist(context: Context) {
        if (persistJob?.isActive == true) return
        persistJob = persistScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistToDisk(context.applicationContext)
            // 落盘期间可能又攒了新的脏数据（连续出图/连续换图），补排一次。
            // 必须先把句柄清掉，否则下面的 schedulePersist 会因为"上一个任务还活着"而直接返回。
            persistJob = null
            if (dirtyBitmaps.isNotEmpty() || dirtyVideos.isNotEmpty()) {
                schedulePersist(context.applicationContext)
            }
        }
    }

    suspend fun persistToDisk(context: Context) {
        val serverId = currentServerId
        if (serverId == null) {
            DebugLogger.w(TAG, "persistToDisk SKIPPED - currentServerId is null! (dirty: ${dirtyBitmaps.size} bitmaps, ${dirtyVideos.size} videos)")
            return
        }

        val bitmapCount = dirtyBitmaps.size
        val videoCount = dirtyVideos.size
        DebugLogger.i(TAG, "persistToDisk START (server: ${serverId.take(8)}..., dirty: $bitmapCount bitmaps, $videoCount videos)")

        if (bitmapCount == 0 && videoCount == 0) {
            DebugLogger.d(TAG, "persistToDisk: nothing to persist")
            return
        }

        withContext(Dispatchers.IO) {
            // Launch all writes in parallel for maximum speed
            val bitmapsToPersist = dirtyBitmaps.toList()
            val videosToPersist = dirtyVideos.toList()

            // Create parallel jobs for all items
            val bitmapJobs = bitmapsToPersist.map { key ->
                async {
                    val bitmap = bitmaps[key]
                    if (bitmap == null) {
                        DebugLogger.w(TAG, "persistToDisk: bitmap ${key.baseFilename} is dirty but not in memory!")
                        dirtyBitmaps.remove(key)
                        return@async false
                    }
                    try {
                        val preview = downscaleForPreview(bitmap)
                        context.openFileOutput(key.filename(serverId), Context.MODE_PRIVATE).use { out ->
                            preview.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        if (preview !== bitmap) preview.recycle()
                        dirtyBitmaps.remove(key)
                        DebugLogger.d(TAG, "persistToDisk: ${key.baseFilename} -> OK")
                        true
                    } catch (e: Exception) {
                        DebugLogger.e(TAG, "persistToDisk: ${key.baseFilename} -> FAILED: ${e.message}")
                        false
                    }
                }
            }

            val videoJobs = videosToPersist.map { key ->
                async {
                    val bytes = videoBytes[key]
                    if (bytes == null) {
                        DebugLogger.w(TAG, "persistToDisk: video ${key.baseFilename} is dirty but not in memory!")
                        dirtyVideos.remove(key)
                        return@async false
                    }
                    try {
                        File(context.filesDir, key.filename(serverId)).writeBytes(bytes)
                        dirtyVideos.remove(key)
                        DebugLogger.d(TAG, "persistToDisk: ${key.baseFilename} -> OK")
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
        }
    }

    /**
     * Load persisted media from disk into memory for the current server.
     * Called from MainContainerActivity.onCreate().
     */
    suspend fun loadFromDisk(context: Context) {
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
        dirtyBitmaps.remove(key)
        dirtyVideos.remove(key)

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
     */
    fun clearAll() {
        DebugLogger.i(TAG, "Clearing all media from memory")
        bitmaps.clear()
        videoBytes.clear()
        dirtyBitmaps.clear()
        dirtyVideos.clear()
        currentTtvPromptId = null
        currentItvPromptId = null
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
     */
    fun discoverVideoPromptIds(context: Context) {
        val serverId = currentServerId ?: return
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
     * @param context Application context
     * @param validServerIds List of server IDs that should be kept
     */
    fun cleanupOrphanedMediaFiles(context: Context, validServerIds: Set<String>) {
        DebugLogger.i(TAG, "Cleaning up orphaned media files")
        try {
            context.filesDir.listFiles()?.forEach { file ->
                // Check if file starts with a UUID pattern (server-scoped file)
                val name = file.name
                val underscoreIndex = name.indexOf('_')
                if (underscoreIndex > 0) {
                    val potentialServerId = name.substring(0, underscoreIndex)
                    // UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx (36 chars)
                    if (potentialServerId.length == 36 && potentialServerId.contains('-')) {
                        if (potentialServerId !in validServerIds) {
                            DebugLogger.d(TAG, "Deleting orphaned file: $name")
                            file.delete()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to cleanup orphaned files: ${e.message}")
        }
    }
}
