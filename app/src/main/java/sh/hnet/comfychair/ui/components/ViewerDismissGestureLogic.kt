package sh.hnet.comfychair.ui.components

import kotlin.math.abs

/**
 * 下滑脱离手势的纯判定逻辑。
 *
 * 为什么抽成纯函数：手势流程本身无法在 JVM 单测里跑，但「方向怎么锁定、
 * 松手该不该关闭、位移映射成什么视觉」这些决策规则可以精确覆盖边界——
 * UI 层只负责把指针事件喂给这里，保证两边行为一致。
 */
enum class ViewerPanDirection { UNDECIDED, HORIZONTAL, VERTICAL }

/**
 * N8（v4.16.9）：二维自由拖动松手后的归宿。
 * - [FLY_BACK]：关闭查看器并飞回图库格子（原 playFlyBackDismiss 链路）；
 * - [SPRING_BACK]：不关闭，位移双轴弹簧回 0（回全屏，原 settleDismissBack 链路）。
 */
enum class DismissOutcome { FLY_BACK, SPRING_BACK }

/** 脱离拖动某个位移量对应的视觉状态（作用于图片层，graphicsLayer 消费） */
data class DismissVisuals(val scale: Float, val alpha: Float)

/**
 * 飞回几何用的轻量矩形（px，B-23b 第三批 E4）。
 *
 * 刻意不用 compose 的 Rect：纯函数模块保持零 compose 依赖，JVM 单测
 * 无需 Android / compose 类路径就能跑。
 */
data class ViewerRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

/**
 * 飞回动画的 graphicsLayer 因子（E4，作用于图片层）。
 *
 * scaleX/scaleY 分开：格子是正方形而 Fit 图片不一定是，落格瞬间按 X/Y
 * 各自对齐格子（轻微变形与缩略图的 Crop 语义一致，随后即被真实缩略图接管）。
 */
data class FlyBackFactors(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
    val alpha: Float
)

/** 飞回起始因子快照（松手瞬间的脱离拖动视觉，与 [FlyBackFactors] 同口径） */
data class FlyBackStartFactors(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
    val alpha: Float
)

object ViewerDismissGesture {

    /** 松手关闭阈值一：位移超过屏高的 22% */
    const val DISMISS_DISTANCE_RATIO = 0.22f

    /** 松手关闭阈值二：向下甩速超过 900dp/s（px 换算由调用处按屏幕密度完成） */
    const val DISMISS_VELOCITY_DP_PER_SEC = 900f

    /** 方向锁定阈值：累计位移超过 8dp 才判定主方向，避免轻点被误判成拖动 */
    const val DIRECTION_LOCK_THRESHOLD_DP = 8f

    /** 斜向偏置：只有 |dy| 明显大于 |dx|（>1.2 倍）才算垂直，斜向一律让给 Pager 翻页 */
    const val VERTICAL_BIAS = 1.2f

    /** 捏合返回：未放大态下双指缩小到该比例以下即视为"想退出" */
    const val PINCH_DISMISS_SCALE = 0.92f

    /** 已放大门槛：页面缩放超过它时捏合只负责"缩回 1f"（既有 coerceIn 路径），不触发退出 */
    const val PINCH_ZOOMED_THRESHOLD = 1.05f

    /** 脱离拖动最多缩小 35%（下限 0.65） */
    private const val DISMISS_MAX_SCALE_REDUCTION = 0.35f
    private const val MIN_DISMISS_SCALE = 0.65f

    /** 脱离拖动最多降低 80% 不透明度（下限 0.2），图片淡出暗示"可以松手关闭" */
    private const val DISMISS_MAX_ALPHA_REDUCTION = 0.8f
    private const val MIN_DISMISS_ALPHA = 0.2f

    /**
     * 方向锁定：根据自按下起的累计位移判定主方向。
     *
     * 手势内一旦锁定就不再翻转（调用方持有锁定结果），所以这里只做单次判定：
     * - 未过阈值 → UNDECIDED（两轴都先不处理，等下一帧）
     * - |dy| > |dx| * 1.2 → VERTICAL（进入脱离拖动）
     * - 其余（含 |dy| < |dx| * 1.2 的斜向）→ HORIZONTAL（不 consume，让 Pager 翻页）
     */
    fun resolvePanDirection(
        accumulatedDx: Float,
        accumulatedDy: Float,
        thresholdPx: Float
    ): ViewerPanDirection {
        val maxMove = maxOf(abs(accumulatedDx), abs(accumulatedDy))
        if (maxMove < thresholdPx) return ViewerPanDirection.UNDECIDED
        return if (abs(accumulatedDy) > abs(accumulatedDx) * VERTICAL_BIAS) {
            ViewerPanDirection.VERTICAL
        } else {
            ViewerPanDirection.HORIZONTAL
        }
    }

    /**
     * 松手时是否应关闭查看器：位移达标（dy > 屏高 * 22%）或向下甩速达标（> 阈值），
     * 任一满足即可关闭；两个都差一点则弹回。
     *
     * velocityThresholdPxPerSec 由调用处按密度把 DISMISS_VELOCITY_DP_PER_SEC 换算成 px，
     * 纯函数不做密度换算（JVM 侧无法模拟 Density）。
     */
    fun shouldDismiss(
        dy: Float,
        screenHeightPx: Float,
        velocityYDownPxPerSec: Float,
        velocityThresholdPxPerSec: Float
    ): Boolean {
        val distanceExceeded = dy > screenHeightPx * DISMISS_DISTANCE_RATIO
        val velocityExceeded = velocityYDownPxPerSec > velocityThresholdPxPerSec
        return distanceExceeded || velocityExceeded
    }

    /**
     * 位移 → 视觉状态映射（仅向下 dy > 0 进入脱离模式时调用）。
     * 越往下滑图片越小、越透明，两端 clamp 防止缩没了或完全消失。
     */
    fun dismissTransform(dy: Float, screenHeightPx: Float): DismissVisuals {
        // dy <= 0（未进入脱离，或向上拖）与屏高未测得时都保持原样；
        // 同时挡住除零——NaN 会穿透 coerceIn 直接污染 graphicsLayer
        if (dy <= 0f || screenHeightPx <= 0f) return DismissVisuals(1f, 1f)
        val progress = (dy / screenHeightPx).coerceIn(0f, 1f)
        val scale = (1f - progress * DISMISS_MAX_SCALE_REDUCTION).coerceAtLeast(MIN_DISMISS_SCALE)
        val alpha = (1f - progress * DISMISS_MAX_ALPHA_REDUCTION).coerceAtLeast(MIN_DISMISS_ALPHA)
        return DismissVisuals(scale, alpha)
    }

    // ============================================================
    // 下拉透出图库 + 位置飞回（B-23b 第三批，E1~E5）
    // ============================================================

    /** E2：透出触发阈值——下拉偏离原位达屏高的 10% */
    const val REVEAL_PROGRESS_RATIO = 0.10f

    /** E2：透出后黑底遮罩的目标 alpha（0.2 = 图库透出约 80%） */
    const val REVEAL_MASK_ALPHA = 0.2f

    /**
     * E2：拖动位移 → 黑底遮罩目标 alpha 的分段映射。
     *
     * 进度 < 10% → 1.0（纯黑，E1）；≥ 10% → 0.2（图库透出约 80%）。两次状态间的
     * 过渡本身由调用方的 120ms tween 播放，这里只给"目标值"：跟手期间每帧读一次、
     * 目标翻转时上层启动过渡。NaN / 屏高未测得时一律按纯黑处理（宁可黑也不透出错帧）。
     */
    fun dismissMaskTargetAlpha(dy: Float, screenHeightPx: Float): Float {
        if (!dy.isFinite() || !screenHeightPx.isFinite() || screenHeightPx <= 0f) return 1f
        if (dy <= 0f) return 1f
        return if (dy / screenHeightPx >= REVEAL_PROGRESS_RATIO) REVEAL_MASK_ALPHA else 1f
    }

