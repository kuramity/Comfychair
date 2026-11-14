package sh.hnet.comfychair.viewmodel

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.util.GenerationMetadata
import sh.hnet.comfychair.util.MetadataParser
import sh.hnet.comfychair.util.Mp4MetadataExtractor
import sh.hnet.comfychair.util.PngMetadataExtractor
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import java.io.File

/**
 * 读取图片生成参数时只取文件头部这么多字节。
 *
 * PNG 的参数写在 IDAT 之前的 tEXt chunk 里（实测一张 1.5MB 的图，参数仅占前 2591 字节），
 * 64KB 留足余量；而整张原图可能几十到数百 MB，为读参数全量下载在慢网络下要几十秒。
 */
private const val METADATA_HEAD_BYTES = 64L * 1024

/**
 * Viewer mode
 */
enum class ViewerMode {
    GALLERY,  // Launched from gallery, supports navigation
    SINGLE    // Launched from generation screen, single item only
}

/**
 * 查看器加载失败的三档文案档位（H6）。
 *
 * 只分三档是因为文案只需要回答一个问题：「重试有没有用」——
 * 文件没了（重试没用）、网络不可达（重试可能有用）、其它（说不准）。
 * UI 层按档位选 string 资源，映射关系见 [ViewerLoadPolicy.categorizeFailure]。
 */
enum class ViewerFailureCategory {
    /** 服务器上已经没有这个文件（/view 404，典型场景是电脑端清理了 output） */
    FILE_MISSING,
    /** 网络或服务器不可达（离线 / 断线 / 传输停滞）—— 重试可能成功 */
    NETWORK,
    /** 其它失败（解码失败、写盘失败等），只给通用提示 */
    GENERIC
}

/**
 * 查看器单页内容的显示状态（H1-a/H1-b）。
 */
enum class ViewerShowState {
    /** 内容已就位（图已解出 / 视频 URI 已就绪） */
    CONTENT,
    /** 内容缺失且正在加载 —— 转圈 */
    LOADING,
    /** 内容缺失且不在加载 —— 失败占位。修复前视频页走不到这里（无条件转圈）、图片页静默黑屏 */
    FAILED
}

/**
 * 查看器加载失败的纯判定逻辑。抽成 object 以便 JVM 单测直接覆盖，
 * 不依赖 MediaCache / ConnectionManager 的运行时状态（与 GalleryHistoryParser 同一模式）。
 */
internal object ViewerLoadPolicy {

    /**
     * ComfyUIClient 的失败分型 → 文案档位。
     *
     * FILE_MISSING 与 NETWORK 必须分开：一个重试也不会好（文件没了），
     * 一个重试可能好（网络恢复）—— 用户需要看出这两种失败不是一回事。
     * STALLED（传输停滞）本质也是链路问题，归 NETWORK。
     */
    fun categorizeFailure(failure: ConnectionFailure): ViewerFailureCategory = when (failure) {
        ConnectionFailure.FILE_MISSING -> ViewerFailureCategory.FILE_MISSING
        ConnectionFailure.NETWORK, ConnectionFailure.STALLED -> ViewerFailureCategory.NETWORK
        else -> ViewerFailureCategory.GENERIC
    }

    /**
     * 「是否显示失败态」判定（H1-a/H1-b 的核心规则）。
     *
     * 内容缺失时只有"确定不在加载"才算失败：视频失败后 currentVideoUri 永远是 null，
     * 原实现拿它当"还在加载"的信号，结果就是死转圈。
     *
     * @param isVideo 是否视频条目。判定结果与它无关（内容缺失 + 不在加载 = 失败，图视频同理），
     *                保留在签名里是为了让调用处把"视频页无内容"也送进来判定——
     *                修复前它根本进不了失败分支
     */
    fun resolveShowState(isVideo: Boolean, hasContent: Boolean, isLoading: Boolean): ViewerShowState = when {
        hasContent -> ViewerShowState.CONTENT
        isLoading -> ViewerShowState.LOADING
        else -> ViewerShowState.FAILED
    }
}

/**
 * Represents an item in the media viewer
 */
