package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import sh.hnet.comfychair.model.GalleryItem
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.util.GenerationMetadata
import sh.hnet.comfychair.util.MetadataParser
import sh.hnet.comfychair.util.Mp4MetadataExtractor
import sh.hnet.comfychair.util.PhotoManager
import sh.hnet.comfychair.util.PngMetadataExtractor
import sh.hnet.comfychair.util.ShareFileStore
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import java.io.File
import java.util.Collections

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
 * 「移入回收站」结果档位。
 *
 * 两个布尔位就是界面必须遵守的两条纪律：
 * - [removeFromUi]：只有真正进站才允许把条目从当前界面移除（查看器队列 / 图库选中项）；
 * - [showSuccess]：只有全部进站才允许提示成功。
 *
 * FAILED 两者皆 false —— 落盘失败时移除条目会让界面之间数据分叉
 * （查看器没了、回收站没有、图库还在）。
 */
internal enum class TrashDeleteResult(
    val removeFromUi: Boolean,
    val showSuccess: Boolean
) {
    /** 全部进站：移除 + 提示成功 */
    SUCCESS(true, true),

    /** 部分进站：只移除已进站的（仓储已处理），提示部分失败 */
    PARTIAL(true, false),

    /** 一条都没进站：界面保持原样，提示失败 */
    FAILED(false, false)
}

/**
 * 「移入回收站」返回值 → 界面动作的纯判定。
 *
 * [GalleryRepository.moveToTrash] 的契约：返回进站的回收站身份键列表，
 * **空列表 = 未进站**（回收站落盘失败 / 异常兜底）。调用方必须先过这里再动界面，
 * 否则会出现"用户以为删了，其实还在"的数据分叉。
 *
 * 抽成 object 以便 JVM 单测直接覆盖（与 [ViewerLoadPolicy] 同一模式）。
 */
internal object TrashDeleteOutcome {

    /** 单条删除（查看器）：返回身份键非空才算进站。 */
    fun single(trashIdentities: List<String>): TrashDeleteResult =
        if (trashIdentities.isEmpty()) TrashDeleteResult.FAILED else TrashDeleteResult.SUCCESS

    /** 批量删除（图库多选）：按实际进站条数与请求条数分档；0 条 = 全败，绝不报成功。 */
    fun batch(successCount: Int, requestedCount: Int): TrashDeleteResult = when {
        successCount <= 0 -> TrashDeleteResult.FAILED
        successCount < requestedCount -> TrashDeleteResult.PARTIAL
        else -> TrashDeleteResult.SUCCESS
    }