    /**
     * 查看器页背景分支选择（N3/N4，v4.16.8；G1，v4.16.11 收敛黑化责任，纯函数 JVM 单测钉住）。
     *
     * ImageViewer.drawBehind 直接调用本函数、按返回的枚举绘制（代码级绑定，
     * 不是"注释里声称的对应"）：判定表在这里改，drawBehind 的绘制跟着变；
     * 判定表退化会让 ViewerBackgroundBranchTest 立刻变红。分支顺序不能换：
     * ① 进入页全屏 → E1/E2 分段遮罩（透出逻辑完全不变）；
     * ② 脱离拖动中 → 旧线性映射（无论是否并排态）；
     * ③ 其余全屏态 → 页内满宽满高黑：盖住背后的图库截图（N3/N4：非进入页 /
     *    已翻页 / 邻页加载中都可能整页透明，不画就透出图库）；
     * ④ 其余并排态 → 页内**不画**，由容器背景（MediaViewerScreen 根 Box 的
     *    background(Color.Black)，见 G1）承接。
     *
     * 为什么并排态不再画"档位宽度黑"（G1 拆除的固定宽度补丁）：那是一块宽度 =
     * 页宽 × 档位比例的手工黑矩形，补丁宽度即漏洞宽度——并排态页盒只平移不缩放
     * （缩放发生在 ImageViewer 内部），补丁只能靠手算宽度贴住图片边缘；一旦页面
     * 被整体平移（越界跟手 / 平台 overscroll），补丁跟着走、它没覆盖到的两侧
     * 15% 露边区就露出背后的图库截图。黑化改由容器统一负责后，页内任何间隙
     * （越界露出的新带、无邻页的一端、加载中的邻页）都是容器纯黑，与补丁宽度无关。
     *
     * G4（v4.16.14）新增参数 [inSwipeDelete]：队列模式的**竖滑删除手势**与下滑关闭
     * 共用同一个纵向位移状态（dismissDragY），但它不是"关闭"——不该触发 ②的
     * DISMISS_LINEAR 页内黑遮罩（那块遮罩在并排态会盖住两侧露边邻图，重现
     * "各吃 48px"的旧 bug）。删除手势激活期间直接按 ③/④ 的状态归属走，
     * 与 dismissY 数值无关。默认 false：原三参调用点与既有单测行为不变。
     *
     * @param hasFlyBackCellRect 本页是否带飞回格子矩形（进入页）
     * @param inFullScreen 当前是否全屏态（并排进度 = 0）
     * @param dismissY 脱离拖动的实时位移（px，> 0 才算拖动中）
     * @param inSwipeDelete 本页面内竖滑删除手势是否在途（跟手 / 进站动画 / 回位弹簧）
     */
    fun resolveBackgroundBranch(
        hasFlyBackCellRect: Boolean,
        inFullScreen: Boolean,
        dismissY: Float,
        inSwipeDelete: Boolean = false
    ): ViewerBackgroundBranch = when {
        hasFlyBackCellRect && inFullScreen -> ViewerBackgroundBranch.FLYBACK_MASK
        inSwipeDelete ->
            // 删除手势在途：页内不画关闭遮罩。全屏态兜底满页黑（盖截图），
            // 并排态交给容器纯黑（露边区必须透出邻图）
            if (inFullScreen) ViewerBackgroundBranch.FULL_PAGE_BLACK
            else ViewerBackgroundBranch.CONTAINER_BLACK
        dismissY > 0f -> ViewerBackgroundBranch.DISMISS_LINEAR
        inFullScreen -> ViewerBackgroundBranch.FULL_PAGE_BLACK
        else -> ViewerBackgroundBranch.CONTAINER_BLACK
    }

    /**
     * E4：飞回目标的 graphicsLayer 因子换算。
     *
     * 图片内容（Fit 矩形，居中于容器、未变换）最终要正好落在格子矩形上：
     * - 目标缩放 = 格子边长 / 内容边长（X/Y 各自算，E4"落到格子时尺寸 = 其他缩略图"；
     *   格子缩略图是 Crop 语义，飞回图按 X/Y 拉到格子比例与它视觉一致）
     * - 目标平移 = 格子中心 − 容器中心（graphicsLayer 缩放以层中心为锚点，内容又
     *   居中于层 → 缩放不改变内容中心位置，平移直接对准格子中心即可）
     *
     * 任一尺寸无效（≤0 / NaN）时返回恒等因子（scale 1、平移 0、不透明）：
     * 调用方会把这种异常退回普通关闭链路，不会把恒等结果渲染出来。
     */
    fun flyBackTargetFactors(
        containerWidth: Float,
        containerHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
        cellRect: ViewerRect
    ): FlyBackFactors {
        val valid = containerWidth > 0f && containerHeight > 0f &&
            contentWidth > 0f && contentHeight > 0f &&
            cellRect.width > 0f && cellRect.height > 0f &&
            cellRect.width.isFinite() && cellRect.height.isFinite()
        if (!valid) return FlyBackFactors(1f, 1f, 0f, 0f, 1f)
        return FlyBackFactors(
            scaleX = cellRect.width / contentWidth,
            scaleY = cellRect.height / contentHeight,
            translationX = cellRect.centerX - containerWidth / 2f,
            translationY = cellRect.centerY - containerHeight / 2f,
            alpha = 1f
        )
    }

    /**
     * E4：起始因子与目标因子按 t ∈ [0,1] 插值（t 由调用方缓动后喂入）。
     *
     * alpha 单独恢复到 1：拖动中图片已变淡（dismissTransform 的 alpha），飞回途中
     * 必须可见。t 越界 clamp；NaN 因子按 0 位移 / 1 缩放兜底，不让 NaN 进 graphicsLayer。
     */
    fun flyBackFactors(
        start: FlyBackStartFactors,
        target: FlyBackFactors,
        t: Float
    ): FlyBackFactors {
        val p = if (t.isFinite()) t.coerceIn(0f, 1f) else 1f
        return FlyBackFactors(
            scaleX = lerp(start.scaleX.safeOr(1f), target.scaleX.safeOr(1f), p),
            scaleY = lerp(start.scaleY.safeOr(1f), target.scaleY.safeOr(1f), p),
            translationX = lerp(start.translationX.safeOr(0f), target.translationX.safeOr(0f), p),
            translationY = lerp(start.translationY.safeOr(0f), target.translationY.safeOr(0f), p),
            alpha = lerp(start.alpha.safeOr(1f), 1f, p)
        )
    }

    /** NaN / 无穷按兜底值替换（进 graphicsLayer 前的最后一道闸） */
    private fun Float.safeOr(fallback: Float): Float = if (isFinite()) this else fallback

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * 捏合是否触发返回（R-B23a2：判定源从"逐帧当前缩放"改为"手势起点缩放锁定"）。
     *
     * 为什么按起点锁定：旧实现每帧喂入 scaleBeforeZoom，双击放大到 2x 后一口气捏合时，
     * 缩回 1f 的后续帧被误判成"未放大"，多余 zoom 帧继续累计越过 0.92 → 误触关闭。
     * 与 [ViewerSideBySide.canEngageFromStart] 同思路——按下瞬间定一次，整段手势不变。
     *
     * @param scaleAtGestureStart 手势按下瞬间的页面缩放（1f = 未放大），整段手势内不变
     * @param gestureScale 本次手势累计的缩放倍率（<1 为缩小）
     * @param pointerCount 当前按下的手指数
     */
    fun shouldDismissByPinch(
        scaleAtGestureStart: Float,
        gestureScale: Float,
        pointerCount: Int
    ): Boolean {
        // 单指一律交给既有方向锁定逻辑（水平让给 Pager、垂直走下滑脱离）
        if (pointerCount < 2) return false
        // 起手已放大：整段手势只做"缩回 1f"，永不退出（保持既有 coerceIn 语义）
        if (scaleAtGestureStart > PINCH_ZOOMED_THRESHOLD) return false
        return gestureScale <= PINCH_DISMISS_SCALE
    }

    // ============================================================
    // N6（v4.16.9）：放大态水平边界判定（平移到界后让位给翻页）
    // ============================================================

    /**
     * 边界判定的浮点容差（px）：constrainOffset 的 coerceIn 会把 offset 精确钳在
     * ±excessWidth，容差只为吸收累计浮点误差；1px 在任何密度下都低于可感知范围。
     */
    const val EDGE_TOLERANCE_PX = 1f

