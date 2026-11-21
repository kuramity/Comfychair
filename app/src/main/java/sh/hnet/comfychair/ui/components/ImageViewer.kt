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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    //
    // G6（任务书 12.2）：回调带**退出原因**。三条退出路径（下滑、双指收缩、系统返回）
    // 在宿主页 MediaViewerScreen.exitViewer(reason) 收敛成同一个清理入口，本组件不再
    // 各自决定"关哪几个标志位"——旧写法正是残影的来源（见 ViewerExitCleanup 的注释）。
    onViewerClose: ((ViewerExitReason) -> Unit)? = null,
    // G6：统一清理入口的注册表。本组件把"页内图层复位"注册进去，
    // 系统返回这条路径（不经过本组件的手势代码）也能清到页内状态；
    // 组件离开组合时注销，保证重复进出不累积注册
    exitRegistry: ViewerExitRegistry? = null,
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
    onPanEdgeFling: ((direction: Int) -> Unit)? = null,
    // ============================================================
    // G4（任务书 11.4）：队列模式竖滑快捷删除
    // ============================================================
    //
    // 三个参数全 null 时本组件行为与改动前完全一致（全屏下滑关闭链路原样保留）。
    //
    // **判定按手势起点锁定**（与并排 D3、捏合 R-B23a2 同一原则）：方向锁定的那一帧
    // 读一次 [swipeDeleteEnabled]，整段手势只服从一个语义——绝不允许"拖到一半
    // 上层档位进度变了，纵移从删除切回关闭"这种中途换轨（G3 病根同款）。
    //
    // 语义分流：
    // - 队列（并排）态纵向锁定 → 竖滑删除：跟手上行/下行对称，松手达阈值
    //   [ViewerSwipeDelete.resolveOutcome] → 进站动画 → [onSwipeDeleteCommit]；
    //   未达阈值 → 弹簧回位；
    // - 全屏态纵向锁定 → 既有下滑关闭（dismissDragY > 0 单向 + 飞回/淡出），
    //   删除链路完全不参与，G5/G6 已验收行为零变化。
    //
    // 轴间仲裁（实施要求 4）：删除手势激活期间 [onSwipeDeletePhaseChange] 上报 true，
    // 宿主页冻结横向越界消费与吸附归零；本组件纵向锁定后事件全部 consume，
    // pager 拿不到增量，横向吸附天然没有输入（与越界机制的 rawDrag 只在水平
    // 手势里累计，两段手势不会同时持有两轴）。
    //
    // @return true = 本次纵向手势走删除语义；false = 走既有下滑关闭
    swipeDeleteEnabled: (() -> Boolean)? = null,
    // 进站动画播完（= 图片已"甩进回收站"）时回调一次。宿主在这里落回收站条目、
    // 移除队列项；pager 因条目列表变化整段重建，本页手势状态随作用域销毁
    onSwipeDeleteCommit: (() -> Unit)? = null,
    // 删除手势相位上报（true = 激活 / false = 结束）：宿主页用它仲裁横向机制
    onSwipeDeletePhaseChange: ((Boolean) -> Unit)? = null
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
    // G6（任务书 12.2）：退出回调也走 rememberUpdatedState。pointerInput(Unit) 的闭包
    // 只在首次组合建一次，直接引用参数会把回调冻结在"第一次组合的那个 lambda"上——
    // 而宿主页的统一清理入口（MediaViewerScreen.exitViewer）是每次组合重建的局部函数
    val currentOnViewerClose by rememberUpdatedState(onViewerClose)
    // G4（任务书 11.4）：竖滑删除的三个接线同样走 rememberUpdatedState（同上理由：
    // 手势闭包只建一次，"当前是否队列态"这类每次组合都可能变的判定必须读最新值）
    val currentSwipeDeleteEnabled by rememberUpdatedState(swipeDeleteEnabled)
    val currentOnSwipeDeleteCommit by rememberUpdatedState(onSwipeDeleteCommit)
    val currentOnSwipeDeletePhaseChange by rememberUpdatedState(onSwipeDeletePhaseChange)

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

    // 容器尺寸（onSizeChanged 写入）。声明提到这里：下方 E5 的透出收回
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

    // E5（v4.16.14 修）：**手指一松就把透出作废**，由 120ms tween 从当前值平滑回到纯黑。
    //
    // 修复前的写法是：回弹期间用 snapshotFlow 订阅 dismissDragAnimatable 的位移，
    // 直到位移跌回 10% 阈值才把 maskRevealed 翻 false（见 ViewerDismissGesture.
    // dismissMaskTargetAlpha）。问题出在"回弹比淡出慢"这条时序：
    //   ① 手指还在拖时 maskRevealed = true，遮罩目标变成 0.2，开始 120ms 淡出；
    //   ② 用户在淡出还没走完时松手 → 回弹弹簧把位移从松手值送回 0，需要 200~400ms；
    //   ③ 这段时间里 maskRevealed **仍然是 true**，于是那条 tween 在手指抬起之后
    //      继续朝 0.2 补完 —— 图库截图（含各格缩略图）在松手后反而变得更清楚；
    //   ④ 位移掉到 10% 以下才翻 false，再花 120ms 淡回纯黑。
    // 观感就是"松手回弹的瞬间，屏幕边缘/底部露出的图库缩略图闪一下"：透出不是被
    // 收回，而是先补完再收回。画面里那一块"图片"就是图库截图里的缩略图 ——
    // 竖版/方图在 20:9 屏上 Fit 后上下各有黑边，底部那条黑边正对着图库最下面一排格子。
    //
    // 透出的语义是"继续下拉就能关闭"的提示，手指一松就没有意义：立刻作废才是对的。
    // 只改"何时开始收回"，tween 时长（120ms）和图片的回弹弹簧一点没动，
    // 所以不会出现"背景瞬变黑"的抽帧感 —— 遮罩永远从当前值连续过渡。
    LaunchedEffect(isDismissSettling) {
        if (isDismissSettling && currentFlyBackCellRect != null) {
            maskRevealed = ViewerDismissGesture.shouldRevealMask(
                displacement = dismissDragAnimatable.value,
                screenHeightPx = containerSize.height.toFloat(),
                // 已松手：不管此刻位移多深，透出一律收回
                gestureActive = false
            )
        }
    }

    // E4：飞回动画。isFlyBackAnimating = 松手达阈值后"图片从松手状态插值到格子矩形"
    // 的收尾在播；期间 graphicsLayer 因子完全由插值接管、新手势被丢弃，播完才 onClose
    var isFlyBackAnimating by remember { mutableStateOf(false) }
    val flyBackAnimatable = remember { Animatable(0f) }
    var flyBackStart by remember { mutableStateOf(FlyBackStartFactors(1f, 1f, 0f, 0f, 1f)) }
    // G6：飞回收尾协程句柄。统一清理要能取消它（任务书排查项 1 要求"退出路径的在途
    // 动画必须回收"，旧实现只在 completion 里读结果、没有取消入口）
    var flyBackJob by remember { mutableStateOf<Job?>(null) }

    // 双击缩放动画的在途协程句柄（G6：清理时取消，见 resetViewerLayers）。
    // 注意只取消、不改 isAnimating / scale / offset —— 退出快照要冻结在当前帧，
    // 把数据源开关翻回去会让图片在转场里突然跳回全屏尺寸（字段取舍见 ViewerExitCleanup.KEPT_FIELDS）
    var zoomJob by remember { mutableStateOf<Job?>(null) }

    // ============================================================
    // G4（任务书 11.4）：竖滑删除的页面状态
    // ============================================================
    // swipeDeleteActive：本次纵向手势**锁定为删除语义**（队列态下的纵向方向锁定帧
    // 置位，回位弹簧播完 / 进站动画结束才复位）。graphicsLayer 与 drawBehind 都按它
    // 分流视觉：true = ViewerSwipeDelete 双向映射 + 页内不画关闭遮罩；
    // false = 既有下滑关闭链路原样。锁定只发生在手势第一帧（见手势循环里的
    // gestureDeleteMode 局部量），中途上层档位变化不会翻转语义——与并排 D3
    // "按手势起点锁定"同一原则，杜绝"拖一半从删除切成关闭"的 G3 式竞争。
    var swipeDeleteActive by remember { mutableStateOf(false) }
    // 进站动画在途：与 isFlyBackAnimating 同职责（期间输入消费丢弃、动画独占位移写值）
    var isSwipeDeleteFlying by remember { mutableStateOf(false) }
    var swipeDeleteFlyJob by remember { mutableStateOf<Job?>(null) }
    // 进站动画的位移写值走 dismissDragAnimatable（isDismissSettling 当数据源开关，
    // 与回位弹簧同一取值口径），动画值由这条协程持有。
    // 甩速阈值 dp→px 的换算放在每次手势开始时（见 pointerInput 循环内），
    // 与既有 dismiss 阈值同一口径，旋转/密度变化后不失效。

    // 单击/双击判定所需的跨手势状态（G6：整组上移到清理函数之前声明，
    // 因为 resetViewerLayers 要把 pendingSingleTap 取消掉 —— 局部函数不允许前向引用）
    // Track last tap for double-tap detection (must persist across gestures)
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }

    // Pending single tap - we delay single tap action to check for double-tap
    var pendingSingleTap by remember { mutableStateOf(false) }
    var pendingSingleTapTime by remember { mutableStateOf(0L) }

    // 未达关闭阈值时的弹簧回位（位移/缩放/透明度都会随位移归零而复原）
    // 与飞回收尾（playFlyBackDismiss）定义在 displayedImageSize 之后：两者都要读
    // containerSize / displayedImageSize，Kotlin 局部函数不允许前向引用

    // G6（任务书 12.2）：双指收缩退出**不再自播内容层淡出动画**。
    //
    // 旧实现（C1/C2）在这里维护了第二条转场状态机：pinchCloseScale/AlphaAnimatable
    // 把内容缩小淡出 160ms（参数与窗口动画 viewer_fade_out_shrink 一字不差），
    // 播完才 onClose()；而收尾协程第一件事是 `isPinchClosing = false` —— 标志位一翻回，
    // 图片立刻恢复成完全不透明，finish() 取的窗口快照于是把这层图片**再淡一次**到
    // 宿主页上方：半透明的图片叠在提示词卡片与生成按钮上，就是报告里的残影（E4）。
    //
    // 现在三条退出路径（下滑、双指收缩、系统返回）共用同一段窗口转场，
    // 本组件只管"退出时把机制状态归位"，不再负责动画 —— 见 ViewerExitCleanup。
    var exitCommitted by remember { mutableStateOf(false) }

    /**
     * 页内图层的统一复位：字段集合就是 [ViewerExitCleanup.PAGE_FIELDS]，
     * **与退出原因无关**（下滑 / 双指收缩 / 系统返回三条路径执行的是同一份清理）。
     *
     * 只做两件事：① 取消在途动画协程（含被中断、被取消的收尾，任务书实施指令 2/3）；
     * ② 复位机制状态。刻意不改 scale / offset / dismissDrag / 档位进度 ——
     * 退出转场的窗口快照要延续松手那一刻的画面，理由见 KEPT_FIELDS。
     */
    val resetViewerLayers: (ViewerExitReason) -> Unit = { reason ->
        exitCommitted = true
        isFlyBackAnimating = false
        pendingSingleTap = false
        // G4：删除手势状态一并复位（宿主退出时若有在途进站动画，取消它并上报
        // 手势相位结束——宿主页的横向冻结标志不能带着 true 退出）
        swipeDeleteFlyJob?.cancel()
        swipeDeleteFlyJob = null
        if (swipeDeleteActive) {
            swipeDeleteActive = false
            currentOnSwipeDeletePhaseChange?.invoke(false)
        }
        zoomJob?.cancel()
        zoomJob = null
        settleJob?.cancel()
        settleJob = null
        flyBackJob?.cancel()
        flyBackJob = null
        if (BuildConfig.DEBUG) {
            Log.d("ViewerExit", "页内图层复位 reason=$reason")
        }
    }

    // G6：把复位函数注册给宿主页的统一清理入口。
    // 用 rememberUpdatedState 转发：注册的 lambda 实例保持稳定（注销/重复进出不会
    // 累积注册），而它每次调用都拿到**最新一次组合**的复位函数
    val latestResetViewerLayers by rememberUpdatedState(resetViewerLayers)
    DisposableEffect(exitRegistry) {
        val unregister = exitRegistry?.register { reason -> latestResetViewerLayers(reason) }
        onDispose { unregister?.invoke() }
    }

    /**
     * 页内向宿主页发起退出的唯一出口：本组件的两条收尾路径（下滑脱离 / 飞回落格、
     * 双指收缩）都只调用它，退出原因由调用点给出。
     *
     * 本函数不自己清图层 —— 清图层是宿主页 [ViewerExitRegistry.fire] 的职责，
     * 这样"系统返回"这条完全不经过本组件的路径执行的是同一份清理。
     */
    fun requestViewerExit(reason: ViewerExitReason) {
        if (exitCommitted) return
        exitCommitted = true
        if (BuildConfig.DEBUG) {
            Log.d("ViewerExit", "发起退出 reason=$reason flyBack=$isFlyBackAnimating")
        }
        currentOnViewerClose?.invoke(reason)
    }

    // Current values (used during gestures for immediate feedback)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isAnimating by remember { mutableStateOf(false) }

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
            // E5（v4.16.14 修）：透出在松手这一刻就作废——由监听 isDismissSettling 的
            // effect 立刻把 maskRevealed 翻 false，120ms tween 从当前值收回纯黑。
            // 修复前这里靠"位移跌回 10% 以下"才收回，导致淡出在手指抬起后还继续补完
            // （图库缩略图在回弹途中反而更清楚），就是松手瞬间闪一下的根因。
            val settleY = launch {
                dismissDragAnimatable.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 300f))
            }
            val settleX = launch {
                dismissDragAnimatableX.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 300f))
            }
            settleY.join()
            settleX.join()
            isDismissSettling = false
            // G4：删除手势的回位弹簧播完才算手势结束——此刻清激活标志并向宿主解除
            // 横向冻结（提前解除会让残余弹簧帧又撞回越界机制）
            if (swipeDeleteActive) {
                swipeDeleteActive = false
                currentOnSwipeDeletePhaseChange?.invoke(false)
            }
        }
    }

    /**
     * G4：进站动画。竖滑达删除阈值松手后，图片沿拖拽方向继续外推到视口外
     * （终点由 [ViewerSwipeDelete.flyTargetDy] 给出），缩放/透明/旋转沿用跟手同一
     * 映射外推到饱和值 —— 跟手段与动画段共用一个函数，衔接处不可能跳变。
     *
     * 播完才回调 [onSwipeDeleteCommit]（宿主页此时落回收站条目、移除队列项，
     * pager 因 itemsKey 变化整段重建，本页状态随组合销毁自然归位）。
     * 动画被取消（组件离开组合 / 统一清理）= 协程带 cause 结束：**不补发 commit**，
     * 与 playFlyBackDismiss 的取消语义一致（条目没进回收站就不该有后续撤销）。
     */
    fun playSwipeDeleteEnterBin() {
        if (isSwipeDeleteFlying) return
        val startDy = dismissDragY
        isSwipeDeleteFlying = true
        // 与 settleDismissBack 同一 scope（Main）：Animatable 依赖帧时钟，
        // 换 Default 线程会直接 "No frame clock in the context" 崩
        swipeDeleteFlyJob = scope.launch {
            val target = ViewerSwipeDelete.flyTargetDy(startDy, containerSize.height.toFloat())
            dismissDragAnimatable.snapTo(startDy)
            isDismissSettling = true
            dismissDragY = 0f
            dismissDragAnimatable.animateTo(
                target,
                tween(ViewerSwipeDelete.FLY_TO_TRASH_DURATION_MS, easing = FastOutSlowInEasing)
            )
            isDismissSettling = false
            isSwipeDeleteFlying = false
            swipeDeleteFlyJob = null
            // 手势相位于此刻解除：条目移除引发的 pager 重建紧随其后，
            // 冻结标志活过动画段、死在动画段末尾，不留残值
            swipeDeleteActive = false
            currentOnSwipeDeletePhaseChange?.invoke(false)
            currentOnSwipeDeleteCommit?.invoke()
        }
    }

    // E4：飞回收尾。松手达关闭阈值且本页启用透出时，不立即关闭——图片从松手瞬间
    // 的位置/尺寸（起始快照）边缩小边飞到目标格子矩形上，落格（尺寸 = 其他缩略图）
    // 后才退出查看器。前提被打破（放大态 / 并排态 / 格子缺失）一律退回旧链路
    fun playFlyBackDismiss() {
        if (isFlyBackAnimating || exitCommitted || currentOnViewerClose == null) return
        val cellRect = currentFlyBackCellRect
        val sideScale = currentSideBySideScale?.invoke() ?: 1f
        if (cellRect == null || scale != 1f || sideScale != 1f || displayedImageSize == Size.Zero) {
            // G6：退回旧链路时也必须走统一出口（原因：下滑脱离）
            requestViewerExit(ViewerExitReason.DISMISS_DRAG)
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
        flyBackJob = scope.launch {
            flyBackAnimatable.snapTo(0f)
            flyBackAnimatable.animateTo(
                1f,
                tween(FLY_BACK_DURATION_MS, easing = FastOutSlowInEasing)
            )
        }.also { job ->
            job.invokeOnCompletion { cause ->
                // cause 非空 = 协程被取消（动画期间组件离开组合 / 统一清理自己取消它）：
                // 与捏合收尾同款处理，不重复发起退出（清理已经在做同一份复位）
                if (cause == null) {
                    // E4 二次修复（v4.19.2）：**不要**在这里关 isFlyBackAnimating。
                    //
                    // 飞回动画结束时 flyBackAnimatable.value 已到 1f，graphicsLayer 的飞回分支
                    // 经 flyBackTargetFactors 插值出的正是「图片停在目标格子里」——那是正确的
                    // 最后一帧，与宿主页的窗口转场天然衔接。
                    //
                    // 一旦把它置 false，graphicsLayer 立刻落到 else 分支：scale = 1（全屏）、
                    // translation = 0、alpha = 1，在 requestViewerExit 真正销毁组件之前闪出一帧
                    // 「全屏极清晰的图」。这正是上一次修复（只清零 dismissDrag 而不改开关时机）
                    // 换成的新残影形态 —— 关这个开关的动作本身就是残影来源，与位移清不清零无关。
                    //
                    // 组件马上会被 requestViewerExit 销毁，标志位无需手工复位。
                    currentOnFlyBackCompleted?.invoke()
                    requestViewerExit(ViewerExitReason.DISMISS_DRAG)
                }
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
            // 底色/拖动遮罩（v4.16.8 N3/N4；G1 v4.16.11 收敛黑化责任）：分支判定收敛到
            // 纯函数 ViewerDismissGesture.resolveBackgroundBranch（判定表由 JVM 单测
            // ViewerBackgroundBranchTest 钉住），这里只按返回的枚举绘制——
            // 判定与绘制分离，改判定规则必挂测试，不存在"内联复制两处漂移"：
            // ① 进入页全屏：E1/E2 分段遮罩（拖动中淡到 0.2 透出图库）；
            // ② 脱离拖动中：旧线性映射遮罩；
            // ③ 全屏态其余情况：满宽满高黑——盖住透出链路背后的图库截图（N3/N4：
            //    非进入页 / 已翻页 / 加载中页整页透明就透图库）；
            // ④ 并排（队列）态：页内不画任何黑，背景黑化由容器（MediaViewerScreen
            //    根 Box 的 background）统一负责——固定宽度黑补丁（页宽 × 档位比例）
            //    已随 G1 拆除，露边区/无邻页端/越界新带都由容器那座纯黑承接。
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
                        dismissY = dismissY,
                        // G4：删除手势在途不画关闭遮罩（判定表见纯函数重载），
                        // 否则并排态下滑删除时页内黑块会盖住两侧露边邻图
                        inSwipeDelete = swipeDeleteActive
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
                    ViewerBackgroundBranch.CONTAINER_BLACK -> {
                        // 并排（队列）态：页内不画黑。两侧 15% 露边区、无邻页的一端、
                        // 横向越界跟手露出的新带，都由容器背景（纯黑）承接——
                        // G1 拆除的"档位宽度黑"不再参与任何遮挡。
                        Unit
                    }
                    ViewerBackgroundBranch.FULL_PAGE_BLACK -> {
                        // 全屏态满宽满高黑（N3/N4）：全屏没有邻页露边，满页黑不会盖住
                        // 任何邻图；它的唯一职责是盖住透出链路背后的图库截图。
                        // 并排态不铺满页：并排下页与页布局区域重叠（页盒只平移不缩放），
                        // 满页黑会重演"各吃 48px"互盖邻图的旧 bug——那正是并排态走
                        // 上一分支（容器负责）的原因。
                        // 全屏且 flyBackCellRect == null（非进入页 / 已翻页 / 邻页加载中）
                        // 与全屏进入页非拖动态都命中这里：翻页态下 pager 的两页并排排列、
                        // 不重叠（页宽 = 视口宽，靠 pager 自身滚动物理平移），满宽黑只盖
                        // 本页，不盖邻页。
                        drawRect(color = Color.Black)
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
                    // G4：删除手势激活时改用双向对称的删除视觉（上滑/下滑镜像，
                    // 缩放/透明/旋转共用 ViewerSwipeDelete.visuals 一个映射——
                    // 跟手段与进站动画段同一函数，衔接零跳变）
                    val deleteVisuals = if (swipeDeleteActive) {
                        ViewerSwipeDelete.visuals(dismissY, containerSize.height.toFloat())
                    } else {
                        null
                    }
                    val currentScale = if (isAnimating) scaleAnimatable.value else scale
                    val currentOffset = if (isAnimating) offsetAnimatable.value else offset
                    // E4 残影排查（临时日志，保留供用户验证时序）：只在 DEBUG 包生效，
                    // release 包 BuildConfig.DEBUG 为 false，编译器会整段裁掉，零开销。
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "FlyBackProbe",
                            "flyBack=$isFlyBackAnimating settling=$isDismissSettling " +
                                "dy=$dismissY dx=$dismissX scale=$currentScale alpha=${visuals.alpha}"
                        )
                    }
                    // G6（任务书 12.2）：这里**不再有**捏合关闭的内容层淡出因子。
                    // 旧实现把双指收缩退出做成两段动画：内容层先缩小淡出 160ms
                    // （pinchCloseScale/AlphaAnimatable），播完再把 isPinchClosing 翻回
                    // false —— 翻回的那一帧图片恢复完全不透明，紧接着 finish() 的窗口
                    // 转场把这层图片**再淡一次**到宿主页上方，就是报告里的半透明残影。
                    // 现在退出转场只有窗口层那一段，三条路径共用同一时序（动画时序统一），
                    // 内容层不再维护自己的退出状态机。
                    // 并排档位：上层给的"当前页占视口比例"（全屏 1f / 并排 0.7f / 临时档 0.55f）。
                    // 与缩放、脱离淡出因子并列相乘 —— 三种视觉叠加时互不覆盖
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
                            rotationZ = 0f
                        } else {
                            // 动画中格子矩形丢失（不应发生）：按松手视觉定格，
                            // 由统一退出出口 requestViewerExit 收尾，绝不把 NaN 渲染出来
                            scaleX = currentScale * visuals.scale
                            scaleY = currentScale * visuals.scale
                            translationX = currentOffset.x + dismissX
                            translationY = currentOffset.y + dismissY
                            alpha = visuals.alpha
                            rotationZ = 0f
                        }
                    } else {
                        // E4 二次修复兜底：已经提交退出（exitCommitted = true 由 requestViewerExit /
                        // resetViewerLayers 设置，且全仓没有任何地方把它设回 false）时，内容层一律不画。
                        // 这样即便某条路径又把 isFlyBackAnimating 翻回了 false（例如宿主页的退出清理
                        // resetViewerLayers），也绝不会再画出 scale=1 的全屏回落帧。
                        if (exitCommitted) {
                            alpha = 0f
                            // rotationZ 同样显式归零：graphicsLayer 属性块跨帧复用，
                            // 兜底分支也必须保持"每帧写全"的复位纪律
                            rotationZ = 0f
                        } else {
                            val effScaleFactor = deleteVisuals?.scale ?: visuals.scale
                            scaleX = currentScale * effScaleFactor * sideScale
                            scaleY = currentScale * effScaleFactor * sideScale
                            // N8：横向自由拖动位移叠加在平移上（透出程度仍只看 dismissY）。
                            // G4：删除模式下 dismissX 恒 0（updateDismissDrag 锁死横轴），
                            // 这里不需要分支——位移源头就是轴间仲裁本身
                            translationX = currentOffset.x + dismissX
                            translationY = currentOffset.y + dismissY
                            alpha = deleteVisuals?.alpha ?: visuals.alpha
                            // rotationZ 必须**每帧显式赋值**：graphicsLayer 的属性块跨帧复用，
                            // 不写会残留上一次删除手势的倾角（删除结束、日常拖动带歪图）
                            rotationZ = deleteVisuals?.rotationDeg ?: 0f
                        }
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
                        // G4：本手势纵向锁定时的语义（true = 队列竖滑删除 / false = 下滑关闭）。
                        // **只在方向锁定的那一帧读一次 swipeDeleteEnabled 判定**，整段手势不翻转
                        // （D3/R-B23a2 的"按手势起点锁定"原则：中途上层从队列切全屏、
                        //  或并排进度被别的动画改动，都不允许换掉已经跟手运动的语义）。
                        var gestureDeleteMode = false
                        var accumulatedDx = 0f
                        var accumulatedDy = 0f
                        val velocityTracker = VelocityTracker()
                        val directionThresholdPx = ViewerDismissGesture.DIRECTION_LOCK_THRESHOLD_DP.dp.toPx()
                        val velocityThresholdPx = ViewerDismissGesture.DISMISS_VELOCITY_DP_PER_SEC.dp.toPx()
                        // G4：删除甩速阈值同样按手势起点换算（PointerInputScope 自带 Density）
                        val swipeDeleteVelocityThresholdPx =
                            ViewerSwipeDelete.DELETE_VELOCITY_DP_PER_SEC.dp.toPx()

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
                            currentOnViewerClose != null &&
                            ViewerSideBySide.canEngageFromStart(scale)

                        // 向下全量跟手；向上按阻尼轻微跟手（明确区别于"上滑关闭"，松手弹回）。
                        // N8（v4.16.9）：横向自由拖动——累计 dx 乘以"下拉深度权重"（resolveFreeDragFactor），
                        // 前 10% 屏高锁纵向（权重 0），10%~20% 渐进放开，20% 后完全跟手。
                        // x 的口径是"累计 dx × 权重"而不是增量累计：权重渐变时位移连续无跳变，
                        // 手指往回拖时 x 自动反向，透出程度依旧只看 y（横向不影响遮罩）
                        fun updateDismissDrag(totalDy: Float, totalDx: Float) {
                            // G4：删除手势的纵移**双向对称跟手、横向锁死**——
                            // 上滑与下滑等价（相册式甩删），横向不引入自由位移，
                            // 这就是"竖向删除激活期间冻结横向机制"在本组件侧的一半；
                            // 另一半（越界消费与吸附归零）由宿主页按相位标志冻结。
                            if (swipeDeleteActive) {
                                dismissDragY = totalDy
                                dismissDragX = 0f
                                return
                            }
                            dismissDragY = if (totalDy > 0f) totalDy else totalDy * 0.2f
                            dismissDragX = totalDx * ViewerDismissGesture.resolveFreeDragFactor(
                                dismissDragY,
                                containerSize.height.toFloat(),
                                ViewerDismissGesture.FREE_DRAG_THRESHOLD_RATIO
                            )
                            // E2：拖动进度跨过 10% 时翻转遮罩目标（翻转由 LaunchedEffect
                            // 启动 120ms tween 过渡）；未启用透出时布尔恒 false 不产生过渡。
                            // 判据统一走纯函数 shouldRevealMask：除了 10% 位移阈值，
                            // 还要"手指仍按在屏上"——松手后的收回在下方 isDismissSettling
                            // 的 effect 里做（同一个判据，gestureActive = false）
                            if (currentFlyBackCellRect != null) {
                                maskRevealed = ViewerDismissGesture.shouldRevealMask(
                                    displacement = dismissDragY,
                                    screenHeightPx = containerSize.height.toFloat(),
                                    gestureActive = true
                                )
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
                            // G4：进站动画在途同理——220ms 内图片已经在"甩进回收站"的路上，
                            // 任何新输入都不能改变它的结局（松手、翻页、二次拖都不接）
                            if (isSwipeDeleteFlying) {
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            // Track if we ever had multiple pointers
                            if (currentPointerCount > pointerCount) {
                                pointerCount = currentPointerCount
                            }

                            if (currentPointerCount >= 2) {
                                // 捏合开始时脱离拖动还在进行：立即弹回，否则残留的
                                // translationY 会叠在捏合的 offset 计算上产生跳变。
                                // G4 补充：删除手势在途（swipeDeleteActive）时**不弹回**——
                                // 纵向锁定后本层已把单指事件全部 consume，正常到不了双指；
                                // 第二指落下属于异常输入（多指抖动），维持删除手势、
                                // 忽略本帧，绝不让半途的横向机制抢走纵向手势的位移状态
                                if (lockedDirection == ViewerPanDirection.VERTICAL &&
                                    dismissDragY != 0f && !swipeDeleteActive
                                ) {
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
                                    // 成退出意图（那一帧 zoom 可远小于阈值）。退出回调为 null 时
                                    // 不累计，行为与原来完全一致。判定源是手势起点快照而非
                                    // scaleBeforeZoom：起手已放大的手势永远不触发关闭（R-B23a2）
                                    // G6：已经发起退出（exitCommitted）时不再累计新意图
                                    if (currentOnViewerClose != null && !exitCommitted) {
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
                                    } else if (currentOnViewerClose != null) {
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
                                                // G4：锁定帧一次性判定本手势语义。
                                                // 删除链路只在**队列（并排）态**生效（宿主提供的
                                                // enabled lambda 内含 sideBySide 判定），全屏态
                                                // 返回 false → 走既有下滑关闭，G5/G6 行为零变化。
                                                gestureDeleteMode =
                                                    currentSwipeDeleteEnabled?.invoke() == true
                                                if (gestureDeleteMode) {
                                                    swipeDeleteActive = true
                                                    currentOnSwipeDeletePhaseChange?.invoke(true)
                                                }
                                                if (BuildConfig.DEBUG) {
                                                    Log.d(
                                                        "SwipeDelete",
                                                        "纵向锁定 删除模式=$gestureDeleteMode " +
                                                            "dy=$accumulatedDy dx=$accumulatedDx"
                                                    )
                                                }
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

                        // 脱离拖动松手：位移或向下甩速达标 → 退出查看器（走统一出口）；
                        // 否则弹簧回位。已经发起退出（exitCommitted）时不再触发。
                        // B-23b 第三批（E4）：达标时先走飞回收尾——本页启用透出则图片
                        // 边缩小边飞回格子、落格后才退出；未启用（playFlyBackDismiss
                        // 内部兜底）行为与旧版一致，直接走统一出口
                        if (lockedDirection == ViewerPanDirection.VERTICAL &&
                            currentOnViewerClose != null && !exitCommitted
                        ) {
                            val velocity = velocityTracker.calculateVelocity()
                            if (gestureDeleteMode) {
                                // G4：队列态竖滑删除的松手归宿（上滑/下滑对称判定，
                                // 阈值与几何见 ViewerSwipeDelete 纯函数；velocity.y 的
                                // 符号约定不影响判定——取 |vy|）
                                val outcome = ViewerSwipeDelete.resolveOutcome(
                                    dyPx = dismissDragY,
                                    screenHeightPx = size.height.toFloat(),
                                    velocityYPxPerSec = velocity.y,
                                    velocityThresholdPxPerSec = swipeDeleteVelocityThresholdPx
                                )
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        "SwipeDelete",
                                        "松手 dy=$dismissDragY vy=${velocity.y} -> $outcome"
                                    )
                                }
                                when (outcome) {
                                    SwipeDeleteOutcome.ENTER_TRASH -> playSwipeDeleteEnterBin()
                                    SwipeDeleteOutcome.CANCEL -> settleDismissBack()
                                }
                            } else {
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
                            }
                        } else if (wasSideBySidePinch) {
                            // 并排捏合松手（D1~D5）：把累计倍率交回上层吸附（进度 ≥ 0.5 → 并排，
                            // 否则回全屏）。开关关闭时走不到这里（并排回调为 null，未进并排路径）
                            currentOnSideBySideSettle?.invoke(pinchGestureScale)
                        } else if (pinchDismissIntent && currentOnViewerClose != null) {
                            // 捏合退出（C1，G6 改版）：走到这里必然是手势已结束——全部手指
                            // 抬起，或事件流被中断/取消（do-while 的退出条件同样成立）。
                            // 意图成立后**直接走统一出口**：不再在内容层自播一段淡出，
                            // 退出转场由宿主页与窗口层承担一次、也只承担一次。
                            // 旧写法（playPinchCloseDismiss）播完要把 isPinchClosing 翻回
                            // false，图片在那一帧恢复不透明，随后 finish() 的窗口快照
                            // 把这层图片再淡一次到宿主页上 = 报告里的半透明残影（E4）。
                            // requestViewerExit 自带 exitCommitted 防重入，被中断/取消时
                            // 也不会发起第二次退出（G6 实施指令 2）
                            requestViewerExit(ViewerExitReason.PINCH_IN)
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
                                        // G6：动画协程登记到 zoomJob，统一清理能取消它
                                        //（只取消、不改 isAnimating / scale / offset，
                                        //  退出快照因此冻结在当前帧，见 ViewerExitCleanup.KEPT_FIELDS）
                                        zoomJob = scope.launch {
                                            isAnimating = true
                                            scaleAnimatable.snapTo(currentScale)
                                            offsetAnimatable.snapTo(currentOffset)
                                            // Run both animations in parallel
                                            launch { scaleAnimatable.animateTo(1f, tween(250)) }
                                            launch { offsetAnimatable.animateTo(Offset.Zero, tween(250)) }
                                        }.also { job ->
                                            job.invokeOnCompletion {
                                                if (it == null) {
                                                    scale = 1f
                                                    offset = Offset.Zero
                                                    isAnimating = false
                                                }
                                            }
                                        }
                                    } else {
                                        // Animate zoom in to crop scale (fill screen)
                                        val targetScale = cropScale.coerceIn(1f, 5f)
                                        val newOffset = Offset(
                                            x = (size.width / 2f - firstDownPosition.x) * (targetScale - 1f) / targetScale,
                                            y = (size.height / 2f - firstDownPosition.y) * (targetScale - 1f) / targetScale
                                        )
                                        val constrainedOffset = constrainOffset(newOffset, targetScale)

                                        zoomJob = scope.launch {
                                            isAnimating = true
                                            scaleAnimatable.snapTo(currentScale)
                                            offsetAnimatable.snapTo(currentOffset)
                                            // Run both animations in parallel
                                            launch { scaleAnimatable.animateTo(targetScale, tween(250)) }
                                            launch { offsetAnimatable.animateTo(constrainedOffset, tween(250)) }
                                        }.also { job ->
                                            job.invokeOnCompletion {
                                                if (it == null) {
                                                    scale = targetScale
                                                    offset = constrainedOffset
                                                    isAnimating = false
                                                }
                                            }
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
