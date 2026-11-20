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
    val threeD: ThreeDResultHolder.Result? = null
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
    val isAllSelected: Boolean = false
)

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
            combine(
                repository.galleryItems,
                repository.isLoading,
                repository.isManualRefreshing,
                _selectedItems,
                _isSelectionMode
            ) { items, isLoading, isManualRefreshing, selectedItems, isSelectionMode ->
                GalleryUiState(
                    items = items,
                    isLoading = isLoading,
                    isRefreshing = isManualRefreshing, // Only show indicator for manual refresh
                    selectedItems = selectedItems,
                    isSelectionMode = isSelectionMode,
                    // 逐项核对而不是比数量：刷新后列表可能变化，选中集合里可能残留已消失的键
                    isAllSelected = items.isNotEmpty() && items.all { getItemKey(it) in selectedItems }
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

    fun deleteItem(item: GalleryItem) {
        DebugLogger.i(TAG, "Deleting item: ${item.promptId}")
        viewModelScope.launch {
            // G4：删除 = 移入回收站（阶段一，可恢复）。成功恒为 true（纯本地操作）
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
        val restored = repository.restoreFromTrash(identityKey)
        viewModelScope.launch {
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
            withContext(Dispatchers.IO) {
                val key = item.toCacheKey()
                val bitmap = MediaCache.getBitmap(key)
                    ?: MediaCache.fetchImage(key, item.subfolder, item.type)

                if (bitmap == null) {
                    _events.emit(GalleryEvent.ShowToast(R.string.error_save_image))
                    return@withContext
                }

                // P1-22：成功/失败以真实写入结果为准（IS_PENDING 流程，不再假成功）
                val saved = PhotoManager.saveBitmap(context, bitmap)
                _events.emit(
                    GalleryEvent.ShowToast(
                        if (saved) R.string.msg_image_saved_to_gallery else R.string.error_save_image
                    )
                )
            }
        }
    }

    fun saveVideoToGallery(context: Context, item: GalleryItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val key = item.toCacheKey()
                val videoBytes = MediaCache.getVideoBytes(key)
                    ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

                if (videoBytes == null) {
                    _events.emit(GalleryEvent.ShowToast(R.string.error_save_video))
                    return@withContext
                }

                val saved = PhotoManager.saveVideoBytes(context, videoBytes)
                _events.emit(
                    GalleryEvent.ShowToast(
                        if (saved) R.string.msg_video_saved_to_gallery else R.string.error_save_video
                    )
                )
            }
        }
    }

    fun fetchFullImage(item: GalleryItem, onResult: (Bitmap?) -> Unit) {
        viewModelScope.launch {
            val key = item.toCacheKey()
            val bitmap = MediaCache.getBitmap(key)
                ?: MediaCache.fetchImage(key, item.subfolder, item.type)
            onResult(bitmap)
        }
    }

    fun fetchVideoUri(context: Context, item: GalleryItem, onResult: (Uri?) -> Unit) {
        viewModelScope.launch {
            val key = item.toCacheKey()
            // Ensure bytes are cached
            MediaCache.getVideoBytes(key)
                ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

            // Get URI from cache (creates temp file from cached bytes)
            val uri = MediaCache.getVideoUri(key, context)
            onResult(uri)
        }
    }

    fun shareImage(context: Context, item: GalleryItem) {
        viewModelScope.launch {
            val key = item.toCacheKey()
            val bitmap = MediaCache.getBitmap(key)
                ?: MediaCache.fetchImage(key, item.subfolder, item.type)

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
            val videoBytes = MediaCache.getVideoBytes(key)
                ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

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

    private fun getItemKey(item: GalleryItem): String = "${item.promptId}_${item.filename}"

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
            val failCount = selectedItems.size - successCount

            // Show result toast
            if (failCount == 0) {
                _events.emit(GalleryEvent.ShowToast(R.string.msg_items_deleted_success))
            } else {
                _events.emit(GalleryEvent.ShowToast(R.string.msg_some_items_failed_to_delete))
            }

            // Clear selection after delete
            clearSelection()
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

            for (item in selectedItems) {
                val success = if (item.isVideo) {
                    saveVideoToGalleryInternal(context, item)
                } else {
                    saveImageToGalleryInternal(context, item)
                }
                if (success) successCount++ else failCount++
            }

            // Show result toast
            if (failCount == 0) {
                _events.emit(GalleryEvent.ShowToast(R.string.msg_items_saved_to_gallery))
            } else {
                _events.emit(GalleryEvent.ShowToast(R.string.msg_some_items_failed_to_save))
            }

            // Clear selection after save
            clearSelection()
        }
    }

    private suspend fun saveImageToGalleryInternal(context: Context, item: GalleryItem): Boolean {
        return withContext(Dispatchers.IO) {
            val key = item.toCacheKey()
            val bitmap = MediaCache.getBitmap(key)
                ?: MediaCache.fetchImage(key, item.subfolder, item.type)

            if (bitmap == null) return@withContext false

            // P1-22：真实写入结果（IS_PENDING → 发布），失败返回 false 让调用方计入失败数
            PhotoManager.saveBitmap(context, bitmap)
        }
    }

    private suspend fun saveVideoToGalleryInternal(context: Context, item: GalleryItem): Boolean {
        return withContext(Dispatchers.IO) {
            val key = item.toCacheKey()
            val videoBytes = MediaCache.getVideoBytes(key)
                ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

            if (videoBytes == null) return@withContext false

            PhotoManager.saveVideoBytes(context, videoBytes)
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
            val bitmap = MediaCache.getBitmap(key)
                ?: MediaCache.fetchImage(key, item.subfolder, item.type)

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
            val videoBytes = MediaCache.getVideoBytes(key)
                ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

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