    /**
     * N6（A2）：放大态贴边继续同向拖的翻页触发阈值（占视口宽的比例）。
     * 手指在边界外的过冲累计达到该比例即翻页。取 5% 的理由：贴边后 offset 已被
     * constrainOffset 钳死，继续同向拖不存在任何别的效果，翻页是唯一合理反馈，
     * 阈值只承担"滤掉抖动"的职责——5% 在 320px 视口是 16px（3~4 帧跟手拖动）、
     * 在 1080px 视口是 54px，两种屏宽下都低于"误触感知线"且远小于死区感阈值。
     * 初版 0.15 在设备实测中永远达不到：swipe 到边界后剩余过冲帧的 pan 累计
     * 仅 ~27px（N6Probe 日志），阈值从未触发。
     */
    const val EDGE_FLING_OVERSCROLL_RATIO = 0.05f

    /**
     * N6：放大态水平平移是否已贴住左/右任一边界。
     *
     * excessWidth =（缩放后图宽 − 容器宽）的一半，与 constrainOffset 的钳制上界同源：
     * |offsetX| 触及 excessWidth（容差内）即贴边。excessWidth ≤ 0（crop 放大或图宽
     * 恰好铺满容器）时水平没有平移余地，任何位置都算贴边——左右拖动全都该让位给翻页，
     * 与系统相册"放大到铺满宽度后横拖即翻页"的手感一致。异常输入（NaN）一律判未贴边：
     * 让位是保守行为，不确定时宁可继续平移。
     */
    fun isAtHorizontalEdge(offsetX: Float, excessWidth: Float, tolerance: Float): Boolean {
        if (!offsetX.isFinite() || !excessWidth.isFinite() || !tolerance.isFinite()) return false
        if (excessWidth <= 0f) return true
        return abs(offsetX) >= excessWidth - tolerance
    }

    /**
     * N6：放大态平移的本次水平分量里，"指向已贴边边界之外"的过冲量（px，带方向）。
     *
     * 返回 0 = 正常平移（未贴边 / 拖动方向指回屏内），调用方照旧更新 offset 并 consume；
     * 非 0 = 让位信号：正 = 向右界外推（右翻方向），负 = 向左界外推（左翻方向）。
     * 方向必须一致才计过冲——贴右界时向左拖是"往回平移"，不是翻页意图，返回 0 让
     * 调用方把累计清零（避免先右后左的抖动手势攒出假过冲）。
     */
    fun horizontalEdgeOverscrollPan(
        offsetX: Float,
        excessWidth: Float,
        panX: Float,
        tolerance: Float
    ): Float {
        if (!isAtHorizontalEdge(offsetX, excessWidth, tolerance)) return 0f
        // 无水平余量：两侧都算贴边，任意方向的拖动都是过冲（方向语义由符号自带）
        if (excessWidth <= 0f) return panX
        return if (offsetX > 0f && panX > 0f) panX
        else if (offsetX < 0f && panX < 0f) panX
        else 0f
    }

    // ============================================================
    // N8（v4.16.9）：二维自由拖动（下拉过阈值后放开横向跟手）
    // ============================================================

    /**
     * N8：横向自由拖动的满格阈值（下拉深度占屏高的比例）。
     * 用户规格"向下拖动约 20% 之后即可自由移动"；起步点取其一半（10%），恰好与
     * REVEAL_PROGRESS_RATIO（透出图库的阈值）同步——图库开始透出时横向开始跟手，
     * 两个反馈同时出现，不会有"图库已透出、横拖还没反应"的错位感。
     */
    const val FREE_DRAG_THRESHOLD_RATIO = 0.20f

    /**
     * N8：下拉深度 → 横向跟手权重（0..1）。
     *
     * 分段线性：进度 ≤ 起步点（thresholdRatio 的一半）→ 0（前段仍锁纵向）；
     * 起步点 → thresholdRatio 之间线性爬升到 1；之后恒 1（完全放开）。
     * 线性而不是阶跃：权重乘在累计 dx 上，权重渐变时 x 位移渐进跟手，
     * 不会在过阈值的瞬间从 0 突跳到全量。
     *
     * thresholdRatio ≤ 0 视为"全程放开"（返回 1）——反证用例靠它变红：
     * 阈值改 0 后 0.1×屏高的断言（期望 0）会得到 1。
     * 屏高未测得 / NaN 一律返回 0：宁可不放开，也不把 NaN 带进位移。
     */
    fun resolveFreeDragFactor(dismissDy: Float, screenHeightPx: Float, thresholdRatio: Float): Float {
        if (!dismissDy.isFinite() || !screenHeightPx.isFinite() || screenHeightPx <= 0f) return 0f
        if (!thresholdRatio.isFinite() || thresholdRatio <= 0f) return 1f
        val progress = abs(dismissDy) / screenHeightPx
        val start = thresholdRatio * 0.5f
        if (progress <= start) return 0f
        return ((progress - start) / (thresholdRatio - start)).coerceIn(0f, 1f)
    }

    /**
     * N8：二维拖动松手归宿判定（shouldDismiss 的 x 轴扩展版）。
     *
     * 判定顺序：
     * ① 横向甩速超阈值（|vx| > 阈值）→ FLY_BACK：自由拖动后横向快速甩动是明确的
     *    "甩掉查看器"意图，纵向上不再要求拖够 22%（原版只看 y，横向甩永远弹回）；
     * ② 纵向达既有阈值（dy > 22% 屏高 或 下甩 vy > 阈值）→ FLY_BACK（原语义不变）；
     * ③ 其余（含 |dy| < 3% 屏高且 |dx| < 3% 屏宽的"接近原位"）→ SPRING_BACK 回全屏。
     *    归宿②的显式条件在判定上与③重合（dy 不达标本来就走 SPRING_BACK），这里
     *    保留 dx/screenWidthPx 参数把规格写进签名：横向位移大而纵身未达标同样回全屏
     *    （x 弹簧归零），绝不允许"横拖很远但 dy 没到 22% 就误关闭"。
     */
    fun resolveDismissOutcome(
        dy: Float,
        dx: Float,
        velocityYDownPxPerSec: Float,
        velocityXPxPerSec: Float,
        screenHeightPx: Float,
        screenWidthPx: Float,
        velocityThresholdPxPerSec: Float
    ): DismissOutcome {
        if (abs(velocityXPxPerSec) > velocityThresholdPxPerSec) return DismissOutcome.FLY_BACK
        val distanceExceeded = dy > screenHeightPx * DISMISS_DISTANCE_RATIO
        val velocityExceeded = velocityYDownPxPerSec > velocityThresholdPxPerSec
        return if (distanceExceeded || velocityExceeded) {
            DismissOutcome.FLY_BACK
        } else {
            DismissOutcome.SPRING_BACK
        }
    }
}

/**
 * 查看器页背景的绘制分支（N3/N4，v4.16.8；G1，v4.16.11 拆掉并排固定宽度补丁）。
 *
 * ImageViewer 的 drawBehind 按优先级取**第一个**命中的分支：
 * - [FLYBACK_MASK]：进入页全屏态——E1/E2 分段遮罩（拖动中淡到 0.2 透出图库）；
 * - [DISMISS_LINEAR]：脱离拖动中——旧线性映射遮罩（D5，并排态下拉也走这条）；
 * - [FULL_PAGE_BLACK]：全屏态其余情况——页内满宽满高黑。全屏态没有"邻页露边"，
 *   满页黑不会盖住任何邻图；它的职责只有一个：盖住透出链路背后的图库截图
 *   （非进入页 / 已翻页 / 加载中页）；
 * - [CONTAINER_BLACK]：并排（队列）态——页内**不画任何黑**。两侧 15% 露边区、
 *   无邻页的一端、越界跟手露出的新带，全部由容器背景（纯黑）承接，与页内
 *   几何无关（G1：固定宽度黑补丁拆除后，背景黑化不再依赖任何遮挡层）。
 */
enum class ViewerBackgroundBranch {
    FLYBACK_MASK,
    DISMISS_LINEAR,
    FULL_PAGE_BLACK,
    CONTAINER_BLACK
}

