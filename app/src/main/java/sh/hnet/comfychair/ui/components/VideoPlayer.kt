package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberPresentationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey

/**
 * Scale mode for video display
 */
enum class VideoScaleMode {
    /** Fit video to container, letterboxing if needed */
    FIT,
    /** Fill container, cropping edges if needed */
    CROP
}

/**
 * Composable wrapper for Media3 ExoPlayer
 * Provides video playback with looping, configurable scaling, and optional zoom/pan gestures.
 *
 * Uses a thumbnail + conditional player strategy to handle navigation transitions.
 * AndroidView renders on a separate surface layer that doesn't participate in
 * Compose animations and can "bleed through" during transitions. To solve this:
 * - When active: Shows video player with thumbnail overlay initially, then reveals video
 * - When inactive: Completely removes the video player from composition, shows only thumbnail
 *
 * @param videoUri The URI of the video to play
 * @param modifier Modifier for the player view
 * @param showController Whether to show playback controls
 * @param isActive Set to false when screen is not visible to destroy player and show thumbnail
 * @param scaleMode How to scale the video (FIT or CROP)
 * @param enableZoom Whether to enable pinch-to-zoom and double-tap zoom gestures (only works with FIT mode)
 * @param onSingleTap Callback when user taps on the video (only works when showController is false)
 * @param cacheKey Optional cache key to look up pre-fetched metadata from MediaCache
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    videoUri: Uri?,
    modifier: Modifier = Modifier,
    showController: Boolean = false,
    isActive: Boolean = true,
    scaleMode: VideoScaleMode = VideoScaleMode.CROP,
    enableZoom: Boolean = false,
    onSingleTap: (() -> Unit)? = null,
    cacheKey: MediaCacheKey? = null,
    // 下滑脱离关闭的回调；null = 不启用（生成页预览等场景）。脱离手势只在
    // enableZoom && !showController 下挂载——控制器可见时拖动语义归控制器
    onClose: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Initialize thumbnail synchronously from cache to avoid black flash during transitions
    // This ensures the thumbnail is available on the very first composition
    val initialThumbnail = remember(cacheKey) {
        cacheKey?.let { MediaCache.getBitmap(it) }
    }
    var thumbnail by remember(cacheKey) { mutableStateOf(initialThumbnail) }

    // Video dimensions - also check cache synchronously for instant display
    val cachedDimensions = remember(cacheKey) {
        cacheKey?.let { MediaCache.getVideoDimensions(it) }
    }
    var videoWidth by remember(cacheKey) { mutableStateOf(cachedDimensions?.width ?: 0) }
    var videoHeight by remember(cacheKey) { mutableStateOf(cachedDimensions?.height ?: 0) }

    // Zoom/pan state
    val scaleAnimatable = remember { Animatable(1f) }
    val offsetAnimatable = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var isAnimating by remember { mutableStateOf(false) }

    // 下滑脱离拖动：手势中的实时位移（px，向下为正）；回位时由 Animatable 接管写值，
    // isDismissSettling 是两者的数据源开关——graphicsLayer 里按它取值（与 ImageViewer 同一套）
    var dismissDragY by remember { mutableFloatStateOf(0f) }
    val dismissDragAnimatable = remember { Animatable(0f) }
    var isDismissSettling by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    // 未达关闭阈值时的弹簧回位（位移/缩放/透明度都会随位移归零而复原）
    fun settleDismissBack() {
        settleJob?.cancel()
        settleJob = scope.launch {
            // 先同步当前值再切数据源，避免切换瞬间渲染出一帧零位移
            dismissDragAnimatable.snapTo(dismissDragY)
            isDismissSettling = true
            dismissDragY = 0f
            dismissDragAnimatable.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 300f))
            isDismissSettling = false
        }
    }

    // Tap detection state
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }
    var pendingSingleTap by remember { mutableStateOf(false) }
    var pendingSingleTapTime by remember { mutableStateOf(0L) }

    // Handle delayed single tap
    LaunchedEffect(pendingSingleTap, pendingSingleTapTime) {
        if (pendingSingleTap) {
            delay(300L)
            if (pendingSingleTap && System.currentTimeMillis() - pendingSingleTapTime >= 280L) {
                pendingSingleTap = false
                onSingleTap?.invoke()
            }
        }
    }

    // Extract video dimensions when not available from cache
    // Thumbnail and dimensions are initialized synchronously from cache above
    // This LaunchedEffect only handles the fallback case when cache is empty
    LaunchedEffect(videoUri, cacheKey) {
        if (videoUri != null) {
            // Reset zoom/pan state when video changes
            scale = 1f
            offset = Offset.Zero
            // 脱离拖动状态一并归零，防止残留位移带到下一支视频
            settleJob?.cancel()
            isDismissSettling = false
            dismissDragY = 0f

            // Only fall back to MediaMetadataRetriever if cache didn't have dimensions
            if (videoWidth == 0 || videoHeight == 0) {
                withContext(Dispatchers.IO) {
                    // retriever 在外层声明，finally 里统一释放：
                    // setDataSource / getFrameAtTime / extractMetadata 抛异常时也要释放 native 资源
                    var retriever: MediaMetadataRetriever? = null
                    try {
                        val r = MediaMetadataRetriever()
                        retriever = r
                        r.setDataSource(context, videoUri)
                        val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: frame?.width ?: 0
                        val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: frame?.height ?: 0
                        if (thumbnail == null) {
                            thumbnail = frame
                        }
                        videoWidth = width
                        videoHeight = height
                    } catch (e: Exception) {
                        // Keep any existing values
                    } finally {
                        runCatching { retriever?.release() }
                    }
                }
            }
        }
        // Note: We intentionally don't reset thumbnail/dimensions when videoUri becomes null.
        // The cached thumbnail should persist to avoid black flashes during transitions.
    }

    // Calculate video display size for zoom calculations
    val videoAspectRatio = if (videoHeight > 0) videoWidth.toFloat() / videoHeight.toFloat() else 16f / 9f
    val containerAspectRatio = if (containerSize.height > 0) containerSize.width.toFloat() / containerSize.height.toFloat() else 1f

    val displayedVideoSize = remember(containerSize, videoWidth, videoHeight) {
        if (containerSize.width == 0 || containerSize.height == 0 || videoWidth == 0 || videoHeight == 0) {
            Size.Zero
        } else if (videoAspectRatio > containerAspectRatio) {
            // Video is wider - fit to width
            val width = containerSize.width.toFloat()
            val height = width / videoAspectRatio
            Size(width, height)
        } else {
            // Video is taller - fit to height
            val height = containerSize.height.toFloat()
            val width = height * videoAspectRatio
            Size(width, height)
        }
    }

    // Calculate scale needed to fill/crop the screen
    val cropScale = remember(containerSize, videoWidth, videoHeight) {
        if (containerSize.width == 0 || containerSize.height == 0 || videoWidth == 0 || videoHeight == 0) {
            1f
        } else {
            val scaleToFillWidth = containerSize.width.toFloat() / videoWidth.toFloat()
            val scaleToFillHeight = containerSize.height.toFloat() / videoHeight.toFloat()
            val fitScale = minOf(scaleToFillWidth, scaleToFillHeight)
            val cropScaleAbs = maxOf(scaleToFillWidth, scaleToFillHeight)
            cropScaleAbs / fitScale
        }
    }

    // Constrain offset to keep video on screen
    fun constrainOffset(newOffset: Offset, currentScale: Float): Offset {
        if (displayedVideoSize == Size.Zero) return Offset.Zero

        val scaledWidth = displayedVideoSize.width * currentScale
        val scaledHeight = displayedVideoSize.height * currentScale

        val excessWidth = (scaledWidth - containerSize.width).coerceAtLeast(0f) / 2f
        val excessHeight = (scaledHeight - containerSize.height).coerceAtLeast(0f) / 2f

        return Offset(
            x = newOffset.x.coerceIn(-excessWidth, excessWidth),
            y = newOffset.y.coerceIn(-excessHeight, excessHeight)
        )
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerSize = it }
    ) {
        // Content with zoom transformation
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // lambda 形式：状态变化只更新 layer 属性不走重组，拖动才能每帧跟手
                    val dismissY = if (isDismissSettling) dismissDragAnimatable.value else dismissDragY
                    val visuals = ViewerDismissGesture.dismissTransform(
                        dismissY, containerSize.height.toFloat()
                    )
                    val currentScale = if (isAnimating) scaleAnimatable.value else scale
                    val currentOffset = if (isAnimating) offsetAnimatable.value else offset
                    scaleX = currentScale * visuals.scale
                    scaleY = currentScale * visuals.scale
                    translationX = currentOffset.x
                    translationY = currentOffset.y + dismissY
                    alpha = visuals.alpha
                }
                .then(
                    if (enableZoom && !showController) {
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                val firstDownTime = System.currentTimeMillis()
                                val firstDownPosition = firstDown.position

                                // 新手势按下：若上一手势的回位动画还在跑，停掉并从当前动画值
                                // 无缝接管位移，避免画面先跳回原点再跟手
                                if (isDismissSettling) {
                                    settleJob?.cancel()
                                    isDismissSettling = false
                                    dismissDragY = dismissDragAnimatable.value
                                }

                                var wasPanOrZoom = false
                                var pointerCount = 1
                                var totalMovement = 0f

                                // scale==1 时的方向锁定（见单指分支）：本手势内判定一次后不再翻转
                                var lockedDirection = ViewerPanDirection.UNDECIDED
                                var accumulatedDx = 0f
                                var accumulatedDy = 0f
                                val velocityTracker = VelocityTracker()
                                val directionThresholdPx = ViewerDismissGesture.DIRECTION_LOCK_THRESHOLD_DP.dp.toPx()
                                val velocityThresholdPx = ViewerDismissGesture.DISMISS_VELOCITY_DP_PER_SEC.dp.toPx()

                                // 向下全量跟手；向上按阻尼轻微跟手（明确区别于"上滑关闭"，松手弹回）
                                fun updateDismissDrag(totalDy: Float) {
                                    dismissDragY = if (totalDy > 0f) totalDy else totalDy * 0.2f
                                }

                                do {
                                    val event = awaitPointerEvent()
                                    val currentPointerCount = event.changes.size

                                    if (currentPointerCount > pointerCount) {
                                        pointerCount = currentPointerCount
                                    }

                                    if (currentPointerCount >= 2) {
                                        // 捏合开始时脱离拖动还在进行：立即弹回，否则残留的
                                        // translationY 会叠在捏合的 offset 计算上产生跳变
                                        if (lockedDirection == ViewerPanDirection.VERTICAL && dismissDragY != 0f) {
                                            settleDismissBack()
                                        }
                                        // Multi-touch: zoom and pan
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val centroid = event.calculateCentroid()

                                        if (zoom != 1f || pan != Offset.Zero) {
                                            wasPanOrZoom = true

                                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                                            val zoomChange = newScale / scale
                                            val newOffset = Offset(
                                                x = offset.x * zoomChange + (1 - zoomChange) * (centroid.x - size.width / 2f) + pan.x,
                                                y = offset.y * zoomChange + (1 - zoomChange) * (centroid.y - size.height / 2f) + pan.y
                                            )

                                            scale = newScale
                                            offset = constrainOffset(newOffset, newScale)
                                        }

                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } else if (currentPointerCount == 1) {
                                        val change = event.changes.first()
                                        if (change.positionChanged()) {
                                            val movement = (change.position - change.previousPosition).getDistance()
                                            totalMovement += movement

                                            if (scale > 1f) {
                                                val pan = change.position - change.previousPosition
                                                if (pan != Offset.Zero) {
                                                    wasPanOrZoom = true
                                                    // Scale the pan delta to match the zoom level
                                                    // (translation is in pre-scale coordinates)
                                                    offset = constrainOffset(
                                                        Offset(offset.x + pan.x * scale, offset.y + pan.y * scale),
                                                        scale
                                                    )
                                                    change.consume()
                                                }
                                            } else if (onClose != null) {
                                                // scale==1 且查看器可关闭：方向锁定分流。
                                                // 水平必须留给 Pager 翻页（翻页唯一入口），只有明显
                                                // 垂直（|dy| > |dx|*1.2）的手势才归脱离拖动，斜向一律
                                                // 算水平——宁可少触发关闭，绝不抢翻页
                                                val pan = change.position - change.previousPosition
                                                accumulatedDx += pan.x
                                                accumulatedDy += pan.y
                                                velocityTracker.addPosition(change.uptimeMillis, change.position)

                                                if (lockedDirection == ViewerPanDirection.UNDECIDED) {
                                                    lockedDirection = ViewerDismissGesture.resolvePanDirection(
                                                        accumulatedDx, accumulatedDy, directionThresholdPx
                                                    )
                                                    if (lockedDirection == ViewerPanDirection.VERTICAL) {
                                                        // 锁定即脱离拖动：标记非 tap，位移由累计值驱动
                                                        wasPanOrZoom = true
                                                    }
                                                }
                                                if (lockedDirection == ViewerPanDirection.VERTICAL) {
                                                    // accumulatedDy 自按下累计（锁定帧已含本帧增量），
                                                    // 直接以累计值驱动位移，跟手无跳变
                                                    updateDismissDrag(accumulatedDy)
                                                    // 垂直手势归脱离拖动：consume 后 Pager 彻底收不到，
                                                    // 不会出现垂直拖动带着翻页面走
                                                    change.consume()
                                                }
                                                // HORIZONTAL / UNDECIDED：不 consume，事件继续流向 Pager
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                // 脱离拖动松手：位移或向下甩速达标 → 关闭查看器（复用 onClose）；
                                // 否则弹簧回位
                                if (lockedDirection == ViewerPanDirection.VERTICAL && onClose != null) {
                                    val velocity = velocityTracker.calculateVelocity()
                                    val shouldClose = ViewerDismissGesture.shouldDismiss(
                                        dy = dismissDragY,
                                        screenHeightPx = size.height.toFloat(),
                                        velocityYDownPxPerSec = velocity.y,
                                        velocityThresholdPxPerSec = velocityThresholdPx
                                    )
                                    if (shouldClose) {
                                        onClose()
                                    } else {
                                        settleDismissBack()
                                    }
                                }

                                // Handle taps
                                val upTime = System.currentTimeMillis()
                                val gestureTime = upTime - firstDownTime
                                val maxTapMovement = 30f
                                val isTap = !wasPanOrZoom && gestureTime < 300 && pointerCount == 1 && totalMovement < maxTapMovement

                                if (isTap) {
                                    val timeSinceLastTap = upTime - lastTapTime
                                    val distanceFromLastTap = (firstDownPosition - lastTapPosition).getDistance()

                                    if (lastTapTime > 0 && timeSinceLastTap < 300 && distanceFromLastTap < 100) {
                                        // Double tap - toggle zoom
                                        pendingSingleTap = false
                                        val currentScaleVal = scale
                                        val currentOffsetVal = offset

                                        if (currentScaleVal > 1.05f) {
                                            // Zoom out to fit
                                            scope.launch {
                                                isAnimating = true
                                                scaleAnimatable.snapTo(currentScaleVal)
                                                offsetAnimatable.snapTo(currentOffsetVal)
                                                launch { scaleAnimatable.animateTo(1f, tween(250)) }
                                                launch { offsetAnimatable.animateTo(Offset.Zero, tween(250)) }
                                            }.invokeOnCompletion {
                                                scale = 1f
                                                offset = Offset.Zero
                                                isAnimating = false
                                            }
                                        } else {
                                            // Zoom in to crop
                                            val targetScale = cropScale.coerceIn(1f, 5f)
                                            val newOffset = Offset(
                                                x = (size.width / 2f - firstDownPosition.x) * (targetScale - 1f) / targetScale,
                                                y = (size.height / 2f - firstDownPosition.y) * (targetScale - 1f) / targetScale
                                            )
                                            val constrainedOffset = constrainOffset(newOffset, targetScale)

                                            scope.launch {
                                                isAnimating = true
                                                scaleAnimatable.snapTo(currentScaleVal)
                                                offsetAnimatable.snapTo(currentOffsetVal)
                                                launch { scaleAnimatable.animateTo(targetScale, tween(250)) }
                                                launch { offsetAnimatable.animateTo(constrainedOffset, tween(250)) }
                                            }.invokeOnCompletion {
                                                scale = targetScale
                                                offset = constrainedOffset
                                                isAnimating = false
                                            }
                                        }
                                        lastTapTime = 0L
                                        lastTapPosition = Offset.Zero
                                    } else {
                                        // First tap - schedule single tap
                                        lastTapTime = upTime
                                        lastTapPosition = firstDownPosition
                                        pendingSingleTapTime = upTime
                                        pendingSingleTap = true
                                    }
                                } else {
                                    lastTapTime = 0L
                                    lastTapPosition = Offset.Zero
                                    pendingSingleTap = false
                                }
                            }
                        }
                    } else if (onSingleTap != null && !showController) {
                        // Simple tap detection without zoom
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                val firstDownTime = System.currentTimeMillis()
                                var totalMovement = 0f

                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size == 1) {
                                        val change = event.changes.first()
                                        if (change.positionChanged()) {
                                            totalMovement += (change.position - change.previousPosition).getDistance()
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                val upTime = System.currentTimeMillis()
                                val gestureTime = upTime - firstDownTime
                                if (gestureTime < 300 && totalMovement < 30f) {
                                    onSingleTap()
                                }
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            // Determine content scale for thumbnail
            val thumbnailContentScale = when {
                enableZoom -> ContentScale.Fit
                scaleMode == VideoScaleMode.CROP -> ContentScale.Crop
                else -> ContentScale.Fit
            }

            if (isActive && videoUri != null) {
                // Use shared ExoPlayer instance with reference counting
                val exoPlayer = remember { SharedVideoPlayer.registerConsumer(context) }

                // Unregister when leaving composition
                DisposableEffect(Unit) {
                    onDispose {
                        SharedVideoPlayer.unregisterConsumer()
                    }
                }

                // Prepare video (without playing) when URI changes
                LaunchedEffect(videoUri) {
                    SharedVideoPlayer.prepareVideo(context, videoUri)
                }

                // Use PresentationState to track when video surface is ready
                val presentationState = rememberPresentationState(exoPlayer)

                // Start playback only when surface is ready (first frame can be rendered)
                // This ensures video starts from frame 0 when thumbnail is replaced
                LaunchedEffect(presentationState.coverSurface) {
                    if (!presentationState.coverSurface) {
                        SharedVideoPlayer.startPlayback()
                    }
                }

                // Use video dimensions from MediaMetadataRetriever (extracted earlier)
                // This is more reliable than presentationState.videoSizeDp when sharing ExoPlayer
                val hasValidDimensions = videoWidth > 0 && videoHeight > 0
                val playerVideoAspectRatio = if (hasValidDimensions) {
                    videoWidth.toFloat() / videoHeight.toFloat()
                } else {
                    16f / 9f // Default until dimensions available
                }

                // Calculate scale to maintain aspect ratio
                // CROP: scale up so video fills container completely (excess is clipped)
                // FIT: scale so video fits entirely within container (may have letterboxing)
                val aspectRatioScale = remember(containerSize, videoWidth, videoHeight, scaleMode, enableZoom) {
                    if (containerSize.width == 0 || containerSize.height == 0 || !hasValidDimensions) {
                        1f
                    } else {
                        val containerAspect = containerSize.width.toFloat() / containerSize.height.toFloat()

                        if (enableZoom || scaleMode == VideoScaleMode.FIT) {
                            // FIT mode: no additional scaling needed beyond aspectRatio modifier
                            1f
                        } else {
                            // CROP mode: scale up to fill container
                            if (playerVideoAspectRatio > containerAspect) {
                                // Video is wider - scale based on height to fill
                                playerVideoAspectRatio / containerAspect
                            } else {
                                // Video is taller - scale based on width to fill
                                containerAspect / playerVideoAspectRatio
                            }
                        }
                    }
                }

                // Only render PlayerSurface when dimensions are ready
                // SurfaceView renders on a separate hardware layer that can briefly show stretched
                // content before Compose overlays cover it, so we prevent this by not rendering at all
                if (hasValidDimensions) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        PlayerSurface(
                            player = exoPlayer,
                            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                            modifier = Modifier
                                .aspectRatio(playerVideoAspectRatio)
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = aspectRatioScale
                                    scaleY = aspectRatioScale
                                }
                        )

                        // Shutter overlay - show until surface is ready (first frame rendered)
                        if (presentationState.coverSurface) {
                            if (thumbnail != null) {
                                Image(
                                    bitmap = thumbnail!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = thumbnailContentScale
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black)
                                )
                            }
                        }
                    }
                } else {
                    // Show placeholder until dimensions are extracted
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = thumbnailContentScale
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        )
                    }
                }
            } else if (thumbnail != null) {
                // When inactive, show only the thumbnail
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = thumbnailContentScale
                )
            }
        }
    }
}

/**
 * Composable for fullscreen video playback with controls and close button.
 * Uses native Compose PlayerSurface with PresentationState for proper first-frame detection.
 *
 * @param videoUri The URI of the video to play
 * @param modifier Modifier for the player view
 * @param onDismiss Callback when user wants to dismiss
 */
