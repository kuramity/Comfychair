package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.util.Log
import sh.hnet.comfychair.BuildConfig
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 捏合关闭收尾（C2）：内容缩小到的目标缩放 */
private const val PINCH_CLOSE_TARGET_SCALE = 0.86f

/** 捏合关闭收尾时长（ms），与 Activity 过渡动画（viewer_fade_out_shrink）观感衔接 */
private const val PINCH_CLOSE_DURATION_MS = 160

/**
 * 下拉透出黑底遮罩的过渡时长（ms，B-23b 第三批 E2）：客户规格"约 120ms 快速淡到 0.2"。
 * 分段目标值由 ViewerDismissGesture.dismissMaskTargetAlpha 给出，这里只负责两态间的过渡
 */
private const val REVEAL_MASK_TRANSITION_MS = 120

/** 飞回动画时长（ms，E4）：落在派工单给的 200~260 区间内，与吸附/收尾动画观感一致 */
private const val FLY_BACK_DURATION_MS = 240

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
    onClose: (() -> Unit)? = null,
    // 并排预览三级手势（D1~D5，v4.16.2）：非 null 时未放大态的双指捏合交给并排进度，
    // 不再触发"缩小淡出关闭"。四个回调都由 MediaViewerScreen 在开关开启时传入；
    // ImageSlotViewer 等无关闭语义的调用方不传，行为与改动前完全一致
    // 手势起点回调：上层在这里抓取"本次捏合的起始进度"（ImageViewer 只上报自起点累计的倍率）
    onSideBySideStart: (() -> Unit)? = null,
    // 跟手回调：参数是本次手势自 1.0 起累计的缩放倍率（<1 合拢 / >1 张开）
    onSideBySide: ((gestureScale: Float) -> Unit)? = null,
    // 松手回调：上层用它做吸附档位判定（进度 → 并排 / 全屏）
    onSideBySideSettle: ((gestureScale: Float) -> Unit)? = null,
    // 并排态点击上报：参数是页内坐标（px）。上层只在并排激活（进度 > 0.5）时传非 null
    // —— 全屏态单击走既有 onSingleTap 链路
    onSideBySideTap: ((pageLocalX: Float, pageLocalY: Float) -> Unit)? = null,
    // 并排档位缩放：上层把"当前页应占视口比例"以 lambda 传进来（0.7 = 并排、0.55 = 临时缩小档）。
    // 用 lambda 而不是 Float：它在 graphicsLayer lambda 内被读取，捏合动画每帧只更新 layer
    // 属性、不触发本组件重组。null = 全屏（因子 1，视觉零变化）
    sideBySideScale: (() -> Float)? = null,
    // B-23b 第三批（E1~E5）：飞回目标格子矩形（window px，与页内坐标系一致——页
    // 从 window (0,0) 起铺满全屏）。非 null = 该页启用"下拉透出图库 + 位置飞回"：
    // 黑底遮罩改走分段映射（E2），松手达阈值播飞回（E4）；null = 行为与旧版完全一致
    flyBackCellRect: ViewerRect? = null,
    // 飞回动画完成后的通知（E4）：先于 onClose 调用，上层据此抑制 Activity 层的
    // 缩小淡出过渡（飞回已无缝落格，再淡出反而突兀）
    onFlyBackCompleted: (() -> Unit)? = null,
    // N6（v4.16.9，方案 A2）：放大态平移贴边后继续同向拖动过冲达阈值时回调翻页。
    // 参数是 pager 页方向：+1 = 下一张 / -1 = 上一张（手指向左拖 = 看右边 = 下一张）。
    // 为什么主动回调而不是"让位"（A1，不再 consume 事件）：设备实测 A1 无效——
    // Pager 的 scrollable 在手势早期的 touch slop 阶段就被本层 consume 打断，后续
    // 放行它也不再接手（N6Probe 取证：overscroll 三帧放行、页码纹丝不动）。null = 不启用
    onPanEdgeFling: ((direction: Int) -> Unit)? = null
) {
    // 手势事件在 pointerInput(Unit) 内消费闭包：回调参数要用 rememberUpdatedState
    // 转成稳定引用，重组更新后手势里读到的才是最新值（并排态激活/退出全靠它切换）
    val currentOnSideBySideStart by rememberUpdatedState(onSideBySideStart)
    val currentOnSideBySide by rememberUpdatedState(onSideBySide)
    val currentOnSideBySideSettle by rememberUpdatedState(onSideBySideSettle)
    val currentOnSideBySideTap by rememberUpdatedState(onSideBySideTap)
    val currentSideBySideScale by rememberUpdatedState(sideBySideScale)
    val currentFlyBackCellRect by rememberUpdatedState(flyBackCellRect)
    val currentOnFlyBackCompleted by rememberUpdatedState(onFlyBackCompleted)
    val currentOnPanEdgeFling by rememberUpdatedState(onPanEdgeFling)

    // Animatable values for smooth zoom transitions
    val scaleAnimatable = remember { Animatable(1f) }
    val offsetAnimatable = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()

    // 下滑脱离拖动：手势中的实时位移（px，向下为正）；回位时由 Animatable 接管写值，
    // isDismissSettling 是两者的数据源开关——graphicsLayer/drawBehind 里按它取值。
    // N8（v4.16.9）：mirror 一条 x 轴——下拉过阈值后横向也跟手（二维自由拖动），
    // y 轴口径不变（向下全量 / 向上 0.2 阻尼），透出程度依旧只看 y
    var dismissDragY by remember { mutableFloatStateOf(0f) }
    var dismissDragX by remember { mutableFloatStateOf(0f) }
    val dismissDragAnimatable = remember { Animatable(0f) }
    val dismissDragAnimatableX = remember { Animatable(0f) }
    var isDismissSettling by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    // 容器尺寸（onSizeChanged 写入）。声明提到这里：下方 E5 的 snapshotFlow 监听
    // 与飞回收尾都要读它，Kotlin 局部变量不允许前向引用
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // E1/E2：透出模式的黑底遮罩。maskRevealed = 拖动进度 ≥ 10%（分段目标 0.2），
    // 翻转由 120ms tween 过渡；reveal 未启用时恒 false、遮罩恒 1f（drawBehind 不读它）
    var maskRevealed by remember { mutableStateOf(false) }
    val maskAlphaAnimatable = remember { Animatable(1f) }
    LaunchedEffect(maskRevealed) {
        maskAlphaAnimatable.animateTo(
            if (maskRevealed) ViewerDismissGesture.REVEAL_MASK_ALPHA else 1f,
            tween(REVEAL_MASK_TRANSITION_MS, easing = FastOutSlowInEasing)
        )
    }

    // E5：回弹动画中监听进度跌回 10% 以下 → 遮罩恢复纯黑。用 snapshotFlow 订阅
    // Animatable 的每帧值（与拖动中的跨阈值检测同口径），不占用 animateTo 的 block
    LaunchedEffect(isDismissSettling) {
        if (isDismissSettling && currentFlyBackCellRect != null) {
            snapshotFlow { dismissDragAnimatable.value }.collect { value ->
                maskRevealed = ViewerDismissGesture.dismissMaskTargetAlpha(
                    value, containerSize.height.toFloat()
                ) < 1f
            }
        }
    }

    // E4：飞回动画。isFlyBackAnimating = 松手达阈值后"图片从松手状态插值到格子矩形"
    // 的收尾在播；期间 graphicsLayer 因子完全由插值接管、新手势被丢弃，播完才 onClose
    var isFlyBackAnimating by remember { mutableStateOf(false) }
    val flyBackAnimatable = remember { Animatable(0f) }
    var flyBackStart by remember { mutableStateOf(FlyBackStartFactors(1f, 1f, 0f, 0f, 1f)) }

    // 未达关闭阈值时的弹簧回位（位移/缩放/透明度都会随位移归零而复原）
    // 与飞回收尾（playFlyBackDismiss）定义在 displayedImageSize 之后：两者都要读
    // containerSize / displayedImageSize，Kotlin 局部函数不允许前向引用

    // 捏合关闭收尾（C2）：内容缩小淡出后再真正关闭。isPinchClosing 是防重入标志——
    // 动画播放期间第二次捏合或下滑不再触发关闭（否则会叠加两层退出）
    val pinchCloseScaleAnimatable = remember { Animatable(1f) }
    val pinchCloseAlphaAnimatable = remember { Animatable(1f) }
    var isPinchClosing by remember { mutableStateOf(false) }

    fun playPinchCloseDismiss() {
        // isFlyBackAnimating 互斥（B-23b 第三批）：飞回与捏合收尾是两条退出链路，
        // 任何一条在播时另一条都不得再启动——若两者都触发，以飞回为准
        if (isPinchClosing || isFlyBackAnimating || onClose == null) return
        isPinchClosing = true
        scope.launch {
            // 先同步基准值再启动，避免首帧跳变（与 settleDismissBack 同款处理）
            pinchCloseScaleAnimatable.snapTo(1f)
            pinchCloseAlphaAnimatable.snapTo(1f)
            launch {
                pinchCloseScaleAnimatable.animateTo(
                    PINCH_CLOSE_TARGET_SCALE,
                    tween(PINCH_CLOSE_DURATION_MS, easing = FastOutSlowInEasing)
                )
            }
            launch {
                pinchCloseAlphaAnimatable.animateTo(
                    0f,
                    tween(PINCH_CLOSE_DURATION_MS, easing = FastOutSlowInEasing)
                )
            }
        }.invokeOnCompletion { cause ->
            isPinchClosing = false
            // cause 非空 = 协程被取消（如动画期间翻页导致本组件离开组合）：
            // 只复位标志，不触发关闭——关闭必须由"动画正常播完"驱动
            if (cause == null) {
                onClose?.invoke()
            }
        }
    }

    // Current values (used during gestures for immediate feedback)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
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

    // 未达关闭阈值时的弹簧回位（位移/缩放/透明度都会随位移归零而复原）
    // N8（v4.16.9）：x 轴与 y 轴同参数并行回位，两轴都落定才退出回位状态——
    // 只等 y 的话 x 还在半途就被切回手势值，图片会带着横向残位移瞬跳
    fun settleDismissBack() {
        settleJob?.cancel()
        settleJob = scope.launch {
            // 先同步当前值再切数据源，避免切换瞬间渲染出一帧零位移
            dismissDragAnimatable.snapTo(dismissDragY)
            dismissDragAnimatableX.snapTo(dismissDragX)
            isDismissSettling = true
            dismissDragY = 0f
            dismissDragX = 0f
            // E5：回弹过程中遮罩恢复纯黑——由上方监听 isDismissSettling 的
            // snapshotFlow 按 Animatable 每帧值驱动 maskRevealed 翻转
            val settleY = launch {
                dismissDragAnimatable.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 300f))
            }
            val settleX = launch {
                dismissDragAnimatableX.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 300f))
            }
            settleY.join()
            settleX.join()
            isDismissSettling = false
        }
    }

    // E4：飞回收尾。松手达关闭阈值且本页启用透出时，不立即关闭——图片从松手瞬间
    // 的位置/尺寸（起始快照）边缩小边飞到目标格子矩形上，落格（尺寸 = 其他缩略图）
    // 后才回调 onClose。前提被打破（放大态 / 并排态 / 格子缺失）一律退回旧链路
    fun playFlyBackDismiss() {
        if (isFlyBackAnimating || isPinchClosing) return
        val cellRect = currentFlyBackCellRect
        val sideScale = currentSideBySideScale?.invoke() ?: 1f
        if (cellRect == null || scale != 1f || sideScale != 1f || displayedImageSize == Size.Zero) {
            onClose?.invoke()
            return
        }
        // 起始快照：松手瞬间的脱离拖动视觉（graphicsLayer 因子口径，见下方 graphicsLayer）。
        // N8（v4.16.9）：自由拖动后 x 也要进快照——否则图片先瞬跳回中轴再飞，观感断裂
        val startY = dismissDragY
        val startX = dismissDragX
        val visuals = ViewerDismissGesture.dismissTransform(startY, containerSize.height.toFloat())
        flyBackStart = FlyBackStartFactors(
            scaleX = visuals.scale,
            scaleY = visuals.scale,
            translationX = offset.x + startX,
            translationY = offset.y + startY,
            alpha = visuals.alpha
        )
        isFlyBackAnimating = true
        scope.launch {
            flyBackAnimatable.snapTo(0f)
            flyBackAnimatable.animateTo(
                1f,
                tween(FLY_BACK_DURATION_MS, easing = FastOutSlowInEasing)
            )
        }.invokeOnCompletion { cause ->
            // cause 非空 = 协程被取消（动画期间组件离开组合，如翻页/删除重组）：
            // 与捏合收尾同款处理，只复位标志、不触发关闭
            if (cause == null) {
                isFlyBackAnimating = false
                currentOnFlyBackCompleted?.invoke()
                onClose?.invoke()
            }
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
            // 底色/拖动遮罩（v4.16.8 N3/N4 收敛成三分支）：分支判定收敛到纯函数
            // ViewerDismissGesture.resolveBackgroundBranch（判定表由 JVM 单测
            // ViewerBackgroundBranchTest 钉住），这里只按返回的枚举绘制——
            // 判定与绘制分离，改判定规则必挂测试，不存在"内联复制两处漂移"：
            // ① 进入页全屏：E1/E2 分段遮罩（拖动中淡到 0.2 透出图库）；
            // ② 脱离拖动中：旧线性映射遮罩；
            // ③ 其余一律"档位宽度黑"：并排态宽度 = 页宽 × 档位比例（绝不盖露边的
            //    邻图）；全屏态且无飞回格子矩形（非进入页 / 已翻页 / 加载中）时
            //    sideScale = 1 → 满宽满高黑——旧版这类输入什么都不画、整页透明，
            //    翻页两侧与相邻页直接透出图库（N3/N4 的 bug）。
            // 旧版第四分支 pageBackground（整页不透明黑）在本版删除：分支③覆盖
            // 全屏态后它在任何输入下都不可达——背景从此只有这一处来源。
            // 单图槽位（ImageSlotViewer）不传 sideBySideScale / flyBackCellRect，
            // 走分支③满宽满高黑，行为与旧 pageBackground=true 完全一致。
            .drawBehind {
                val dismissY = if (isDismissSettling) dismissDragAnimatable.value else dismissDragY
                // 并排态（页被缩放平移）时分段遮罩会跟着页面几何走、透出的截图对不上，
                // 所以只有全屏态才走 E2 分段遮罩；并排态回退旧线性映射（D5"现有链路"）
                val inFullScreen = (currentSideBySideScale?.invoke() ?: 1f) == 1f
                when (
                    ViewerDismissGesture.resolveBackgroundBranch(
                        hasFlyBackCellRect = flyBackCellRect != null,
                        inFullScreen = inFullScreen,
                        dismissY = dismissY
                    )
                ) {
                    ViewerBackgroundBranch.FLYBACK_MASK -> {
                        // E1/E2：透出模式——遮罩恒画（初始 1f 把背景截图盖成纯黑），
                        // 拖动越过 10% 阈值后由 120ms tween 快速淡到 0.2（图库透出约 80%）
                        drawRect(Color.Black.copy(alpha = maskAlphaAnimatable.value))
                    }
                    ViewerBackgroundBranch.DISMISS_LINEAR -> {
                        drawRect(
                            Color.Black.copy(
                                alpha = ViewerDismissGesture.dismissTransform(
                                    dismissY, containerSize.height.toFloat()
                                ).alpha
                            )
                        )
                    }
                    ViewerBackgroundBranch.SIDE_OR_FULL_BLACK -> {
                        // 档位宽度黑（Q1-1，v4.16.5 引入；N3/N4，v4.16.8 放开到全屏无格子态）：
                        // 宽度 = 页宽 × 当前档位比例（全屏 1 / 并排 0.7 / 临时档 0.55），居中、满高。
                        // 为什么并排态不铺满页：并排下页与页布局区域重叠、右侧邻页画在上层，
                        // 满页黑会重演"各吃 48px"互盖邻图的旧 bug。档位宽度与图片的档位缩放由
                        // 同一个 sideScale 驱动，黑缘与露边邻图边缘几何上严格重合（黑缘 =
                        // 页心 ± 页宽×档位/2 = 邻图缘），动画全程既盖住本页透出的截图、又
                        // 绝不盖住两侧露出的邻页。松手弹回全屏时 sideScale → 1，黑宽同步
                        // 回满页，过渡连续。
                        // 全屏态且 flyBackCellRect == null（非进入页 / 已翻页 / 邻页加载中）
                        // 时 sideScale = 1 → 满宽满高黑：翻页态下 pager 的两页并排排列、
                        // 不重叠（页宽 = 视口宽，靠 pager 自身滚动物理平移，与并排态的
                        // graphicsLayer 收拢不同），满宽黑只盖本页，不盖邻页。
                        // 并排下拉（dismissY > 0）走上一分支的旧线性映射，透出图库的
                        // D5 行为保持不变
                        val sideScale = currentSideBySideScale?.invoke() ?: 1f
                        val blackWidth = size.width * sideScale
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset((size.width - blackWidth) * 0.5f, 0f),
                            size = Size(blackWidth, size.height)
                        )
                    }
                }
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
                    // N8（v4.16.9）：横向自由拖动位移，数据源开关与 y 同口径
                    val dismissX = if (isDismissSettling) dismissDragAnimatableX.value else dismissDragX
                    val visuals = ViewerDismissGesture.dismissTransform(
                        dismissY, containerSize.height.toFloat()
                    )
                    val currentScale = if (isAnimating) scaleAnimatable.value else scale
                    val currentOffset = if (isAnimating) offsetAnimatable.value else offset
                    // 捏合关闭收尾：整层缩小淡出（isPinchClosing 为 false 时因子恒 1，视觉零变化）
                    val pinchScale = if (isPinchClosing) pinchCloseScaleAnimatable.value else 1f
                    val pinchAlpha = if (isPinchClosing) pinchCloseAlphaAnimatable.value else 1f
                    // 并排档位：上层给的"当前页占视口比例"（全屏 1f / 并排 0.7f / 临时档 0.55f）。
                    // 与上面两个因子并列相乘 —— 三种视觉叠加时互不覆盖
                    val sideScale = currentSideBySideScale?.invoke() ?: 1f
                    if (isFlyBackAnimating) {
                        // E4：飞回——因子由"松手快照 → 目标格子矩形"的插值完全接管
                        //（不再叠加 dismissY / pinch / side，此刻它们全是松手瞬间的终值）
                        val cellRect = currentFlyBackCellRect
                        if (cellRect != null && containerSize.width > 0 && containerSize.height > 0) {
                            val target = ViewerDismissGesture.flyBackTargetFactors(
                                containerWidth = containerSize.width.toFloat(),
                                containerHeight = containerSize.height.toFloat(),
                                contentWidth = displayedImageSize.width,
                                contentHeight = displayedImageSize.height,
                                cellRect = cellRect
                            )
                            val f = ViewerDismissGesture.flyBackFactors(
                                start = flyBackStart,
                                target = target,
                                t = flyBackAnimatable.value
                            )
                            scaleX = f.scaleX
                            scaleY = f.scaleY
                            translationX = f.translationX
                            translationY = f.translationY
                            alpha = f.alpha
                        } else {
                            // 动画中格子矩形丢失（不应发生）：按松手视觉定格，
                            // 由 onClose 收尾，绝不把 NaN 渲染出来
                            scaleX = currentScale * visuals.scale
                            scaleY = currentScale * visuals.scale
                            translationX = currentOffset.x + dismissX
                            translationY = currentOffset.y + dismissY
                            alpha = visuals.alpha
                        }
                    } else {
                        scaleX = currentScale * visuals.scale * pinchScale * sideScale
                        scaleY = currentScale * visuals.scale * pinchScale * sideScale
                        // N8：横向自由拖动位移叠加在平移上（透出程度仍只看 dismissY）
                        translationX = currentOffset.x + dismissX
                        translationY = currentOffset.y + dismissY
                        alpha = visuals.alpha * pinchAlpha
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val firstDownTime = System.currentTimeMillis()
                        val firstDownPosition = firstDown.position

                        // 新手势按下：若上一手势的回位动画还在跑，停掉并从当前动画值
                        // 无缝接管位移，避免图片先跳回原点再跟手（N8：x 轴同口径接管）
                        if (isDismissSettling) {
                            settleJob?.cancel()
                            isDismissSettling = false
                            dismissDragY = dismissDragAnimatable.value
                            dismissDragX = dismissDragAnimatableX.value
                        }

                        var wasPanOrZoom = false
                        var pointerCount = 1
                        var totalMovement = 0f  // Track total finger movement

                        if (BuildConfig.DEBUG) {
                            Log.d("N6Probe", "down scale=$scale offset=$offset")
                        }

                        // scale==1 时的方向锁定（见单指分支）：本手势内判定一次后不再翻转
                        var lockedDirection = ViewerPanDirection.UNDECIDED
                        var accumulatedDx = 0f
                        var accumulatedDy = 0f
                        val velocityTracker = VelocityTracker()
                        val directionThresholdPx = ViewerDismissGesture.DIRECTION_LOCK_THRESHOLD_DP.dp.toPx()
                        val velocityThresholdPx = ViewerDismissGesture.DISMISS_VELOCITY_DP_PER_SEC.dp.toPx()

                        // 捏合退出（C1）：本手势累计的双指缩小倍率（<1 为缩小）与退出意图。
                        // 每帧重新判定而非 once-true 锁死——捏过头又张开时意图自动取消
                        var pinchGestureScale = 1f
                        var pinchDismissIntent = false

                        // N6（A2，v4.16.9）：放大态贴边过冲累计量与"本次手势已触发翻页"标志。
                        // 累计在拖动方向反转时清零（先右后左的抖动不攒假过冲）；触发一次后
                        // 本手势内不再触发——连翻靠抬手再滑，避免一次长拖连翻数页
                        var edgeOverscrollAccum = 0f
                        var edgeFlingConsumed = false

                        // 捏合退出的起点锁定（R-B23a2）：按下瞬间的缩放快照，整段手势不变。
                        // 旧实现逐帧喂 scaleBeforeZoom 有误判：双击放大到 2x 后一口气捏合，
                        // 缩回 1f 的后续帧会被当成"未放大"继续累计 zoom，越过 0.92 阈值
                        // 误触关闭。与下方 sideBySideAllowed 同思路：起点定一次，手势结束
                        // 随本作用域销毁自动复位，下一次手势重新判定
                        val scaleAtGestureStart = scale

                        // 双指意图锁定（Q1-2，v4.16.5）：按下瞬间的并排进度快照（0 = 全屏，
                        // 由档位比例反解），与第一帧非恒等 zoom 的方向一起决定本段手势走
                        // "放大"还是"并排"。锁定结果存于下方 pinchIntent，手势结束随作用域销毁
                        val sideProgressAtStart = ViewerSideBySide.progressFromPageFraction(
                            currentSideBySideScale?.invoke() ?: 1f
                        )
                        var pinchIntent = ViewerSideBySide.PinchIntent.UNDECIDED

                        // 并排捏合（D1~D5）：本手势是否走了并排路径（未放大态 + 上层已开启）。
                        // 松手时按它分流：走了并排 → 上层吸附；没走 → 既有退出意图判定
                        var wasSideBySidePinch = false

                        // 本手势是否允许进并排：**按手势起点锁定**，不是每帧重算。
                        // 若每帧按当前 scale 判，从"已放大"开始捏合时，缩放缩回 1f 的那一帧
                        // 起同一根手指的剩余动作会中途切成并排（实测：双击放大后合拢，
                        // 图片先缩回 Fit 又继续缩成 70% 并排，手感像是被"劫持"）。
                        // 规格要求已放大时整段手势只做 zoom，所以这里一次定死。
                        val sideBySideAllowed = currentOnSideBySide != null &&
                            onClose != null &&
                            ViewerSideBySide.canEngageFromStart(scale)

                        // 向下全量跟手；向上按阻尼轻微跟手（明确区别于"上滑关闭"，松手弹回）。
                        // N8（v4.16.9）：横向自由拖动——累计 dx 乘以"下拉深度权重"（resolveFreeDragFactor），
                        // 前 10% 屏高锁纵向（权重 0），10%~20% 渐进放开，20% 后完全跟手。
                        // x 的口径是"累计 dx × 权重"而不是增量累计：权重渐变时位移连续无跳变，
                        // 手指往回拖时 x 自动反向，透出程度依旧只看 y（横向不影响遮罩）
                        fun updateDismissDrag(totalDy: Float, totalDx: Float) {
                            dismissDragY = if (totalDy > 0f) totalDy else totalDy * 0.2f
                            dismissDragX = totalDx * ViewerDismissGesture.resolveFreeDragFactor(
                                dismissDragY,
                                containerSize.height.toFloat(),
                                ViewerDismissGesture.FREE_DRAG_THRESHOLD_RATIO
                            )
                            // E2：拖动进度跨过 10% 时翻转遮罩目标（翻转由 LaunchedEffect
                            // 启动 120ms tween 过渡）；未启用透出时布尔恒 false 不产生过渡
                            if (currentFlyBackCellRect != null) {
                                maskRevealed = ViewerDismissGesture.dismissMaskTargetAlpha(
                                    dismissDragY, containerSize.height.toFloat()
                                ) < 1f
                            }
                        }

                        do {
                            val event = awaitPointerEvent()
                            val currentPointerCount = event.changes.size

                            // E4：飞回动画期间（<250ms 且马上关闭）输入一律消费丢弃，
                            // 不再开新手势，避免动画被后续指针事件中途打断产生跳变
                            if (isFlyBackAnimating) {
                                event.changes.forEach { it.consume() }
                                continue
                            }

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
                                // 本帧更新前的缩放：累计重置判定要用它——从放大缩回 1f 的
                                // 那一帧起才开始累计退出倍率（放大帧的 >1 zoom 不计入）
                                val scaleBeforeZoom = scale

                                // 并排捏合接管（D1~D5）：手势起点未放大且上层开启三级手势时，
                                // 双指合拢交给并排进度（上层换算成 pager 露边），本组件
                                // 不进 scale 计算、不触发关闭。已放大态整段手势保持既有 zoom/pan 语义。
                                // Q1-2（v4.16.5）：接管前先做意图锁定——全屏起点第一帧张开
                                // （zoom > 1）判为 ZOOM_IN，整段手势走下方既有放大路径
                                // （v4.16.2 曾把这种张开也当并排进度上报，负进度被钳回 0 且
                                // 事件被 consume，双指放大从此不可达）；合拢判为 SIDE_BY_SIDE，
                                // 沿用 v4.16.2 语义。判定只在 UNDECIDED 时做一次，整段不翻转
                                if (zoom != 1f && pinchIntent == ViewerSideBySide.PinchIntent.UNDECIDED) {
                                    pinchIntent = ViewerSideBySide.resolvePinchIntent(
                                        progressAtStart = sideProgressAtStart,
                                        zoom = zoom,
                                        currentIntent = pinchIntent
                                    )
                                }
                                val sideBySideHandler = currentOnSideBySide
                                if (sideBySideAllowed && sideBySideHandler != null &&
                                    pinchIntent != ViewerSideBySide.PinchIntent.ZOOM_IN
                                ) {
                                    if (zoom != 1f) {
                                        wasPanOrZoom = true
                                        // 手势起点：只在本次手势的第一帧通知一次，上层据此
                                        // 抓取"起始进度"（从全屏 0 捏、从并排 1 继续捏，
                                        // 或从吸附动画中途被打断的中间值）
                                        if (!wasSideBySidePinch) {
                                            currentOnSideBySideStart?.invoke()
                                        }
                                        wasSideBySidePinch = true
                                        pinchGestureScale = (pinchGestureScale * zoom).coerceIn(
                                            ViewerSideBySide.GESTURE_SCALE_MIN,
                                            ViewerSideBySide.GESTURE_SCALE_MAX
                                        )
                                        sideBySideHandler(pinchGestureScale)
                                    }
                                    // 双指事件全部在本层消费：不给 pager 机会把双指当翻页
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (zoom != 1f || pan != Offset.Zero) {
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
                                    if (BuildConfig.DEBUG) {
                                        Log.d("N6Probe", "zoom z=$zoom scale->$newScale intent=$pinchIntent")
                                    }

                                    // 捏合退出意图（C1）：未放大态才累计缩小量；放大态交给既有
                                    // "缩回 1f"路径并重置累计——否则缩回 1f 的最后一帧会被误记
                                    // 成退出意图（那一帧 zoom 可远小于阈值）。onClose == null 时
                                    // 不累计，行为与原来完全一致。判定源是手势起点快照而非
                                    // scaleBeforeZoom：起手已放大的手势永远不触发关闭（R-B23a2）
                                    if (onClose != null && !isPinchClosing) {
                                        if (scaleBeforeZoom > ViewerDismissGesture.PINCH_ZOOMED_THRESHOLD) {
                                            pinchGestureScale = 1f
                                        } else {
                                            pinchGestureScale *= zoom
                                        }
                                        pinchDismissIntent = ViewerDismissGesture.shouldDismissByPinch(
                                            scaleAtGestureStart = scaleAtGestureStart,
                                            gestureScale = pinchGestureScale,
                                            pointerCount = pointerCount
                                        )
                                    }
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
                                            // N6（v4.16.9，方案 A2）：贴边后继续同向拖的过冲累计。
                                            // horizontalEdgeOverscrollPan 在"未贴边 / 方向指回屏内"
                                            // 时返回 0——后者同时把累计清零，防抖动手势攒假过冲。
                                            // 单位换算：pan 来自指针事件的本地坐标，graphicsLayer 的
                                            // scale 变换使它 = 手指屏幕位移 / scale（设备实测 155px
                                            // 手指在 5x 下只上报 ~31px 本地位移），累计前乘回 scale
                                            // 归一到屏幕 px，与下方阈值（视口宽比例 = 屏幕 px）同单位。
                                            // 过冲达到视口宽的 5% 即回调上层翻页（A1 让位实测无效，
                                            // 见参数注释）；回调后本手势防重，事件仍照常 consume
                                            //（翻页动画已由 pager 接管，后续拖动不再影响它）
                                            val scaledWidth = displayedImageSize.width * scale
                                            val excessWidth =
                                                (scaledWidth - containerSize.width).coerceAtLeast(0f) / 2f
                                            val overscrollPan = ViewerDismissGesture
                                                .horizontalEdgeOverscrollPan(
                                                    offset.x, excessWidth, pan.x,
                                                    ViewerDismissGesture.EDGE_TOLERANCE_PX
                                                )
                                            val edgeFlingHandler = currentOnPanEdgeFling
                                            if (overscrollPan != 0f && edgeFlingHandler != null && !edgeFlingConsumed) {
                                                edgeOverscrollAccum += overscrollPan * scale
                                                val flingThresholdPx =
                                                    size.width * ViewerDismissGesture.EDGE_FLING_OVERSCROLL_RATIO
                                                if (abs(edgeOverscrollAccum) >= flingThresholdPx) {
                                                    edgeFlingConsumed = true
                                                    // 方向换算：过冲带符号（正 = 手指向右推右界 = 看左边
                                                    // = 上一张），翻页参数是 pager 方向，取相反数
                                                    val pageDirection = if (edgeOverscrollAccum > 0f) -1 else 1
                                                    edgeFlingHandler(pageDirection)
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d(
                                                            "N6Probe",
                                                            "edgeFling dir=$pageDirection accum=$edgeOverscrollAccum"
                                                        )
                                                    }
                                                }
                                            } else if (overscrollPan == 0f) {
                                                edgeOverscrollAccum = 0f
                                            }
                                            if (BuildConfig.DEBUG) {
                                                Log.d(
                                                    "N6Probe",
                                                    "pan scale=$scale pan=$pan offset=$offset " +
                                                        "excess=$excessWidth overscroll=$overscrollPan"
                                                )
                                            }
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
                                            if (BuildConfig.DEBUG) {
                                                Log.d(
                                                    "N6Probe",
                                                    "dir locked=$lockedDirection dx=$accumulatedDx dy=$accumulatedDy"
                                                )
                                            }
                                            if (lockedDirection == ViewerPanDirection.VERTICAL) {
                                                // 锁定即脱离拖动：标记非 tap，位移由累计值驱动
                                                wasPanOrZoom = true
                                            }
                                        }
                                        if (lockedDirection == ViewerPanDirection.VERTICAL) {
                                            // accumulatedDy 自按下累计（锁定帧已含本帧增量），
                                            // 直接以累计值驱动位移，跟手无跳变。
                                            // N8（v4.16.9）：accumulatedDx 一起传入——是否生效由
                                            // resolveFreeDragFactor 按下拉深度决定（前 10% 锁纵向）
                                            updateDismissDrag(accumulatedDy, accumulatedDx)
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
                        // 否则弹簧回位。isPinchClosing 期间不再触发（捏合收尾动画还在播）。
                        // B-23b 第三批（E4）：达标时先走飞回收尾——本页启用透出则图片
                        // 边缩小边飞回格子、落格后才 onClose；未启用（playFlyBackDismiss
                        // 内部兜底）行为与旧版一致，直接 onClose
                        if (lockedDirection == ViewerPanDirection.VERTICAL && onClose != null && !isPinchClosing) {
                            val velocity = velocityTracker.calculateVelocity()
                            // N8（v4.16.9）：归宿判定换成二维版——横向甩速超阈值同样判关闭，
                            // 其余与原 shouldDismiss 同口径；未达关闭的一律双轴弹簧回全屏
                            val outcome = ViewerDismissGesture.resolveDismissOutcome(
                                dy = dismissDragY,
                                dx = dismissDragX,
                                velocityYDownPxPerSec = velocity.y,
                                velocityXPxPerSec = velocity.x,
                                screenHeightPx = size.height.toFloat(),
                                screenWidthPx = size.width.toFloat(),
                                velocityThresholdPxPerSec = velocityThresholdPx
                            )
                            if (outcome == DismissOutcome.FLY_BACK) {
                                playFlyBackDismiss()
                            } else {
                                settleDismissBack()
                            }
                        } else if (wasSideBySidePinch) {
                            // 并排捏合松手（D1~D5）：把累计倍率交回上层吸附（进度 ≥ 0.5 → 并排，
                            // 否则回全屏）。开关关闭时走不到这里（并排回调为 null，未进并排路径）
                            currentOnSideBySideSettle?.invoke(pinchGestureScale)
                        } else if (pinchDismissIntent && onClose != null && !isPinchClosing) {
                            // 捏合退出（C1）：走到这里必然是全部手指已抬起（do-while 退出条件），
                            // 意图成立后先播收尾动画（C2），动画结束回调里才真正 onClose
                            playPinchCloseDismiss()
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
                            val sideBySideTap = currentOnSideBySideTap
                            if (sideBySideTap != null) {
                                // 并排态点击（D4"点哪张图哪张全屏"）：立即上报页内坐标，
                                // 不走单双击延迟——放大回全屏是大动画，迟 300ms 会显得不跟手
                                lastTapTime = 0L
                                lastTapPosition = Offset.Zero
                                pendingSingleTap = false
                                sideBySideTap(firstDownPosition.x, firstDownPosition.y)
                            } else {
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
