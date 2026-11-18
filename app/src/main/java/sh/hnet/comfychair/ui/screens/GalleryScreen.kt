package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.core.view.drawToBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import sh.hnet.comfychair.BitmapCache
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.components.ViewerRect
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

    // ============================================================
    // 网格捏合改列数（C1~C3，v4.16.2）
    // ============================================================
    // 开关关闭 → 恒 2 列、不挂捏合手势，完全回到旧行为（AppSettings 组合期读一次）
    val pinchColumnsEnabled = remember { AppSettings.isGalleryPinchColumnsEnabled(context) }
    // 低端机降级（C3）：捏合过程不逐帧跟手，松手一次性换列。
    // 用 isLowRamDevice 而不是运行期掉帧检测 —— 判定稳定可复现，也不会因为
    // 一次瞬时卡顿把普通设备永久降级
    val isLowRamDevice = remember {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice == true
    }
    // 已提交列数（2/3/4）：rememberSaveable 让旋转/重建后保持用户选定的档位
    var columns by rememberSaveable { mutableStateOf<Int>(GALLERY_MIN_COLUMNS) }
    // 捏合跟手用的容器级缩放（1f = 原尺寸）。挂在 graphicsLayer 上读，
    // 捏合每帧只更新 layer 属性、不触发网格与卡片重组
    var pinchScale by remember { mutableFloatStateOf(1f) }
    // 本次手势的起始列数（手势只上报"自起点累计的缩放倍率"，起点必须自己记）
    var gestureBaseColumns by remember { mutableFloatStateOf(GALLERY_MIN_COLUMNS.toFloat()) }
    var columnJob by remember { mutableStateOf<Job?>(null) }
    // 换列后的锚定索引（C3"改列不跳图"）：由 settleColumns 在提交新列数前写入，
    // 由下面的 LaunchedEffect(columns) 在新列布局生效后再真正滚过去
    var settleAnchor by remember { mutableStateOf<Int?>(null) }

    // B-23b 第三批：grid 节点在 window 中的位置。截图（decorView 全屏像素）与
    // 格子矩形必须同一坐标系——visibleItemsInfo.offset 是相对 grid viewport 的，
    // 加上这个 origin 才是与截图同基准的 window 坐标
    var gridOriginInWindow by remember { mutableStateOf(Offset.Zero) }

    // 锚定落点必须在"新列数已经排版完成"之后执行。
    // 若在提交列数的同一帧调 requestScrollToItem，那条请求会被旧列数的 measure 吃掉；
    // 换列后 LazyGrid 按**像素偏移**重排，落点就偏到别的行去了。改由
    // LaunchedEffect(columns) 驱动，此时新列数已经参与排版，scrollToItem 落点确定。
    //
    // 注意"锚点未必能贴顶"：列数变多时内容变矮，网格会滚到最大值（clamp），锚点项
    // 停在屏幕中部而不是顶部 —— 这是 LazyGrid 的物理限制，不是锚定失效。判据是
    // "锚点仍在可见区"（实测 2→3 列：锚点项仍可见，改列前可见的 6 张全部仍可见）。
    LaunchedEffect(columns) {
        val anchor = settleAnchor ?: return@LaunchedEffect
        settleAnchor = null
        gridState.scrollToItem(anchor)
    }

    // 开关关掉时强制回到 2 列：rememberSaveable 可能存着上次捏出来的 3/4 列，
    // 只"不挂手势"是不够的 —— "关闭 = 固定 2 列"是回退契约的一部分
    LaunchedEffect(pinchColumnsEnabled) {
        if (!pinchColumnsEnabled) {
            columns = GALLERY_MIN_COLUMNS
            pinchScale = 1f
        }
    }

    /**
     * 捏合松手：吸附到最近档位。
     *
     * 跟手期间网格仍按"已提交列数"排版（LazyVerticalGrid 不支持分数列），视觉上的
     * 连续缩放来自容器 graphicsLayer。所以换列必须分两步走才不会闪：
     * ① 先把缩放补间到"目标档位的真实格子尺寸"（视觉尺寸连续）
     * ② 同一帧内提交列数并把缩放复位 1 —— 排版换成新列数，视觉尺寸不变
     *
     * 参数是**手势自起点累计的缩放倍率**（不是连续列数）：换算成档位必须经过
     * [galleryTargetColumns]，把起点列数一起带进去。早期版本在这里直接拿倍率当列数
     * 吸附（倍率 0.66 会被 roundToInt 成 1 再钳回 2），结果是捏合永远只停在 2 列。
     */
    fun settleColumns(baseColumns: Float, gestureScale: Float) {
        columnJob?.cancel()
        val target = galleryTargetColumns(baseColumns, gestureScale)
        // 锚定：换列前记下当前第一项，换列后把它锚回可见区顶部，用户正在看的那张不会被挤走
        val anchor = gridState.firstVisibleItemIndex
        if (isLowRamDevice || !pinchColumnsEnabled) {
            // 低端机（C3）：过程不跟手，直接一次到位
            pinchScale = 1f
            if (target != columns) {
                settleAnchor = anchor
                columns = target
            }
            return
        }
        val targetScale = galleryPinchScaleForColumns(columns, target.toFloat())
        columnJob = scope.launch {
            animate(
                initialValue = pinchScale,
                targetValue = targetScale,
                animationSpec = tween(COLUMN_SETTLE_MS)
            ) { value, _ -> pinchScale = value }
            // 以下三行必须写在同一次快照里（中间没有挂起点）→ 同一帧生效，不会闪。
            // 锚定只写 settleAnchor：真正的滚动由 LaunchedEffect(columns) 在新布局上执行
            pinchScale = 1f
            if (target != columns) {
                settleAnchor = anchor
                columns = target
            }
        }
    }

    // State and effects
    // Activity result launcher for MediaViewerActivity
    val mediaViewerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Restore active view to Gallery
        MediaCache.setActiveView(ActiveView.GALLERY)

        // B-23b 第三批：查看器已关闭，释放透出用的图库截图（下次打开会被整体覆盖，
        // 这里主动清掉省一份位图内存）
        BitmapCache.clearGallerySnapshot()

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

    // N2（v4.16.8）挖空的兜底色：正常路径从截图 gutter 采样（与周围像素一致），
    // 采样失败时用主题背景兜底。主题支持用户自定义配色，所以不能写死色值
    val galleryFallbackBackground = MaterialTheme.colorScheme.background.toArgb()

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

        // ============================================================
        // B-23b 第三批（E1~E2）：截图与格子矩形——必须在 launch 之前同步截取
        // ============================================================
        // View.draw 是同步的：这一刻图库还在前台，截到的才是图库画面；
        // launch 之后查看器窗口盖上来，再截就是查看器自己。
        // 捏合改列动画进行中（pinchScale != 1）时格子矩形是布局坐标、与视觉位置
        // 错位，按"无截图"处理回退纯黑，绝不透出错位的画面
        var revealSnapshot: Bitmap? = null
        var revealCellRect: ViewerRect? = null
        if (AppSettings.isGalleryDismissRevealEnabled(context) && pinchScale == 1f) {
            try {
                revealSnapshot = (context as? Activity)?.window?.decorView?.drawToBitmap()
                // 目标格子矩形（window 坐标）：visibleItemsInfo.offset 是相对 grid
                // 内容区起点（contentPadding 之后）的偏移，加上 grid 节点的 window
                // 位置**与 contentPadding** 才与截图同基准。
                // v4.16.8 N2 修正：旧版漏加 contentPadding，矩形整体左上偏 8dp，
                // 格子的右缘与底缘各露出一条 8dp（右下角交汇最明显）。
                // 本函数在点击回调里执行，被点格子必然在可见区（不可见点不到）
                val itemInfo = gridState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == clickedIndex }
                if (itemInfo != null) {
                    val paddingPx = GALLERY_GRID_CONTENT_PADDING_DP *
                        context.resources.displayMetrics.density
                    revealCellRect = galleryRevealCellRect(
                        gridOriginX = gridOriginInWindow.x,
                        gridOriginY = gridOriginInWindow.y,
                        contentPaddingPx = paddingPx,
                        itemOffsetX = itemInfo.offset.x.toFloat(),
                        itemOffsetY = itemInfo.offset.y.toFloat(),
                        itemWidthPx = itemInfo.size.width.toFloat(),
                        itemHeightPx = itemInfo.size.height.toFloat()
                    )
                    // ============================================================
                    // N2（v4.16.8）：截图内挖空——把目标格子区域在 Bitmap 像素空间里
                    // 填成图库背景色。填色发生在静态截图内，不存在跨窗口坐标系对齐
                    // 问题（旧覆盖层方案正是栽在这里）；透出的画面上那格就是"空置
                    // 格子"。开关关闭时跳过，由查看器的黑矩形 Canvas 接管（旧方案）
                    // ============================================================
                    if (revealSnapshot != null &&
                        AppSettings.isGalleryRevealHolePunch(context)
                    ) {
                        val punched = holePunchGallerySnapshot(
                            snapshot = requireNotNull(revealSnapshot),
                            cellRect = requireNotNull(revealCellRect),
                            gutterHalfPx = paddingPx * 0.5f,
                            fallbackColor = galleryFallbackBackground
                        )
                        if (punched == null) {
                            // 挖空失败（copy OOM 等）：放弃整个截图回退纯黑——绝不让
                            // 未挖空的截图透出原格子缩略图（N2 的 bug 就是"空置格透图"）
                            revealSnapshot = null
                            revealCellRect = null
                        } else {
                            revealSnapshot = punched
                        }
                    }
                }
            } catch (_: Exception) {
                // 截图失败（窗口未就绪等）：按"无截图"处理，查看器回退纯黑，不许崩
                revealSnapshot = null
                revealCellRect = null
            }
        }
        BitmapCache.putGallerySnapshot(revealSnapshot, revealCellRect)

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
    // columns 进依赖：捏合换列后可见项数量与优先级区间都会变，必须重新上报
    LaunchedEffect(
        gridState.firstVisibleItemIndex,
        gridState.layoutInfo.visibleItemsInfo.size,
        columns
    ) {
        if (prefetchItems.isNotEmpty() && MediaCache.isActiveView(ActiveView.GALLERY)) {
            // Debounce scroll updates to avoid excessive calls during fast scrolling
            kotlinx.coroutines.delay(100)
            MediaCache.updateGalleryPosition(
                firstVisibleIndex = gridState.firstVisibleItemIndex,
                visibleItemCount = gridState.layoutInfo.visibleItemsInfo.size,
                allItems = prefetchItems,
                // 预取优先级要按真实列数算（一行几列直接决定"附近几张"）
                columnsInGrid = columns
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
                    // 列数由捏合手势决定（2/3/4，C1）；开关关闭时恒为 2
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    // N2：内边距与挖空的格子矩形换算共用一个常量（一处来源）
                    contentPadding = PaddingValues(GALLERY_GRID_CONTENT_PADDING_DP.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        // B-23b 第三批：记录 grid 在 window 中的位置（透出截图与格子
                        // 矩形的坐标系基准，见 launchMediaViewer）
                        .onGloballyPositioned { gridOriginInWindow = it.positionInWindow() }
                        // 捏合跟手的容器级缩放：clip 在 layer 外层，放大时内容被视口裁掉，
                        // 不会溢到顶栏/刷新指示器上
                        .clipToBounds()
                        .graphicsLayer {
                            scaleX = pinchScale
                            scaleY = pinchScale
                        }
                        // 双指捏合改列数（C1~C3）；单指事件一律不消费，滚动与点击不受影响
                        .then(
                            if (pinchColumnsEnabled) {
                                Modifier.pointerInput(Unit) {
                                    detectGalleryColumnPinch(
                                        onPinchStart = {
                                            columnJob?.cancel()
                                            gestureBaseColumns = columns.toFloat()
                                            pinchScale = 1f
                                        },
                                        onPinchZoom = { accumulated ->
                                            // C3 低端机降级：捏合过程不跟手，松手才一次换列
                                            if (!isLowRamDevice) {
                                                columnJob?.cancel()
                                                val continuous = galleryColumnsForGesture(
                                                    gestureBaseColumns, accumulated
                                                )
                                                pinchScale = galleryPinchScaleForColumns(
                                                    columns, continuous
                                                )
                                            }
                                        },
                                        onPinchEnd = { accumulated ->
                                            // 松手结算也要经过同一套换算：起点列数 + 累计倍率
                                            settleColumns(gestureBaseColumns, accumulated)
                                        }
                                    )
                                }
                            } else {
                                Modifier
                            }
                        )
                ) {
                if (uiState.isLoading && uiState.items.isEmpty()) {
                    // Loading state - show as full-span item
                    // 全宽占位项跟随列数（maxLineSpan = 当前一行几列），否则 3/4 列时会错位
                    item(span = { GridItemSpan(maxLineSpan) }) {
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
                    item(span = { GridItemSpan(maxLineSpan) }) {
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

// ============================================================
// 下拉透出"截图挖空"（N2，v4.16.8）
// ============================================================

/** 图库网格四周内边距（dp）。格子矩形换算与 LazyVerticalGrid 的 contentPadding 必须同值——一处来源 */
internal const val GALLERY_GRID_CONTENT_PADDING_DP = 8f

/**
 * 下拉透出的目标格子矩形（window px，v4.16.8 N2 修正版，纯函数便于单测）。
 *
 * 坐标换算：visibleItemsInfo.offset 是相对 grid **内容区起点**（contentPadding 之后）
 * 的偏移，所以格子真实位置 = grid 节点在 window 中的位置 + contentPadding + offset。
 * 旧版漏加 contentPadding，矩形整体左上偏 8dp（320x640@160dpi 实测：旧矩形
 * [156,244] vs 真实格子 [164,252]），格子的右缘与底缘各露出一条 8dp。
 */
internal fun galleryRevealCellRect(
    gridOriginX: Float,
    gridOriginY: Float,
    contentPaddingPx: Float,
    itemOffsetX: Float,
    itemOffsetY: Float,
    itemWidthPx: Float,
    itemHeightPx: Float
): ViewerRect = ViewerRect(
    left = gridOriginX + contentPaddingPx + itemOffsetX,
    top = gridOriginY + contentPaddingPx + itemOffsetY,
    width = itemWidthPx,
    height = itemHeightPx
)

/**
 * N2（v4.16.8）：从截图上目标格子外围的 gutter 取背景色。
 *
 * 为什么采样而不是直接用主题色：主题支持用户自定义配色，且 Compose 的
 * colorScheme.background 与 XML theme 的 windowBackground 不保证同值——只有
 * "采周围真实像素"能保证挖出来的空格与格子之间空隙视觉无缝。
 * 采样点：上缘 → 左缘的 gutter 中点（格子矩形外 gutter 8dp 的正中）；
 * 都越界时返回 null，调用方用主题背景兜底。
 */
private fun sampleSnapshotGutterColor(
    snapshot: Bitmap,
    cellRect: ViewerRect,
    gutterHalfPx: Float
): Int? {
    val samplePoints = listOf(
        cellRect.centerX to (cellRect.top - gutterHalfPx),
        (cellRect.left - gutterHalfPx) to cellRect.centerY
    )
    for ((x, y) in samplePoints) {
        val px = x.roundToInt()
        val py = y.roundToInt()
        if (px >= 0 && px < snapshot.width && py >= 0 && py < snapshot.height) {
            return snapshot.getPixel(px, py)
        }
    }
    return null
}

/**
 * N2（v4.16.8）：在图库截图的像素空间内把目标格子区域挖成背景色。
 *
 * 填色发生在静态截图内 → 不存在跨窗口坐标系对齐问题（旧覆盖层方案正是栽在这里）。
 * 挖空只改这张一次性截图，图库真实渲染不受影响：查看器关闭、截图释放后，
 * 被点格子自动恢复显示原图。
 *
 * 失败（copy OOM / 绘制异常）返回 null，调用方必须放弃整张截图回退纯黑——
 * 绝不能把未挖空的截图留在透出链路上（那等于 N2 的 bug 原样透出）。
 */
private fun holePunchGallerySnapshot(
    snapshot: Bitmap,
    cellRect: ViewerRect,
    gutterHalfPx: Float,
    fallbackColor: Int
): Bitmap? {
    return try {
        // drawToBitmap 可能返回不可变位图：挖空要往里画，先拷一份可变的
        //（整屏截图 10MB 级，只 copy 这一次）
        val target = if (snapshot.isMutable) {
            snapshot
        } else {
            snapshot.copy(Bitmap.Config.ARGB_8888, true)
        }
        val paint = Paint().apply {
            color = sampleSnapshotGutterColor(target, cellRect, gutterHalfPx)
                ?: fallbackColor
            style = Paint.Style.FILL
        }
        // 外扩 1px：吃掉光栅化舍入可能留下的格子边缘残像（外扩区域本来就是
        // 背景色 gutter，视觉零差别）
        val canvas = Canvas(target)
        val left = (cellRect.left - 1f).coerceAtLeast(0f)
        val top = (cellRect.top - 1f).coerceAtLeast(0f)
        val right = (cellRect.right + 1f).coerceAtMost(target.width.toFloat())
        val bottom = (cellRect.bottom + 1f).coerceAtMost(target.height.toFloat())
        canvas.drawRect(left, top, right, bottom, paint)
        target
    } catch (_: Exception) {
        null
    }
}

/**
 * 图库格子显示的降采样倍率（纯函数，便于单测）。
 *
 * 统一档落盘是长边 720（见 [sh.hnet.comfychair.cache.MediaCache.GALLERY_DECODE_DIMENSION]），
 * 显示时按列数缩：2 列直接用；3 列约 480（720×2/3）；4 列约 360（720×1/2）。
 * 返回显示位图相对统一档的比例（1f = 原样用、不复制）。
 *
 * 也是"捏合改列数"（v4.16.2，C1~C3）的格子尺寸档位表：格子宽度与列数成反比，
 * 所以两档之间的尺寸比正好等于本函数两极之比 —— [galleryPinchScaleForColumns]
 * 直接用它算跟手系数，换列时视觉尺寸严格连续。
 */
internal fun galleryDisplayScaleForColumns(columns: Int): Float = when {
    columns <= 2 -> 1f
    columns == 3 -> 2f / 3f
    else -> 1f / 2f
}

// ============================================================
// 网格捏合改列数（C1~C3，v4.16.2）的纯逻辑 —— 全部可在 JVM 单测里精确覆盖边界
// ============================================================

/** 列数下限（C1：2/3/4 三档） */
internal const val GALLERY_MIN_COLUMNS = 2

/** 列数上限 */
internal const val GALLERY_MAX_COLUMNS = 4

/** 换列吸附动画时长（ms）。与查看器并排吸附同量级，快而不生硬 */
internal const val COLUMN_SETTLE_MS = 220

/**
 * 捏合手势 → 连续列数。
 *
 * 手指张开（gestureScale > 1）= 想把格子放大 = 列数变少，故取倒数式除法；
 * 结果钳在 [2, 4]，超出档位区间时格子尺寸停在端点（不会出现 1 列或 6 列）。
 *
 * @param baseColumns 本次手势起始的列数
 * @param gestureScale 本次手势累计缩放倍率（>1 张开 / <1 合拢）
 */
internal fun galleryColumnsForGesture(baseColumns: Float, gestureScale: Float): Float {
    val base = baseColumns.coerceIn(
        GALLERY_MIN_COLUMNS.toFloat(), GALLERY_MAX_COLUMNS.toFloat()
    )
    // 手势倍率异常（0 / 负数 / NaN / 无穷）一律视为"没捏"，返回起始列数兜底
    if (!gestureScale.isFinite() || gestureScale <= 0f) return base
    return (base / gestureScale).coerceIn(
        GALLERY_MIN_COLUMNS.toFloat(), GALLERY_MAX_COLUMNS.toFloat()
    )
}

/**
 * 松手吸附：连续列数 → 最近档位（2 / 3 / 4）。
 *
 * 四舍五入即可覆盖全部分界：2.49 → 2、2.5 → 3、3.49 → 3、3.5 → 4；
 * 越界值与异常值一律钳到档位区间端点。
 */
internal fun galleryColumnsSnap(continuousColumns: Float): Int {
    if (!continuousColumns.isFinite()) return GALLERY_MIN_COLUMNS
    return continuousColumns.roundToInt().coerceIn(GALLERY_MIN_COLUMNS, GALLERY_MAX_COLUMNS)
}

/**
 * 松手吸附的**唯一正确入口**：起点列数 + 本次手势累计倍率 → 目标档位（2 / 3 / 4）。
 *
 * 存在的意义是把"倍率 → 连续列数 → 吸附档位"这两步锁在一个函数里。手势回调给的是
 * 倍率，吸附要的是列数，中间少走一步就会变成"倍率直接 roundToInt"——0.66 倍会被
 * 舍成 1 再钳回 2，表现为捏合永远只停在 2 列。调用方一律只传倍率进来。
 */
internal fun galleryTargetColumns(baseColumns: Float, gestureScale: Float): Int =
    galleryColumnsSnap(galleryColumnsForGesture(baseColumns, gestureScale))

/**
 * 连续列数 → 格子尺寸系数（2 列 1f、3 列 2/3、4 列 1/2，与整数版严格一致）。
 *
 * 整数档位上与 [galleryDisplayScaleForColumns] 完全同值，中间值线性插值，
 * 供捏合跟手期间逐帧取用。
 */
internal fun galleryDisplayScaleForColumnCount(columns: Float): Float {
    if (!columns.isFinite() || columns <= 0f) return 1f
    return (2f / columns).coerceIn(
        galleryDisplayScaleForColumns(GALLERY_MAX_COLUMNS),
        galleryDisplayScaleForColumns(GALLERY_MIN_COLUMNS)
    )
}

/**
 * 捏合过程中的容器缩放系数：把"已提交列数"的格子尺寸换算成"目标连续列数"的尺寸。
 *
 * 例：已提交 2 列（格子系数 1f）、手指捏到连续 2.67 列（系数 0.75）→ 返回 0.75，
 * 即格子显示为 3 列时的尺寸；已提交 4 列捏到 2 列 → 返回 2f（格子放大一倍）。
 * 松手时同一函数也给出"目标档位相对当前档位"的补间终值，换列瞬间尺寸不跳。
 */
internal fun galleryPinchScaleForColumns(committedColumns: Int, continuousColumns: Float): Float {
    val from = galleryDisplayScaleForColumns(committedColumns)
    if (from <= 0f) return 1f
    return galleryDisplayScaleForColumnCount(continuousColumns) / from
}

/**
 * 图库网格的双指捏合检测（C1~C3）。
 *
 * 关键点在 [PointerEventPass.Initial] + 双指期间消费：
 * - 单指事件一律不吃 → 网格滚动、卡片点击、下拉刷新全部照旧；
 * - 两指按下后消费全部位置变化 → 网格不会在捏合的同时被滚动，下拉刷新也不会被
 *   两指的上下漂移误触发。
 *
 * 手势结束有两种走法，都要结算：手指数从 2 掉到 1（用户抬手顺序不定，先抬一根
 * 即视为捏合结束），或全部抬起。
 */
private suspend fun PointerInputScope.detectGalleryColumnPinch(
    onPinchStart: () -> Unit,
    onPinchZoom: (accumulatedScale: Float) -> Unit,
    onPinchEnd: (accumulatedScale: Float) -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var accumulated = 1f
        var started = false
        var finished = false
        while (!finished) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount >= 2) {
                // 双指期间全消费：屏蔽网格滚动与下拉刷新
                event.changes.forEach { if (it.pressed) it.consume() }
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    if (!started) {
                        started = true
                        onPinchStart()
                    }
                    // 累计倍率钳在 0.25~4：极端手势不会把连续列数换算推到溢出
                    accumulated = (accumulated * zoom).coerceIn(0.25f, 4f)
                    onPinchZoom(accumulated)
                }
            } else if (started) {
                // 手指数掉到 1：捏合结束（不等全部抬起，避免用户留一根手指时无响应）
                finished = true
            }
            if (event.changes.none { it.pressed }) finished = true
        }
        if (started) onPinchEnd(accumulated)
    }
}