/**
 * 并排预览三级手势（D1~D5，v4.16.2）的纯判定逻辑。
 *
 * 五步状态机（用户逐条确认，一字不许简化）：
 * ① 全屏（进查看器初始态）→ 双指合拢 → ② 松手停在"并排"（中间约 70%、两侧各露约 15%）
 * → 继续合拢 → ③ 图片临时再缩小（约 55%），松手弹回并排（**不回全屏、不回图库**）
 * → 张开手指 → ④ 回全屏。任意状态下拉仍走现有下滑关闭链路。
 *
 * 进度语义：0 = 全屏；1 = 并排标准档（页宽 70%）；1.5 = 临时缩小档（页宽 55%）。
 * 与 [ViewerDismissGesture] 一样只放纯函数，手势流程在 ImageViewer /
 * MediaViewerScreen，两边行为靠单测对齐。
 */
object ViewerSideBySide {

    /** 并排标准档：中间页占视口宽度的比例（70%，规格允许 65~75 区间内视觉微调） */
    const val PAGE_FRACTION = 0.7f

    /** 每单位进度单侧露出的视口比例（进度 1 时两侧各露 15%，与 PAGE_FRACTION 互补） */
    const val EDGE_FRACTION_PER_PROGRESS = 0.15f

    /** 进度上限（临时缩小档）：1.5 → 页宽 55%，再捏不更小 —— 松手必弹回并排 */
    const val MAX_PROGRESS = 1.5f

    /** 吸附分界：松手进度 ≥ 0.5 吸附到并排（1），否则回全屏（0） */
    const val SETTLE_THRESHOLD = 0.5f

    /**
     * 手势缩放与进度的换算基数：未放大态合拢到 0.7 时进度恰好走满 1。
     * 由此跟手期间"视觉缩放 ≈ 手指缩放"（页宽 = 1 - 0.3 × 进度 = 手势累计倍率）。
     */
    private const val GESTURE_RANGE = 0.3f

    /** 捏合手势累计倍率的钳制范围（防极端手势数值爆掉进度换算） */
    const val GESTURE_SCALE_MIN = 0.4f
    const val GESTURE_SCALE_MAX = 2.5f

    /**
     * 双指手势意图（Q1-2，v4.16.5）：一段手势第一帧产生非恒等 zoom 时判定一次，
     * 此后锁定到手势结束（调用方在 awaitEachGesture 作用域内持有局部变量）。
     *
     * 为什么必须锁定：v4.16.2 把未放大态的**全部**双指 zoom 交给并排进度，导致
     * 全屏态"张开"也被当成并排进度上报（zoom>1 算出负进度、钳回 0，事件还被
     * consume）→ 双指张开放大不可达。修复不是逐帧分流——一段手势里又放大又进
     * 并排会抖——而是按下后第一帧锁定方向，整段手势只服从一个意图。
     */
    enum class PinchIntent {
        /** 还没产生非恒等 zoom：两轴都先不处理，等下一帧再判 */
        UNDECIDED,
        /** 张开（zoom > 1）且起点全屏：整段手势走既有 zoom/pan 放大路径，不进并排 */
        ZOOM_IN,
        /** 合拢（zoom < 1），或起点已在并排进度上：整段手势走并排进度（现有语义） */
        SIDE_BY_SIDE
    }

    /**
     * 把"当前页占视口比例"反解回并排进度（[pageFraction] 的逆运算）。
     *
     * 意图判定需要知道手势起点的进度（0 = 全屏），但上层经 [sideBySideScale]
     * lambda 给 ImageViewer 的只有档位比例（pageFraction(sideProgress)）。
     * pageFraction 是线性换算（fraction = 1 − 0.3 × progress），可精确反解；
     * fraction ≥ 1 或异常值一律按全屏 0 兜底——宁可少进并排，不误判方向。
     */
    fun progressFromPageFraction(fraction: Float): Float {
        if (!fraction.isFinite() || fraction >= 1f) return 0f
        if (fraction <= 0f) return MAX_PROGRESS
        return ((1f - fraction) / (2f * EDGE_FRACTION_PER_PROGRESS))
            .coerceIn(0f, MAX_PROGRESS)
    }

    /**
     * 双指手势意图判定（Q1-2，纯函数，JVM 单测钉住）。
     *
     * 规格逐条：
     * - 已锁定（非 UNDECIDED）→ 维持原意图，绝不翻转（逐帧切换会让一段手势里
     *   "先放大又缩成并排"，观感抖动）；
     * - 本帧无明确方向（恒等倍率 / 非有限值）→ 保持 UNDECIDED，等下一帧；
     * - 起点进度 > 0（并排态 / 吸附动画被打断的中间值）→ 整段交并排换算：
     *   张开是"从并排回全屏"的合法输入，走 [progressFromGesture] 现有语义；
     * - 起点全屏：张开 → ZOOM_IN（恢复双指放大），合拢 → SIDE_BY_SIDE（v4.16.2 语义）。
     *
     * @param progressAtStart 手势按下瞬间的并排进度（[progressFromPageFraction] 的结果）
     * @param zoom 本帧指针事件的缩放倍率（calculateZoom）
     * @param currentIntent 本手势当前已锁定的意图
     */
    fun resolvePinchIntent(
        progressAtStart: Float,
        zoom: Float,
        currentIntent: PinchIntent
    ): PinchIntent {
        if (currentIntent != PinchIntent.UNDECIDED) return currentIntent
        if (!zoom.isFinite() || zoom == 1f) return PinchIntent.UNDECIDED
        if (progressAtStart > 0f) return PinchIntent.SIDE_BY_SIDE
        return if (zoom > 1f) PinchIntent.ZOOM_IN else PinchIntent.SIDE_BY_SIDE
    }

    /**
     * 进度 → 单侧露边占视口比例（pager contentPadding 的水平参数来源）。
     * 进度 0 → 0（全屏无露边）；1 → 15%；1.5 → 22.5%（页宽 55%）。
     *
     * 非有限值（NaN / 无穷）一律按全屏 0 处理：进度会直接进 graphicsLayer 的
     * 缩放因子，NaN 会让整层消失且 coerceIn 拦不住它。
     */
    fun edgeFraction(progress: Float): Float {
        if (!progress.isFinite()) return 0f
        return (EDGE_FRACTION_PER_PROGRESS * progress)
            .coerceIn(0f, EDGE_FRACTION_PER_PROGRESS * MAX_PROGRESS)
    }

    /**
     * 进度 → 中间页占视口宽度比例。1 - 2 × 单侧露边，与 edgeFraction 严格互补。
     */
    fun pageFraction(progress: Float): Float = 1f - 2f * edgeFraction(progress)

    /**
     * 捏合跟手：手势起点进度 + 本次累计缩放倍率 → 当前进度。
     *
     * baseProgress 取手势按下瞬间的实际进度（0 全屏 / 1 并排 / 中间值 = 在途动画被打断），
     * gestureScale 是本次手势自 1.0 起累计的缩放（<1 合拢、>1 张开）。合拢缩小、
     * 张开放大、两端 clamp 在 [0, MAX_PROGRESS]；反向操作自动回退（捏过头再张开，
     * 进度原路返回）。
     */
    fun progressFromGesture(baseProgress: Float, gestureScale: Float): Float {
        // 起点进度异常（NaN / 无穷）按全屏兜底；手势倍率 0 / 负 / NaN 视为"没捏"
        val base = if (baseProgress.isFinite()) {
            baseProgress.coerceIn(0f, MAX_PROGRESS)
        } else {
            0f
        }
        if (!gestureScale.isFinite() || gestureScale <= 0f) return base
        return (base + (1f - gestureScale) / GESTURE_RANGE)
            .coerceIn(0f, MAX_PROGRESS)
    }

    /**
     * 松手吸附：进度 ≥ 0.5 → 并排（返回 1），否则回全屏（返回 0）。
     *
     * 一个函数覆盖状态机全部松手分支：全屏态捏过半 → 进并排；没过半 → 弹回全屏；
     * 并排态继续合拢（进度必然 > 1 ≥ 阈值）→ 弹回并排（不回图库）；
     * 并排态张开到手（进度掉过 0.5）→ 回全屏。
     */
    fun settleProgress(progress: Float): Float = if (progress >= SETTLE_THRESHOLD) 1f else 0f

