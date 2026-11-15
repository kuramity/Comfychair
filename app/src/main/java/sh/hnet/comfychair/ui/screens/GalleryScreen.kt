package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.cache.ActiveView
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.ui.components.shared.ThreeDViewerSheet
import sh.hnet.comfychair.connection.rememberIsOffline
import sh.hnet.comfychair.ui.components.rememberLazyBitmap
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GalleryEvent
import sh.hnet.comfychair.viewmodel.GalleryItem
import sh.hnet.comfychair.viewmodel.GalleryViewModel
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.MediaViewerItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    generationViewModel: GenerationViewModel,
    galleryViewModel: GalleryViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by galleryViewModel.uiState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()

    // 离线态从连接状态派生：强制离线与自动离线都要显示"未缓存"占位图标，且要实时刷新
    val isOffline = rememberIsOffline()

    // Grid state for scroll tracking
    // （声明提到 launcher 之前：回调里要按查看器回传的 key 定位滚动位置）
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // State and effects
    // Activity result launcher for MediaViewerActivity
    val mediaViewerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Restore active view to Gallery
        MediaCache.setActiveView(ActiveView.GALLERY)

        // Refresh gallery if item was deleted (silent refresh, no spinner)
        if (result.resultCode == Activity.RESULT_OK) {
            val itemDeleted = result.data?.getBooleanExtra(MediaViewerActivity.RESULT_ITEM_DELETED, false) ?: false
            if (itemDeleted) {
                galleryViewModel.refresh()
            }

            // 位置双向同步（C3）：查看器里翻到第 N 张后返回，列表自动滚到那一张。
            // 回传的是稳定键（promptId_filename），在当前 items 里找索引——
            // 该项可能已被删除：找不到就静默忽略，绝不滚动到错误位置
            val currentKey = result.data?.getStringExtra(MediaViewerActivity.EXTRA_CURRENT_ITEM_KEY)
            if (currentKey != null) {
                val index = uiState.items.indexOfFirst {
                    "${it.promptId}_${it.filename}" == currentKey
                }
                val isAlreadyVisible = gridState.layoutInfo.visibleItemsInfo.any { it.index == index }
                if (index >= 0 && !isAlreadyVisible) {
                    // non-animated：返回瞬间长距离滑动观感差，直接落位更稳
                    scope.launch { gridState.scrollToItem(index) }
                }
            }
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        galleryViewModel.initialize()
    }

    // Load gallery when connected
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            galleryViewModel.loadGallery()
        }
    }

    // Event handling
    LaunchedEffect(Unit) {
        galleryViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is GalleryEvent.ShowMedia -> {
                    // Handle media display
                }
            }
        }
    }

    // Helper function to convert GalleryItems to MediaViewerItems
    fun galleryItemsToViewerItems(items: List<GalleryItem>): List<MediaViewerItem> {
        return items.map { item ->
            MediaViewerItem(
                promptId = item.promptId,
                filename = item.filename,
                subfolder = item.subfolder,
                type = item.type,
                isVideo = item.isVideo,
                index = item.index
            )
        }
    }

    // 当前从图库打开的 3D 产物（非空时全屏显示 3D 查看器）
    var active3D by remember { mutableStateOf<ThreeDResultHolder.Result?>(null) }

    // Function to launch media viewer
    fun launchMediaViewer(clickedIndex: Int) {
        // Set active view to MediaViewer
        MediaCache.setActiveView(ActiveView.MEDIA_VIEWER)

        // Update priorities to protect items around clicked index
        val allKeys = uiState.items.map { it.toCacheKey() }
        MediaCache.updateNavigationPriorities(clickedIndex, allKeys)

        val viewerItems = galleryItemsToViewerItems(uiState.items)
        val intent = MediaViewerActivity.createGalleryIntent(
            context = context,
            hostname = ConnectionManager.hostname,
            port = ConnectionManager.port,
            items = viewerItems,
            initialIndex = clickedIndex
        )
        mediaViewerLauncher.launch(intent)
        // 打开过渡（C2）：launch 内部同步发起 startActivityForResult，这里紧接着设置——
        // 列表保持不动，查看器以缩小淡入进场（与关闭时的缩小淡出对称）
        (context as? Activity)?.overridePendingTransition(
            R.anim.viewer_fade_in_grow, R.anim.gallery_stay
        )
    }

    // 批量删除前的确认（一次删掉几十张不可撤销，必须拦一道）
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Create prefetch items list from gallery items
    val prefetchItems = remember(uiState.items) {
        uiState.items.map { item ->
            MediaCache.PrefetchItem(
                key = item.toCacheKey(),
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type
            )
        }
    }

    // Track scroll position and update cache priorities with debounce
    // Only update when Gallery is the active view (not when MediaViewer is animating closed)
    LaunchedEffect(gridState.firstVisibleItemIndex, gridState.layoutInfo.visibleItemsInfo.size) {
        if (prefetchItems.isNotEmpty() && MediaCache.isActiveView(ActiveView.GALLERY)) {
            // Debounce scroll updates to avoid excessive calls during fast scrolling
            kotlinx.coroutines.delay(100)
            MediaCache.updateGalleryPosition(
                firstVisibleIndex = gridState.firstVisibleItemIndex,
                visibleItemCount = gridState.layoutInfo.visibleItemsInfo.size,
                allItems = prefetchItems,
                columnsInGrid = 2
            )
        }
    }

    // Prefetch when items become available or change (e.g., after manual refresh)
    // Use first item's key as part of the effect key to detect list changes
    val itemsKey = remember(uiState.items) {
        if (uiState.items.isEmpty()) "" else "${uiState.items.size}_${uiState.items.first().promptId}"
    }
    LaunchedEffect(itemsKey) {
        if (uiState.items.isNotEmpty()) {
            // Base prefetch on current scroll position instead of always starting from 0
            val startIndex = gridState.firstVisibleItemIndex.coerceAtLeast(0)
            val endIndex = (startIndex + 24).coerceAtMost(uiState.items.size)
            val initialItems = uiState.items.subList(startIndex, endIndex).map { item ->
                MediaCache.PrefetchItem(
                    key = item.toCacheKey(),
                    isVideo = item.isVideo,
                    subfolder = item.subfolder,
                    type = item.type
                )
            }
            MediaCache.initialPrefetch(initialItems)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (uiState.isSelectionMode) {
                    Text(stringResource(R.string.gallery_selected_count, uiState.selectedItems.size))
                } else {
                    Text(stringResource(R.string.title_gallery))
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                if (uiState.isSelectionMode) {
                    IconButton(onClick = { galleryViewModel.clearSelection() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.button_cancel_selection))
                    }
                }
            },
            actions = {
                if (uiState.isSelectionMode) {
                    // Selection mode actions: Select all, Delete, Save, and Share
                    IconButton(onClick = { galleryViewModel.toggleSelectAll() }) {
                        Icon(
                            imageVector = if (uiState.isAllSelected) {
                                Icons.Default.DoneAll
                            } else {
                                Icons.Default.SelectAll
                            },
                            contentDescription = stringResource(
                                if (uiState.isAllSelected) {
                                    R.string.button_deselect_all
                                } else {
                                    R.string.button_select_all
                                }
                            ),
                            tint = if (uiState.isAllSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = uiState.selectedItems.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.button_delete_history_item),
                            tint = if (uiState.selectedItems.isNotEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }
                    IconButton(onClick = { galleryViewModel.saveSelectedToGallery(context) }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.button_save_image))
                    }
                    IconButton(onClick = { galleryViewModel.shareSelected(context) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.button_share))
                    }
                } else {
                    // Normal mode actions: Select and Menu
                    IconButton(onClick = { galleryViewModel.enterSelectionMode() }) {
                        Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.button_gallery_select))
                    }
                    AppMenuDropdown(
                        onSettings = onNavigateToSettings,
                        onLogout = onLogout
                    )
                }
            }
        )

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { galleryViewModel.manualRefresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            // Always use LazyVerticalGrid for consistent nested scroll behavior with pull-to-refresh
            NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                if (uiState.isLoading && uiState.items.isEmpty()) {
                    // Loading state - show as full-span item
                    item(span = { GridItemSpan(2) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (uiState.items.isEmpty()) {
                    // Empty state - show as full-span item
                    item(span = { GridItemSpan(2) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = stringResource(R.string.msg_gallery_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // Gallery items
                    itemsIndexed(uiState.items, key = { _, item -> "${item.promptId}_${item.filename}" }) { index, item ->
                        GalleryItemCard(
                            item = item,
                            isSelected = galleryViewModel.isItemSelected(item),
                            selectionMode = uiState.isSelectionMode,
                            isOffline = isOffline,
                            onTap = {
                                if (uiState.isSelectionMode) {
                                    // In selection mode, tap toggles selection
                                    galleryViewModel.toggleSelection(item)
                                } else if (item.threeD != null) {
                                    // 3D 产物条目：直达 3D 查看器（加载 .glb），不进图片查看器。
                                    // 这是 IDOL 图生3D 结果在图库里的正确出口 —— 点开图片查看器
                                    // 只能看到预览 png，用户会以为 3D 功能没有产物。
                                    active3D = item.threeD
                                } else {
                                    // Normal mode, tap opens MediaViewer
                                    launchMediaViewer(index)
                                }
                            },
                            onLongPress = {
                                // Long press enters selection mode and selects this item
                                galleryViewModel.toggleSelection(item)
                            }
                        )
                    }
                }
                }
            }
        }
    }

    // 批量删除确认：删除走 ComfyUI 历史接口，删掉即从服务器消失，不可撤销
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.dialog_delete_selected_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dialog_delete_selected_message,
                        uiState.selectedItems.size
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        galleryViewModel.deleteSelected()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.button_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.button_cancel))
                }
            }
        )
    }

    // 图库 3D 查看器：与生成页同一个组件（下载 glb → SceneView 渲染 → 失败弹错误）
    if (active3D != null) {
        ThreeDViewerSheet(
            result = active3D!!,
            onDismiss = { active3D = null }
        )
    }
}

