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

/** 脱离拖动某个位移量对应的视觉状态（作用于图片层，graphicsLayer 消费） */
data class DismissVisuals(val scale: Float, val alpha: Float)

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

    /**
     * 捏合是否触发返回。
     * @param currentScale 当前页面缩放（1f = 未放大）
     * @param gestureScale 本次手势累计的缩放倍率（<1 为缩小）
     * @param pointerCount 当前按下的手指数
     */
    fun shouldDismissByPinch(currentScale: Float, gestureScale: Float, pointerCount: Int): Boolean {
        // 单指一律交给既有方向锁定逻辑（水平让给 Pager、垂直走下滑脱离）
        if (pointerCount < 2) return false
        // 已放大时捏合只做"回到 1f"，不退出，保持既有 coerceIn 语义
        if (currentScale > PINCH_ZOOMED_THRESHOLD) return false
        return gestureScale <= PINCH_DISMISS_SCALE
    }
}
