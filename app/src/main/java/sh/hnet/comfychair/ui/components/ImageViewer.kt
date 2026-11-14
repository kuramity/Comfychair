package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Image viewer with constrained pinch-to-zoom and pan.
 * - Pinch to zoom between 1x and 5x
 * - Double-tap to toggle between fit (1x) and crop (fill screen) zoom
 * - Pan with boundary constraints (image always stays on screen)
 * - Single tap callback for UI toggle (with delay to detect double-tap)
 */
@Composable
fun ImageViewer(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    onSingleTap: () -> Unit = {},
    // 下滑脱离关闭的回调；null = 不启用脱离手势（如缩略图槽位等无"关闭"语义的场景）
    onClose: (() -> Unit)? = null
) {
    // Animatable values for smooth zoom transitions
    val scaleAnimatable = remember { Animatable(1f) }
    val offsetAnimatable = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()

    // 下滑脱离拖动：手势中的实时位移（px，向下为正）；回位时由 Animatable 接管写值，
    // isDismissSettling 是两者的数据源开关——graphicsLayer/drawBehind 里按它取值
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

    // Current values (used during gestures for immediate feedback)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var isAnimating by remember { mutableStateOf(false) }

    // Track last tap for double-tap detection (must persist across gestures)
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }

    // Pending single tap - we delay single tap action to check for double-tap
    var pendingSingleTap by remember { mutableStateOf(false) }
    var pendingSingleTapTime by remember { mutableStateOf(0L) }

    // Handle delayed single tap (wait to see if it becomes a double-tap)
    androidx.compose.runtime.LaunchedEffect(pendingSingleTap, pendingSingleTapTime) {
        if (pendingSingleTap) {
            kotlinx.coroutines.delay(300L) // Wait for potential second tap
            // If still pending after delay, it's a true single tap
            if (pendingSingleTap && System.currentTimeMillis() - pendingSingleTapTime >= 280L) {
                pendingSingleTap = false
                onSingleTap()
            }
        }
    }

    // Calculate the displayed image size based on Fit scaling
    val imageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
    val containerAspectRatio = if (containerSize.height > 0) {
        containerSize.width.toFloat() / containerSize.height.toFloat()
    } else 1f

    // Calculate scale needed to fill/crop the screen
    val cropScale = remember(containerSize, bitmap.width, bitmap.height) {
        if (containerSize.width == 0 || containerSize.height == 0) {
            1f
        } else {
            val scaleToFillWidth = containerSize.width.toFloat() / bitmap.width.toFloat()
            val scaleToFillHeight = containerSize.height.toFloat() / bitmap.height.toFloat()
            val fitScale = minOf(scaleToFillWidth, scaleToFillHeight)
            val cropScaleAbs = maxOf(scaleToFillWidth, scaleToFillHeight)
            // Return the ratio: how much to zoom from fit to crop
            cropScaleAbs / fitScale
        }
    }

    val displayedImageSize = remember(containerSize, bitmap.width, bitmap.height) {
        if (containerSize.width == 0 || containerSize.height == 0) {
            Size.Zero
        } else if (imageAspectRatio > containerAspectRatio) {
            // Image is wider - fit to width
            val width = containerSize.width.toFloat()
            val height = width / imageAspectRatio
            Size(width, height)
        } else {
            // Image is taller - fit to height
            val height = containerSize.height.toFloat()
            val width = height * imageAspectRatio
            Size(width, height)
        }
    }

    // Calculate constrained offset to keep image on screen
    fun constrainOffset(newOffset: Offset, currentScale: Float): Offset {
        if (displayedImageSize == Size.Zero) return Offset.Zero

        val scaledWidth = displayedImageSize.width * currentScale
        val scaledHeight = displayedImageSize.height * currentScale

        // Calculate how much the image exceeds the container
        val excessWidth = (scaledWidth - containerSize.width).coerceAtLeast(0f) / 2f
        val excessHeight = (scaledHeight - containerSize.height).coerceAtLeast(0f) / 2f

        return Offset(
            x = newOffset.x.coerceIn(-excessWidth, excessWidth),
            y = newOffset.y.coerceIn(-excessHeight, excessHeight)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 黑遮罩透明度随脱离拖动联动（与图片同一映射）。当前查看器是独立
            // 不透明 Activity，外层还有黑背景，联动暂无可见效果——保留是为
            // 未来改成透明容器时不用再动这里；现阶段视觉反馈来自图片本体淡出
            .drawBehind {
                val dismissY = if (isDismissSettling) dismissDragAnimatable.value else dismissDragY
                drawRect(
                    Color.Black.copy(
                        alpha = ViewerDismissGesture.dismissTransform(
                            dismissY, containerSize.height.toFloat()
                        ).alpha
                    )
                )
            }
            .onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            // 缩放时用高质量采样（默认的 Low 是双线性，放大后边缘会发毛）
            filterQuality = FilterQuality.High,
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
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val firstDownTime = System.currentTimeMillis()
                        val firstDownPosition = firstDown.position

                        // 新手势按下：若上一手势的回位动画还在跑，停掉并从当前动画值
                        // 无缝接管位移，避免图片先跳回原点再跟手
                        if (isDismissSettling) {
                            settleJob?.cancel()
                            isDismissSettling = false
                            dismissDragY = dismissDragAnimatable.value
                        }

                        var wasPanOrZoom = false
                        var pointerCount = 1
                        var totalMovement = 0f  // Track total finger movement

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

                            // Track if we ever had multiple pointers
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

                                    // Zoom toward centroid
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
                                // Single finger
                                val change = event.changes.first()
                                if (change.positionChanged()) {
                                    val movement = (change.position - change.previousPosition).getDistance()
                                    totalMovement += movement

                                    // Pan only when zoomed
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

                        // Handle taps after gesture ends
                        val upTime = System.currentTimeMillis()
                        val gestureTime = upTime - firstDownTime

                        // Only treat as tap if:
                        // - No pan/zoom occurred
                        // - Gesture was quick (< 300ms)
                        // - Single finger only
                        // - Total movement was minimal (< 30 pixels) - this prevents swipes from being taps
                        val maxTapMovement = 30f
                        val isTap = !wasPanOrZoom &&
                                    gestureTime < 300 &&
                                    pointerCount == 1 &&
                                    totalMovement < maxTapMovement

                        if (isTap) {
                            // This was a tap
                            val timeSinceLastTap = upTime - lastTapTime
                            val distanceFromLastTap = (firstDownPosition - lastTapPosition).getDistance()

                            if (lastTapTime > 0 && timeSinceLastTap < 300 && distanceFromLastTap < 100) {
                                // Double tap detected - cancel pending single tap and animate zoom
                                pendingSingleTap = false

                                val currentScale = scale
                                val currentOffset = offset

                                if (currentScale > 1.05f) {
                                    // Animate zoom out to 1x (fit to screen)
                                    scope.launch {
                                        isAnimating = true
                                        scaleAnimatable.snapTo(currentScale)
                                        offsetAnimatable.snapTo(currentOffset)
                                        // Run both animations in parallel
                                        launch { scaleAnimatable.animateTo(1f, tween(250)) }
                                        launch { offsetAnimatable.animateTo(Offset.Zero, tween(250)) }
                                    }.invokeOnCompletion {
                                        scale = 1f
                                        offset = Offset.Zero
                                        isAnimating = false
                                    }
                                } else {
                                    // Animate zoom in to crop scale (fill screen)
                                    val targetScale = cropScale.coerceIn(1f, 5f)
                                    val newOffset = Offset(
                                        x = (size.width / 2f - firstDownPosition.x) * (targetScale - 1f) / targetScale,
                                        y = (size.height / 2f - firstDownPosition.y) * (targetScale - 1f) / targetScale
                                    )
                                    val constrainedOffset = constrainOffset(newOffset, targetScale)

                                    scope.launch {
                                        isAnimating = true
                                        scaleAnimatable.snapTo(currentScale)
                                        offsetAnimatable.snapTo(currentOffset)
                                        // Run both animations in parallel
                                        launch { scaleAnimatable.animateTo(targetScale, tween(250)) }
                                        launch { offsetAnimatable.animateTo(constrainedOffset, tween(250)) }
                                    }.invokeOnCompletion {
                                        scale = targetScale
                                        offset = constrainedOffset
                                        isAnimating = false
                                    }
                                }
                                // Reset tap tracking after double-tap
                                lastTapTime = 0L
                                lastTapPosition = Offset.Zero
                            } else {
                                // First tap - record it and schedule delayed single tap action
                                lastTapTime = upTime
                                lastTapPosition = firstDownPosition
                                pendingSingleTapTime = upTime
                                pendingSingleTap = true
                            }
                        } else {
                            // Not a tap (was a pan/zoom/swipe) - reset tap tracking
                            lastTapTime = 0L
                            lastTapPosition = Offset.Zero
                            pendingSingleTap = false
                        }
                    }
                },
            contentScale = ContentScale.Fit
        )
    }
}