    /**
     * 并排接管第一帧是否要归零 pager 的翻页残留（N5，v4.16.8，纯函数）。
     *
     * 翻页拖动进行中落第二指时，双指事件被 ImageViewer 全部 consume，pager 的
     * drag 被取消且不触发 settle，currentPageOffsetFraction 冻结在中间值——页
     * 平移公式一直含它，并排后整页带着偏移（"图片只占半边、另一侧黑区"）。
     * 归零用 scrollToPage（瞬时），只在残留超过 1% 视口宽时执行，避免在
     * fraction 本来就是 0（绝大多数情况）时发起无意义的 scroll 调用。
     *
     * NaN / 无穷按"需要归零"处理：scrollToPage 会把任何状态复位到当前页原点。
     */
    fun shouldNormalizePager(offsetFraction: Float): Boolean {
        if (!offsetFraction.isFinite()) return true
        return abs(offsetFraction) > 0.01f
    }

    /**
     * 并排态点击命中分区（D4"点哪张图哪张全屏"）。
     *
     * @param xInViewport 点击点在视口（屏幕）内的横坐标
     * @param viewportWidth 视口宽度（px，<=0 时一律判居中兜底）
     * @param progress 当前并排进度（决定露边宽度）
     * @return -1 = 点在左侧露边页，0 = 居中页，1 = 右侧露边页
     */
    fun tapZone(xInViewport: Float, viewportWidth: Float, progress: Float): Int {
        if (viewportWidth <= 0f) return 0
        val edge = viewportWidth * edgeFraction(progress)
        return when {
            xInViewport < edge -> -1
            xInViewport > viewportWidth - edge -> 1
            else -> 0
        }
    }

    /**
     * 手势起点是否允许进入并排预览（D3 语义隔离）。
     *
     * 规格："已放大（currentScale > 1.05）时保持现有 zoom/pan 语义不变（捏合只回原始大小）"。
     * 判定必须**按手势起点锁定**而不是每帧重算：从放大态开始捏合时，缩到 1f 之后
     * 同一根手指的剩余动作若被并排逻辑接管，用户会看到"图片缩回原大小后又继续缩成
     * 并排"——同一手势里语义被中途换掉（设备实测确认会这样）。
     *
     * @param scaleAtGestureStart 手势按下瞬间的页面缩放（1f = 未放大）
     */
    fun canEngageFromStart(scaleAtGestureStart: Float): Boolean {
        if (!scaleAtGestureStart.isFinite()) return true
        return scaleAtGestureStart <= ViewerDismissGesture.PINCH_ZOOMED_THRESHOLD
    }

    /**
     * 把 ImageViewer 上报的点击横坐标换算到**视口（屏幕）坐标**。
     *
     * 为什么需要这个函数：指针事件拿到的是"该页图片节点层内"的坐标，而这一层在并排态
     * 被 `graphicsLayer` 以页中心为锚点乘了档位系数（70% / 55%），页本身又被平移了
     * `pageDistance × 视口宽 × 档位`。两段变换都要反算回来，否则点击落点会整体偏向
     * 中间（实测：屏幕上点 x=300 的右露边，换算出 264，被判定为"点了中间图"，
     * 表现就是点露边页只放大不翻页）。
     *
     * 坐标变换链（s = 档位比例、W = 视口宽、d = 页距）：
     *   图片层内 u --(以页中心放大 s 倍)--> 页内 v = (u - W/2)·s + W/2
     *   页内 v --(页位移 d·W·s)--> 视口 x = v + d·W·s
     *
     * @param imageLocalX ImageViewer 上报的层内横坐标（可能落在 [0,W] 之外）
     * @param viewportWidth 视口宽度（px）
     * @param fraction 当前档位下"中间页占视口宽度"的比例（[pageFraction] 的返回值）
     * @param pageDistance 该页相对滚动中位置的页距（-1 左邻 / 0 当前 / +1 右邻，可含小数）
     */
    fun viewportXFromImageLocal(
        imageLocalX: Float,
        viewportWidth: Float,
        fraction: Float,
        pageDistance: Float
    ): Float {
        if (viewportWidth <= 0f) return 0f
        // s 异常（NaN / 非正）按全屏 1 处理：此时变换是恒等，至少不会算出 NaN 落点
        val s = if (fraction.isFinite() && fraction > 0f) fraction else 1f
        val half = viewportWidth / 2f
        val d = if (pageDistance.isFinite()) pageDistance else 0f
        val inPage = (imageLocalX - half) * s + half
        return inPage + d * viewportWidth * s
    }
}

/**
 * 查看器横向越界（rubber-band）的纯判定与位移映射（G1，v4.16.11）。
 *
 * 全屏单图与队列（并排）模式共用这一套规则：边界处允许有限越界位移，
 * 位移随手指位移**非线性衰减**（拉得越远越拉不动），松手回弹到吸附位；
 * 露出的区域由容器背景（纯黑）承接——见 MediaViewerScreen 根 Box 的
 * background(Color.Black) 与 ImageViewer 的 CONTAINER_BLACK 分支。
 *
 * 为什么抽成纯函数：手势链路（嵌套滚动增量、回弹动画）在 JVM 单测里跑不起来，
 * 但「这个方向算不算越界」「原始拖拽映射成多少位移」两段算术可以精确覆盖边界，
 * UI 层只负责喂增量、写平移。
 */
object ViewerOverscroll {

    /**
     * 越界位移上限（占视口宽的比例）。
     *
     * 0.18 的取值口径：并排态两端各露 15% 视口，越界上限比它略宽一点，
     * 拖到顶时"黑色延长带（15% 露边 + 18% 越界）"左右对称、不喧宾夺主；
     * 同时保证越界带不会把整幅图推出屏幕（中间页始终还占 70% 的一部分）。
     */
    const val MAX_DISPLACEMENT_RATIO = 0.18f

    /**
     * 阻尼系数：位移公式里乘在原始拖拽距离上的系数（1f = 起步 1:1 跟手）。
     * 下调会整体变"硬"（同样手指位移只跟一部分），上调会变"软"；
     * 1f 是手感基准（起步跟手、越拉越沉），与全屏/并排两态共用同一个值。
     */
    const val DAMPING = 1f

    /**
     * 松手回弹时长（ms）。
     *
     * 用具名时长 + tween（FastOutSlowIn）而不是弹簧：弹簧在欠阻尼下会过冲，
     * 表现为"回弹到位后又弹一下"（验收明确不允许二次回跳）；260ms 与并排
     * 档位吸附（PROGRESS_SETTLE_MS = 280ms）同一节奏，两套动画同时出现时不打架。
     */
    const val SETTLE_DURATION_MS = 260

    /**
     * 这个方向在当前页是否越界（两端对称）。
     *
     * 方向口径与 pager 的滚动增量一致：deltaX > 0 = 内容右移 = 看上一张；
     * deltaX < 0 = 内容左移 = 看下一张。只有"首张继续往右拉 / 末张继续往左拉"
     * 才是越界——其余位置 pager 自己能把增量吃进翻页，不该产生越界位移。
     *
     * 单页（pageCount = 1）两端都是边界，两个方向都算越界；空列表 / 非有限增量
     * / 零增量一律不算（宁可不动，也不把 NaN 带进位移）。
     */
    fun isOutOfBoundsDirection(currentPage: Int, pageCount: Int, deltaX: Float): Boolean {
        if (pageCount <= 0) return false
        if (!deltaX.isFinite() || deltaX == 0f) return false
        // 页码越界（删除条目瞬间的旧值）一律按端点处理，避免越界判定抖动
        val page = currentPage.coerceIn(0, pageCount - 1)
        return if (deltaX > 0f) page == 0 else page == pageCount - 1
    }

