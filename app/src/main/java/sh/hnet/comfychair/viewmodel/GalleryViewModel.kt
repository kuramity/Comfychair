package sh.hnet.comfychair.viewmodel

import android.content.Context
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.PhotoManager
import sh.hnet.comfychair.util.ShareFileStore
import java.io.File

/**
 * Represents a gallery item (image or video).
 * Bitmaps are stored in MediaCache, not in this data class.
 */
data class GalleryItem(
    val promptId: String,
    val filename: String,
    val subfolder: String,
    val type: String,
    val isVideo: Boolean,
    val index: Int = 0, // For sorting
    /**
     * 同一产出（promptId）里的 3D 产物位置（glb 三元组，来自 /history 的 `3d` 字段）。
     *
     * 非空时该条目渲染「3D」角标，点击打开 3D 查看器而不是图片查看器 ——
     * IDOL 图生3D 的输出就是一个 prompt 下两个 node：SaveGLB 写 `3d`、
     * SaveImage 写预览图 `images`，不配对的话用户只能在图库看到 png、
     * 永远找不到 glb 的入口（v4.2.2 真机反馈的实际缺陷）。
     */
    val threeD: ThreeDResultHolder.Result? = null,
    /**
     * 「源已不存在」：服务器上的原文件确认已被删除（/view 返回 **HTTP 404**）。
     *
     * 与「加载中」「已加载」构成图库条目的第三态：
     * - 加载中：模糊/转圈占位，保存不可用；
     * - 已加载：清晰原图，保存可用；
     * - 源已不存在：显示本机缓存的低清副本（不盖模糊），右上角「已删除」角标，
     *   保存可用但明确提示保存的是低清版。
     *
     * **只有 404 才会置 true**（见 `ComfyUIClient.isSourceMissingStatus`）：
     * 超时、断网、5xx 一律不置位 —— 地铁里打开图库不能满屏「已删除」。
     * 该标记随图库索引持久化，刷新合并时与本地旧值做逻辑或，
     * 防止 ComfyUI「只删文件不删 /history」把标记冲掉。
     */
    val sourceMissing: Boolean = false
) {
    /**
     * Create a cache key for this item.
     *
     * type 与当前服务器 ID 一起进键（P2-2）：前者区分 output/temp 的同名文件，
     * 后者避免 MediaCache 单例在多台服务器之间串数据。
     */
    fun toCacheKey() = MediaCacheKey.of(promptId, filename, type)
}

/**
 * UI state for the Gallery screen
 */
data class GalleryUiState(
    val items: List<GalleryItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedItems: Set<String> = emptySet(), // Set of "${promptId}_${filename}" keys
    val isSelectionMode: Boolean = false,
    /** 当前列表是否已全部选中（决定「全选/取消全选」按钮的形态）。 */
    val isAllSelected: Boolean = false,
    /** 服务端还有更早的历史可拉（图库底部显示「加载更早」按钮）。 */
    val canLoadOlder: Boolean = false,
    /** 「加载更早」请求在途（底部显示转圈，防连点重复发）。 */
    val isLoadingOlder: Boolean = false
)

/**
 * 单条媒体「保存到相册」的结果档位（纯枚举，便于批量保存汇总提示）。
 *
 * [SAVED_LOWRES] 是本轮新增的第三态分支：源已不存在（/view 404）但本机有缓存时，
 * 保存的是低清副本 —— 成功文案必须说清楚，不能让用户以为存下了原图。
 */
internal enum class SaveOutcome {
    SAVED,
    SAVED_LOWRES,
    /** 源已不存在且本机连缓存都没有：只能拒绝保存并说明原因 */
    NO_LOCAL_COPY,
    FAILED
}

/**
 * Events emitted by gallery operations
 */
sealed class GalleryEvent {
    data class ShowToast(val messageResId: Int) : GalleryEvent()
    data class ShowMedia(val item: GalleryItem, val bitmap: Bitmap?, val videoUri: Uri?) : GalleryEvent()

    /**
     * G4：一次"回收站彻底删除"批处理的分路由结果。
     * 文案由 GalleryScreen 按四类计数拼装（ViewModel 没有 Context，格式化归 UI 层），
     * 每类各说各话 —— 服务器删了 / 只清了本机 / 待重试 / 没连上那台，绝不混报。
     */
    data class TrashPurged(val summary: GalleryRepository.TrashPurgeSummary) : GalleryEvent()
}

/**
 * ViewModel for the Gallery screen.
 * Uses GalleryRepository for data management and background loading.
 */
