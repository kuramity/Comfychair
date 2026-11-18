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
     * 查看器页背景分支选择（N3/N4，v4.16.8，纯函数 JVM 单测钉住）。
     *
     * ImageViewer.drawBehind 直接调用本函数、按返回的枚举绘制（代码级绑定，
     * 不是"注释里声称的对应"）：判定表在这里改，drawBehind 的绘制跟着变；
     * 判定表退化（如"非并排全屏不返回 SIDE_OR_FULL_BLACK"）会让
     * ViewerBackgroundBranchTest 立刻变红。分支顺序不能换：
     * ① 进入页全屏 → E1/E2 分段遮罩（透出逻辑完全不变）；
     * ② 脱离拖动中 → 旧线性映射（无论是否并排态）；
     * ③ 其余一律档位宽度黑——并排态宽度 = 页宽 × 档位比例；全屏态无飞回格子
     *    （非进入页 / 已翻页）时档位比例 = 1，即满宽满高黑。
     *    旧版在这类"整页透明"的输入下什么都不画（透出图库截图/网格，N3/N4），
     *    反证：把"非进入页全屏"判成无背景，第 ③ 条断言必失败。
     *
     * @param hasFlyBackCellRect 本页是否带飞回格子矩形（进入页）
     * @param inFullScreen 当前是否全屏态（并排进度 = 0）
     * @param dismissY 脱离拖动的实时位移（px，> 0 才算拖动中）
     */
    fun resolveBackgroundBranch(
        hasFlyBackCellRect: Boolean,
        inFullScreen: Boolean,
        dismissY: Float
    ): ViewerBackgroundBranch = when {
        hasFlyBackCellRect && inFullScreen -> ViewerBackgroundBranch.FLYBACK_MASK
        dismissY > 0f -> ViewerBackgroundBranch.DISMISS_LINEAR
        else -> ViewerBackgroundBranch.SIDE_OR_FULL_BLACK
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
 * 查看器页背景的绘制分支（N3/N4，v4.16.8）。
 *
 * ImageViewer 的 drawBehind 按优先级取**第一个**命中的分支：
 * - [FLYBACK_MASK]：进入页全屏态——E1/E2 分段遮罩（拖动中淡到 0.2 透出图库）；
 * - [DISMISS_LINEAR]：脱离拖动中——旧线性映射遮罩（D5，并排态下拉也走这条）；
 * - [SIDE_OR_FULL_BLACK]：档位宽度黑（并排态宽度 = 页宽 × 档位比例，绝不盖露边
 *   的邻图）；全屏态且无飞回格子矩形（非进入页 / 已翻页）时 sideScale = 1，
 *   满宽满高黑——把原本整页透明的"相邻页 / 加载中页"全部压黑（N3/N4 修复）。
 *
 * 旧版还有第四分支 pageBackground（整页不透明黑）：分支三改造覆盖全屏态后它在
 * 任何输入下都不可达，已随本版删除——背景从此只有这一处来源。
 */
enum class ViewerBackgroundBranch { FLYBACK_MASK, DISMISS_LINEAR, SIDE_OR_FULL_BLACK }

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