    /**
     * 原始拖拽距离 → 实际越界位移（带符号，非线性衰减 + 上限夹取）。
     *
     * 公式 d = M·u/(u+M)（u = |raw| × damping，M = maxDistance）：
     * - 起步导数 = damping（1f 时手指位移与实际位移 1:1，跟手不滞后）；
     * - 处处单调递增但严格小于 M（越拉越沉，永远拉不到上限之上）；
     * - u 远小于 M 时 d ≈ u（小幅拖拽也有等量可见位移），u 远大于 M 时 d → M。
     * 这就是"位移随拖拽距离非线性衰减 + 越界位移上限"的完整实现；末尾的
     * coerceIn 只是浮点兜底（数学上 d 恒 < M）。
     *
     * maxDistance ≤ 0（视口尺寸未测得）/ 任一参数非有限 → 0：宁可不跟手，
     * 也不把 NaN 写进 graphicsLayer 平移（NaN 会让整层消失）。
     */
    fun rubberBandDisplacement(rawDistance: Float, maxDistance: Float, damping: Float): Float {
        if (!rawDistance.isFinite() || !maxDistance.isFinite() || !damping.isFinite()) return 0f
        if (maxDistance <= 0f || rawDistance == 0f) return 0f
        val amount = abs(rawDistance) * damping.coerceAtLeast(0f)
        if (!amount.isFinite() || amount <= 0f) return 0f
        val displacement = maxDistance * amount / (amount + maxDistance)
        if (!displacement.isFinite()) return 0f
        val clamped = displacement.coerceIn(0f, maxDistance)
        return if (rawDistance > 0f) clamped else -clamped
    }

    /**
     * [rubberBandDisplacement] 的反解：已知当前越界位移，求等效的原始拖拽距离。
     *
     * 用途：回弹动画播放中用户再次按下，位移必须从当前值无缝接续（先反解出 raw
     * 作为新手势的起点）。若直接按 0 起算，位移会先跳回 0 再跟手（可见跳变）。
     * 反解公式 u = M·d/(M−d)，raw = u/damping；d 已贴上限时按 0.999M 兜底，
     * 保证结果有限（不会因为除零炸出 Infinity）。
     */
    fun rawDistanceForDisplacement(displacement: Float, maxDistance: Float, damping: Float): Float {
        if (!displacement.isFinite() || !maxDistance.isFinite() || !damping.isFinite()) return 0f
        if (maxDistance <= 0f || damping <= 0f || displacement == 0f) return 0f
        val magnitude = abs(displacement).coerceAtMost(maxDistance * 0.999f)
        val amount = maxDistance * magnitude / (maxDistance - magnitude)
        if (!amount.isFinite()) return 0f
        val raw = amount / damping
        if (!raw.isFinite()) return 0f
        return if (displacement > 0f) raw else -raw
    }

    /**
     * 视口宽度 → 越界位移上限（px）。宽度未测得 / 非有限 → 0（本次不产生位移）。
     */
    fun maxDisplacementPx(viewportWidth: Float): Float {
        if (!viewportWidth.isFinite() || viewportWidth <= 0f) return 0f
        return viewportWidth * MAX_DISPLACEMENT_RATIO
    }

    /**
     * 一次水平增量喂进越界机制后的结果（纯数据）。
     *
     * @param rawDrag 更新后的原始累计（px，带符号；0 = 已回到吸附位）
     * @param displacement 更新后的可见位移（px，带符号）
     * @param consumed 本次被越界机制消费掉的增量（px，带符号）。pager 只能拿到
     *                 `delta - consumed`，越界与翻页的先后顺序全靠这个数区分
     */
    data class DragStep(val rawDrag: Float, val displacement: Float, val consumed: Float)

    /**
     * 越界外推：pager 在端点已经吃不下、且增量指向界外。
     *
     * 全额消费（`consumed = delta`）：平台 overscroll 效果已关闭，这里不消费的话
     * 同一段手指位移会被父级再算一次。原始累计直接相加，位移按 [rubberBandDisplacement]
     * 非线性衰减——拖得越远，同样增量换到的位移越少。
     *
     * 异常输入（非有限增量 / 上限为 0）不消费、不产生位移，只把累计原样带回：
     * 宁可不跟手，也不把 NaN 写进平移。
     */
    fun accumulateOutward(
        rawDrag: Float,
        delta: Float,
        maxDistance: Float,
        damping: Float
    ): DragStep {
        val base = if (rawDrag.isFinite()) rawDrag else 0f
        if (!delta.isFinite() || delta == 0f) {
            return DragStep(base, rubberBandDisplacement(base, maxDistance, damping), 0f)
        }
        val next = base + delta
        if (!next.isFinite()) {
            return DragStep(base, rubberBandDisplacement(base, maxDistance, damping), 0f)
        }
        return DragStep(next, rubberBandDisplacement(next, maxDistance, damping), delta)
    }

    /**
     * 回拖：增量指回屏内（与已产生的越界位移反向）。
     *
     * 顺序规则——**先把越界位移吃回来，再谈翻页**：
     * - 没吃回零点：整段增量都用于回弹（`consumed = delta`），pager 拿不到东西翻页；
     * - 越过零点：只消费到零点的那一段（rawDrag / displacement 归 0），
     *   剩余增量（delta − consumed）留给 pager 正常翻页。
     *
     * 没有累计 / 同向增量 / 异常输入一律 `consumed = 0`：调用方应原样把增量交给
     * pager（同向增量属于 [accumulateOutward] 的职责，这里不重复实现）。
     */
    fun reverseStep(
        rawDrag: Float,
        delta: Float,
        maxDistance: Float,
        damping: Float
    ): DragStep {
        if (!rawDrag.isFinite() || rawDrag == 0f) return DragStep(0f, 0f, 0f)
        if (!delta.isFinite() || delta == 0f) {
            return DragStep(rawDrag, rubberBandDisplacement(rawDrag, maxDistance, damping), 0f)
        }
        // 同向（继续往外拉）：不由本函数处理，不消费
        if ((rawDrag > 0f) == (delta > 0f)) {
            return DragStep(rawDrag, rubberBandDisplacement(rawDrag, maxDistance, damping), 0f)
        }
        val next = rawDrag + delta
        val crossedZero = next == 0f || (next > 0f) != (rawDrag > 0f)
        if (crossedZero) {
            // 只吃回零点：剩余量（若无）交给 pager
            return DragStep(0f, 0f, -rawDrag)
        }
        return DragStep(next, rubberBandDisplacement(next, maxDistance, damping), delta)
    }

    /**
     * 越界状态下松手甩速该不该被吞掉（G2 补漏，v4.16.13）。
     *
     * 规则与 [reverseStep] 的拖拽口径**逐字对齐**（那边是增量、这边是速度）：
     * - 甩速方向与越界方向**相同**（继续往外甩）→ true：两端没有可翻的页，
     *   速度必须吞掉，否则 pager 会带着越界位移往外 fling；
     * - 甩速方向**指向页内**（越过零点的回甩）→ false：把速度整份交给 pager，
     *   pager 能做正常的惯性跨页。修复前 onPreFling 不看方向、无条件
     *   `return available`，于是"越界后快速回甩"也被吞干净，只能靠拖动过半吸附
     *   —— 与拖拽路径（回拖越过零点后余量交 pager）自相矛盾。
     *
     * 方向取哪个状态：正常手势中 rawDrag 与 displacement 同号且都非零；
     * rawDrag 已归零（回弹动画在途、抬手与 fling 同帧时会出现）就用 displacement
     * 的符号补位。两者都是 0 表示当前没有越界状态，一律返回 false（不吞）。
     *
     * 零速 / 非有限速度返回 false：此时"吞不吞"对 pager 没有区别（0 速度翻不了页），
     * 保守取不吞，避免把 NaN 语义写进分支。
     */
    fun shouldSwallowFlingVelocity(rawDrag: Float, displacement: Float, velocityX: Float): Boolean {
        if (!velocityX.isFinite() || velocityX == 0f) return false
        val direction = when {
            rawDrag.isFinite() && rawDrag != 0f -> rawDrag
            displacement.isFinite() && displacement != 0f -> displacement
            else -> return false
        }
        return (direction > 0f) == (velocityX > 0f)
    }
}

// ============================================================
// G6（任务书 12.2）：查看器退出的统一清理计划
// ============================================================

/**
 * 查看器退出原因。
 *
 * **只进日志**：既不参与"清哪些字段"的决策（见 [ViewerExitCleanup.plan]，
 * 对任何原因返回同一份清单），也不参与"这条路径自己播一段退出动画"的决策
 * （见 [ViewerExitCleanup.playsContentSideTransition]，恒为 false）。
 * 这是 G6 的结构性要求：三条退出路径不得各留一份清理、也不得各维护一份动画时序。
 */
