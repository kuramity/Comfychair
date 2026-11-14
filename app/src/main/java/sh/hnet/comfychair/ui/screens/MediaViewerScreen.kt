package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.ui.components.ImageViewer
import sh.hnet.comfychair.ui.components.MetadataBottomSheet
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.ui.components.rememberLazyBitmap
import sh.hnet.comfychair.ui.components.VideoScaleMode
import sh.hnet.comfychair.viewmodel.MediaViewerEvent
import sh.hnet.comfychair.viewmodel.MediaViewerViewModel
import sh.hnet.comfychair.viewmodel.ViewerFailureCategory
import sh.hnet.comfychair.viewmodel.ViewerLoadPolicy
import sh.hnet.comfychair.viewmodel.ViewerMode
import sh.hnet.comfychair.viewmodel.ViewerShowState

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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val currentMetadata by viewModel.currentMetadata.collectAsState()
    val isLoadingMetadata by viewModel.isLoadingMetadata.collectAsState()
    val scope = rememberCoroutineScope()

    // Layout constants - 8dp because navigationBarsPadding() adds ~24dp, totaling ~32dp like Workflow Editor
    val toolbarBottomPadding = 8.dp
    val fabBottomPadding = toolbarBottomPadding + 4.dp

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

                    key(itemsKey) {
                        val pagerState = rememberPagerState(
                            initialPage = uiState.currentIndex.coerceIn(0, uiState.items.size - 1),
                            pageCount = { uiState.items.size }
                        )

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

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
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

                            // Use clipToBounds to ensure content doesn't bleed during transitions
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clipToBounds()
                            ) {
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
                                    onViewerClose = onClose
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

                        // Navigation arrows need pagerState, so move them inside
                        // Left navigation arrow
                        if (uiState.totalCount > 1) {
                            AnimatedVisibility(
                                visible = uiState.isUiVisible && uiState.currentIndex > 0,
                                enter = fadeIn(animationSpec = tween(250)),
                                exit = fadeOut(animationSpec = tween(250)),
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 16.dp)
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = stringResource(R.string.media_viewer_previous),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            // Right navigation arrow
                            AnimatedVisibility(
                                visible = uiState.isUiVisible && uiState.currentIndex < uiState.totalCount - 1,
                                enter = fadeIn(animationSpec = tween(250)),
                                exit = fadeOut(animationSpec = tween(250)),
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 16.dp)
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = stringResource(R.string.media_viewer_next),
                                        modifier = Modifier.size(32.dp)
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

        // Back FAB
        AnimatedVisibility(
            visible = uiState.isUiVisible,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(250)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = fabBottomPadding)
        ) {
            FloatingActionButton(
                onClick = onClose,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.content_description_back)
                )
            }
        }

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
    onViewerClose: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
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
                    onClose = onViewerClose
                )
            }
            failureCategory != null -> {
                // 加载失败（H1-a/H1-b）：图标 + 分档文案 + 重试。
                // 修复前视频页到这里永远转圈、图片页静默黑屏，用户无从分辨"在加载"还是"坏了"
                MediaLoadFailurePlaceholder(
                    category = failureCategory,
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize()
                )
            }
            isVideo || isLoading -> {
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