    /**
     * 删除成功后「已移入回收站」Toast 是否由 ViewModel 发（remainingCount = 删除后队列剩余条数）。
     *
     * 契约：**只在队列删空时发**。还有剩余项时撤销条（MediaViewerScreen 的 trashUndo 槽）
     * 正在显示，文字同为「已移入回收站」，提示由撤销条独占 —— 此时再发 Toast，
     * 屏幕上会出现两条相同提示交叉淡入重叠（真机录屏确认的缺陷）。
     * 删空时撤销槽被清掉、没有"就地撤销"的位置，Toast 是唯一反馈，必须发。
     */
    fun shouldEmitTrashToast(remainingCount: Int): Boolean = remainingCount <= 0
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
    val index: Int = 0,
    /**
     * 源已不存在（/view 404，标记随图库索引持久化）。
     *
     * 查看器队列是进入时拍下的快照，不随图库仓储实时刷新，所以该字段必须一起带过来：
     * 否则从图库点开一张已删除的图，查看器又会去请求原图、白等一次超时。
     * 旧版本保存的查看器会话没有这个键 → fromJson 缺省 false，兼容。
     */
    val sourceMissing: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("promptId", promptId)
            put("filename", filename)
            put("subfolder", subfolder)
            put("type", type)
            put("isVideo", isVideo)
            put("index", index)
            put("sourceMissing", sourceMissing)
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
                index = json.optInt("index", 0),
                sourceMissing = json.optBoolean("sourceMissing", false)
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
 * G4（任务书 11.4）：查看器内"移入回收站"的一次性撤销槽位。
 *
 * 竖滑删除（和工具栏删除键，同一链路）落回收站后给出**限时撤销**入口
 * （用户已确认的交互决策：2 秒）。槽位是单发的：新的删除会顶掉旧槽位
 * （旧条目此时只能在回收站界面里恢复，不再给就地撤销 —— "一次性撤销入口"的语义）。
 *
 * @param trashIdentity 回收站条目身份键（阶段一撤销 = 按它恢复）
 * @param item 被移走的队列项（撤销时原样插回）
 * @param viewerIndex 移走时在**查看器列表**里的下标（撤销回原位置；
 *                    图库列表侧的原位置由 Repository 按条目保存的 index 找回）
 */
data class ViewerTrashUndoState(
    val trashIdentity: String,
    val item: MediaViewerItem,
    val viewerIndex: Int
)

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
    // 多条 IO 协程并发写入（批量预取）+ 主线程读/clear：用同步包装保证单次操作安全。
    // 不能换 ConcurrentHashMap——它的 value 不允许 null，而这里的 null 是合法的
    // "负缓存"值（标记查过但没有元数据）。
    private val cachedMetadata: MutableMap<Int, GenerationMetadata?> =
        Collections.synchronizedMap(mutableMapOf())
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
                loadFailure = if (uri == null) inferLoadFailure(key, item) else null
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
                type = item.type,
                // 源已不存在：预取原图只会再拿一个 404
                sourceMissing = item.sourceMissing
            )
        }

        MediaCache.prefetchAround(index, prefetchItems)
    }

    /** Convert MediaViewerItem to MediaCacheKey（type + 当前服务器进键，见 P2-2） */
    private fun MediaViewerItem.toCacheKey() = MediaCacheKey.of(promptId, filename, type)

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
                val uri = MediaCache.fetchVideoUri(
                    key = key,
                    subfolder = item.subfolder,
                    type = item.type,
                    context = context,
                    // 源已不存在：只读本机缓存的视频字节，不再请求已删除的原文件
                    localOnly = !shouldRequestOriginal(item)
                )
                _uiState.value = _uiState.value.copy(
                    currentVideoUri = uri,
                    isLoading = false,
                    // 失败必须留档（H1-a）：修复前这里只置 null，UI 拿"videoUri == null"
                    // 当"还在加载"的信号，结果失败后永远转圈
                    loadFailure = if (uri == null) inferLoadFailure(key, item) else null
                )
            } else {
                // 原图优先，超限时自动降级到服务端压缩版（见 MediaCache.fetchViewerImage）：
                // 200 MB 的放大图手机读不动，但"长什么样"还得让人看到。
                // 源已不存在：只读本机缓存（720 档/旧磁盘），不发请求 —— 这是第三态的核心。
                val viewerImage = MediaCache.fetchViewerImage(
                    key = key,
                    subfolder = item.subfolder,
                    type = item.type,
                    localOnly = !shouldRequestOriginal(item)
                )
                _uiState.value = _uiState.value.copy(
                    currentBitmap = viewerImage.bitmap,
                    compressedFallback = viewerImage.compressedFallback,
                    isLoading = false,
                    // 失败必须留档（H1-b）：修复前图片失败只走黑屏分支，无提示无重试
                    loadFailure = if (viewerImage.bitmap == null) inferLoadFailure(key, item) else null
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
    private fun inferLoadFailure(
        key: MediaCacheKey,
        item: MediaViewerItem? = null
    ): ViewerFailureCategory = when {
        // 已持久化的第三态（源已不存在）优先：即使本次没走到网络、missingKeys 为空，
        // 文案也必须是"文件不存在"，不能退回可重试的 NETWORK/GENERIC。
        item?.sourceMissing == true -> ViewerFailureCategory.FILE_MISSING
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
        val state = _uiState.value
        val item = state.currentItem ?: return
        MediaCache.clearFileMissing(item.toCacheKey())
        if (item.sourceMissing) {
            // 用户显式重试 = 允许再探一次：必须一并清掉持久化标记，
            // 否则 shouldRequestOriginal 会一直短路，重试变成"原地再失败一次"。
            // 文件确实被放回服务器 → 恢复正常；仍然 404 → 重新标记，第三态不丢。
            GalleryRepository.getInstance().clearSourceMissing(item.promptId, item.filename)
            _uiState.value = state.copy(
                items = state.items.map {
                    if (it.promptId == item.promptId && it.filename == item.filename) {
                        it.copy(sourceMissing = false)
                    } else {
                        it
                    }
                },
                loadFailure = null
            )
        }
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
            val cacheKey = item.toCacheKey()
            val fromLocalOrServer: ByteArray? = when {
                // For SINGLE mode videos, try to read from local file first
                state.mode == ViewerMode.SINGLE && item.isVideo && state.currentVideoUri != null -> {
                    try {
                        // P2-12：播放 URI 是 file://，contentResolver 读不了，统一走 ShareFileStore
                        ShareFileStore.openMediaInput(context, state.currentVideoUri)?.use {
                            it.readBytes()
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                // For items with server file info and a client, fetch from server
                // （离线态不请求服务器：此时客户端仍在，但离线语义就是"不发请求"）
                // 源已不存在（/view 404 已确认）：参数也只能从本机缓存字节里取，
                // 不再为一条注定 404 的记录白打两次请求（头段 + 全量回退）。
                item.filename.isNotEmpty() && ConnectionManager.clientOrNull != null &&
                    !ConnectionManager.isOffline &&
                    !item.sourceMissing && !MediaCache.isFileMissing(cacheKey) -> {
                    val client = ConnectionManager.clientOrNull!!
                    if (item.isVideo) {
                        // 视频的参数在文件尾部（moov box），取头部没有意义，必须拿到完整字节。
                        // 先复用内存缓存里的那一份：图库预取/缩略图提取通常已经取过同一段视频，
                        // 不查缓存就会同一条目下两份（几十 MB）。缓存没命中才真去下载。
                        val cached = MediaCache.getCachedVideoBytes(
                            MediaCacheKey.of(item.promptId, item.filename, item.type)
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
            // G4 阶段一：删除 = **移入回收站**（可恢复）。
            // 走 Repository 的回收站入口，一次完成：回收站落盘（完整定位 + 时间戳 +
            // serverId）、列表过滤、持久墓碑 + 会话墓碑。
            // 与旧"直接删除"的关键差别：**不再 MediaCache.evict** —— 阶段一本地资源
            // 必须保留（撤销与回收站缩略图都要用）；本地残留的清理由阶段二
            // （回收站彻底删除 → Repository.finishLocalCleanup）负责。
            //
            // 这里刻意不要求"先连上服务器"：移入回收站是纯本地操作，离线时也应当能做；
            // 服务器文件的联动删除只发生在阶段二，且只认服务器响应。
            // P1-19 粒度约束原样保留：条目身份 = (promptId, filename)。
            // 回收站落盘（原子写 + fsync + 写后读回）不能压在 Main 线程，与
            // GalleryRepository.deleteItem 同一口径走 IO；withContext 结束后自动
            // 回到主线程，后面的 UI 状态更新照旧。
            val identities = withContext(Dispatchers.IO) {
                GalleryRepository.getInstance().moveToTrash(
                    listOf(
                        GalleryItem(
                            promptId = item.promptId,
                            filename = item.filename,
                            subfolder = item.subfolder,
                            type = item.type,
                            isVideo = item.isVideo,
                            index = item.index
                        )
                    )
                )
            }

            // 返回空列表 = 没进站（回收站落盘失败或异常兜底）。此时条目仍在
            // 图库里，查看器队列不能移除 —— 不移除、不填撤销槽、不提示成功，
            // 只报失败；否则"查看器没了、回收站没有、图库还在"，界面之间数据分叉。
            if (TrashDeleteOutcome.single(identities) == TrashDeleteResult.FAILED) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_history_item_delete))
                return@launch
            }

            // Mark that items were removed from the list (for result reporting)
            _hasDeletedItems.value = true

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

            // 一次性撤销槽位（竖滑删除与工具栏删除共用）：新删除顶掉旧槽位
            identities.firstOrNull()?.let { identity ->
                _trashUndo.value = ViewerTrashUndoState(
                    trashIdentity = identity,
                    item = item,
                    viewerIndex = actualIndexToRemove.coerceAtLeast(0)
                )
                scheduleUndoExpiry(identity)
            }

            if (currentItems.isEmpty()) {
                // No more items, close viewer（队列都删空了就没有"就地撤销"的位置，
                // 条目仍在回收站里，可去图库回收站界面恢复）
                _trashUndo.value = null
                // 删空后撤销条已被清掉，这里必须发 Toast，否则用户收不到任何成功反馈
                //（是否发由 shouldEmitTrashToast 判定，契约见该函数注释）
                if (TrashDeleteOutcome.shouldEmitTrashToast(currentItems.size)) {
                    _events.emit(MediaViewerEvent.ShowToast(R.string.msg_trash_moved))
                }
                _events.emit(MediaViewerEvent.ItemDeleted)
                _events.emit(MediaViewerEvent.Close)
            } else {
                // Adjust index and show next/previous item —— 进站后队列补位：
                // 移除下标即新位置（末位被删则自动退一位），下一张顶上
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
                // 还有剩余项：撤销条正在显示（文字同为「已移入回收站」），提示由撤销条独占，
                // 此处不再发 Toast —— 否则真机上两条相同提示重叠（shouldEmitTrashToast 判 false）
                _events.emit(MediaViewerEvent.ItemDeleted)
                loadCurrentItem()
            }
        }
    }

    // ============================================================
    // G4：竖滑删除的一次性撤销（限时 2 秒，用户已确认的交互决策）
    // ============================================================

    private val _trashUndo = MutableStateFlow<ViewerTrashUndoState?>(null)

    /** 撤销槽位（非 null = 限时撤销窗口开着）。 */
    val trashUndo: StateFlow<ViewerTrashUndoState?> = _trashUndo.asStateFlow()

    /** 2 秒后关窗：只关**当时还开着的那条**的窗（新删除已顶掉旧槽位时不误关新窗）。 */
    private fun scheduleUndoExpiry(identity: String) {
        viewModelScope.launch {
            // 时长单一来源：手势侧的 ViewerSwipeDelete.UNDO_WINDOW_MS（2 秒，用户确认值）
            kotlinx.coroutines.delay(sh.hnet.comfychair.ui.components.ViewerSwipeDelete.UNDO_WINDOW_MS)
            if (_trashUndo.value?.trashIdentity == identity) {
                _trashUndo.value = null
            }
        }
    }

    /**
     * 撤销刚才的"移入回收站"（阶段一回退）。
     *
     * suspend：restoreFromTrash 落盘含原子写 + fsync + 写后读回，不能压在 Main 线程
     * （与 GalleryRepository.deleteItem 同一口径），调用方需在协程中调用
     * （撤销条 onClick 经 scope.launch 进入）。
     *
     * 两侧同时恢复：图库列表侧走 Repository.restoreFromTrash（删墓碑 + 按保存的
     * index 找回原位置）；查看器队列侧把条目插回删除前的下标（"恢复原队列位置"）。
     * 槽位已被超时关闭 / 被新删除顶掉 → 静默返回 false（回收站界面仍可恢复，
     * 这里不弹"撤销失败"吓人——用户点到的多半是刚过期的条）。
     */
    suspend fun undoTrashMove(): Boolean {
        val undo = _trashUndo.value ?: return false
        _trashUndo.value = null
        val restored = withContext(Dispatchers.IO) {
            GalleryRepository.getInstance().restoreFromTrash(undo.trashIdentity)
        }
        if (!restored) {
            viewModelScope.launch {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_history_item_delete))
            }
            return false
        }
        val current = _uiState.value
        if (current.items.none {
                it.promptId == undo.item.promptId && it.filename == undo.item.filename
            }
        ) {
            val items = current.items.toMutableList()
            val at = undo.viewerIndex.coerceIn(0, items.size)
            items.add(at, undo.item)
            val safeIndex = at.coerceIn(0, items.size - 1)
            cachedMetadata.clear()
            _currentMetadata.value = null
            _uiState.value = current.copy(
                items = items,
                currentIndex = safeIndex,
                currentBitmap = null,
                currentVideoUri = null,
                isLoading = true
            )
            loadCurrentItem()
        }
        viewModelScope.launch {
            _events.emit(MediaViewerEvent.ShowToast(R.string.msg_trash_restored))
        }
        return true
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
            // P1-22：成功/失败以真实写入结果为准（IS_PENDING 流程）
            val saved = PhotoManager.saveBitmap(context, bitmap)
            _events.emit(
                MediaViewerEvent.ShowToast(
                    if (saved) R.string.msg_image_saved_to_gallery else R.string.error_save_image
                )
            )
        }
    }

    private suspend fun saveVideoFromUri(context: Context, videoUri: Uri?) {
        if (videoUri == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_video))
            return
        }

        withContext(Dispatchers.IO) {
            // P2-12：播放 URI 已改为 file://（进程内播放，不经 FileProvider），
            // contentResolver.openInputStream 读不了 file://，统一走 ShareFileStore
            val videoBytes = runCatching {
                ShareFileStore.openMediaInput(context, videoUri)?.use { it.readBytes() }
            }.getOrNull()
            if (videoBytes == null) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.error_save_video))
                return@withContext
            }

            val saved = PhotoManager.saveVideoBytes(context, videoBytes)
            _events.emit(
                MediaViewerEvent.ShowToast(
                    if (saved) R.string.msg_video_saved_to_gallery else R.string.error_save_video
                )
            )
        }
    }

    private suspend fun saveImageFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        withContext(Dispatchers.IO) {
            // 源已不存在：允许保存，但只能保存本机缓存副本（原图在服务器上已删除）。
            // 判定同时看持久化字段与本次会话的 404 标记：查看器队列是进入时的快照，
            // item.sourceMissing 可能还没跟上刚刚发生的 404。
            val sourceMissing = item.sourceMissing || MediaCache.isFileMissing(key)
            // 带档位标注地取本机副本（R3-3）：本机可能缓存着全尺寸原图（查看器看过的
            // 原图 / 升级前的旧磁盘原图），命中的就是原图 —— 文案不能再说「已保存低清版」
            val localCopy = if (sourceMissing) MediaCache.getLocalImageWithQuality(key) else null
            val bitmap = if (sourceMissing) {
                localCopy?.first
            } else {
                MediaCache.getBitmap(key)
                    ?: MediaCache.fetchImage(key, item.subfolder, item.type)
            }

            if (bitmap == null) {
                _events.emit(
                    MediaViewerEvent.ShowToast(
                        if (sourceMissing) {
                            R.string.msg_image_source_missing_no_cache
                        } else {
                            R.string.error_save_image
                        }
                    )
                )
                return@withContext
            }

            val saved = PhotoManager.saveBitmap(context, bitmap)
            _events.emit(
                MediaViewerEvent.ShowToast(
                    when {
                        !saved -> R.string.error_save_image
                        // 只有实际存下的是低清档（720 统一档 / 旧缩略图）才说「低清版」；
                        // 命中全尺寸副本时按普通成功提示
                        sourceMissing && localCopy?.second == MediaCache.LocalImageQuality.LOW_RES ->
                            R.string.msg_image_saved_lowres
                        else -> R.string.msg_image_saved_to_gallery
                    }
                )
            )
        }
    }

    private suspend fun saveVideoFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        withContext(Dispatchers.IO) {
            // Try cache first, then fetch if needed
            val sourceMissing = item.sourceMissing || MediaCache.isFileMissing(key)
            val videoBytes = if (sourceMissing) {
                MediaCache.getLocalVideoBytes(key)
            } else {
                MediaCache.getVideoBytes(key)
                    ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)
            }

            if (videoBytes == null) {
                _events.emit(
                    MediaViewerEvent.ShowToast(
                        if (sourceMissing) {
                            R.string.msg_image_source_missing_no_cache
                        } else {
                            R.string.error_save_video
                        }
                    )
                )
                return@withContext
            }

            val saved = PhotoManager.saveVideoBytes(context, videoBytes)
            _events.emit(
                MediaViewerEvent.ShowToast(
                    when {
                        !saved -> R.string.error_save_video
                        // 视频缓存不是"低分辨率"：用视频专用文案，不能套图片的"低清版"
                        sourceMissing -> R.string.msg_video_saved_lowres
                        else -> R.string.msg_video_saved_to_gallery
                    }
                )
            )
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
                // P2-12：分享载荷写进 cacheDir/share/
                val shareFile = ShareFileStore.file(context, "share_image.png")
                shareFile.outputStream().use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }

                val uri = ShareFileStore.uriFor(context, shareFile)

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

        // P2-12：播放 URI 现在是 file://，不能直接塞进 ACTION_SEND（会抛 FileUriExposedException），
        // 先复制一份到 cacheDir/share/ 再分享它的 content URI。
        val shareUri = withContext(Dispatchers.IO) {
            ShareFileStore.exportVideoForShare(context, videoUri)
        }
        if (shareUri == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_video))
            return
        }

        withContext(Dispatchers.Main) {
            try {
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, shareUri)
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

        // 源已不存在：分享载荷只能用本机缓存，不发原图请求
        val bitmap = if (item.sourceMissing) {
            MediaCache.getLocalImage(key)
        } else {
            MediaCache.getBitmap(key)
                ?: MediaCache.fetchImage(key, item.subfolder, item.type)
        }

        if (bitmap == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_image))
            return
        }

        shareBitmap(context, bitmap)
    }

    private suspend fun shareVideoFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        // Try cache first, then fetch if needed（源已不存在时只用本机字节）
        val videoBytes = if (item.sourceMissing) {
            MediaCache.getLocalVideoBytes(key)
        } else {
            MediaCache.getVideoBytes(key)
                ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)
        }

        if (videoBytes == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.error_share_video))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                // P2-12：分享载荷写进 cacheDir/share/
                val shareFile = ShareFileStore.file(context, "share_video.mp4")
                shareFile.writeBytes(videoBytes)

                val uri = ShareFileStore.uriFor(context, shareFile)

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