enum class ViewerExitReason {
    /** 下滑 / 二维自由拖动松手达阈值 */
    DISMISS_DRAG,

    /** 未放大态双指收缩到 0.92 以下 */
    PINCH_IN,

    /** 系统返回键（MediaViewerScreen 的 BackHandler） */
    SYSTEM_BACK,

    /** 条目被删空等 ViewModel 主动要求关闭 */
    AUTO_CLOSE
}

/**
 * 退出清理计划（纯函数，JVM 单测钉住；实现见 ImageViewer.resetViewerLayers 与
 * MediaViewerScreen.exitViewer）。
 *
 * **为什么必须有这份清单**（G6 残影的根因，按代码逐段核对后的结论）：
 * 1. 三条退出路径原先各清各的 —— 下滑走 playFlyBackDismiss、双指收缩走
 *    playPinchCloseDismiss、系统返回只调 onClose()：页内的缩放/收尾状态只有
 *    前两条会碰，而且碰的方式是"翻可见标志位"；
 * 2. 双指收缩还额外维护了**第二条转场状态机**：内容层先自己缩小淡出
 *    （PINCH_CLOSE_TARGET_SCALE 0.86 / 160ms，与窗口动画 viewer_fade_out_shrink
 *    参数一字不差），播完才 finish()；而收尾协程第一件事就是
 *    `isPinchClosing = false` —— 标志位一翻回 false，graphicsLayer 的淡出因子
 *    就从动画终值（alpha 0f）回到 1f，图片重新变成完全不透明；
 *    紧接着 finish() 的 overridePendingTransition 把"此刻的窗口画面"再淡出
 *    160ms，宿主页已经在下面画好了，于是那一层不透明图片就被淡成半透明
 *    叠在提示词卡片与生成按钮上 —— 这正是任务书 E4 描述的残影
 *    （两段淡出 + 标志位翻回，缺一个都不会出现）。
 *
 * 修法因此是两条，缺一不可：
 * - **动画时序统一**：双指收缩不再自播内容层淡出，三条路径共用同一段窗口转场
 *   （[playsContentSideTransition] 恒 false，把这条规则做成可测的纯函数）；
 * - **清理收敛**：三条路径只调 MediaViewerScreen.exitViewer(reason)，
 *   取消在途动画协程 + 把机制状态归位 + 复位 pager 平移量
 *   （字段清单就是下面的 PLAN，与 reason 无关）。
 *
 * 清单分两段（字段归属的组件不同，但**同一个入口按同一顺序**执行）：
 * - [PAGE_FIELDS]：页内（ImageViewer）的收尾动画与机制标志位；
 * - [SCREEN_FIELDS]：屏幕级（MediaViewerScreen）的越界位移与在途动画。
 *
 * [KEPT_FIELDS] 是**刻意不复位**的字段与理由：退出转场的窗口快照要延续用户
 * 松手那一刻的画面，复位它们会造成可见跳变（把"残影"换成"抽帧"不算修好）。
 */
object ViewerExitCleanup {

    /**
     * 页内必须复位的字段（ImageViewer）。
     *
     * 每一项都是"机制状态"（标志位 / 在途协程），复位它们不会改变当前帧画面：
     * - exitCommitted：本页已发起退出的标志位。置位后不得再启动任何收尾动画、
     *   不得再累计新的退出意图（防"两次淡出"与重复 finish）；
     * - isFlyBackAnimating：飞回收尾的在途标志位（下滑路径专用）；
     * - pendingSingleTap：退出后不该再补发一次"单击切换 UI"；
     * - zoomJob / settleJob / flyBackJob：在途动画协程。转场被中断或取消也必须
     *   回收，不允许留着一个还在往已销毁的图层里写值（任务书实施指令 2/3）。
     */
    val PAGE_FIELDS: List<String> = listOf(
        "exitCommitted",
        "isFlyBackAnimating",
        "pendingSingleTap",
        "zoomJob",
        "settleJob",
        "flyBackJob"
    )

    /**
     * 屏幕级必须复位的字段（MediaViewerScreen）。
     *
     * - overscrollPx / overscrollRawDrag：pager 的 graphicsLayer 平移量。
     *   留着不清，退出快照就是一幅"整体偏移 0~18% 视口宽"的图，淡出时像
     *   一层贴歪了的残影；
     * - overscrollSettleJob / progressJob：回弹与档位吸附动画；
     * - pagerGestureActive：手势相位。退出后不可能再有手指，留着 true
     *   会让"静止残留归零"那条 effect 永久放弃归零。
     */
    val SCREEN_FIELDS: List<String> = listOf(
        "overscrollPx",
        "overscrollRawDrag",
        "overscrollSettleJob",
        "progressJob",
        "pagerGestureActive"
    )

    /**
     * 刻意**不复位**的字段（连同理由钉在这里，防止以后有人"顺手清干净"）：
     * - scale / offset / isAnimating：双击缩放或平移动画进行中退出，冻结在
     *   当前帧（取消动画协程但不改数据源开关）才连续；把它们改成 1f/Zero
     *   会在退出快照里出现一次"突然回全屏"；
     * - dismissDragY / dismissDragX：下滑退出松手时图片就停在该位置，
     *   窗口淡出延续这个位置才是"跟着手关掉"的观感；
     * - sideProgress / gestureBaseProgress：并排（队列）态下拉关闭是已验收
     *   行为，档位布局要带到转场结束；
     * - maskRevealed：透出遮罩的目标值。置回 false 会让黑幕淡回纯黑，
     *   把已经透出来的图库又盖掉一帧；
     * - isDismissSettling：弹簧回位的数据源开关。清理只取消回位协程、**不翻这个开关**，
     *   图层因此冻结在回位的当前帧 —— 翻回 false 会让图片从半途"瞬跳"回手势位移值。
     */
    val KEPT_FIELDS: List<String> = listOf(
        "scale",
        "offset",
        "isAnimating",
        "dismissDragY",
        "dismissDragX",
        "isDismissSettling",
        "sideProgress",
        "gestureBaseProgress",
        "maskRevealed"
    )

    /** 唯一清单实例：三个 reason 拿到的是同一个对象（单测断言引用相等） */
    private val PLAN: List<String> = PAGE_FIELDS + SCREEN_FIELDS

    /**
     * 退出必须复位的字段清单。
     *
     * 签名带 reason 是为了让调用点写清楚"因哪条路径退出"，但**返回值与 reason
     * 无关** —— 任何按 reason 分支"少清几项"的改动都会让 ViewerExitCleanupTest 变红。
     */
    fun plan(reason: ViewerExitReason): List<String> = PLAN

    /**
     * 该退出原因是否需要**内容层自己再播一段退出动画**。
     *
     * G6 的答案是**一律 false**：三条退出路径共用同一段窗口转场
     * （res/anim/viewer_fade_out_shrink：alpha 1→0 + scale 1→0.86 + 160ms），
     * 内容层不再维护第二条转场状态机。
     *
     * 修复前双指收缩在内容层先淡出 160ms，播完把 isPinchClosing 翻回 false
     * （图片恢复不透明），随后 finish() 取的窗口快照把这层图片**再淡一次**到
     * 宿主页上方 —— 那半透明的一层就是报告里的残影。任务书 12.1 实施指令 3
     * "三条退出路径统一到同一转场实现，禁止各自维护一份动画时序"在退出侧的
     * 落点就是这里。
     *
     * 这个函数保留（恒返回 false）而不是删掉：任何"给某条路径单独加一段动画"
     * 的改动都会让 ViewerExitCleanupTest 变红。
     */
    fun playsContentSideTransition(reason: ViewerExitReason): Boolean = false
}