/**
 * Gallery item card with lazy bitmap loading.
 * Bitmaps are loaded from MediaCache on-demand.
 */
@Composable
private fun GalleryItemCard(
    item: GalleryItem,
    isSelected: Boolean,
    selectionMode: Boolean = false,
    isOffline: Boolean = false,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    // Create cache key directly from item's stable properties
    val cacheKey = item.toCacheKey()
    // 手动重试计数：加载失败后点占位图标重新拉取（v4.6.0，以前失败即定格）
    var retryTick by remember(cacheKey) { mutableStateOf(0) }
    // 列表格用服务端压缩版缩略图：放大类工作流的原图可达 200 MB，
    // 为了一格 180dp 的预览去传原图，是整个图库里最贵的一笔开销（见 MediaCache.fetchThumbnail）
    val (bitmap, isLoading) = rememberLazyBitmap(
        cacheKey = cacheKey,
        isVideo = item.isVideo,
        subfolder = item.subfolder,
        type = item.type,
        thumbnail = true,
        retryTick = retryTick
    )
    // 服务器上已确认没有这个文件（404，例如电脑端清理了 output）
    val isMissing = !isLoading && bitmap == null && MediaCache.isFileMissing(cacheKey)
    // 失败占位（转圈结束但没拿到图）：点击重试的分流条件
    val failedPlaceholder = bitmap == null && !isLoading

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.medium
                    )
                } else {
                    Modifier
                }
            )
            // 加载失败的占位：点击 = 手动重试（v4.6.0，见 retryTick 的注释）。
            // 分流必须在卡片内部（bitmap/isLoading/retryTick 都是这个卡片的状态，
            // 外层的 itemsIndexed lambda 看不见）；选择模式下照常走 onTap（选中逻辑）。
            // key 用 failedPlaceholder：状态翻转时重启手势检测，避免闭包读到旧值。
            .pointerInput(failedPlaceholder) {
                detectTapGestures(
                    onTap = {
                        if (failedPlaceholder && !selectionMode) {
                            retryTick++
                        } else {
                            onTap()
                        }
                    },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail from cache
            when {
                bitmap != null -> {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = if (item.isVideo) {
                            stringResource(R.string.content_description_gallery_video_thumbnail)
                        } else {
                            stringResource(R.string.content_description_gallery_thumbnail)
                        },
                        modifier = Modifier.fillMaxSize(),
                        // 高质量采样：Compose 默认的 FilterQuality.Low 是双线性、无 mipmap，
                        // 把一张几千像素的图缩到几百像素的格子时会有明显的锯齿与摩尔纹
                        // （用户看到的"边缘毛躁"里有一部分就是它）
                        filterQuality = FilterQuality.High,
                        contentScale = ContentScale.Crop
                    )
                }
                isLoading -> {
                    // Loading placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                else -> {
                    // Fallback placeholder - show CloudOff for offline mode, otherwise normal icon。
                    // 点击卡片本体即可手动重试（分流在外层 onTap，v4.6.0）：
                    // 超时/网络抖动/文件被放回，都不用再把格子滑出屏幕才能重新加载。
                    // 404 的"文件不存在"显示专门图标提示。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isMissing -> Icons.Default.ImageSearch
                                isOffline -> Icons.Default.CloudOff
                                item.isVideo -> Icons.Default.PlayArrow
                                item.threeD != null -> Icons.Default.ViewInAr
                                else -> Icons.Default.Image
                            },
                            contentDescription = when {
                                isMissing -> stringResource(R.string.gallery_file_missing_on_server)
                                isOffline -> stringResource(R.string.content_description_not_cached)
                                else -> null
                            },
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Selection indicator
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.label_item_selected),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Video indicator (only show when not selected and not loading)
            if (item.isVideo && !isSelected && bitmap != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.content_description_video),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // 3D 徽章：该产出带 glb（IDOL 图生3D 等），点击条目打开的是 3D 查看器
            if (item.threeD != null && !isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ViewInAr,
                        contentDescription = stringResource(R.string.label_3d_badge),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