@OptIn(UnstableApi::class)
@Composable
fun FullscreenVideoPlayer(
    videoUri: Uri?,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    val context = LocalContext.current

    // Video dimensions for aspect ratio calculations (same approach as VideoPlayer)
    var videoWidth by remember { mutableStateOf(0) }
    var videoHeight by remember { mutableStateOf(0) }

    // Extract video dimensions using MediaMetadataRetriever (consistent with VideoPlayer)
    LaunchedEffect(videoUri) {
        if (videoUri != null) {
            withContext(Dispatchers.IO) {
                // retriever 在外层声明，finally 里统一释放（异常路径同样不泄漏 native 资源）
                var retriever: MediaMetadataRetriever? = null
                try {
                    val r = MediaMetadataRetriever()
                    retriever = r
                    r.setDataSource(context, videoUri)
                    val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    videoWidth = width
                    videoHeight = height
                } catch (e: Exception) {
                    videoWidth = 0
                    videoHeight = 0
                } finally {
                    runCatching { retriever?.release() }
                }
            }
        } else {
            videoWidth = 0
            videoHeight = 0
        }
    }

    // Use shared ExoPlayer instance with reference counting
    val exoPlayer = remember { SharedVideoPlayer.registerConsumer(context) }

    // Unregister when leaving composition
    DisposableEffect(Unit) {
        onDispose {
            SharedVideoPlayer.unregisterConsumer()
        }
    }

    // Prepare video (without playing) when URI changes
    LaunchedEffect(videoUri) {
        if (videoUri != null) {
            SharedVideoPlayer.prepareVideo(context, videoUri)
        }
    }

    // Use PresentationState to track when video surface is ready
    val presentationState = rememberPresentationState(exoPlayer)

    // Start playback only when surface is ready (first frame can be rendered)
    // This ensures video starts from frame 0 when thumbnail is replaced
    LaunchedEffect(presentationState.coverSurface) {
        if (!presentationState.coverSurface) {
            SharedVideoPlayer.startPlayback()
        }
    }

    // Use video dimensions from MediaMetadataRetriever for consistent aspect ratio
    val hasValidDimensions = videoWidth > 0 && videoHeight > 0
    val videoAspectRatio = if (hasValidDimensions) {
        videoWidth.toFloat() / videoHeight.toFloat()
    } else {
        16f / 9f // Default aspect ratio
    }

    Box(modifier = modifier) {
        // Native Compose PlayerSurface with aspect ratio (FIT mode for fullscreen)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            PlayerSurface(
                player = exoPlayer,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier
                    .then(
                        if (hasValidDimensions) {
                            Modifier.aspectRatio(videoAspectRatio)
                        } else {
                            Modifier
                        }
                    )
                    .fillMaxSize()
            )
        }

        // Shutter overlay - controlled by PresentationState.coverSurface
        // Shows black background until first frame is rendered or dimensions not ready
        if (presentationState.coverSurface || !hasValidDimensions) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }

        // Close button
        if (onDismiss != null) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.content_description_close),
                    tint = Color.White
                )
            }
        }
    }
}
