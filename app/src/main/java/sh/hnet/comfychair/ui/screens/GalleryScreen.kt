package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import sh.hnet.comfychair.BitmapCache
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.MediaViewerSession
import sh.hnet.comfychair.R
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryMetadataCache
import sh.hnet.comfychair.storage.GalleryTrashEntry
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.components.ViewerRect
import sh.hnet.comfychair.cache.ActiveView
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.ui.components.shared.ThreeDViewerSheet
import sh.hnet.comfychair.connection.rememberIsOffline
import sh.hnet.comfychair.ui.components.rememberLazyBitmap
import sh.hnet.comfychair.util.galleryInitialFirstVisibleItemIndex
import sh.hnet.comfychair.util.galleryOpenIndex
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
    val uiState by galleryViewModel.uiState.collectAsStateWithLifecycle()
    val connectionStatus by generationViewModel.connectionStatus.collectAsStateWithLifecycle()

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
        // P0：锚点来自换列前的 firstVisibleItemIndex，期间列表可能被删除变短。
        // 统一用纯函数夹到 [0, size-1]（空列表 → 0），不让旧的大索引流到任何下标运算里。
        gridState.scrollToItem(
            galleryInitialFirstVisibleItemIndex(
                itemCount = uiState.items.size,
                savedFirstVisibleIndex = anchor
            )
        )
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

    // 打开查看器的在途标记：截图改成异步（P1-28）后需要防止连点重复 launch。
    // 声明必须在 mediaViewerLauncher 回调之前（回调里复位）
    var isLaunchingViewer by remember { mutableStateOf(false) }

    // State and effects
    // Activity result launcher for MediaViewerActivity
    val mediaViewerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 查看器已返回：允许下一次点击再打开（P1-28 的防连点标记在这里复位）
        isLaunchingViewer = false

        // Restore active view to Gallery
        MediaCache.setActiveView(ActiveView.GALLERY)

        // B-23b 第三批：查看器已关闭，释放透出用的图库截图（下次打开会被整体覆盖，
        // 这里主动清掉省一份位图内存）
        BitmapCache.clearGallerySnapshot()

        // P1-29：查看器已关闭，释放进程内保存的条目列表
        MediaViewerSession.clear()

        // Refresh gallery if item was deleted (silent refresh, no spinner)
        if (result.resultCode == Activity.RESULT_OK) {
            val itemDeleted = result.data?.getBooleanExtra(MediaViewerActivity.RESULT_ITEM_DELETED, false) ?: false
            if (itemDeleted) {
                galleryViewModel.refresh()
            }

            // 位置双向同步（C3）：查看器里翻到第 N 张后返回，列表自动滚到那一张。
            // 回传的是稳定键，在当前 items 里找索引——该项可能已被删除：
            // 找不到就静默忽略，绝不滚动到错误位置。
            //
            // P0 复核 L3：查看器内部已统一到 NUL 口径（GalleryMetadataCache.itemIdentityKey），
            // 但 MediaViewerActivity 的 EXTRA_CURRENT_ITEM_KEY 目前仍回传旧的下划线格式
            // （该文件不在本次可改范围）。这里**同时接受两种格式**：等 Activity 侧也切到
            // NUL 后无需再改本处，且切换前后都不会断位置同步。
            val currentKey = result.data?.getStringExtra(MediaViewerActivity.EXTRA_CURRENT_ITEM_KEY)
            if (currentKey != null) {
                val index = uiState.items.indexOfFirst { item ->
                    GalleryMetadataCache.itemIdentityKey(item.promptId, item.filename) == currentKey ||
                        "${item.promptId}_${item.filename}" == currentKey
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
                // G4：回收站"彻底删除"的分路由结果播报。四类各说各话 ——
                // 服务器删了 / 只清了本机（服务器文件保留）/ 待重试 / 属于其它服务器。
                // 谎报任何一种状态都是破坏性操作红线，所以哪怕只命中一类也如实说。
                is GalleryEvent.TrashPurged -> {
                    val s = event.summary
                    val parts = buildList {
                        if (s.removedWithServer > 0) {
                            add(context.getString(R.string.msg_trash_purged_server, s.removedWithServer))
                        }
                        // 404 收敛的子集：文件在服务器上已不存在（多半是电脑端删的），
                        // 单独说明，免得用户以为手机端动过服务器上的其它文件
                        if (s.alreadyGone > 0) {
                            add(context.getString(R.string.msg_trash_purged_already_gone, s.alreadyGone))
                        }
                        if (s.removedLocalOnly > 0) {
                            add(
                                context.getString(
                            R.string.msg_trash_purged_local_only, s.removedLocalOnly
                                )
                            )
                        }
                        if (s.queuedForRetry > 0) {
                            add(context.getString(R.string.msg_trash_purge_pending, s.queuedForRetry))
                        }
                        if (s.keptForOtherServer > 0) {
                            add(
                                context.getString(
                                    R.string.msg_trash_kept_other_server, s.keptForOtherServer
                                )
                            )
                        }
                    }
                    if (parts.isNotEmpty()) {
                        Toast.makeText(context, parts.joinToString("；"), Toast.LENGTH_LONG).show()
                    }
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
                index = item.index,
                // 第三态随队列带进查看器：否则查看器会再请求一次已删除的原图、白等超时
                sourceMissing = item.sourceMissing
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
        if (isLaunchingViewer) return

        // P0（空列表防御）：图库被删空 / 索引失效（点击后列表又变短）时**绝不启动查看器**。
        // 空列表进入查看器本身就是非法状态；把 size = 0 的队列交给 Pager /
        // `coerceIn(0, size - 1)` 只会换来一次闪退。这里直接不启动。
        val safeIndex = galleryOpenIndex(clickedIndex, uiState.items.size) ?: return

        // Set active view to MediaViewer
        MediaCache.setActiveView(ActiveView.MEDIA_VIEWER)

        // Update priorities to protect items around clicked index
        val allKeys = uiState.items.map { it.toCacheKey() }
        MediaCache.updateNavigationPriorities(safeIndex, allKeys)

        // P2-B 配套：网格预取已经只暖缩略图，原图只在"真的要点开全屏"时才需要。
        // 点击到查看器真正显示之间还有 PixelCopy 截图 + 挖空 + startActivity 的空档，
        // 这里把被点开这张的原图排进预取队列（命中则由 worker 直接跳过）：
        // 查看器首帧照旧命中原图缓存，"点图进全屏的速度"不因为 B 项退步。
        uiState.items.getOrNull(safeIndex)?.let { item ->
            MediaCache.prefetchOriginal(
                MediaCache.PrefetchItem(
                    key = allKeys[safeIndex],
                    isVideo = item.isVideo,
                    subfolder = item.subfolder,
                    type = item.type,
                    sourceMissing = item.sourceMissing
                )
            )
        }

        val viewerItems = galleryItemsToViewerItems(uiState.items)
        val intent = MediaViewerActivity.createGalleryIntent(
            context = context,
            hostname = ConnectionManager.hostname,
            port = ConnectionManager.port,
            items = viewerItems,
            initialIndex = safeIndex
        )
        val activity = context as? Activity
        isLaunchingViewer = true

        fun startViewer() {
            mediaViewerLauncher.launch(intent)
            // 打开过渡（C2）：launch 内部同步发起 startActivityForResult，这里紧接着设置——
            // 列表保持不动，查看器以缩小淡入进场（与关闭时的缩小淡出对称）
            activity?.overridePendingTransition(
                R.anim.viewer_fade_in_grow, R.anim.gallery_stay
            )
        }

        // ============================================================
        // B-23b 第三批（E1~E2）+ P1-28：截图与格子矩形
        // ============================================================
        // 修复前在点击回调里同步 `decorView.drawToBitmap()`：整屏（含顶栏、全部可见格子）
        // 的软件绘制压在主线程上，图库越满越久，点开查看器会先卡一下。
        // 现在改走 PixelCopy：像素拷贝由 RenderThread 完成，回调回到主线程；
        // 挖空（copy + 逐格填色）再挪到 Default 线程。
        //
        // 捏合改列动画进行中（pinchScale != 1）时格子矩形是布局坐标、与视觉位置
        // 错位，按"无截图"处理回退纯黑，绝不透出错位的画面
        val revealEnabled = AppSettings.isGalleryDismissRevealEnabled(context) && pinchScale == 1f
        if (!revealEnabled || activity == null) {
            BitmapCache.putGallerySnapshot(null, null)
            startViewer()
            return
        }

        // 目标格子矩形（window 坐标）必须在主线程、且在截图发起前读：
        // visibleItemsInfo 只在组合线程有效，截图后布局可能已经变化
        val paddingPx = GALLERY_GRID_CONTENT_PADDING_DP * context.resources.displayMetrics.density
        val itemInfo = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == safeIndex }
        val revealCellRect: ViewerRect? = itemInfo?.let {
            galleryRevealCellRect(
                gridOriginX = gridOriginInWindow.x,
                gridOriginY = gridOriginInWindow.y,
                contentPaddingPx = paddingPx,
                itemOffsetX = it.offset.x.toFloat(),
                itemOffsetY = it.offset.y.toFloat(),
                itemWidthPx = it.size.width.toFloat(),
                itemHeightPx = it.size.height.toFloat()
            )
        }
        val holePunchEnabled = AppSettings.isGalleryRevealHolePunch(context)
        val fallbackColor = galleryFallbackBackground

        scope.launch {
            // ① 截屏（PixelCopy，异步、不占主线程做整屏绘制）
            val snapshot = captureWindowSnapshot(activity)
            // ② 挖空（像素级操作，放 Default 线程）
            val punched = if (snapshot != null && revealCellRect != null && holePunchEnabled) {
                withContext(Dispatchers.Default) {
                    holePunchGallerySnapshot(
                        snapshot = snapshot,
                        cellRect = revealCellRect,
                        gutterHalfPx = paddingPx * 0.5f,
                        fallbackColor = fallbackColor
                    )
                }
            } else {
                snapshot
            }
            // 挖空失败（copy OOM 等）：放弃整个截图回退纯黑——绝不让未挖空的截图
            // 透出原格子缩略图（N2 的 bug 就是"空置格透图"）
            val finalSnapshot = if (holePunchEnabled && revealCellRect != null && punched == null) {
                null
            } else {
                punched
            }
            BitmapCache.putGallerySnapshot(
                finalSnapshot,
                if (finalSnapshot != null) revealCellRect else null
            )
            // ③ 启动查看器（回到主线程执行 startActivityForResult）
            startViewer()
        }
    }

    // 批量删除前的确认（一次移几十张进回收站也要拦一道，防手滑）
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // ============================================================
    // G4（任务书 11.4）：回收站（图库一级回收站 = 删除的落点与恢复入口）
    // ============================================================
    //
    // 两阶段的 UI 对应：
    // - 阶段一（这里及查看器的删除动作）：移入回收站。列表消失、本地缓存保留；
    // - 阶段二（回收站面板里的"彻底删除 / 清空"）：服务器联动删除 + 本地残留清理。
    //   **首次启用必须先过清单确认**（pendingPurgeDialog），确认状态按服务器持久化。
    //
    // 面板用全屏 Dialog：不动图库既有布局树（网格滚动位置、捏合状态在开合之间零损失），
    // 系统返回键天然被 Dialog 接走（关面板而不是退出页面）。
    val trashEntries by galleryViewModel.trashEntries.collectAsStateWithLifecycle()
    var showTrashScreen by rememberSaveable { mutableStateOf(false) }
    // 非空 = 首次启用的"将删除文件清单"确认框开着，值就是确认后要执行的 identities
    var pendingPurgeIdentities by remember { mutableStateOf<List<String>?>(null) }
    // 当前连接服务器 id：条目 serverId 与它比对，决定"能否联动删服务器"的文案与分组
    val currentServerId = ConnectionManager.currentServerId.orEmpty()

    /**
     * 发起"彻底删除"（阶段二入口的统一闸口）。
     *
     * 破坏性硬约束 2 的落点：当前服务器**首次**走这条链路时，先展示将删除的
     * 服务器文件清单并等确认；确认状态按 serverId 持久化（用户已答复的交互
     * 决策：只问一次，之后同服务器不再问）。清单里没有当前服务器条目时
     * （全是别家服务器的）没有服务器删除动作，不必拦。
     */
    fun requestPurgeTrash(identities: List<String>) {
        if (identities.isEmpty()) return
        val onCurrentServer = identities.count { id ->
            trashEntries.any { it.identityKey == id && it.serverId == currentServerId }
        }
        if (onCurrentServer == 0 || AppSettings.isServerLinkedDeleteConfirmed(context, currentServerId)) {
            galleryViewModel.purgeTrashEntries(identities)
        } else {
            pendingPurgeIdentities = identities
        }
    }

    // Create prefetch items list from gallery items
    val prefetchItems = remember(uiState.items) {
        uiState.items.map { item ->
            MediaCache.PrefetchItem(
                key = item.toCacheKey(),
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type,
                // 源已不存在：预取原图只会再拿一个 404
                sourceMissing = item.sourceMissing
            )
        }
    }

    // Track scroll position and update cache priorities with debounce
    // Only update when Gallery is the active view (not when MediaViewer is animating closed)
    // columns 进依赖：捏合换列后可见项数量与优先级区间都会变，必须重新上报
    //
    // P2-D：原来把 `gridState.firstVisibleItemIndex` / `layoutInfo.visibleItemsInfo.size`
    // 写在 LaunchedEffect 的 key 位置 —— 这两次读取发生在 **composition 阶段**，
    // 每滚过一行就换一次 key：先取消旧协程、再整页重组一遍（网格 content lambda
    // 与每个可见项的 key 计算都跟着重跑）。改为 snapshotFlow：状态只在协程里读，
    // 帧内多次变化合并成一次发射；distinctUntilChanged 去掉同值的重复发射；
    // collectLatest 用一个 100ms 的可取消 delay 做去抖（新值到达即取消旧的等待，
    // 效果等同 debounce，且不需要 @FlowPreview 的 debounce）。
    // 依赖仍保留 columns 与 prefetchItems：换列后列宽/可见数变了、条目列表变了
    // （刷新/删除）都必须重新上报一次；snapshotFlow 会立刻发射当前值，首帧不漏报。
    LaunchedEffect(columns, prefetchItems) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.layoutInfo.visibleItemsInfo.size
        }
            .distinctUntilChanged()
            .collectLatest { (firstVisibleIndex, visibleItemCount) ->
                if (prefetchItems.isEmpty() || !MediaCache.isActiveView(ActiveView.GALLERY)) {
                    return@collectLatest
                }
                // 去抖：快速滑动期间不重排预取队列，停手 100ms 才按最终位置算一次
                delay(100)
                // P2-B：优先级重排与预取都是 LRU/队列/文件存在性判断，
                // 不该压在主线程上（磁盘优先模式下 isCached 要做多次文件 stat）
                withContext(Dispatchers.Default) {
                    MediaCache.updateGalleryPosition(
                        firstVisibleIndex = firstVisibleIndex,
                        visibleItemCount = visibleItemCount,
                        allItems = prefetchItems,
                        // 预取优先级要按真实列数算（一行几列直接决定"附近几张"）
                        columnsInGrid = columns
                    )
                }
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
            // P1-23：区间两端都要夹到 size 以内。删除条目 / 刷新后 firstVisibleItemIndex
            // 可能仍停在旧的大值上，原先只夹尾部，subList(start > size) 会直接抛越界
            val range = galleryPrefetchRange(
                itemCount = uiState.items.size,
                firstVisibleIndex = gridState.firstVisibleItemIndex
            )
            val initialItems = uiState.items.subList(range.first, range.last).map { item ->
                MediaCache.PrefetchItem(
                    key = item.toCacheKey(),
                    isVideo = item.isVideo,
                    subfolder = item.subfolder,
                    type = item.type,
                    // 首屏预取同样必须跳过已标记条目：冷启动时 missingKeys 为空，
                    // 漏传会让已持久化的「已删除」条目再发一轮 /view 404 请求。
                    sourceMissing = item.sourceMissing
                )
            }
            MediaCache.initialPrefetch(initialItems)
        }
    }

    // ============================================================
    // N4-R：长按滑选（线性连续区间）
    // ============================================================
    // 手势本体（长按阈值、精确/最近命中、区间、边缘滚动）全在 GalleryDragSelect.kt 的
    // rememberGalleryDragSelectModifier 里。这里只提供三样数据出口：
    //   · 条目数（区间边界）；
    //   · index → ViewModel 选择键（与 _selectedItems 同口径，走 GalleryViewModel.getItemKey）；
    //   · 当前选中集合（手势开始时被快照成 baseline）+ 整体设置集合（applyDragSelection）。
    //
    // 用 rememberUpdatedState 取最新值：手势块的 pointerInput key 恒为 Unit、只创建一次，
    // 闭包必须读稳定 State，否则会一直用首帧的旧列表 / 旧选中集。
    val latestGalleryItems = rememberUpdatedState(uiState.items)
    val latestSelectedKeys = rememberUpdatedState(uiState.selectedItems)
    val galleryDragSelectModifier = rememberGalleryDragSelectModifier(
        gridState = gridState,
        itemCount = { latestGalleryItems.value.size },
        itemKeyAt = { index ->
            latestGalleryItems.value.getOrNull(index)?.let { galleryViewModel.getItemKey(it) }
        },
        selectedKeys = { latestSelectedKeys.value },
        applySelection = { keys -> galleryViewModel.applyDragSelection(keys) }
    )

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
                    // Normal mode actions: Trash (G4), Select and Menu
                    IconButton(onClick = { showTrashScreen = true }) {
                        Box {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.action_gallery_trash),
                                tint = if (trashEntries.isNotEmpty()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            // 角标只报"有多少条待处理"，不做拦截性弹窗
                            if (trashEntries.isNotEmpty()) {
                                Text(
                                    text = if (trashEntries.size > 99) "99+" else trashEntries.size.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        // 角标外推只能走 offset：padding() 不接受负值，运行时
                                        // require 会抛 IllegalArgumentException: Padding must be
                                        // non-negative（Padding.kt:483；v4.17.0 code138 真机崩溃点）。
                                        // 换算依据：负 padding 的语义是「内容越过该边界外溢」——
                                        // top = -4.dp = 向上溢出 4dp，end = -6.dp（LTR 下即 right）
                                        // = 向右溢出 6dp；等价于绘制平移 offset(x = 6.dp, y = -4.dp)。
                                        // 但 x 只能取 2dp：Material3 1.5.0-alpha11 的 IconButton 会把
                                        // 内容 clip 到 40dp 正圆（SmallIconButtonTokens：24+8+8=40dp，
                                        // ContainerShapeRound = CornerFull，已用 javap 核对字节码），
                                        // x = 6dp 时角标右上角约在 (38, 4)，距圆心约 24dp > 半径 20dp，
                                        // 会被切掉一角；x = 2dp 时最外点约 (34, 4)，只在顶边留下约 2dp
                                        // 的圆弧内收，角标视觉完整，仍压在图标右上角外侧。
                                        .offset(x = 2.dp, y = (-4).dp)
                                        .background(
                                            MaterialTheme.colorScheme.error,
                                            CircleShape
                                        )
                                        .padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
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
            //
            // A1（2026-11-25，回退 P2-A）：网格重新包回 NoOverscrollContainer。
            //
            // 为什么回退：P2-A 去掉这层后恢复了 Android 12+ 的 stretch 拉伸，用户随后反馈
            // 「图库滑动老是回弹、很僵硬」——时间线与 P2-A 上线的 v4.22.1 完全吻合。触发条件
            // 很具体：网格滚不满一屏时（捏合到 4 列 + 条目少，列表高度 < 可视高度），任何一次
            // 上滑都只会拉伸再弹回，观感就是「一滑就弹、弹完吸回来」。
            //
            // 回退点：删掉 NoOverscrollContainer 这一层（含其闭合括号）即可恢复 P2-A 行为；
            // 单测断言同步在 GalleryGridPerformanceWiringTest 的 A 用例里。
            //
            // 缩进说明：内部 LazyVerticalGrid 整块（约 180 行）的原始缩进刻意保持不变，
            // 以把本次 diff 压到最小；层级以括号为准，阅读时注意多了一层容器。
            //
            // —— 以下 P2-A 段落为历史记录，保留备查（当时的要求是恢复默认拉伸）——
            // P2-A：网格外层原先裹着 NoOverscrollContainer（LocalOverscrollFactory = null）。
            // 那个容器在字节码层面是「不创建任何 overscroll 效果」，所以用户要的"拉伸感"
            // 不是弱，是零。它自己的类注释写明的引入动机只有一条：规避长列表上 stretch
            // 动画造成的卡顿（Google Issue #233515751）；全仓没有「下拉刷新与 overscroll
            // 打架」的 bug 记录，且 PTR 本身不碰 overscroll factory（1.5.0-alpha11 的
            // PullToRefreshKt / PullToRefreshModifierNode 字节码里没有任何
            // LocalOverscrollFactory 引用，它只作为 NestedScrollConnection 消费位移）。
            // 因此这里按要求移除外层包裹，恢复 Material3 / Android 12+ 的默认 stretch。
            // 两者在同一手势上的实际表现（顶部下拉时 PTR 指示器 vs stretch）必须真机确认，
            // 见执行报告-P2.md 的验收记录。
            // （P2-A 写的那个回退点已执行，见上方 A1 说明。）
            // 注：该容器文件未删除，日志/节点浏览/提示词库/设置/工作流这 5 个文件里还有 8 处调用。
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
                        // N4-R：长按滑选（线性连续区间）。与捏合检测同为 Initial 通道的自定义手势：
                        // 长按达成前不消费任何事件（滚动/单击/下拉刷新照旧），达成后接管 ——
                        // 起点格与当前手指所在格之间的整段全部选中（跨行时中间整行全选），
                        // 拖回则区间缩短。格子自身的 onLongPress 已移除：两处都留会在同一个
                        // 500ms 阈值上各触发一次（选中/取消互相抵消，看起来像没选中）。
                        .then(galleryDragSelectModifier)
                ) {
                if (uiState.isLoading && uiState.items.isEmpty()) {
                    // Loading state - show as full-span item
                    // 全宽占位项跟随列数（maxLineSpan = 当前一行几列），否则 3/4 列时会错位
                    // P2-E：补 contentType + 稳定 key —— 否则它和卡片共用同一个复用池，
                    // 首屏从"转圈"切到"卡片"时要整槽重建一次内容
                    item(
                        span = { GridItemSpan(maxLineSpan) },
                        key = GALLERY_STATE_LOADING_KEY,
                        contentType = GALLERY_GRID_STATE_CONTENT_TYPE
                    ) {
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
                    item(
                        span = { GridItemSpan(maxLineSpan) },
                        key = GALLERY_STATE_EMPTY_KEY,
                        contentType = GALLERY_GRID_STATE_CONTENT_TYPE
                    ) {
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
                    // key 必须与去重口径（GalleryRepository 的 distinctBy）完全一致：
                    // 用 "_" 拼会让 (a, b_c) 与 (a_b, c) 撞成同一个 key，
                    // LazyGrid 撞 key 会抛 IllegalArgumentException = 闪退。
                    itemsIndexed(
                        uiState.items,
                        key = { _, item -> GalleryMetadataCache.itemIdentityKey(item.promptId, item.filename) },
                        // P2-E：卡片/页脚/占位项分池复用。缺 contentType 时三者共用同一个
                        // 复用槽，滑到页脚会把卡片槽整槽换成按钮再换回来（多两次内容替换）
                        contentType = { _, _ -> GALLERY_GRID_CARD_CONTENT_TYPE }
                    ) { index, item ->
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
                            }
                            // N4-R：长按不再挂在格子上。长按入口统一由网格容器的
                            // detectDragSelectAfterLongPress 处理（进入选择模式 + 选中落点所在格
                            // + 继续拖动按「线性区间」整段选中）。两处并存会在同一阈值上
                            // 各触发一次，选中与取消互相抵消。
                        )
                    }

                    // ============================================================
                    // 「加载更早」分页入口（config-audit-report 事项2）
                    // ============================================================
                    //
                    // 选**底部按钮**而不是"滚到底自动加载"，两条理由：
                    // - 对既有滚动链路零侵入：本页已挂着三处按 gridState 驱动的
                    //   LaunchedEffect（预取优先级、捏合换列锚定、查看器回位），自动加载
                    //   要再插一处并和它们互相让位；按钮只是网格尾部一个全宽 item，
                    //   PullToRefreshBox 的下拉刷新与滚动恢复行为原样不动。
                    // - 自动加载有一个按钮天然没有的重触发环：整页净新增为 0（条目都被
                    //   墓碑滤掉/索引已包含）时列表高度不变、"仍在底部"会连发请求，
                    //   需要额外的防抖闸；按钮只在用户明确点击时发一笔。
                    if (uiState.canLoadOlder || uiState.isLoadingOlder) {
                        item(
                            span = { GridItemSpan(maxLineSpan) },
                            key = GALLERY_LOAD_OLDER_FOOTER_KEY,
                            contentType = GALLERY_GRID_FOOTER_CONTENT_TYPE
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (uiState.isLoadingOlder) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    TextButton(onClick = { galleryViewModel.loadOlder() }) {
                                        Text(stringResource(R.string.button_gallery_load_older))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    // 批量删除确认（G4 后语义 = 移入回收站，可恢复；不再是"删掉即从服务器消失"）
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

    // ============================================================
    // G4：回收站面板（全屏 Dialog）
    // ============================================================
    if (showTrashScreen) {
        Dialog(
            onDismissRequest = { showTrashScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                GalleryTrashPanel(
                    entries = trashEntries,
                    currentServerId = currentServerId,
                    onBack = { showTrashScreen = false },
                    onRestore = { identity -> galleryViewModel.restoreTrashEntry(identity) },
                    onPurgeOne = { identity -> requestPurgeTrash(listOf(identity)) },
                    onClearAll = { requestPurgeTrash(trashEntries.map { it.identityKey }) }
                )
            }
        }
    }

    // G4 硬约束 2：首次启用"联动删除服务器文件"前，展示**将删除的服务器文件清单**
    // 并确认；确认状态按 serverId 持久化，之后同服务器不再问（用户已确认：只问一次）。
    pendingPurgeIdentities?.let { identities ->
        val manifest = trashEntries.filter { it.identityKey in identities.toSet() && it.serverId == currentServerId }
        AlertDialog(
            onDismissRequest = { pendingPurgeIdentities = null },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.dialog_trash_purge_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.dialog_trash_purge_message, manifest.size))
                    LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                        items(manifest, key = { it.identityKey }) { entry ->
                            Text(
                                text = entry.item.filename,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.dialog_trash_purge_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingPurgeIdentities = null
                        AppSettings.setServerLinkedDeleteConfirmed(context, currentServerId)
                        galleryViewModel.purgeTrashEntries(identities)
                    }
                ) {
                    Text(
                        text = stringResource(R.string.button_confirm_and_purge),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPurgeIdentities = null }) {
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

// ============================================================
// G4（任务书 11.4）：回收站面板
// ============================================================

/**
 * 回收站全屏列表面板。
 *
 * 每行 = 一条回收站条目：缩略图（仅当前服务器条目，别家的缓存键对不上不白拉网络）、
 * 文件名、删除时间与**两阶段状态**（待重试 / 属于其它服务器原样如实标注），
 * 行尾两个动作：恢复（阶段一回退）、彻底删除（阶段二，走调用方的首次确认闸口）。
 *
 * **不做任何自动清理**（硬约束 4）：面板里没有"过期自动删"之类的策略入口，
 * 条目只随用户逐条恢复 / 彻底删除 / 清空而消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTrashPanel(
    entries: List<GalleryTrashEntry>,
    currentServerId: String,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onPurgeOne: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(stringResource(R.string.gallery_trash_title, entries.size))
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.gallery_trash_back)
                    )
                }
            },
            actions = {
                // 清空 = 对全部条目发起阶段二；别家服务器的条目会被如实报"保留"，
                // 不会被冒充成"已清空"
                TextButton(onClick = onClearAll, enabled = entries.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.gallery_trash_clear_all),
                        color = if (entries.isNotEmpty()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            }
        )
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = stringResource(R.string.gallery_trash_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val timeFormat = remember {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
            }
            // 排序结果 remember 住：entries 不变的重组不再每次重排
            val sortedEntries = remember(entries) { entries.sortedByDescending { it.deletedAt } }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sortedEntries, key = { it.identityKey }) { entry ->
                    GalleryTrashEntryRow(
                        entry = entry,
                        onCurrentServer = entry.serverId == currentServerId,
                        timeText = timeFormat.format(Date(entry.deletedAt)),
                        onRestore = { onRestore(entry.identityKey) },
                        onPurge = { onPurgeOne(entry.identityKey) }
                    )
                }
            }
        }
    }
}

/** 回收站单行。两阶段状态在这一处可观测：待重试（红字 + lastError）/ 在站（灰字）。 */
@Composable
private fun GalleryTrashEntryRow(
    entry: GalleryTrashEntry,
    onCurrentServer: Boolean,
    timeText: String,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 缩略图：只有当前服务器的条目值得拉缓存（key 里 serverId 不同台对不上，
        // 别家条目走网络请求要么失败要么拿错文件 —— 直接图标兜底）
        if (onCurrentServer) {
            val cacheKey = MediaCacheKey.of(
                entry.item.promptId, entry.item.filename, entry.item.type, entry.serverId
            )
            val (bitmap, _) = rememberLazyBitmap(
                cacheKey = cacheKey,
                isVideo = entry.item.isVideo,
                subfolder = entry.item.subfolder,
                type = entry.item.type,
                thumbnail = true
            )
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = if (entry.item.isVideo) Icons.Default.PlayArrow else Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = entry.item.filename,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 状态行按优先级：待重试 > 非当前服务器 > 普通在站
            when {
                entry.pendingRetry -> Text(
                    text = buildString {
                        append(stringResource(R.string.gallery_trash_pending_retry))
                        entry.lastError?.let { append("（").append(it).append("）") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                !onCurrentServer -> Text(
                    text = stringResource(R.string.gallery_trash_other_server),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Text(
                    text = stringResource(R.string.gallery_trash_in_trash_since, timeText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onRestore) {
            Icon(
                Icons.Default.Restore,
                contentDescription = stringResource(R.string.gallery_trash_restore),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onPurge) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.gallery_trash_delete_forever),
                tint = MaterialTheme.colorScheme.error
            )
        }
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
    onTap: () -> Unit
) {
    // Create cache key directly from item's stable properties
    val cacheKey = item.toCacheKey()
    // 手动重试计数：加载失败后点占位图标重新拉取（v4.6.0，以前失败即定格）
    var retryTick by remember(cacheKey) { mutableStateOf(0) }
    // 列表格用服务端压缩版缩略图：放大类工作流的原图可达 200 MB，
    // 为了一格 180dp 的预览去传原图，是整个图库里最贵的一笔开销（见 MediaCache.fetchThumbnail）
    //
    // 第三态：条目 sourceMissing（/view 404 已持久化）时**不发任何请求**，
    // 只读本机 720 档缓存 —— 第二次进图库不再白等一次超时。
    // 用户点格子手动重试（retryTick > 0）则临时放行网络一次，文件被放回时能恢复。
    val sourceMissing = item.sourceMissing || MediaCache.isFileMissing(cacheKey)
    val (bitmap, isLoading) = rememberLazyBitmap(
        cacheKey = cacheKey,
        isVideo = item.isVideo,
        subfolder = item.subfolder,
        type = item.type,
        thumbnail = true,
        retryTick = retryTick,
        // 已确认源不存在（持久标记或本次会话刚 404）：不发网络请求，直接用本机 720 档；
        // 用户点格子重试过一次后（retryTick > 0）允许再探一次，文件被放回时可恢复。
        localOnly = sourceMissing && retryTick == 0
    )
    // 服务器上已确认没有这个文件（404，例如电脑端清理了 output）
    val isMissing = !isLoading && bitmap == null && sourceMissing
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
            // 第三态（sourceMissing）且本机无缓存时不在这里重试：点开查看器，
            // 由查看器明确显示「原图已被删除，且本机无缓存」（重试入口也在那）。
            //
            // N4-R：这里只剩 tap。长按（进入选择模式 + 线性区间滑选）改由网格容器的
            // GalleryDragSelect 统一处理；容器在长按达成后会消费抬手事件，
            // 所以「长按后松手」不会掉回这里变成一次 tap（instrumented 用例已覆盖）。
            .pointerInput(failedPlaceholder, item.sourceMissing) {
                detectTapGestures(
                    onTap = {
                        if (failedPlaceholder && !item.sourceMissing && !selectionMode) {
                            retryTick++
                        } else {
                            onTap()
                        }
                    }
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
                                isMissing && item.sourceMissing ->
                                    stringResource(R.string.msg_image_source_missing_no_cache)
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

            // 「已删除」角标（第三态）：源已不存在、画面是本机缓存的低清副本。
            // 视觉复用回收站角标：error 底 + onError 文字 + CircleShape。
            // 纵向错位只用 offset（padding 不接受负值，v4.17.0 真机崩溃点见 NoNegativePaddingTest），
            // 与 3D 角标同占 TopEnd 时下移一行，两者都不被遮挡。
            if (sourceMissing && !isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = if (item.threeD != null) 30.dp else 0.dp)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_source_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onError
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
 * 「加载更早」页脚 item 的稳定 key。
 * 与条目 key（NUL 二元组）不可能相撞：这个常量不含 NUL，且全网格只有这一个页脚。
 */
internal const val GALLERY_LOAD_OLDER_FOOTER_KEY = "gallery_load_older_footer"

/**
 * 网格 item 的 contentType（P2-E）。
 *
 * `LazyVerticalGrid` 的复用池按 contentType 分区：卡片（每格一张位图 + 角标）、
 * 页脚（一个按钮）、状态占位（转圈/空态插图）三种内容毫不相干，共用同一槽位时
 * 滑到页脚会把卡片槽整槽拆掉重建。分区之后三者各自复用，滚动期少两次内容替换。
 */
internal const val GALLERY_GRID_CARD_CONTENT_TYPE = "gallery_card"
internal const val GALLERY_GRID_FOOTER_CONTENT_TYPE = "gallery_footer"
internal const val GALLERY_GRID_STATE_CONTENT_TYPE = "gallery_state"

/**
 * 状态占位 item 的稳定 key（P2-E）。
 *
 * 修复前 loading / empty 两个全宽占位项没有 key：网格在"转圈 → 空态 → 有图"之间切换时，
 * 同一个槽位换了身份却没有 key 可对齐，只能整槽丢弃重建。
 */
internal const val GALLERY_STATE_LOADING_KEY = "gallery_state_loading"
internal const val GALLERY_STATE_EMPTY_KEY = "gallery_state_empty"

/**
 * 首屏预取的窗口大小（从首个可见项往后取这么多项）。
 *
 * 抽成常量是为了让 [galleryPrefetchRange] 的边界行为可以在单测里精确覆盖。
 */
internal const val GALLERY_PREFETCH_WINDOW = 24

/**
 * 首屏预取的索引区间（P1-23，纯函数便于单测）。
 *
 * 修复前是 `items.subList(firstVisibleItemIndex, (firstVisible + 24).coerceAtMost(size))`：
 * 只把**尾部**夹到 size，起点没夹。删除条目 / 刷新后列表变短时，
 * `firstVisibleItemIndex` 可能还停在旧的大值上（> size），`subList` 直接抛
 * `IndexOutOfBoundsException` —— 表现为删完图回到图库就崩。
 *
 * 这里把两端都夹进 `[0, size]`，并保证 `start <= end`（空列表返回 0..0，subList 合法）。
 */
internal fun galleryPrefetchRange(
    itemCount: Int,
    firstVisibleIndex: Int,
    windowSize: Int = GALLERY_PREFETCH_WINDOW
): IntRange {
    if (itemCount <= 0) return 0..0
    val start = firstVisibleIndex.coerceIn(0, itemCount)
    val end = (start + windowSize.coerceAtLeast(0)).coerceAtMost(itemCount)
    return start..end
}

/**
 * 用 PixelCopy 异步抓取当前窗口画面（P1-28）。
 *
 * 为什么不用 `decorView.drawToBitmap()`：那是**软件绘制**，整屏（顶栏 + 所有可见格子）
 * 在调用线程上逐层 draw 一遍。它在点击回调里同步执行 = 主线程卡顿，
 * 图库越满越明显。
 *
 * PixelCopy 由系统在 RenderThread 侧把窗口表面拷进 Bitmap，拷贝完成后再回调主线程，
 * 主线程只做"发起"和"收结果"。
 *
 * @return 成功返回窗口尺寸的位图；窗口未就绪 / 拷贝失败返回 null（调用方回退纯黑）
 */
internal suspend fun captureWindowSnapshot(activity: Activity): Bitmap? =
    withContext(Dispatchers.Main.immediate) {
        val window = activity.window
        val decorView = window.decorView
        val width = decorView.width
        val height = decorView.height
        if (width <= 0 || height <= 0) return@withContext null

        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return@withContext null
        }

try {
            // 超时兜底：窗口正在销毁/不可见时 PixelCopy 的回调可能永远不来，
            // 没有超时的话协程会一直挂着 —— 表现为"点了图没反应"
            withTimeoutOrNull(WINDOW_SNAPSHOT_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    PixelCopy.request(
                        window,
                        bitmap,
                        { result ->
                            if (!continuation.isActive) return@request
                            if (result == PixelCopy.SUCCESS) {
                                continuation.resume(bitmap)
                            } else {
                                bitmap.recycle()
                                continuation.resume(null)
                            }
                        },
                        Handler(Looper.getMainLooper())
                    )
                }
            } ?: run {
                bitmap.recycle()
                null
            }
        } catch (e: Exception) {
            // 窗口还没挂上 Surface（极早期的点击）等情况：按"无截图"处理
            bitmap.recycle()
            null
        }
    }

/** 窗口截图（PixelCopy）的超时上限（ms）：超时按"无截图"回退，绝不让打开查看器挂住 */
private const val WINDOW_SNAPSHOT_TIMEOUT_MS = 800L

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
