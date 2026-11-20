package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * G4 队列竖滑快捷删除的纯逻辑测试：
 * - 归宿判定**双向对称**（上滑与下滑等价，任务书原文"上滑或下滑"）；
 * - 视觉映射双向对称 + 单调 + 越界钳制 + NaN 全兜底（破坏性手势的输入纪律）；
 * - 进站终点符号与拖拽方向一致；
 * - 背景分支仲裁：删除手势激活期间不得走 DISMISS_LINEAR（否则页内黑块盖住并排露边邻图）。
 *
 * 手势本身（pointerInput 链路）跑不进 JVM，钉住判定/映射这两段算术 ——
 * 与 ViewerOverscrollTest / ViewerPageMotionTest 同一测试策略。
 */
class ViewerSwipeDeleteLogicTest {

    private val height = 2000f          // 模拟屏高（px）
    private val velocityThreshold = 900f // 模拟阈值（px/s）

    // ============================================================
    // resolveOutcome：双向阈值
    // ============================================================

    @Test
    fun `下滑超阈值 - 进站`() {
        assertEquals(
            SwipeDeleteOutcome.ENTER_TRASH,
            ViewerSwipeDelete.resolveOutcome(
                dyPx = height * ViewerSwipeDelete.DELETE_DISTANCE_RATIO,
                screenHeightPx = height,
                velocityYPxPerSec = 0f,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `上滑同样深度 - 等价进站（双向对称）`() {
        assertEquals(
            SwipeDeleteOutcome.ENTER_TRASH,
            ViewerSwipeDelete.resolveOutcome(
                dyPx = -height * ViewerSwipeDelete.DELETE_DISTANCE_RATIO,
                screenHeightPx = height,
                velocityYPxPerSec = 0f,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `阈值两侧 - 差一点点不进站`() {
        assertEquals(
            SwipeDeleteOutcome.CANCEL,
            ViewerSwipeDelete.resolveOutcome(
                dyPx = height * 0.249f,
                screenHeightPx = height,
                velocityYPxPerSec = 0f,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `甩速达标即进站 - 上下两个方向都算（取绝对值）`() {
        assertEquals(
            SwipeDeleteOutcome.ENTER_TRASH,
            ViewerSwipeDelete.resolveOutcome(
                dyPx = 0f, screenHeightPx = height,
                velocityYPxPerSec = velocityThreshold,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
        assertEquals(
            SwipeDeleteOutcome.ENTER_TRASH,
            ViewerSwipeDelete.resolveOutcome(
                dyPx = 0f, screenHeightPx = height,
                velocityYPxPerSec = -velocityThreshold,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
        // 差 1px/s 不算
        assertEquals(
            SwipeDeleteOutcome.CANCEL,
            ViewerSwipeDelete.resolveOutcome(
                dyPx = 0f, screenHeightPx = height,
                velocityYPxPerSec = velocityThreshold - 1f,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `异常输入一律 CANCEL - 不确定时宁可什么都不删`() {
        assertEquals(
            SwipeDeleteOutcome.CANCEL,
            ViewerSwipeDelete.resolveOutcome(Float.NaN, height, 0f, velocityThreshold)
        )
        assertEquals(
            SwipeDeleteOutcome.CANCEL,
            ViewerSwipeDelete.resolveOutcome(height, 0f, 0f, velocityThreshold)
        )
        // 甩速 NaN 不能独立触发进站（距离不足时按速度判定要看它有限）
        assertEquals(
            SwipeDeleteOutcome.CANCEL,
            ViewerSwipeDelete.resolveOutcome(0f, height, Float.NaN, velocityThreshold)
        )
    }

    // ============================================================
    // visuals：双向对称 + 单调 + 钳制 + NaN 兜底
    // ============================================================

    @Test
    fun `零位移恒等视觉`() {
        val v = ViewerSwipeDelete.visuals(0f, height)
        assertEquals(1f, v.scale, 0f)
        assertEquals(1f, v.alpha, 0f)
        assertEquals(0f, v.rotationDeg, 0f)
    }

    @Test
    fun `同深度上滑下滑 - scale 与 alpha 对称`() {
        val down = ViewerSwipeDelete.visuals(600f, height)
        val up = ViewerSwipeDelete.visuals(-600f, height)
        assertEquals(down.scale, up.scale, 0f)
        assertEquals(down.alpha, up.alpha, 0f)
    }

    @Test
    fun `旋转方向与拖拽方向一致（上滑向上翻、下滑向下翻）`() {
        assertTrue(ViewerSwipeDelete.visuals(600f, height).rotationDeg > 0f)
        assertTrue(ViewerSwipeDelete.visuals(-600f, height).rotationDeg < 0f)
    }

    @Test
    fun `进度随深度单调 - 越拖越小越透明`() {
        val a = ViewerSwipeDelete.visuals(200f, height)
        val b = ViewerSwipeDelete.visuals(500f, height)
        val c = ViewerSwipeDelete.visuals(900f, height)
        assertTrue(a.scale > b.scale && b.scale > c.scale)
        assertTrue(a.alpha > b.alpha && b.alpha > c.alpha)
    }

    @Test
    fun `钳制 - 拖出屏外数值不越界（进站动画段同样安全）`() {
        val v = ViewerSwipeDelete.visuals(height * 10f, height)
        // 1f - 0.65f 的二进制浮点结果与 0.35f 字面量差 ~2e-8，钳位下限断言用工程容差
        assertEquals(0.35f, v.scale, 1e-4f)
        assertTrue(v.alpha in 0.02f..1f)
        assertTrue(abs(v.rotationDeg) <= 10f)
    }

    @Test
    fun `NaN 与未测得屏高 - 恒等视觉（绝不把 NaN 写进 graphicsLayer）`() {
        val nan = ViewerSwipeDelete.visuals(Float.NaN, height)
        assertEquals(1f, nan.scale, 0f)
        val noSize = ViewerSwipeDelete.visuals(500f, 0f)
        assertEquals(1f, noSize.alpha, 0f)
    }

    // ============================================================
    // flyTargetDy：进站终点
    // ============================================================

    @Test
    fun `终点符号跟随拖出方向，幅度足以飞出视口`() {
        assertEquals(height * 1.3f, ViewerSwipeDelete.flyTargetDy(500f, height), 0f)
        assertEquals(-height * 1.3f, ViewerSwipeDelete.flyTargetDy(-500f, height), 0f)
        // 终点代入 visuals 落在饱和极值（衔接零跳变的实现依据）
        val end = ViewerSwipeDelete.visuals(ViewerSwipeDelete.flyTargetDy(500f, height), height)
        assertEquals(0.35f, end.scale, 1e-4f)
    }

    @Test
    fun `异常输入返回 0 - 动画退化为原地淡出，不炸`() {
        assertEquals(0f, ViewerSwipeDelete.flyTargetDy(0f, height), 0f)
        assertEquals(0f, ViewerSwipeDelete.flyTargetDy(Float.NaN, height), 0f)
        assertEquals(0f, ViewerSwipeDelete.flyTargetDy(500f, 0f), 0f)
    }

    // ============================================================
    // resolveBackgroundBranch：删除手势激活期间的仲裁（新增参数行为）
    // ============================================================

    @Test
    fun `删除手势在途 - 并排态不走 DISMISS_LINEAR（黑块不盖两侧邻图）`() {
        assertEquals(
            ViewerBackgroundBranch.CONTAINER_BLACK,
            ViewerDismissGesture.resolveBackgroundBranch(
                hasFlyBackCellRect = false,
                inFullScreen = false,
                dismissY = 500f,
                inSwipeDelete = true
            )
        )
    }

    @Test
    fun `删除手势在途 - 全屏兜底仍满页黑（盖截图职责不变）`() {
        assertEquals(
            ViewerBackgroundBranch.FULL_PAGE_BLACK,
            ViewerDismissGesture.resolveBackgroundBranch(
                hasFlyBackCellRect = false,
                inFullScreen = true,
                dismissY = 500f,
                inSwipeDelete = true
            )
        )
    }

    @Test
    fun `删除手势不在途 - 原判定表逐条不变（G1 行为零变化）`() {
        assertEquals(
            ViewerBackgroundBranch.FLYBACK_MASK,
            ViewerDismissGesture.resolveBackgroundBranch(true, true, 0f)
        )
        assertEquals(
            ViewerBackgroundBranch.DISMISS_LINEAR,
            ViewerDismissGesture.resolveBackgroundBranch(false, true, 500f)
        )
        assertEquals(
            ViewerBackgroundBranch.DISMISS_LINEAR,
            ViewerDismissGesture.resolveBackgroundBranch(false, false, 500f)
        )
        assertEquals(
            ViewerBackgroundBranch.FULL_PAGE_BLACK,
            ViewerDismissGesture.resolveBackgroundBranch(false, true, 0f)
        )
        assertEquals(
            ViewerBackgroundBranch.CONTAINER_BLACK,
            ViewerDismissGesture.resolveBackgroundBranch(false, false, 0f)
        )
    }

    @Test
    fun `常量锁定 - 删除阈值必须比关闭阈值更苛刻（破坏性操作）`() {
        assertTrue(
            ViewerSwipeDelete.DELETE_DISTANCE_RATIO > ViewerDismissGesture.DISMISS_DISTANCE_RATIO
        )
        assertTrue(
            ViewerSwipeDelete.DELETE_VELOCITY_DP_PER_SEC >= ViewerDismissGesture.DISMISS_VELOCITY_DP_PER_SEC
        )
    }

    @Test
    fun `撤销窗口 = 2 秒（用户已确认的交互决策）`() {
        assertEquals(2000L, ViewerSwipeDelete.UNDO_WINDOW_MS)
    }
}
