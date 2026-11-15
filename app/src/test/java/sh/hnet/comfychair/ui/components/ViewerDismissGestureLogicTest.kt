package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下滑脱离手势的三条纯判定规则（实现见 ViewerDismissGestureLogic.kt）：
 * 1. 方向锁定——水平必须让给 Pager 翻页，只有明显垂直才进脱离拖动；
 * 2. 松手关闭——位移或甩速任一达标即关，否则弹回；
 * 3. 位移→缩放/透明度映射——两端 clamp，不能缩没了或完全消失。
 */
class ViewerDismissGestureLogicTest {

    // ---------- 1. 方向锁定 ----------

    @Test
    fun `累计位移没过阈值不算方向`() {
        // 8dp 阈值以内都是"未决"，此时既不 consume 也不动图片，轻点不受影响
        assertEquals(
            ViewerPanDirection.UNDECIDED,
            ViewerDismissGesture.resolvePanDirection(
                accumulatedDx = 5f,
                accumulatedDy = 6f,
                thresholdPx = ViewerDismissGesture.DIRECTION_LOCK_THRESHOLD_DP
            )
        )
    }

    @Test
    fun `水平主导锁定为水平`() {
        // dx=50 明显大于 dy，手势应整体让给 Pager 翻页
        assertEquals(
            ViewerPanDirection.HORIZONTAL,
            ViewerDismissGesture.resolvePanDirection(50f, 10f, 8f)
        )
    }

    @Test
    fun `垂直主导锁定为垂直`() {
        // dy=50 明显大于 dx，进入下滑脱离拖动
        assertEquals(
            ViewerPanDirection.VERTICAL,
            ViewerDismissGesture.resolvePanDirection(10f, 50f, 8f)
        )
    }

    @Test
    fun `斜向偏水平时算水平`() {
        // |dy|=55 未超过 |dx|*1.2=60：斜向偏水平一律算水平，不抢翻页
        assertEquals(
            ViewerPanDirection.HORIZONTAL,
            ViewerDismissGesture.resolvePanDirection(50f, 55f, 8f)
        )
    }

    @Test
    fun `恰好等于1点2倍偏置仍算水平`() {
        // |dy| = |dx| * 1.2 的边界：判定条件是"大于"，相等不给垂直
        assertEquals(
            ViewerPanDirection.HORIZONTAL,
            ViewerDismissGesture.resolvePanDirection(50f, 60f, 8f)
        )
    }

    // ---------- 2. 松手是否关闭 ----------

    /** 便于换算：屏高 2000px 时 22% = 440px；速度阈值按 3x 密度取 2700px/s */
    private val screenHeight = 2000f
    private val velocityThreshold =
        ViewerDismissGesture.DISMISS_VELOCITY_DP_PER_SEC * 3f