class GalleryViewModel : ViewModel() {

    // Constants
    companion object {
        private const val TAG = "Gallery"
    }

    // State
    private val repository = GalleryRepository.getInstance()

    /** G4：回收站条目（含两阶段状态）。UI 直接 collect 这个流即可。 */
    val trashEntries: StateFlow<List<sh.hnet.comfychair.storage.GalleryTrashEntry>> =
        repository.trashEntries

    // Selection state (local to this ViewModel)
    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    private val _isSelectionMode = MutableStateFlow(false)

    // Combine repository state with local selection state
    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GalleryEvent>()
    val events: SharedFlow<GalleryEvent> = _events.asSharedFlow()

    init {
        // Observe repository state and combine with local selection state
        viewModelScope.launch {
            // 数据流 + 分页流 + 选择流 三组各自先合（combine 定长重载只到 5 个），
            // 再汇成一份 GalleryUiState
            val dataFlows = combine(
                repository.galleryItems,
                repository.isLoading,
                repository.isManualRefreshing
            ) { items, isLoading, isManualRefreshing ->
                Triple(items, isLoading, isManualRefreshing)
            }
            val pagingFlows = combine(
                repository.canLoadOlder,
                repository.isLoadingOlder
            ) { canLoadOlder, isLoadingOlder -> canLoadOlder to isLoadingOlder }
            val selectionFlows = combine(
                _selectedItems,
                _isSelectionMode
            ) { selectedItems, isSelectionMode -> selectedItems to isSelectionMode }
            combine(dataFlows, pagingFlows, selectionFlows) { data, paging, selection ->
                val (items, isLoading, isManualRefreshing) = data
                GalleryUiState(
                    items = items,
                    isLoading = isLoading,
                    isRefreshing = isManualRefreshing, // Only show indicator for manual refresh
                    selectedItems = selection.first,
                    isSelectionMode = selection.second,
                    canLoadOlder = paging.first,
                    isLoadingOlder = paging.second,
                    // 逐项核对而不是比数量：刷新后列表可能变化，选中集合里可能残留已消失的键
                    isAllSelected = items.isNotEmpty() &&
                        items.all { getItemKey(it) in selection.first }
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun initialize() {
        // MediaCache is used for all fetch operations
        // Repository is already initialized by GenerationViewModel
        // G4：回收站列表是 UI 直接观察的流 —— 初始化就把磁盘记录读进来
        // （repository.initialize 已在 MainContainerActivity 落过 context，幂等安全）
        repository.ensureTrashLoaded()
    }

    /**
     * Load gallery - delegates to repository.
     * If repository already has data, this will return immediately with cached data.
     * The repository handles background refreshing.
     */
    fun loadGallery() {
        DebugLogger.d(TAG, "Loading gallery")
        // Repository already has data from background preload, no need to reload
        // unless explicitly refreshed by user
        if (!repository.hasData()) {
            repository.refresh()
        }
    }

    /**
     * Manual refresh triggered by user (pull-to-refresh).
     * Shows the refresh indicator and displays a Toast on completion.
     */
    fun manualRefresh() {
        DebugLogger.i(TAG, "Manual refresh")
        repository.manualRefresh { success ->
            viewModelScope.launch {
                if (success) {
                    DebugLogger.d(TAG, "Refresh successful")
                    _events.emit(GalleryEvent.ShowToast(R.string.msg_gallery_refresh_success))
                } else {
                    DebugLogger.w(TAG, "Refresh failed")
                    _events.emit(GalleryEvent.ShowToast(R.string.error_gallery_refresh))
                }
            }
        }
    }

    /**
     * Background refresh (silent, no indicator).
     * Used when returning from other screens or after external changes.
     */
    fun refresh() {
        DebugLogger.d(TAG, "Background refresh")
        repository.refresh()
    }

    /**
     * 「加载更早」：向服务端把 history 窗口放宽一档重拉，更早条目追加到列表尾部。
     * 与下拉刷新/后台刷新是两条独立链路（仓库内部互斥），结果只影响 items/canLoadOlder/
     * isLoadingOlder 三个状态。失败静默（按钮还在，可再点）。
     */
    fun loadOlder() {
        DebugLogger.i(TAG, "Loading older gallery history")
        repository.loadOlderItems()
    }

    fun deleteItem(item: GalleryItem) {
        DebugLogger.i(TAG, "Deleting item: ${item.promptId}")
        viewModelScope.launch {
            // G4：删除 = 移入回收站（阶段一，可恢复）。返回值 = 条目是否真的进站：
            // 回收站落盘失败时 moveToTrash 返回空列表 → false，不能当成成功。
            val success = repository.deleteItem(item)
            if (success) {
                DebugLogger.d(TAG, "Moved to trash")
                _events.emit(GalleryEvent.ShowToast(R.string.msg_history_item_deleted_success))
            } else {
                DebugLogger.w(TAG, "Delete failed")
                _events.emit(GalleryEvent.ShowToast(R.string.error_history_item_delete))
            }
        }
    }

    // ============================================================
    // G4（任务书 11.4）：回收站操作入口
    // ============================================================

    /**
     * 恢复一条回收站条目（阶段一回退）。
     * 恢复后列表顺序与元数据一致（条目带着原 index 插回；墓碑已抹除）。
     */
    fun restoreTrashEntry(identityKey: String) {
        viewModelScope.launch {
            // L6：回收站/墓碑的落盘（原子写 + fsync + 写后读回）不能压在 Main 线程；
            // 修法与 MediaViewerViewModel.undoTrashMove 一致（切 IO），结果回主线程发事件。
            val restored = withContext(Dispatchers.IO) {
                repository.restoreFromTrash(identityKey)
            }
            _events.emit(
                GalleryEvent.ShowToast(
                    if (restored) R.string.msg_trash_restored else R.string.msg_trash_restore_failed
                )
            )
        }
    }

    /**
     * 回收站"彻底删除"（阶段二：服务器联动 + 本地残留清理）。
     *
     * **首次启用的清单确认由调用方（GalleryScreen）在进入本函数之前完成** ——
     * 破坏性操作不静默启动。确认状态经 AppSettings 按服务器持久化（只问一次）。
     * 结果分四类回报（服务器已删 / 仅本机清理 / 待重试 / 属于其它服务器），
     * 失败条目留在回收站可重试，本地状态绝不谎报成功。
     */
    fun purgeTrashEntries(identities: List<String>) {
        if (identities.isEmpty()) return
        repository.purgeTrashEntries(identities) { summary ->
            viewModelScope.launch {
                _events.emit(GalleryEvent.TrashPurged(summary))
            }
        }
    }

    fun saveImageToGallery(context: Context, item: GalleryItem) {
        viewModelScope.launch {
            val outcome = saveImageItem(context, item)
            _events.emit(GalleryEvent.ShowToast(saveOutcomeMessage(outcome, item.isVideo)))
        }
    }

    fun saveVideoToGallery(context: Context, item: GalleryItem) {
        viewModelScope.launch {
            val outcome = saveVideoItem(context, item)
            _events.emit(GalleryEvent.ShowToast(saveOutcomeMessage(outcome, isVideo = true)))
        }
    }

    /** 单条保存结果 → 提示文案（源已不存在时明确说"低清版"/"本机无缓存"） */
    private fun saveOutcomeMessage(outcome: SaveOutcome, isVideo: Boolean): Int = when (outcome) {
        SaveOutcome.SAVED ->
            if (isVideo) R.string.msg_video_saved_to_gallery else R.string.msg_image_saved_to_gallery
        // 视频的本地缓存不是"低分辨率"：文案必须说"本机缓存版"，不能说成低清图片
        SaveOutcome.SAVED_LOWRES ->
            if (isVideo) R.string.msg_video_saved_lowres else R.string.msg_image_saved_lowres
        SaveOutcome.NO_LOCAL_COPY -> R.string.msg_image_source_missing_no_cache
        SaveOutcome.FAILED -> if (isVideo) R.string.error_save_video else R.string.error_save_image
    }

    /**
     * 保存一张图片（唯一实现，单条/批量共用）。
     *
     * `sourceMissing == true` 时**只读本机缓存**：原图已确认不在服务器上，
     * 任何网络请求都只会再等一次 404/超时。本机也没有缓存 → NO_LOCAL_COPY，
     * 由界面明确提示「原图已被删除，且本机无缓存」。
     */
    private suspend fun saveImageItem(context: Context, item: GalleryItem): SaveOutcome =
        withContext(Dispatchers.IO) {
            val key = item.toCacheKey()
            // 已持久化的标记与本次会话的 404 标记任一成立都按第三态处理
            val alreadyMissing = item.sourceMissing || MediaCache.isFileMissing(key)
            val bitmap = if (alreadyMissing) {
                MediaCache.getLocalImage(key)
            } else {
                MediaCache.getBitmap(key)
                    ?: MediaCache.fetchImage(key, item.subfolder, item.type)
            }

            if (bitmap == null) {
                return@withContext if (alreadyMissing) SaveOutcome.NO_LOCAL_COPY else SaveOutcome.FAILED
            }

            // P1-22：成功/失败以真实写入结果为准（IS_PENDING 流程，不再假成功）
            if (!PhotoManager.saveBitmap(context, bitmap)) return@withContext SaveOutcome.FAILED
            // 拉取过程中才确认 404 的情况也要报"低清版"：fetchImage 已回落到本机缓存
            if (item.sourceMissing || MediaCache.isFileMissing(key)) {
                SaveOutcome.SAVED_LOWRES
            } else {
                SaveOutcome.SAVED
            }
        }

    /** 保存一个视频（与 [saveImageItem] 同一纪律：源已不存在时不联网，只用本机字节）。 */
    private suspend fun saveVideoItem(context: Context, item: GalleryItem): SaveOutcome =
        withContext(Dispatchers.IO) {
            val key = item.toCacheKey()
            val alreadyMissing = item.sourceMissing || MediaCache.isFileMissing(key)
            val videoBytes = if (alreadyMissing) {
                MediaCache.getLocalVideoBytes(key)
            } else {
                MediaCache.getVideoBytes(key)
                    ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)
            }

            if (videoBytes == null) {
                return@withContext if (alreadyMissing) SaveOutcome.NO_LOCAL_COPY else SaveOutcome.FAILED
            }

            if (!PhotoManager.saveVideoBytes(context, videoBytes)) return@withContext SaveOutcome.FAILED
            if (item.sourceMissing || MediaCache.isFileMissing(key)) {
                SaveOutcome.SAVED_LOWRES
            } else {
                SaveOutcome.SAVED
            }
        }

    fun fetchFullImage(item: GalleryItem, onResult: (Bitmap?) -> Unit) {
        viewModelScope.launch {
            val key = item.toCacheKey()
            // 源已不存在：不再请求原图，直接用本机缓存（720 档/旧磁盘）
            val bitmap = if (item.sourceMissing) {
                MediaCache.getLocalImage(key)
            } else {
                MediaCache.getBitmap(key)
                    ?: MediaCache.fetchImage(key, item.subfolder, item.type)
            }
            onResult(bitmap)
        }
    }

    fun fetchVideoUri(context: Context, item: GalleryItem, onResult: (Uri?) -> Unit) {
        viewModelScope.launch {
            val key = item.toCacheKey()
            // Ensure bytes are cached（源已不存在：只用本机字节，不请求已删除的文件）
            if (item.sourceMissing) {
                MediaCache.getLocalVideoBytes(key)
            } else {
                MediaCache.getVideoBytes(key)
                    ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)
            }

            // Get URI from cache (creates temp file from cached bytes)
            val uri = MediaCache.getVideoUri(key, context)
            onResult(uri)
        }
    }

    fun shareImage(context: Context, item: GalleryItem) {
        viewModelScope.launch {
            val key = item.toCacheKey()
            // 源已不存在：分享也只能用本机缓存，不发起原图请求
            val bitmap = if (item.sourceMissing) {
                MediaCache.getLocalImage(key)
            } else {
                MediaCache.getBitmap(key)
                    ?: MediaCache.fetchImage(key, item.subfolder, item.type)
            }

            if (bitmap == null) {
                _events.emit(GalleryEvent.ShowToast(R.string.error_share_image))
                return@launch
            }

            try {
                // Save to cache for sharing
                // P2-12：分享载荷只能写进 cacheDir/share/（FileProvider 唯一声明的缓存子目录）
                val shareFile = ShareFileStore.file(context, "share_image.png")
                withContext(Dispatchers.IO) {
                    shareFile.outputStream().use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                }

                val uri = ShareFileStore.uriFor(context, shareFile)

                val shareIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    type = "image/png"
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    android.content.Intent.createChooser(
                        shareIntent,
                        context.getString(R.string.share_image)
                    )
                )
            } catch (e: Exception) {
                _events.emit(GalleryEvent.ShowToast(R.string.error_share_image))
            }
        }
    }

    fun shareVideo(context: Context, item: GalleryItem) {
        viewModelScope.launch {
            val key = item.toCacheKey()
            // 源已不存在：分享只能用本机字节，不请求已删除的文件
            val videoBytes = if (item.sourceMissing) {
                MediaCache.getLocalVideoBytes(key)
            } else {
                MediaCache.getVideoBytes(key)
                    ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)
            }

            if (videoBytes == null) {
                _events.emit(GalleryEvent.ShowToast(R.string.error_share_video))
                return@launch
            }

            try {
                // P2-12：分享载荷写进 cacheDir/share/
                val shareFile = ShareFileStore.file(context, "share_video.mp4")
                withContext(Dispatchers.IO) {
                    shareFile.writeBytes(videoBytes)
                }

                val uri = ShareFileStore.uriFor(context, shareFile)

                val shareIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    type = "video/mp4"
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    android.content.Intent.createChooser(
                        shareIntent,
                        context.getString(R.string.share_video)
                    )
                )
            } catch (e: Exception) {
                _events.emit(GalleryEvent.ShowToast(R.string.error_share_video))
            }
        }
    }

    // Selection mode functions

    /**
     * 条目的选择键（`"${promptId}_${filename}"`）。
     *
     * N4-R 起从 private 放开到 internal（**仅可见性变化，语义没动**）：图库长按滑选要把
     * 「layoutInfo 的 index」映射成选择键，UI 层不该再复制一份拼接口径 —— 一旦口径变了，
     * 滑选会静默勾不上。
     */
    internal fun getItemKey(item: GalleryItem): String = "${item.promptId}_${item.filename}"

    fun toggleSelection(item: GalleryItem) {
        val key = getItemKey(item)
        val currentSelected = _selectedItems.value.toMutableSet()

        if (currentSelected.contains(key)) {
            currentSelected.remove(key)
        } else {
            currentSelected.add(key)
        }

        _selectedItems.value = currentSelected
        _isSelectionMode.value = currentSelected.isNotEmpty()
    }

    fun isItemSelected(item: GalleryItem): Boolean {
        return _selectedItems.value.contains(getItemKey(item))
    }

    fun clearSelection() {
        _selectedItems.value = emptySet()
        _isSelectionMode.value = false
    }

    /**
     * 滑选（线性连续区间）：把选中集合**整体设置**为 [keys]，不是逐个 toggle。
     *
     * 空集时同时退出选择模式 —— 与 [toggleSelection] 取消最后一项的行为一致，
     * 否则把区间拖回原点会留下一个空的选择模式界面。
     */
    fun applyDragSelection(keys: Set<String>) {
        _selectedItems.value = keys
        _isSelectionMode.value = keys.isNotEmpty()
    }

    fun enterSelectionMode() {
        _isSelectionMode.value = true
    }

    /**
     * 全选 / 取消全选。
     *
     * 取消全选时**保持**选择模式（用户可以重新勾选），与逐项取消到最后一项自动退出不同。
     */
    fun toggleSelectAll() {
        val allKeys = _uiState.value.items.map { getItemKey(it) }.toSet()
        if (allKeys.isEmpty()) return

        val current = _selectedItems.value
        if (allKeys.all { it in current }) {
            _selectedItems.value = emptySet()
        } else {
            _selectedItems.value = allKeys
            _isSelectionMode.value = true
        }
    }

    fun deleteSelected() {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            // P1-19：删除粒度是**条目**（promptId + filename），不是 promptId。
            // 同一次生成可能产出多张图（批量出图 4 张共用一个 promptId），
            // 以前按 promptId 去重后整批删，选中一张会连带删掉同批的其它三张。
            val successCount = repository.deleteItems(selectedItems)

            // 删除结果统一走纯判定。返回 0 条 = 一条都没进站（回收站落盘失败），
            // 必须报失败——绝不能因为 failCount == 0 之类的边界提示成功。
            val outcome = TrashDeleteOutcome.batch(successCount, selectedItems.size)
            when (outcome) {
                TrashDeleteResult.SUCCESS ->
                    _events.emit(GalleryEvent.ShowToast(R.string.msg_items_deleted_success))
                TrashDeleteResult.PARTIAL ->
                    _events.emit(GalleryEvent.ShowToast(R.string.msg_some_items_failed_to_delete))
                TrashDeleteResult.FAILED ->
                    _events.emit(GalleryEvent.ShowToast(R.string.error_history_item_delete))
            }

            // 全部失败时**保留选择**：条目仍在列表里，用户可直接重试，不必重新勾选。
            // 只有确实有条目进站（SUCCESS/PARTIAL）才清空选择。
            if (outcome != TrashDeleteResult.FAILED) {
                clearSelection()
            }
        }
    }

    fun getSelectedItems(): List<GalleryItem> {
        val selectedKeys = _selectedItems.value
        return _uiState.value.items.filter { getItemKey(it) in selectedKeys }
    }

    fun saveSelectedToGallery(context: Context) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            var successCount = 0
            var failCount = 0
            var lowresImageCount = 0
            var lowresVideoCount = 0
            var noLocalCopyCount = 0

            for (item in selectedItems) {
                val outcome = if (item.isVideo) {
                    saveVideoItem(context, item)
                } else {
                    saveImageItem(context, item)
                }
                when (outcome) {
                    SaveOutcome.SAVED -> successCount++
                    SaveOutcome.SAVED_LOWRES -> {
                        successCount++
                        if (item.isVideo) lowresVideoCount++ else lowresImageCount++
                    }
                    SaveOutcome.NO_LOCAL_COPY -> {
                        failCount++
                        noLocalCopyCount++
                    }
                    SaveOutcome.FAILED -> failCount++
                }
            }

            // Show result toast：低清/本机缓存版成功也要说清楚；全部失败且原因都是"本机无缓存"
            // 时给专门文案，不能只丢一句模糊的"部分保存失败"。
            // 图片与视频分开措辞：视频缓存不是低分辨率，混选时用复数版"本机缓存版"。
            val messageRes = when {
                failCount == 0 && lowresImageCount > 0 && lowresVideoCount > 0 ->
                    R.string.msg_saved_local_copies
                failCount == 0 && lowresVideoCount > 0 -> R.string.msg_video_saved_lowres
                failCount == 0 && lowresImageCount > 0 -> R.string.msg_image_saved_lowres
                failCount == 0 -> R.string.msg_items_saved_to_gallery
                successCount == 0 && noLocalCopyCount == failCount ->
                    R.string.msg_image_source_missing_no_cache
                else -> R.string.msg_some_items_failed_to_save
            }
            _events.emit(GalleryEvent.ShowToast(messageRes))

            // Clear selection after save
            clearSelection()
        }
    }

    fun shareSelected(context: Context) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            try {
                val uris = mutableListOf<Uri>()

                for ((index, item) in selectedItems.withIndex()) {
                    val uri = if (item.isVideo) {
                        getVideoShareUri(context, item, index)
                    } else {
                        getImageShareUri(context, item, index)
                    }
                    uri?.let { uris.add(it) }
                }

                if (uris.isEmpty()) {
                    _events.emit(GalleryEvent.ShowToast(R.string.error_share_items))
                    return@launch
                }

                val shareIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND_MULTIPLE
                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                    type = if (selectedItems.all { it.isVideo }) "video/*"
                           else if (selectedItems.none { it.isVideo }) "image/*"
                           else "*/*"
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    android.content.Intent.createChooser(
                        shareIntent,
                        context.getString(R.string.button_share)
                    )
                )

                // Clear selection after share
                clearSelection()
            } catch (e: Exception) {
                _events.emit(GalleryEvent.ShowToast(R.string.error_share_items))
            }
        }
    }

    private suspend fun getImageShareUri(context: Context, item: GalleryItem, index: Int): Uri? {
        return withContext(Dispatchers.IO) {
            val key = item.toCacheKey()
            // 源已不存在：分享载荷只能用本机缓存，不发原图请求
            val bitmap = if (item.sourceMissing) {
                MediaCache.getLocalImage(key)
            } else {
                MediaCache.getBitmap(key)
                    ?: MediaCache.fetchImage(key, item.subfolder, item.type)
            }

            if (bitmap == null) return@withContext null

            try {
                // P2-12：分享载荷写进 cacheDir/share/
                val shareFile = ShareFileStore.file(context, "share_image_$index.png")
                shareFile.outputStream().use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }

                ShareFileStore.uriFor(context, shareFile)
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun getVideoShareUri(context: Context, item: GalleryItem, index: Int): Uri? {
        return withContext(Dispatchers.IO) {
            val key = item.toCacheKey()
            // 源已不存在：分享载荷只能用本机字节
            val videoBytes = if (item.sourceMissing) {
                MediaCache.getLocalVideoBytes(key)
            } else {
                MediaCache.getVideoBytes(key)
                    ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)
            }

            if (videoBytes == null) return@withContext null

            try {
                val shareFile = ShareFileStore.file(context, "share_video_$index.mp4")
                shareFile.writeBytes(videoBytes)

                ShareFileStore.uriFor(context, shareFile)
            } catch (e: Exception) {
                null
            }
        }
    }
}