/**
 * 页内组件向统一退出入口注册"图层复位"的小注册表（G6）。
 *
 * 为什么需要它：缩放 / 脱离位移 / 收尾动画这些状态属于分页组件 ImageViewer 的组合域，
 * 屏幕级入口够不到；而系统返回这条路径根本不经过 ImageViewer 的手势代码。
 * 没有注册表就得在 BackHandler 里"再写一份清理"，正是任务书禁止的"各留一份"。
 *
 * ImageViewer 在 DisposableEffect 里注册、离开组合时注销，因此
 * ① 三条退出路径都只调 [ViewerExitRegistry.fire]（同一份清理），
 * ② 重复进出不会累积注册（注销保证）。
 *
 * 本类只持有回调，不引 compose 类型，保持纯函数模块可在 JVM 单测里直接实例化。
 */
class ViewerExitRegistry {

    private val cleanups = mutableListOf<(ViewerExitReason) -> Unit>()

    /** 当前注册的复位回调数量（单测用它证明"重复进出不累积"） */
    val registered: Int get() = cleanups.size

    /**
     * 注册一个复位回调，返回注销函数。
     *
     * 同一个回调重复注册会被忽略（防重组时忘记注销导致累积）。
     */
    fun register(cleanup: (ViewerExitReason) -> Unit): () -> Unit {
        if (cleanups.none { it === cleanup }) cleanups += cleanup
        // 用下标删除而不是 removeIf：removeIf 是 JDK/Android API 24+ 的默认方法，
        // 低版本设备上会 NoSuchMethodError；这里只有一处注销路径，下标删除更直白
        return {
            val index = cleanups.indexOfFirst { it === cleanup }
            if (index >= 0) cleanups.removeAt(index)
        }
    }

    /**
     * 按注册顺序执行全部复位回调。
     *
     * 遍历前先拷贝：回调自身可能注销（组件正离开组合），直接遍历原列表会
     * ConcurrentModificationException —— 那等于退出时崩溃。
     */
    fun fire(reason: ViewerExitReason) {
        cleanups.toList().forEach { it(reason) }
    }
}

// ============================================================
// G4（任务书 11.4）：队列模式竖滑快捷删除 —— 纯判定逻辑
// ============================================================

/** 竖滑删除松手的归宿。 */
enum class SwipeDeleteOutcome {
    /** 位移或甩速达标：播放进站动画并移入回收站（阶段一，可恢复） */
    ENTER_TRASH,

    /** 未达标：弹簧回位，什么都不删 */
    CANCEL
}

/** 竖滑跟手期间的图片视觉（与 [DismissVisuals] 同思路：判定与绘制分离）。 */
data class SwipeDeleteVisuals(
    val scale: Float,
    val alpha: Float,
    val rotationDeg: Float
)

/**
 * 队列模式"上滑/下滑 → 拖入一级回收站"（手机相册式快捷删除）的判定与位移映射。
 *
 * 与下滑关闭（[ViewerDismissGesture]）的关系：共用同一手势入口与同一个纵向位移
 * 状态（ImageViewer 的 dismissDragY），**语义按模式分流** ——
 * 全屏态下拉 = 关闭查看器（G5/G6 已验收行为，原样保留）；
 * 队列（并排）态竖滑 = 删除当前图（本对象）。
 *
 * 双向对称：上滑与下滑等价（派工单原文"上滑或下滑"），判定用 |dy| / |vy|；
 * 方向偏差沿用 ViewerDismissGesture.resolvePanDirection 的 1.2 倍纵向偏置锁——
 * 斜向手势一律让给横向翻页，这条是 30 次斜滑误触率验收的第一道闸。
 */
object ViewerSwipeDelete {

    /**
     * 松手删除阈值：|dy| 超过屏高的 25%。
     *
     * 比下滑关闭的 22% 略高一档：删除是破坏性操作（虽然可撤销、进回收站），
     * 手感应"明显比关闭更坚决"，压低斜滑/过滑误触率。
     */
    const val DELETE_DISTANCE_RATIO = 0.25f

    /** 松手删除的甩速阈值（dp/s）：比关闭的 900 高，快速擦过不算删除意图 */
    const val DELETE_VELOCITY_DP_PER_SEC = 1100f

    /** 视觉饱和深度：|dy| 达到屏高 60% 时跟手视觉到极值（缩放 0.35 / 全透明前段） */
    const val VISUAL_SATURATION_RATIO = 0.6f

    /** 进站动画时长（ms）：与档位吸附（280）/ 越界回弹（260）同一节奏带 */
    const val FLY_TO_TRASH_DURATION_MS = 220

    /** 撤销提示保留时长（ms）——用户已确认的交互决策：2 秒 */
    const val UNDO_WINDOW_MS = 2000L

    /** 跟手期间的最大缩小（下限 0.35）与最大旋转（±10°，相册式"甩进垃圾桶"的倾向感） */
    private const val MIN_SCALE = 0.35f
    private const val MAX_ROTATION_DEG = 10f

    /**
     * 松手归宿：|dy| ≥ 屏高×25% 或 |vy| ≥ 阈值 → 进站。
     *
     * 两个阈值任一达标即可（与下滑关闭同构）；NaN / 屏高未测得一律 CANCEL——
     * 不确定时宁可什么都不删（破坏性操作的保守方向）。
     * velocityThresholdPxPerSec 由调用方按密度换算喂入（纯函数不做 Density 运算）。
     */
    fun resolveOutcome(
        dyPx: Float,
        screenHeightPx: Float,
        velocityYPxPerSec: Float,
        velocityThresholdPxPerSec: Float
    ): SwipeDeleteOutcome {
        if (!dyPx.isFinite() || !screenHeightPx.isFinite() || screenHeightPx <= 0f) {
            return SwipeDeleteOutcome.CANCEL
        }
        val distanceExceeded = abs(dyPx) >= screenHeightPx * DELETE_DISTANCE_RATIO
        val velocityExceeded = velocityYPxPerSec.isFinite() &&
            abs(velocityYPxPerSec) >= velocityThresholdPxPerSec
        return if (distanceExceeded || velocityExceeded) {
            SwipeDeleteOutcome.ENTER_TRASH
        } else {
            SwipeDeleteOutcome.CANCEL
        }
    }

    /**
     * 纵向位移 → 跟手视觉。双向对称（上滑下滑镜像），进度 = |dy| / (屏高×60%) 钳 [0,1]。
     *
     * 缩放 1→0.35、透明度 1→0、旋转随拖拽方向偏转（上滑向上翻、下滑向下翻）。
     * 进站动画不另设公式：把位移继续外推到 [flyTargetDy] 代入本函数即可，
     * 跟手段与动画段共用一个映射，衔接处不可能跳变。
     */
    fun visuals(dyPx: Float, screenHeightPx: Float): SwipeDeleteVisuals {
        if (!dyPx.isFinite() || !screenHeightPx.isFinite() || screenHeightPx <= 0f) {
            return SwipeDeleteVisuals(1f, 1f, 0f)
        }
        val saturation = (screenHeightPx * VISUAL_SATURATION_RATIO).coerceAtLeast(1f)
        val progress = (abs(dyPx) / saturation).coerceIn(0f, 1f)
        val direction = if (dyPx >= 0f) 1f else -1f
        return SwipeDeleteVisuals(
            scale = (1f - 0.65f * progress).coerceAtLeast(MIN_SCALE),
            // 透明度到 0.02（而非 0）：进站动画末帧仍有一粒可感的"消失点"，
            // 纯 0 会让图片在落定前一帧凭空跳没
            alpha = (1f - progress).coerceAtLeast(0.02f),
            rotationDeg = direction * MAX_ROTATION_DEG * progress
        )
    }

    /**
     * 进站动画的终点位移（px，带符号）：沿拖拽方向飞出 1.3 屏高。
     *
     * 终点代入 [visuals] 正好落在饱和极值（progress=1：scale 0.35 / alpha 0.02 /
     * rotation ±10°），动画结束时图片已飞出视口、近全透明 —— 视觉上就是
     * "被甩进屏幕外下方的回收站"。dyPx 为 0（异常调用）返回 0，动画退化为原地淡出。
     */
    fun flyTargetDy(dyPx: Float, screenHeightPx: Float): Float {
        if (!dyPx.isFinite() || !screenHeightPx.isFinite() || screenHeightPx <= 0f) return 0f
        if (dyPx == 0f) return 0f
        val direction = if (dyPx > 0f) 1f else -1f
        return direction * screenHeightPx * 1.3f
    }
}