@Immutable
data class MediaViewerItem(
    val promptId: String,
    val filename: String,
    val subfolder: String,
    val type: String,
    val isVideo: Boolean,
    val index: Int = 0
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("promptId", promptId)
            put("filename", filename)
            put("subfolder", subfolder)
            put("type", type)
            put("isVideo", isVideo)
            put("index", index)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): MediaViewerItem {
            return MediaViewerItem(
                promptId = json.optString("promptId", ""),
                filename = json.optString("filename", ""),
                subfolder = json.optString("subfolder", ""),
                type = json.optString("type", "output"),
                isVideo = json.optBoolean("isVideo", false),
                index = json.optInt("index", 0)
            )
        }

        fun listToJson(items: List<MediaViewerItem>): String {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(json: String): List<MediaViewerItem> {
            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { i ->
                    fromJson(array.getJSONObject(i))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * UI state for the media viewer.
 * Caching is handled by MediaCache singleton - no local cache maps needed.
 */
@Stable
data class MediaViewerUiState(
    val mode: ViewerMode = ViewerMode.GALLERY,
    val items: List<MediaViewerItem> = emptyList(),
    val currentIndex: Int = 0,
    val isUiVisible: Boolean = true,
    val isLoading: Boolean = false,
    val currentBitmap: Bitmap? = null,
    val currentVideoUri: Uri? = null,
    /**
     * 当前显示的是「服务端压缩版」而不是原图。
     *
     * 只会在一种情况下为 true：原图超过下载上限（放大类工作流可能产出 200 MB 级的 PNG）。
     * 界面据此给出说明，并提供「复制下载链接」让用户去浏览器取原图。
     */
    val compressedFallback: Boolean = false,
    /**
     * 当前条目最近一次加载失败的原因档位（null = 没有待显示的失败）。
     *
     * 供失败占位（图标 + 文案 + 重试，H1-a/H1-b）选文案。成功加载、切换条目、
     * 开始新的加载时都会清掉。
     */
    val loadFailure: ViewerFailureCategory? = null
) {
    val currentItem: MediaViewerItem?
        get() = items.getOrNull(currentIndex)

    val totalCount: Int
        get() = items.size

    val canNavigateBack: Boolean
        get() = mode == ViewerMode.GALLERY && currentIndex > 0

    val canNavigateForward: Boolean
        get() = mode == ViewerMode.GALLERY && currentIndex < items.size - 1
}

/**
 * Events emitted by media viewer operations
 */
sealed class MediaViewerEvent {
    data class ShowToast(val messageResId: Int) : MediaViewerEvent()
    data object ItemDeleted : MediaViewerEvent()
    data object Close : MediaViewerEvent()
}

/**
 * ViewModel for the MediaViewer screen
 */
class MediaViewerViewModel : ViewModel() {

    private var applicationContext: Context? = null

    private val _uiState = MutableStateFlow(MediaViewerUiState())
    val uiState: StateFlow<MediaViewerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MediaViewerEvent>()
    val events: SharedFlow<MediaViewerEvent> = _events.asSharedFlow()

    // Metadata state
    private val cachedMetadata = mutableMapOf<Int, GenerationMetadata?>()
    private val _currentMetadata = MutableStateFlow<GenerationMetadata?>(null)
    val currentMetadata: StateFlow<GenerationMetadata?> = _currentMetadata.asStateFlow()

    private val _isLoadingMetadata = MutableStateFlow(false)
    val isLoadingMetadata: StateFlow<Boolean> = _isLoadingMetadata.asStateFlow()

    // Track whether any items were deleted during this session
    private val _hasDeletedItems = MutableStateFlow(false)
    val hasDeletedItems: StateFlow<Boolean> = _hasDeletedItems.asStateFlow()

    fun initialize(
        context: Context,
        hostname: String,
        port: Int,
        mode: ViewerMode,
        items: List<MediaViewerItem>,
        initialIndex: Int,
        singleBitmap: Bitmap? = null,
        singleVideoUri: Uri? = null
    ) {
        applicationContext = context.applicationContext

        _uiState.value = MediaViewerUiState(
            mode = mode,
            items = items,
            currentIndex = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            currentBitmap = singleBitmap,
            currentVideoUri = singleVideoUri,
            isLoading = mode == ViewerMode.GALLERY && items.isNotEmpty()
        )

        // For gallery mode, set up priorities and load current item
        if (mode == ViewerMode.GALLERY && items.isNotEmpty()) {
            val currentIdx = _uiState.value.currentIndex
            updateCachePrioritiesForIndex(currentIdx)
            triggerPrefetchForIndex(currentIdx)
            preloadMetadataForIndices()
            loadCurrentItem()
        }
    }

    fun toggleUiVisibility() {
        _uiState.value = _uiState.value.copy(isUiVisible = !_uiState.value.isUiVisible)
    }

    fun setCurrentIndex(index: Int) {
        val state = _uiState.value
        if (index < 0 || index >= state.items.size) return

        // Clear metadata when navigating
        clearCurrentMetadata()

        val item = state.items[index]
        val key = item.toCacheKey()

        // Update image cache priorities based on new position
        updateCachePrioritiesForIndex(index)

        // Trigger prefetch IMMEDIATELY for adjacent items (before loading current)
        triggerPrefetchForIndex(index)

        // Pre-load metadata for current + adjacent items
        preloadMetadataForIndices()

        // Try to get from cache immediately (same approach for images and videos)
        val cachedBitmap = if (!item.isVideo) MediaCache.getBitmap(key) else null
        val cachedVideoUri = if (item.isVideo) MediaCache.getCachedVideoUri(key) else null

        if (!item.isVideo && cachedBitmap != null) {
            // Image is cached - show immediately
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = cachedBitmap,
                currentVideoUri = null,
                isLoading = false,
                loadFailure = null
            )
        } else if (item.isVideo && cachedVideoUri != null) {
            // Video is cached - show immediately
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = null,
                currentVideoUri = cachedVideoUri,
                isLoading = false,
                loadFailure = null
            )
        } else if (MediaCache.isPrefetchInProgress(key)) {
            // Prefetch is in progress - wait for it instead of starting new fetch
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = null,
                currentVideoUri = null,
                isLoading = true
            )
            viewModelScope.launch {
                MediaCache.awaitPrefetchCompletion(key)
                showFromCache(item, key)
            }
        } else {
            // Content not cached - load from server
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = null,
                currentVideoUri = null,
                isLoading = true
            )
            loadCurrentItem()
        }
    }

    /**
     * Show item from cache after prefetch completes.
     */
    private fun showFromCache(item: MediaViewerItem, key: MediaCacheKey) {
        if (item.isVideo) {
            val uri = MediaCache.getCachedVideoUri(key)
            _uiState.value = _uiState.value.copy(
                currentVideoUri = uri,
                isLoading = false,
                loadFailure = if (uri == null) inferLoadFailure(key) else null
            )
        } else {
            val bitmap = MediaCache.getBitmap(key)
            _uiState.value = _uiState.value.copy(
                currentBitmap = bitmap,
                isLoading = false,
                loadFailure = if (bitmap == null) inferLoadFailure(key) else null
            )
        }
    }

    /**
     * Update cache priorities based on current viewing position.
     * Ensures adjacent items have higher priority than distant items.
     */
    private fun updateCachePrioritiesForIndex(index: Int) {
        val state = _uiState.value
        if (state.mode != ViewerMode.GALLERY || state.items.isEmpty()) return

        val allKeys = state.items.map { it.toCacheKey() }
        MediaCache.updateNavigationPriorities(index, allKeys)
    }

    /**
     * Trigger prefetch for items around the given index.
     */
    private fun triggerPrefetchForIndex(index: Int) {
        val state = _uiState.value
        if (state.mode != ViewerMode.GALLERY || state.items.isEmpty()) return

        val prefetchItems = state.items.map { item ->
            MediaCache.PrefetchItem(
                key = item.toCacheKey(),
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type
            )
        }

        MediaCache.prefetchAround(index, prefetchItems)
    }

    /** Convert MediaViewerItem to MediaCacheKey */
    private fun MediaViewerItem.toCacheKey() = MediaCacheKey(promptId, filename)

    fun navigateNext() {
        val state = _uiState.value
        if (state.canNavigateForward) {
            setCurrentIndex(state.currentIndex + 1)
        }
    }

    fun navigatePrevious() {
        val state = _uiState.value
        if (state.canNavigateBack) {
            setCurrentIndex(state.currentIndex - 1)
        }
    }

    private fun loadCurrentItem() {
        val state = _uiState.value
        val item = state.currentItem ?: return
        val context = applicationContext ?: return

        val key = item.toCacheKey()

        // Check if already cached
        if (item.isVideo) {
            MediaCache.getCachedVideoUri(key)?.let { uri ->
                _uiState.value = state.copy(
                    currentVideoUri = uri,
                    isLoading = false,
                    loadFailure = null
                )
                return
            }
        } else {
            MediaCache.getBitmap(key)?.let { bitmap ->
                _uiState.value = state.copy(
                    currentBitmap = bitmap,
                    isLoading = false,
                    loadFailure = null
                )
                return
            }
        }

        // 换条目时先把降级标记与上一次的失败提示清掉：新条目的结果出来之前，
        // 上一条目的"压缩版"/"加载失败"都不该挂在新条目上
        _uiState.value = state.copy(isLoading = true, compressedFallback = false, loadFailure = null)

        viewModelScope.launch {
            if (item.isVideo) {
                // Fetch video and create URI in one step
                val uri = MediaCache.fetchVideoUri(key, item.subfolder, item.type, context)
                _uiState.value = _uiState.value.copy(
                    currentVideoUri = uri,
                    isLoading = false,
                    // 失败必须留档（H1-a）：修复前这里只置 null，UI 拿"videoUri == null"
                    // 当"还在加载"的信号，结果失败后永远转圈
                    loadFailure = if (uri == null) inferLoadFailure(key) else null
                )
            } else {
                // 原图优先，超限时自动降级到服务端压缩版（见 MediaCache.fetchViewerImage）：
                // 200 MB 的放大图手机读不动，但"长什么样"还得让人看到
                val viewerImage = MediaCache.fetchViewerImage(key, item.subfolder, item.type)
                _uiState.value = _uiState.value.copy(
                    currentBitmap = viewerImage.bitmap,
                    compressedFallback = viewerImage.compressedFallback,
                    isLoading = false,
                    // 失败必须留档（H1-b）：修复前图片失败只走黑屏分支，无提示无重试
                    loadFailure = if (viewerImage.bitmap == null) inferLoadFailure(key) else null
                )
            }
        }
    }

    /**
     * 依据缓存侧已有的标记推断这次失败的原因档位。
     *
     * 404 记在 MediaCache（[MediaCache.isFileMissing]），离线状态记在 ConnectionManager；
     * 两处都查不到时给 GENERIC —— 宁可文案保守，也不能把可重试的失败说成"文件不存在"。
     */
    private fun inferLoadFailure(key: MediaCacheKey): ViewerFailureCategory = when {
        MediaCache.isFileMissing(key) -> ViewerFailureCategory.FILE_MISSING
        ConnectionManager.isOffline || ConnectionManager.clientOrNull == null ->
            ViewerFailureCategory.NETWORK
        else -> ViewerFailureCategory.GENERIC
    }

    /**
     * 失败占位上的「重试」入口（H1-a/H1-b）。
     *
     * 必须先清 404 短路标记再重新加载 —— 不清的话缩略图/图片链路会在 missingKeys 上
     * 直接短路返回 null，重试变成原地再失败一次。其余失败类型（断线、超时）
     * 本身没有短路标记，直接重拉即可。
     */
    fun retryCurrentItem() {
        val item = _uiState.value.currentItem ?: return
        MediaCache.clearFileMissing(item.toCacheKey())
        loadCurrentItem()
    }

    /**
     * 当前条目的**原图下载链接**（复制给浏览器用）。
     *
     * 刻意不经过 App 的下载与缓存：200 MB 的放大图 App 读不动（会被下载上限挡下并降级），
     * 但浏览器直接下没有任何问题。链接用 App 当前配置的服务器地址拼出来，
     * 手机在同一网络下打开即可下载。
     *
     * 用 OkHttp 的 HttpUrl.Builder 而不是字符串拼接：文件名里常有中文（如"精修_00002_.png"），
     * 手拼很容易漏编码、或者把原始文件名里本来就有的 `+`、`&` 弄坏。
     */
    fun downloadUrlForCurrent(): String? {
        val item = _uiState.value.currentItem ?: return null
        val baseUrl = sh.hnet.comfychair.connection.ConnectionManager.clientOrNull?.getBaseUrl()
            ?: return null
        return baseUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegment("view")
            ?.addQueryParameter("filename", item.filename)
            ?.addQueryParameter("subfolder", item.subfolder)
            ?.addQueryParameter("type", item.type)
            ?.build()
            ?.toString()
    }

    /**
     * Load metadata for the current item.
     * Uses cached metadata if available from pre-loading, otherwise fetches on demand.
     */
    fun loadMetadata() {
        val index = _uiState.value.currentIndex

        // Check cache first - metadata may have been pre-loaded
        if (cachedMetadata.containsKey(index)) {
            _currentMetadata.value = cachedMetadata[index]
            return
        }

        // Not cached - load now (fallback for edge cases like rapid swiping)
        _isLoadingMetadata.value = true

        viewModelScope.launch {
            val metadata = fetchMetadataForIndex(index)
            cachedMetadata[index] = metadata
            _currentMetadata.value = metadata
            _isLoadingMetadata.value = false
        }
    }

    /**
     * Clear metadata when navigating to a different item.
     */
    private fun clearCurrentMetadata() {
        _currentMetadata.value = null
    }

    /**
     * Fetches metadata for a specific item by index.
     * Handles both images (PNG) and videos (MP4).
     *
     * 字节来源按顺序尝试：
     * 1. SINGLE 模式的视频：当前播放用的本地文件；
     * 2. 在线（有客户端）：向服务器要原始字节；
     * 3. **磁盘缓存**（`cacheDir/gallery_cache`，只读）：离线时唯一可用的来源。
     *    这条路径是离线查看生成参数（正负提示词 / 种子）的关键——磁盘上本来就有 PNG 字节。
     *
     * Returns null if metadata cannot be extracted.
     */
    private suspend fun fetchMetadataForIndex(index: Int): GenerationMetadata? {
        val state = _uiState.value
        val item = state.items.getOrNull(index) ?: return null
        val context = applicationContext ?: return null

        return withContext(Dispatchers.IO) {
            val fromLocalOrServer: ByteArray? = when {
                // For SINGLE mode videos, try to read from local file first
                state.mode == ViewerMode.SINGLE && item.isVideo && state.currentVideoUri != null -> {
                    try {
                        context.contentResolver.openInputStream(state.currentVideoUri)?.use {
                            it.readBytes()
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                // For items with server file info and a client, fetch from server
                // （离线态不请求服务器：此时客户端仍在，但离线语义就是"不发请求"）
                item.filename.isNotEmpty() && ConnectionManager.clientOrNull != null &&
                    !ConnectionManager.isOffline -> {
                    val client = ConnectionManager.clientOrNull!!
                    if (item.isVideo) {
                        // 视频的参数在文件尾部（moov box），取头部没有意义，必须拿到完整字节。
                        // 先复用内存缓存里的那一份：图库预取/缩略图提取通常已经取过同一段视频，
                        // 不查缓存就会同一条目下两份（几十 MB）。缓存没命中才真去下载。
                        val cached = MediaCache.getCachedVideoBytes(
                            MediaCacheKey(item.promptId, item.filename)
                        )
                        if (cached != null) {
                            android.util.Log.i(
                                "ComfyChairMetadata",
                                "视频参数复用缓存字节（${cached.size} 字节），未重复下载"
                            )
                            cached
                        } else {
                            kotlin.coroutines.suspendCoroutine<ByteArray?> { continuation ->
                                client.fetchRawBytes(item.filename, item.subfolder, item.type) { rawBytes, _ ->
                                    continuation.resumeWith(Result.success(rawBytes))
                                }
                            }
                        }
                    } else {
                        // PNG 的参数在 IDAT 之前，只取文件头部即可（见 METADATA_HEAD_BYTES）；
                        // 为读参数把整张原图拖下来，在流量/隧道下要几十秒（用户 2026-09-18 报障）
                        val head = kotlin.coroutines.suspendCoroutine<ByteArray?> { continuation ->
                            client.fetchPartialBytes(
                                item.filename, item.subfolder, item.type, METADATA_HEAD_BYTES
                            ) { bytes, _, _ ->
                                continuation.resumeWith(Result.success(bytes))
                            }
                        }
                        if (head != null && PngMetadataExtractor.extractPromptMetadata(head) != null) {
                            // 只记字节数、不记文件名（文件名可能带敏感词）
                            android.util.Log.i(
                                "ComfyChairMetadata",
                                "生成参数已从文件头读出（${head.size} 字节），未下载整张图片"
                            )
                            head
                        } else {
                            android.util.Log.w(
                                "ComfyChairMetadata",
                                "文件头未找到生成参数，回退全量下载"
                            )
                            // 头部没找到参数（例如 tEXt 写在 IDAT 之后）→ 回退全量，保证兼容
                            kotlin.coroutines.suspendCoroutine<ByteArray?> { continuation ->
                                client.fetchRawBytes(item.filename, item.subfolder, item.type) { rawBytes, _ ->
                                    continuation.resumeWith(Result.success(rawBytes))
                                }
                            }
                        }
                    }
                }
                // No source available
                else -> null
            }

            // 在线取不到（离线）时用磁盘缓存里的字节：那份数据就是为离线读取留的
            val bytes = fromLocalOrServer ?: MediaCache.readCachedBytes(item.toCacheKey(), item.isVideo)

            if (bytes == null) return@withContext null

            // Extract metadata based on file type
            val jsonString = if (item.isVideo) {
                Mp4MetadataExtractor.extractPromptMetadata(bytes)
            } else {
                PngMetadataExtractor.extractPromptMetadata(bytes)
            }

            // Parse the workflow JSON
            jsonString?.let { MetadataParser.parseWorkflowJson(it) }
        }
    }

    /**
     * Pre-loads metadata for current and adjacent items in the background.
     * Called when navigating to have metadata ready before user opens sheet.
     * Mirrors the same indices as MediaCache.prefetchAround() for consistency.
     */
    private fun preloadMetadataForIndices() {
        val state = _uiState.value
        if (state.mode != ViewerMode.GALLERY || state.items.isEmpty()) return

        val currentIndex = state.currentIndex
        val itemCount = state.items.size

        // Same pattern as MediaCache.prefetchAround(): current ±1, ±2
        val indicesToPreload = listOf(
            currentIndex,      // Current item (highest priority)
            currentIndex - 1,  // Adjacent
            currentIndex + 1,
            currentIndex - 2,  // Nearby
            currentIndex + 2
        ).filter { it in 0 until itemCount }
         .filter { !cachedMetadata.containsKey(it) }

        indicesToPreload.forEach { idx ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val metadata = fetchMetadataForIndex(idx)
                    cachedMetadata[idx] = metadata
                } catch (e: Exception) {
                    // Silently fail - metadata will load on-demand if needed
                }
            }
        }
    }

    fun deleteCurrentItem() {
        val state = _uiState.value
        val item = state.currentItem ?: return

        viewModelScope.launch {
            // 只从手机端移除：服务器的历史记录与 output 目录里的文件都不动（与图库页同一语义）。
            // 走 Repository 的本地移除入口而不是自己发请求，是因为删除墓碑要跟着落盘 ——
            // 否则下次刷新服务器历史时，这条记录会原样复活。
            //
            // 这里刻意不再要求"先连上服务器"：删除是纯本地操作，离线时也应当能删。
            GalleryRepository.getInstance().removeItemLocally(item.promptId)
            val success = true

            if (success) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_history_item_deleted_success))

                // Mark that items were deleted (for result reporting)
                _hasDeletedItems.value = true

                // Evict from MediaCache (uses stable key, not index)
                MediaCache.evict(item.toCacheKey())

                // Get fresh state after async operation
                val currentState = _uiState.value
                val currentItems = currentState.items.toMutableList()

                // Make sure we're removing the correct item
                val actualIndexToRemove = currentItems.indexOfFirst {
                    it.promptId == item.promptId && it.filename == item.filename
                }

                if (actualIndexToRemove >= 0) {
                    currentItems.removeAt(actualIndexToRemove)
                }

                if (currentItems.isEmpty()) {
                    // No more items, close viewer
                    _events.emit(MediaViewerEvent.ItemDeleted)
                    _events.emit(MediaViewerEvent.Close)
                } else {
                    // Adjust index and show next/previous item
                    val newIndex = actualIndexToRemove.coerceIn(0, currentItems.size - 1)

                    // Clear metadata cache (still uses indices)
                    cachedMetadata.clear()
                    _currentMetadata.value = null

                    _uiState.value = currentState.copy(
                        items = currentItems,
                        currentIndex = newIndex,
                        currentBitmap = null,
                        currentVideoUri = null,
                        isLoading = true
                    )
                    _events.emit(MediaViewerEvent.ItemDeleted)
                    loadCurrentItem()
                }
            } else {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_history_item_delete))
            }
        }
    }

    fun saveCurrentItem() {
        val context = applicationContext ?: return
        val state = _uiState.value

        viewModelScope.launch {
            if (state.mode == ViewerMode.SINGLE) {
                // Save from current bitmap/video
                if (state.currentItem?.isVideo == true || state.currentVideoUri != null) {
                    saveVideoFromUri(context, state.currentVideoUri)
                } else {
                    saveBitmapToGallery(context, state.currentBitmap)
                }
            } else {
                // Gallery mode - fetch and save
                val item = state.currentItem ?: return@launch
                if (item.isVideo) {
                    saveVideoFromServer(context, item)
                } else {
                    saveImageFromServer(context, item)
                }
            }
        }
    }

    private suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap?) {
        if (bitmap == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_image))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                }

                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_image_saved_to_gallery))
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_image))
            }
        }
    }

    private suspend fun saveVideoFromUri(context: Context, videoUri: Uri?) {
        if (videoUri == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_video))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val videoBytes = context.contentResolver.openInputStream(videoUri)?.use {
                    it.readBytes()
                } ?: run {
                    _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_video))
                    return@withContext
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        outputStream.write(videoBytes)
                    }
                }

                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_video_saved_to_gallery))
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_video))
            }
        }
    }

    private suspend fun saveImageFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        withContext(Dispatchers.IO) {
            // Try cache first, then fetch if needed
            val bitmap = MediaCache.getBitmap(key)
                ?: MediaCache.fetchImage(key, item.subfolder, item.type)

            if (bitmap == null) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_image))
                return@withContext
            }

            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                }

                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_image_saved_to_gallery))
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_image))
            }
        }
    }

    private suspend fun saveVideoFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        withContext(Dispatchers.IO) {
            // Try cache first, then fetch if needed
            val videoBytes = MediaCache.getVideoBytes(key)
                ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

            if (videoBytes == null) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_video))
                return@withContext
            }

            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        outputStream.write(videoBytes)
                    }
                }

                _events.emit(MediaViewerEvent.ShowToast(R.string.msg_video_saved_to_gallery))
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_video))
            }
        }
    }

    fun shareCurrentItem() {
        val context = applicationContext ?: return
        val state = _uiState.value

        viewModelScope.launch {
            if (state.mode == ViewerMode.SINGLE) {
                if (state.currentItem?.isVideo == true || state.currentVideoUri != null) {
                    shareVideoFromUri(context, state.currentVideoUri)
                } else {
                    shareBitmap(context, state.currentBitmap)
                }
            } else {
                val item = state.currentItem ?: return@launch
                if (item.isVideo) {
                    shareVideoFromServer(context, item)
                } else {
                    shareImageFromServer(context, item)
                }
            }
        }
    }

    private suspend fun shareBitmap(context: Context, bitmap: Bitmap?) {
        if (bitmap == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_image))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val shareFile = File(context.cacheDir, "share_image.png")
                shareFile.outputStream().use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = "image/png"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    context.startActivity(
                        Intent.createChooser(shareIntent, context.getString(R.string.share_image))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_image))
            }
        }
    }

    private suspend fun shareVideoFromUri(context: Context, videoUri: Uri?) {
        if (videoUri == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_video))
            return
        }

        withContext(Dispatchers.Main) {
            try {
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, videoUri)
                    type = "video/mp4"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    Intent.createChooser(shareIntent, context.getString(R.string.share_video))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_video))
            }
        }
    }

    private suspend fun shareImageFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        // Try cache first, then fetch if needed
        val bitmap = MediaCache.getBitmap(key)
            ?: MediaCache.fetchImage(key, item.subfolder, item.type)

        if (bitmap == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_image))
            return
        }

        shareBitmap(context, bitmap)
    }

    private suspend fun shareVideoFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        // Try cache first, then fetch if needed
        val videoBytes = MediaCache.getVideoBytes(key)
            ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

        if (videoBytes == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_video))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val shareFile = File(context.cacheDir, "share_video.mp4")
                shareFile.writeBytes(videoBytes)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = "video/mp4"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    context.startActivity(
                        Intent.createChooser(shareIntent, context.getString(R.string.share_video))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_video))
            }
        }
    }
}
