package sh.hnet.comfychair.ui.screens

import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarColors
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.ImageViewer
import sh.hnet.comfychair.ui.components.MetadataBottomSheet
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.ui.components.ViewerRect
import sh.hnet.comfychair.ui.components.ViewerSideBySide
import sh.hnet.comfychair.ui.components.rememberLazyBitmap
import sh.hnet.comfychair.ui.components.VideoScaleMode
import sh.hnet.comfychair.viewmodel.MediaViewerEvent
import sh.hnet.comfychair.viewmodel.MediaViewerViewModel
import sh.hnet.comfychair.viewmodel.ViewerFailureCategory
import sh.hnet.comfychair.viewmodel.ViewerLoadPolicy
import sh.hnet.comfychair.viewmodel.ViewerMode
import sh.hnet.comfychair.viewmodel.ViewerShowState

/**
 * 并排进度吸附时长（ms，v4.16.2）。
 *
 * 松手后的档位吸附要"干脆"：太快（<180ms）显得生硬、看不清，太慢（>400ms）会拖
 * 出"松手还黏着手指"的滞后感。280ms + FastOutSlowIn 与顶栏按压反馈的直给手感和
 * 查看器翻页动画（Pager 默认 ~400ms 的短路版本）都合拍。
 */
private const val PROGRESS_SETTLE_MS = 280

/**
 * 把下载链接写进系统剪贴板，返回是否成功。
 *
 * 失败时调用方要给出可读提示 —— 不能让用户以为复制成功了、到浏览器里粘出来是空的。
 */
private fun copyUrlToClipboard(context: android.content.Context, url: String): Boolean = try {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("comfychair_download", url))
    true
} catch (_: Exception) {
    false
}

/**
 * 由缓存侧标记推断失败档位（H6，与 MediaViewerViewModel.inferLoadFailure 同口径）。
 *
 * 为什么 UI 层要自己再查一遍：GALLERY 模式的图片加载走 rememberLazyBitmap（UI 侧链路），
 * 它失败时 ViewModel 不一定记过 loadFailure（预取分支、缩略图链路都是独立失败点），
 * 而 404 标记一定在 MediaCache 上 —— 图库格子的"文件不存在"图标也是这么查的
 * （见 GalleryItemCard）。ViewModel 记录的档位作为非 404 场景的兜底。
 */
private fun inferFailureCategory(
    cacheKey: MediaCacheKey,
    fromViewModel: ViewerFailureCategory?
): ViewerFailureCategory = when {
    MediaCache.isFileMissing(cacheKey) -> ViewerFailureCategory.FILE_MISSING
    ConnectionManager.isOffline || ConnectionManager.clientOrNull == null ->
        ViewerFailureCategory.NETWORK
    else -> fromViewModel ?: ViewerFailureCategory.GENERIC
}