    @Test
    fun `位移达到屏高22个百分点就关闭即使没有甩速`() {
        // 慢慢拖过 22% 线，松手速度为 0 也要关
        assertTrue(
            ViewerDismissGesture.shouldDismiss(
                dy = screenHeight * ViewerDismissGesture.DISMISS_DISTANCE_RATIO + 1f,
                screenHeightPx = screenHeight,
                velocityYDownPxPerSec = 0f,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `向下甩速够快就关闭即使位移很小`() {
        // 快速下甩（fling）：没拖多远也关
        assertTrue(
            ViewerDismissGesture.shouldDismiss(
                dy = 100f,
                screenHeightPx = screenHeight,
                velocityYDownPxPerSec = velocityThreshold + 1f,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `位移和速度都不达标必须弹回`() {
        assertFalse(
            ViewerDismissGesture.shouldDismiss(
                dy = screenHeight * 0.1f,
                screenHeightPx = screenHeight,
                velocityYDownPxPerSec = velocityThreshold * 0.5f,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `向上拖或向上甩永远不关闭`() {
        // 负值 = 向上：距离条件为假；向上甩速度为负也追不上正向阈值
        assertFalse(
            ViewerDismissGesture.shouldDismiss(
                dy = -500f,
                screenHeightPx = screenHeight,
                velocityYDownPxPerSec = -velocityThreshold * 2f,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    // ---------- 3. 位移 → 缩放/透明度映射 ----------

    @Test
    fun `零位移保持原始大小与不透明`() {
        val visuals = ViewerDismissGesture.dismissTransform(0f, screenHeight)
        assertEquals(1f, visuals.scale, 0.0001f)
        assertEquals(1f, visuals.alpha, 0.0001f)
    }

    @Test
    fun `拖到半屏按比例缩小变淡`() {
        // 半屏：scale = 1 - 0.5*0.35 = 0.825；alpha = 1 - 0.5*0.8 = 0.6
        val visuals = ViewerDismissGesture.dismissTransform(screenHeight * 0.5f, screenHeight)
        assertEquals(0.825f, visuals.scale, 0.0001f)
        assertEquals(0.6f, visuals.alpha, 0.0001f)
    }

    @Test
    fun `位移超过屏高被clamp在下限`() {
        // 拖出一屏多：缩放不低于 0.65、不透明度不低于 0.2（图片永远可辨）
        val visuals = ViewerDismissGesture.dismissTransform(screenHeight * 1.5f, screenHeight)
        assertEquals(0.65f, visuals.scale, 0.0001f)
        assertEquals(0.2f, visuals.alpha, 0.0001f)
    }

    @Test
    fun `向上位移或屏高无效都按原样处理`() {
        // 向上拖不进脱离模式；屏高未测得（0）时也不能出 NaN 污染 graphicsLayer
        val upward = ViewerDismissGesture.dismissTransform(-200f, screenHeight)
        assertEquals(1f, upward.scale, 0.0001f)
        assertEquals(1f, upward.alpha, 0.0001f)

        val noSize = ViewerDismissGesture.dismissTransform(100f, 0f)
        assertEquals(1f, noSize.scale, 0.0001f)
        assertEquals(1f, noSize.alpha, 0.0001f)
    }

    // ---------- 4. 捏合返回（C1） ----------

    @Test
    fun `单指捏合不触发退出`() {
        // 单指一律交给既有方向锁定逻辑（水平让给 Pager、垂直走下滑脱离）
        assertFalse(
            ViewerDismissGesture.shouldDismissByPinch(
                currentScale = 1f,
                gestureScale = 0.5f,
                pointerCount = 1
            )
        )
    }

    @Test
    fun `双指缩小过阈值触发退出`() {
        // 未放大态双指捏合缩小到 0.92 以下：iOS 相册式的"捏合退出"
        assertTrue(
            ViewerDismissGesture.shouldDismissByPinch(
                currentScale = 1f,
                gestureScale = 0.9f,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `双指放大不触发退出`() {
        // 张开手指是放大意图，与退出无关
        assertFalse(
            ViewerDismissGesture.shouldDismissByPinch(
                currentScale = 1f,
                gestureScale = 1.1f,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `已放大态捏合缩小不退出`() {
        // scale 超过 1.05 门槛时捏合只负责"缩回 1f"（既有 coerceIn 语义），不退出
        assertFalse(
            ViewerDismissGesture.shouldDismissByPinch(
                currentScale = 1.1f,
                gestureScale = 0.5f,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `恰好等于阈值触发退出`() {
        // gestureScale == PINCH_DISMISS_SCALE 的边界：判定条件是"小于等于"
        assertTrue(
            ViewerDismissGesture.shouldDismissByPinch(
                currentScale = 1f,
                gestureScale = ViewerDismissGesture.PINCH_DISMISS_SCALE,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `略大于阈值不退出`() {
        // 缩小量没到 8%：不做退出，等手指继续捏
        assertFalse(
            ViewerDismissGesture.shouldDismissByPinch(
                currentScale = 1f,
                gestureScale = 0.93f,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `三指捏合同样触发退出`() {
        // pointerCount >= 2 都算捏合手势
        assertTrue(
            ViewerDismissGesture.shouldDismissByPinch(
                currentScale = 1f,
                gestureScale = 0.8f,
                pointerCount = 3
            )
        )
    }

    @Test
    fun `恰好等于放大门槛仍视为未放大`() {
        // currentScale == PINCH_ZOOMED_THRESHOLD 的边界：判定条件是"大于"，相等不算已放大
        assertTrue(
            ViewerDismissGesture.shouldDismissByPinch(
                currentScale = ViewerDismissGesture.PINCH_ZOOMED_THRESHOLD,
                gestureScale = 0.5f,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `没有手指按下不触发退出`() {
        // pointerCount < 2（含 0）一律 false，兜住异常输入
        assertFalse(
            ViewerDismissGesture.shouldDismissByPinch(
                currentScale = 1f,
                gestureScale = 0.5f,
                pointerCount = 0
            )
        )
    }
}