/** 失败档位 → 文案资源（H6 三档映射的 UI 层唯一入口） */
private fun failureMessageRes(category: ViewerFailureCategory): Int = when (category) {
    ViewerFailureCategory.FILE_MISSING -> R.string.viewer_load_failed_file_missing
    ViewerFailureCategory.NETWORK -> R.string.viewer_load_failed_network
    ViewerFailureCategory.GENERIC -> R.string.viewer_load_failed_generic
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaViewerScreen(
    viewModel: MediaViewerViewModel,
    onClose: () -> Unit,
    // B-23b 第三批（E1~E2）：图库整屏截图与目标格子矩形（window px）。
    // 由 GalleryScreen 在 launch 之前截好、经 BitmapCache 并行字段传到这里；
    // null = 单图模式 / 截图失败 / 开关关闭，E1~E5 全部禁用（纯黑 + 现有下滑关闭）
    gallerySnapshot: Bitmap? = null,
    galleryCellRect: ViewerRect? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val currentMetadata by viewModel.currentMetadata.collectAsState()
    val isLoadingMetadata by viewModel.isLoadingMetadata.collectAsState()
    val scope = rememberCoroutineScope()

    // Layout constants - 8dp because navigationBarsPadding() adds ~24dp, totaling ~32dp like Workflow Editor
    val toolbarBottomPadding = 8.dp

    // 并排预览三级手势开关（v4.16.2，总体计划 D1~D5）：组合期读一次，置 false 完全回到
    // v4.15.x 的旧行为（未放大态捏合 = 缩小淡出关闭查看器）
    val threeLevelEnabled = remember { AppSettings.isGalleryThreeLevelEnabled(context) }

    // 下拉透出 + 飞回开关（v4.16.3，总体计划 E1~E5）：组合期读一次，置 false 完全回到
    // 纯黑背景 + 现有下滑关闭（无截图透出、无飞回）
    val dismissRevealEnabled = remember { AppSettings.isGalleryDismissRevealEnabled(context) }

    // N2 挖空开关（v4.16.8）：开启 = 图库截图已在像素空间挖好空置格（默认）；
    // 关闭 = 回退旧的覆盖层方案（在截图之上画黑矩形，即 E3 Canvas）
    val revealHolePunchEnabled = remember { AppSettings.isGalleryRevealHolePunch(context) }

    // Metadata bottom sheet state
    var showMetadataSheet by remember { mutableStateOf(false) }

    // Load metadata when sheet is shown
    LaunchedEffect(showMetadataSheet) {
        if (showMetadataSheet) {
            viewModel.loadMetadata()
        }
    }

    // Handle system back button - ensures onClose() is called with proper result
    BackHandler {
        onClose()
    }

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is MediaViewerEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is MediaViewerEvent.ItemDeleted -> {
                    // Items list changed, pager will recompose with new key
                }
                is MediaViewerEvent.Close -> {
                    onClose()
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Media content
        when (uiState.mode) {
            ViewerMode.GALLERY -> {
                if (uiState.items.isNotEmpty()) {
                    // Key the pager by items list identity to force recreation on deletion
                    val itemsKey = uiState.items.map { "${it.promptId}_${it.filename}" }.joinToString(",")

                    // B-23b 第三批：进入时的初始页。刻意放在 key(itemsKey) 之外——删除
                    // 条目会改变 itemsKey 并销毁内部作用域，initialPage 若放在里面会被
                    // 重置成删除后的 currentIndex，"进入那一刻的页"语义就丢了
                    val initialPage = remember {
                        uiState.currentIndex.coerceIn(0, uiState.items.size - 1)
                    }

                    key(itemsKey) {
                        val pagerState = rememberPagerState(
                            initialPage = initialPage,
                            pageCount = { uiState.items.size }
                        )

                        // ============================================================
                        // 下拉透出图库 + 位置飞回（B-23b 第三批，E1~E5）
                        // ============================================================
                        // 截图是"进入那一刻"的静态画面（客户已知情接受该局限），所以
                        // 透出只在"停留在进入时的那一页"时启用——翻页后截图与真实图库
                        // 脱节，回退纯黑 + 现有下滑关闭。视频页同样不启用（VideoPlayer
                        // 无分段遮罩链路，且初始页若是视频，透出会从播放器背后漏截图）
                        val revealItem = uiState.items.getOrNull(pagerState.currentPage)
                        val revealActive = dismissRevealEnabled &&
                            gallerySnapshot != null && galleryCellRect != null &&
                            pagerState.currentPage == initialPage &&
                            revealItem?.isVideo == false

                        // E1/E2 背景截图层：铺满屏幕、1:1 对齐进查看器那一刻的屏幕像素
                        //（截图本身就是全屏，不缩放不裁剪）。初始态页内黑底遮罩
                        //（ImageViewer drawBehind）alpha=1 把它盖成纯黑（E1）；
                        // 下拉偏离原位约 10% 后遮罩快速淡到 0.2，图库从背后透出（E2）
                        if (revealActive && gallerySnapshot != null && galleryCellRect != null) {
                            Image(
                                bitmap = gallerySnapshot.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                            // E3：目标格子挖黑占位——N2 挖空开关关闭时的旧覆盖层路径。
                            // 挖空开启（默认）时截图里那格已经是"空置格子"，这里不再
                            // 画黑矩形，避免"空置格 + 黑块"叠两层
                            if (!revealHolePunchEnabled) {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawRect(
                                        color = Color.Black,
                                        topLeft = Offset(galleryCellRect.left, galleryCellRect.top),
                                        size = Size(galleryCellRect.width, galleryCellRect.height)
                                    )
                                }
                            }
                        }

                        // Sync pager state with ViewModel (user swipes)
                        LaunchedEffect(pagerState) {
                            snapshotFlow { pagerState.currentPage }
                                .collect { page ->
                                    if (page != uiState.currentIndex) {
                                        viewModel.setCurrentIndex(page)

                                        // Update cache priorities based on new position
                                        val allKeys = uiState.items.map {
                                            MediaCacheKey(it.promptId, it.filename)
                                        }
                                        MediaCache.updateNavigationPriorities(page, allKeys)
                                    }
                                }
                        }

                        // ============================================================
                        // 并排预览三级手势（D1~D5，v4.16.2）
                        // ============================================================
                        // 进度语义见 ViewerSideBySide：0 = 全屏；1 = 并排（当前页占视口 70%、
                        // 两侧各露 15%）；1.5 = 临时缩小档（55%）。捏合期间直接写值跟手，
                        // 松手由 animateProgressTo 做档位吸附。
                        var sideProgress by remember { mutableFloatStateOf(0f) }
                        // 本次捏合的起始进度：ImageViewer 只上报"自手势起点累计的缩放倍率"，
                        // 换算成绝对进度必须知道起点（全屏 0 / 并排 1 / 吸附动画被打断的中间值）
                        var gestureBaseProgress by remember { mutableFloatStateOf(0f) }
                        var progressJob by remember { mutableStateOf<Job?>(null) }
                        var pagerSize by remember { mutableStateOf(IntSize.Zero) }

                        // 布尔派生：只有"越过 0.5"时才让分页内容重组，捏合中的每帧 Float 变化不穿透
                        val sideBySideActive by remember {
                            derivedStateOf { sideProgress > ViewerSideBySide.SETTLE_THRESHOLD }
                        }

                        fun cancelProgressJob() {
                            progressJob?.cancel()
                            progressJob = null
                        }

                        // 档位吸附 / 回全屏的统一动画出口
                        fun animateProgressTo(target: Float) {
                            cancelProgressJob()
                            if (sideProgress == target) return
                            progressJob = scope.launch {
                                animate(
                                    initialValue = sideProgress,
                                    targetValue = target,
                                    animationSpec = tween(
                                        PROGRESS_SETTLE_MS,
                                        easing = FastOutSlowInEasing
                                    )
                                ) { value, _ -> sideProgress = value }
                            }
                        }

                        // D4："点哪张图哪张全屏"。露边页要"先翻到它 + 同时放大回全屏"——
                        // 两件事并行，只做其中一件都会先跳正再缩放，观感断裂
                        fun expandPage(targetPage: Int) {
                            val safe = targetPage.coerceIn(0, uiState.items.size - 1)
                            if (safe == pagerState.currentPage) {
                                animateProgressTo(0f)
                                return
                            }
                            cancelProgressJob()
                            progressJob = scope.launch {
                                launch { pagerState.animateScrollToPage(safe) }
                                animate(
                                    initialValue = sideProgress,
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        PROGRESS_SETTLE_MS,
                                        easing = FastOutSlowInEasing
                                    )
                                ) { value, _ -> sideProgress = value }
                            }
                        }

                        // 供 ImageViewer 读取的"当前页占视口比例"：lambda 形式让捏合动画每帧只更新
                        // graphicsLayer、不触发整页重组（三页同时 60fps 重组会掉帧）
                        val sideScaleProvider: (() -> Float)? = if (threeLevelEnabled) {
                            remember { { ViewerSideBySide.pageFraction(sideProgress) } }
                        } else {
                            null
                        }

                        // 捏合三段回调（与页无关，整段只建一次）
                        val sideStartHandler: (() -> Unit)? = if (threeLevelEnabled) {
                            {
                                cancelProgressJob()
                                gestureBaseProgress = sideProgress
                                // N5Probe（仅 debug 包输出）：手势起点快照
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "N5Probe",
                                        "sideStart base=$gestureBaseProgress " +
                                            "offset=${pagerState.currentPageOffsetFraction} " +
                                            "page=${pagerState.currentPage} " +
                                            "scrolling=${pagerState.isScrollInProgress}"
                                    )
                                }
                                // N5（v4.16.8，返工修订）：翻页拖动进行中落第二指时，双指
                                // 事件被 ImageViewer consume，pager 的 drag 被打断，
                                // currentPageOffsetFraction 可能冻结在中间值。N5Probe 实测
                                // （22.5% 残留复合手势）：这里的 scrollToPage 会挂在 pager
                                // 的 mutator 队列上不返回（post/later 日志 12s+ 未出现），
                                // 归零实际由 pager 打断后的 settle 完成——时序不受我方
                                // 控制。所以本调用只是尽力归零的兜底（无动画、不与
                                // animateScrollToPage 竞争、只在确实有残留时执行），
                                // 确定性保障放在页平移的 graphicsLayer 里：并排态直接把
                                // offsetFraction 视为 0（见下方 N5 返工注释）。本回调每次
                                // 手势只触发一次（ImageViewer 的
                                // wasSideBySidePinch 门控），天然满足"首次判定"
                                if (ViewerSideBySide.shouldNormalizePager(
                                        pagerState.currentPageOffsetFraction
                                    )
                                ) {
                                    // N5Probe（v4.16.8 取证，仅 debug 包输出）：scrollToPage
                                    // 对"被手指拖动中/拖动被打断的 pager"是否真的生效——
                                    // pre=调用瞬间值，post=suspend 返回后的值，later=让出
                                    // 一帧后再读（检测 pager settle 是否又写回残留值）。
                                    if (BuildConfig.DEBUG) {
                                        Log.d(
                                            "N5Probe",
                                            "normalize before offset=" +
                                                "${pagerState.currentPageOffsetFraction} " +
                                                "page=${pagerState.currentPage} " +
                                                "scrolling=${pagerState.isScrollInProgress}"
                                        )
                                    }
                                    scope.launch {
                                        if (BuildConfig.DEBUG) {
                                            Log.d(
                                                "N5Probe",
                                                "normalize pre offset=" +
                                                    "${pagerState.currentPageOffsetFraction}"
                                            )
                                        }
                                        pagerState.scrollToPage(pagerState.currentPage)
                                        if (BuildConfig.DEBUG) {
                                            Log.d(
                                                "N5Probe",
                                                "normalize post offset=" +
                                                    "${pagerState.currentPageOffsetFraction}"
                                            )
                                        }
                                        withFrameNanos { }
                                        if (BuildConfig.DEBUG) {
                                            Log.d(
                                                "N5Probe",
                                                "normalize later offset=" +
                                                    "${pagerState.currentPageOffsetFraction}"
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            null
                        }
                        val sideHandler: ((Float) -> Unit)? = if (threeLevelEnabled) {
                            { gestureScale ->
                                cancelProgressJob()
                                sideProgress = ViewerSideBySide.progressFromGesture(
                                    gestureBaseProgress, gestureScale
                                )
                                // N5Probe（仅 debug 包输出）：并排进度轨迹，配合
                                // start/settle 三点定位复合手势下进度链路的行为
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "N5Probe",
                                        "sideUpdate base=$gestureBaseProgress " +
                                            "scale=$gestureScale " +
                                            "progress=$sideProgress " +
                                            "offset=${pagerState.currentPageOffsetFraction}"
                                    )
                                }
                            }
                        } else {
                            null
                        }
                        val sideSettleHandler: ((Float) -> Unit)? = if (threeLevelEnabled) {
                            {
                                // 松手吸附：≥0.5 → 并排 1.0；<0.5 → 全屏 0。并排态继续合拢
                                // （进度 1.0~1.5 的临时缩小档）同样吸附回 1.0 —— 绝不退出图库
                                // N5Probe（仅 debug 包输出）：松手瞬间进度与吸附目标
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "N5Probe",
                                        "settle progress=$sideProgress " +
                                            "target=${ViewerSideBySide.settleProgress(sideProgress)} " +
                                            "offset=${pagerState.currentPageOffsetFraction}"
                                    )
                                }
                                animateProgressTo(ViewerSideBySide.settleProgress(sideProgress))
                            }
                        } else {
                            null
                        }

                        // 并排是图片专属体验：翻到视频页时把进度收回全屏。视频层没有档位缩放，
                        // 停在并排会变成"全尺寸视频 + 露边"，且点击语义也会错乱
                        LaunchedEffect(pagerState.currentPage, sideBySideActive) {
                            if (sideBySideActive &&
                                uiState.items.getOrNull(pagerState.currentPage)?.isVideo == true
                            ) {
                                animateProgressTo(0f)
                            }
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { pagerSize = it },
                            // 并排露边必须让 pager 提前组合相邻页（API 名 beyondViewportPageCount，
                            // 即派工单里的 beyondBoundsPageCount），否则两侧露出来的是黑底
                            beyondViewportPageCount = if (threeLevelEnabled) 1 else 0,
                            key = { index -> "${uiState.items[index].promptId}_${uiState.items[index].filename}" }
                        ) { page ->
                            val item = uiState.items[page]
                            val isCurrentPage = page == pagerState.currentPage
                            val cacheKey = MediaCacheKey(item.promptId, item.filename)

                            // Lazy load bitmap from cache
                            val (bitmap, isBitmapLoading) = rememberLazyBitmap(
                                cacheKey = cacheKey,
                                isVideo = item.isVideo,
                                subfolder = item.subfolder,
                                type = item.type
                            )

                            // Video URI only needed for current page (VideoPlayer)
                            val videoUri = if (isCurrentPage && item.isVideo) uiState.currentVideoUri else null

                            // 原图还没到之前，先用列表里那张缩略图占位：
                            // 超大图要等服务端压缩（那张 204 MB 的约 9 秒），这段时间屏幕上
                            // 不该只有一个转圈 —— 先看到（模糊的）画面，原图到了再替换
                            val placeholder = if (bitmap == null) MediaCache.getThumbnail(cacheKey) else null
                            val displayBitmap = bitmap ?: placeholder

                            // Show loading if content not available yet
                            val showLoading = if (isCurrentPage) {
                                uiState.isLoading && (if (item.isVideo) videoUri == null else displayBitmap == null)
                            } else {
                                isBitmapLoading && displayBitmap == null
                            }

                            // 失败态判定（H1-a/H1-b）：内容缺失且**确定不在加载**才算失败。
                            // 只对当前页生效 —— 非当前页还没轮到加载，显示"失败"会误导；
                            // 视频页尤其关键：fetchVideoUri 失败后 currentVideoUri 永远是 null，
                            // 修复前这一页无条件转圈（死转圈）。
                            val isBusy = uiState.isLoading || isBitmapLoading
                            val showState = if (isCurrentPage) {
                                val hasContent = if (item.isVideo) {
                                    videoUri != null || displayBitmap != null
                                } else {
                                    displayBitmap != null
                                }
                                ViewerLoadPolicy.resolveShowState(item.isVideo, hasContent, isBusy)
                            } else {
                                ViewerShowState.LOADING
                            }
                            val failureCategory = if (showState == ViewerShowState.FAILED) {
                                inferFailureCategory(cacheKey, uiState.loadFailure)
                            } else {
                                null
                            }
                            // 视频页"缩略图已解出、播放地址没拿到"的失败：缩略图保留，
                            // 叠加一条可重试的失败提示。整屏换成失败占位会把已解出的首帧也藏掉。
                            val videoOverlayFailure = if (
                                isCurrentPage && item.isVideo && !isBusy &&
                                videoUri == null && displayBitmap != null
                            ) {
                                inferFailureCategory(cacheKey, uiState.loadFailure)
                            } else {
                                null
                            }

                            // 并排几何（D2/D3）：页宽保持全屏（布局与命中区域都不变），只做
                            // "整页按页距向内平移"这一步 —— 当前页 k=0 不动，左右邻页各向内收
                            // (1 - 档位比例) × 屏宽，配合图片自身的档位缩放，最终呈现
                            // "中间 70% + 两侧各露 15%"。全部在 graphicsLayer lambda 内读
                            // sideProgress：捏合每帧只更新 layer 属性，不重组分页内容
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        if (threeLevelEnabled) {
                                            val scale = ViewerSideBySide.pageFraction(sideProgress)
                                            // k = 本页相对"滚动中的当前位置"的页距（-1 左邻 / 0 当前 / +1 右邻）
                                            // N5（v4.16.8 返工）：并排态把 pager 的翻页残留视为 0。
                                            // N5Probe 实测：复合手势落第二指后，scrollToPage 会挂在
                                            // pager 的 mutator 队列上不返回（在途手势未释放，挂起
                                            // 12s+ 仍无 post），残留归零实际依赖 pager 被打断后
                                            // "自行 settle"这条不受我方控制的路径。渲染层直接忽略
                                            // 残留值：只要进入并排（sideProgress > 0），页距一律按
                                            // "pager 已归零"计算，冻结值永远污染不了页平移；
                                            // sideProgress == 0（全屏态）保持原样，正常翻页不受影响
                                            val offsetFraction = if (sideProgress > 0f) {
                                                0f
                                            } else {
                                                pagerState.currentPageOffsetFraction
                                            }
                                            val pageDistance = (page - pagerState.currentPage) -
                                                offsetFraction
                                            translationX = pageDistance * size.width * (scale - 1f)
                                        }
                                    }
                                    .clipToBounds()
                            ) {
                                // D4 并排态点击：点在居中页 → 放大回全屏；点在露边页 → 先翻到
                                // 该页再同步放大。全屏态保持 null，单击继续走既有 onSingleTap
                                // （保留双击缩放：并排回调非 null 时 ImageViewer 会跳过双击判定）
                                val sideTap: ((Float, Float) -> Unit)? =
                                    if (threeLevelEnabled && sideBySideActive) {
                                        { imageLocalX, _ ->
                                            // 物理落点可能是任意一页（露边的邻页与当前页的布局
                                            // 区域互相重叠，右侧那页还画在上层）。ImageViewer 上报的
                                            // 是"图片被档位缩放之后"的层内坐标，必须连同缩放与页位移
                                            // 一起反算回视口坐标 —— 少算缩放这一步会让落点整体偏向中间，
                                            // 表现就是"点露边页只放大不翻页"（纯函数里带单测钉住）
                                            val fraction =
                                                ViewerSideBySide.pageFraction(sideProgress)
                                            val pageDistance = (page - pagerState.currentPage) -
                                                pagerState.currentPageOffsetFraction
                                            val screenX = ViewerSideBySide.viewportXFromImageLocal(
                                                imageLocalX = imageLocalX,
                                                viewportWidth = pagerSize.width.toFloat(),
                                                fraction = fraction,
                                                pageDistance = pageDistance
                                            )
                                            val zone = ViewerSideBySide.tapZone(
                                                screenX,
                                                pagerSize.width.toFloat(),
                                                sideProgress
                                            )
                                            // zone：-1 = 左邻、0 = 居中（当前页）、+1 = 右邻
                                            expandPage(pagerState.currentPage + zone)
                                        }
                                    } else {
                                        null
                                    }

                                MediaContent(
                                    isVideo = item.isVideo,
                                    bitmap = displayBitmap,
                                    videoUri = videoUri,
                                    isLoading = showLoading,
                                    onSingleTap = { viewModel.toggleUiVisibility() },
                                    cacheKey = cacheKey,
                                    failureCategory = failureCategory,
                                    onRetry = { viewModel.retryCurrentItem() },
                                    // 下滑脱离手势的关闭出口复用屏幕级 onClose（唯一关闭链路）
                                    onViewerClose = onClose,
                                    // E1~E5 只挂在进入时的那一页图片上（revealActive 已含
                                    // currentPage == initialPage；翻页动画中的邻页 page 不同）
                                    flyBackCellRect = if (revealActive && page == initialPage) {
                                        galleryCellRect
                                    } else {
                                        null
                                    },
                                    // E4：飞回已无缝落格，关闭时抑制 Activity 层缩小淡出
                                    onFlyBackCompleted = if (revealActive && page == initialPage) {
                                        { MediaViewerActivity.suppressNextExitTransition = true }
                                    } else {
                                        null
                                    },
                                    // N6（v4.16.9，方案 A2）：放大态贴边继续同向拖 → 主动翻页，
                                    // direction 是 pager 页方向（+1 下一张 / -1 上一张）。
                                    // coerceIn 兜住边界页（第 0 页上一张 / 末页下一张的
                                    // animateScrollToPage 目标不变、等于空操作）；视频页不传
                                    // （视频层无放大平移链路）。翻页后新页由新组合的
                                    // ImageViewer 实例天然回到 scale=1、居中
                                    onPanEdgeFling = if (item.isVideo) {
                                        null
                                    } else {
                                        { direction ->
                                            scope.launch {
                                                val target = (pagerState.currentPage + direction)
                                                    .coerceIn(0, uiState.items.size - 1)
                                                pagerState.animateScrollToPage(target)
                                            }
                                        }
                                    },
                                    // 并排预览三级手势（仅 GALLERY 图片页接线；视频页不传，
                                    // 由上面的 LaunchedEffect 把进度收回全屏）。
                                    // 这里按 item.isVideo 再门控一次：视频页在播放地址还没拿到的
                                    // 瞬间显示的是缩略图（走 ImageViewer 分支），若把并排回调传进去，
                                    // 捏合会先撑开并排、紧接着被上面那个 effect 拽回全屏，两下打架
                                    onSideBySideStart = if (item.isVideo) null else sideStartHandler,
                                    onSideBySide = if (item.isVideo) null else sideHandler,
                                    onSideBySideSettle = if (item.isVideo) null else sideSettleHandler,
                                    onSideBySideTap = if (item.isVideo) null else sideTap,
                                    sideBySideScale = if (item.isVideo) null else sideScaleProvider
                                )

                                // 原图超过下载上限时的说明 + 取原图的途径。
                                // 只出现在"当前页且确实是降级来的图"上：App 读不动那张图，
                                // 但浏览器可以，所以给一条一键复制的链接而不是让用户自己拼。
                                if (isCurrentPage && uiState.compressedFallback) {
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(16.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.viewer_large_image_notice),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            TextButton(onClick = {
                                                val url = viewModel.downloadUrlForCurrent()
                                                if (url != null && copyUrlToClipboard(context, url)) {
                                                    Toast.makeText(
                                                        context,
                                                        R.string.msg_download_link_copied,
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } else {
                                                    Toast.makeText(
                                                        context,
                                                        R.string.msg_copy_prompt_failed,
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }) {
                                                Text(stringResource(R.string.viewer_copy_download_link))
                                            }
                                        }
                                    }
                                }

                                // 视频播放地址没拿到但缩略图还在：不藏画面，底部叠加可重试的失败说明（H1-a）
                                if (videoOverlayFailure != null) {
                                    MediaLoadFailureBar(
                                        category = videoOverlayFailure,
                                        onRetry = { viewModel.retryCurrentItem() },
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            ViewerMode.SINGLE -> {
                val item = uiState.currentItem
                // SINGLE 失败态（H1-a/H1-b）：内容缺失且不在加载 —— 修复前视频死转圈、图片静默黑屏。
                // 生成页跳转时视频 URI 没拿到（fetchVideoFromHistory 失败）正是这个分支的典型场景
                val hasContent = if (item?.isVideo == true) {
                    uiState.currentVideoUri != null || uiState.currentBitmap != null
                } else {
                    uiState.currentBitmap != null
                }
                val showState = ViewerLoadPolicy.resolveShowState(
                    item?.isVideo ?: false, hasContent, uiState.isLoading
                )
                val singleFailure = if (showState == ViewerShowState.FAILED && item != null) {
                    inferFailureCategory(
                        MediaCacheKey(item.promptId, item.filename),
                        uiState.loadFailure
                    )
                } else {
                    null
                }
                MediaContent(
                    isVideo = item?.isVideo ?: false,
                    bitmap = uiState.currentBitmap,
                    videoUri = uiState.currentVideoUri,
                    isLoading = uiState.isLoading,
                    onSingleTap = { viewModel.toggleUiVisibility() },
                    failureCategory = singleFailure,
                    onRetry = { viewModel.retryCurrentItem() },
                    // 下滑脱离手势的关闭出口复用屏幕级 onClose（唯一关闭链路）
                    onViewerClose = onClose
                )
            }
        }

        // Counter chip (gallery mode only, when multiple items)
        if (uiState.mode == ViewerMode.GALLERY && uiState.totalCount > 1) {
            AnimatedVisibility(
                visible = uiState.isUiVisible,
                enter = fadeIn(animationSpec = tween(250)),
                exit = fadeOut(animationSpec = tween(250)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Text(
                        text = "${uiState.currentIndex + 1} / ${uiState.totalCount}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // Floating Toolbar
        AnimatedVisibility(
            visible = uiState.isUiVisible,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(250)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = toolbarBottomPadding)
        ) {
            MediaViewerFloatingToolbar(
                isGalleryMode = uiState.mode == ViewerMode.GALLERY,
                onDelete = { viewModel.deleteCurrentItem() },
                onSave = { viewModel.saveCurrentItem() },
                onShare = { viewModel.shareCurrentItem() },
                onInfo = { showMetadataSheet = true },
                // 任何图都能复制下载链接 —— 不是只在"原图太大被降级"时才有。
                // 大图在 App 内受内存限制只能看降采样版，这条链接是拿到原始分辨率的通用出路
                onCopyLink = {
                    val url = viewModel.downloadUrlForCurrent()
                    if (url != null && copyUrlToClipboard(context, url)) {
                        Toast.makeText(context, R.string.msg_download_link_copied, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, R.string.msg_copy_prompt_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        // 返回按钮（F2，v4.16.2 已删除）：关闭查看器只剩三条入口 ——
        // ① 下滑手势（MediaContent → ImageViewer/VideoPlayer 的 onViewerClose）
        // ② 并排态张开手指回全屏、并排态下拉关闭
        // ③ 系统返回键（本页顶部 BackHandler）

        // Metadata bottom sheet
        if (showMetadataSheet) {
            MetadataBottomSheet(
                metadata = currentMetadata,
                isLoading = isLoadingMetadata,
                onDismiss = { showMetadataSheet = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaViewerFloatingToolbar(
    isGalleryMode: Boolean,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    onCopyLink: () -> Unit,
    modifier: Modifier = Modifier
) {
    val toolbarColors = FloatingToolbarColors(
        toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        toolbarContentColor = MaterialTheme.colorScheme.onSurface,
        fabContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        fabContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )

    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = toolbarColors,
        content = {
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Delete button (gallery mode only)
                if (isGalleryMode) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.media_viewer_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Save button
                IconButton(onClick = onSave) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = stringResource(R.string.media_viewer_save)
                    )
                }

                // 复制下载链接：大图（App 内只能看降采样版）拿原始分辨率的通用出路
                IconButton(onClick = onCopyLink) {
                    Icon(
                        Icons.Default.Link,
                        contentDescription = stringResource(R.string.media_viewer_copy_link)
                    )
                }

                // Share button
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.media_viewer_share)
                    )
                }

                // Divider before Info
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Info button
                IconButton(onClick = onInfo) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.button_show_metadata)
                    )
                }
            }
        }
    )
}

@Composable
private fun MediaContent(
    isVideo: Boolean,
    bitmap: android.graphics.Bitmap?,
    videoUri: android.net.Uri?,
    isLoading: Boolean,
    onSingleTap: () -> Unit,
    cacheKey: MediaCacheKey? = null,
    // 失败占位（H1-a/H1-b）：调用方判定"内容缺失且不在加载"后传入；null = 不显示失败态
    failureCategory: ViewerFailureCategory? = null,
    onRetry: (() -> Unit)? = null,
    // 下滑脱离关闭的回调；null = 组件不启用脱离手势（缩略图槽位等无关闭语义的场景）
    onViewerClose: (() -> Unit)? = null,
    // B-23b 第三批（E1~E5）：本页的飞回目标格子矩形（window px）。非 null = 该页启用
    // 下拉透出与飞回（分段遮罩在 ImageViewer 内）；null = 行为与旧版完全一致
    flyBackCellRect: ViewerRect? = null,
    // E4：飞回动画完成后的通知（先于 onViewerClose），上层据此抑制 Activity 层缩小淡出
    onFlyBackCompleted: (() -> Unit)? = null,
    // N6（v4.16.9，方案 A2）：放大态贴边继续同向拖的翻页回调，+1 右翻 / -1 左翻。
    // 上层用 pagerState.animateScrollToPage 主动翻页（A1 让位实测无效，见 ImageViewer）
    onPanEdgeFling: ((direction: Int) -> Unit)? = null,
    // 并排预览三级手势（D1~D5，v4.16.2）：仅 GALLERY 模式的图片页由上层接线。
    // 四个回调全 null 时 ImageViewer 的行为与改动前完全一致（不含任何并排分支）
    onSideBySideStart: (() -> Unit)? = null,
    onSideBySide: ((Float) -> Unit)? = null,
    onSideBySideSettle: ((Float) -> Unit)? = null,
    onSideBySideTap: ((Float, Float) -> Unit)? = null,
    sideBySideScale: (() -> Float)? = null
) {
    Box(
        // 这里**不能**铺不透明背景：并排预览（D2/D3）下每一页都会被整体缩放并
        // 向中间平移，页与页的布局区域互相重叠。若每页自带不透明黑底，当前页
        // 的黑底会盖掉左邻露边、右邻的黑底又会盖掉中间图右侧（实测各吃 48px）。
        // 屏幕根容器（MediaViewerScreen 的 Box）已经是纯黑，少这一层视觉零差别。
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            isVideo && videoUri != null -> {
                // Current video page: show VideoPlayer
                VideoPlayer(
                    videoUri = videoUri,
                    modifier = Modifier.fillMaxSize(),
                    showController = false,
                    scaleMode = VideoScaleMode.FIT,
                    enableZoom = true,
                    onSingleTap = onSingleTap,
                    cacheKey = cacheKey,
                    onClose = onViewerClose
                )
            }
            bitmap != null -> {
                // Image OR video thumbnail (for non-current video pages)
                ImageViewer(
                    bitmap = bitmap,
                    modifier = Modifier.fillMaxSize(),
                    onSingleTap = onSingleTap,
                    onClose = onViewerClose,
                    onFlyBackCompleted = onFlyBackCompleted,
                    // E1~E5：格子矩形非 null 时本页启用下拉透出与飞回（分段遮罩、
                    // 飞回落格都在 ImageViewer 内）；null = 与旧版完全一致
                    flyBackCellRect = flyBackCellRect,
                    onPanEdgeFling = onPanEdgeFling,
                    onSideBySideStart = onSideBySideStart,
                    onSideBySide = onSideBySide,
                    onSideBySideSettle = onSideBySideSettle,
                    onSideBySideTap = onSideBySideTap,
                    sideBySideScale = sideBySideScale
                    // v4.16.8 N3/N4：pageBackground 参数已随本版删除——背景分支收敛为
                    // ImageViewer.drawBehind 三分支（判定见 resolveBackgroundBranch），
                    // 全屏无格子态由"档位宽度黑"（sideScale=1 → 满宽满高）兜底，
                    // 背景从此只有一处来源
                )
            }
            else -> {
                // E1：透出页的加载/失败态先盖一层纯黑——背景截图不能从 spinner 与
                // 失败占位后面漏出来（图片分支的黑幕在 ImageViewer 内分段驱动）
                if (flyBackCellRect != null) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                }
                if (failureCategory != null) {
                    // 加载失败（H1-a/H1-b）：图标 + 分档文案 + 重试。
                    // 修复前视频页到这里永远转圈、图片页静默黑屏，用户无从分辨"在加载"还是"坏了"
                    MediaLoadFailurePlaceholder(
                        category = failureCategory,
                        onRetry = onRetry,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (isVideo || isLoading) {
                    // Video pages without content always show spinner (never black)
                    // This handles the case where thumbnail is not yet in cache
                    // （视频页失败态已被上面的 failureCategory 分支拦下，走到这里的只剩
                    //   非当前页视频与真正的加载中 —— 保持"never black"的原语义）
                    CircularProgressIndicator(color = Color.White)
                }
                // Fallback: only images without bitmap and not loading show black
                // （非当前页图片加载失败保持黑屏：它们是候补页，不该替用户下"失败"的结论）
            }
        }
    }
}

/**
 * 整屏加载失败占位（H1-a/H1-b）：图标 + 分档文案 + 重试按钮。
 *
 * 三档图标对齐图库格子的既有语义（见 GalleryItemCard）：
 * 文件不存在是"找不回"，网络不可达是"暂时取不到"，用户要能一眼区分。
 * 黑底上用白色系（与 spinner 同一套），不借主题色 —— 查看器背景恒为黑。
 */
@Composable
private fun MediaLoadFailurePlaceholder(
    category: ViewerFailureCategory,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = when (category) {
                ViewerFailureCategory.FILE_MISSING -> Icons.Default.ImageSearch
                ViewerFailureCategory.NETWORK -> Icons.Default.CloudOff
                ViewerFailureCategory.GENERIC -> Icons.Default.Image
            },
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = Color.White.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(failureMessageRes(category)),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f)
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.viewer_retry))
            }
        }
    }
}

/**
 * 视频页的失败提示条（H1-a）：缩略图保留在画面上，底部叠加一条"没拿到播放地址"
 * 的说明与重试入口。样式对齐"原图过大"的降级提示层（compressedFallback），
 * 同一套 Surface + Row + TextButton，用户看到的是同一种"附加说明"而不是弹窗。
 */
@Composable
private fun MediaLoadFailureBar(
    category: ViewerFailureCategory,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp)
        ) {
            Text(
                text = stringResource(failureMessageRes(category)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.viewer_retry))
            }
        }
    }
}
