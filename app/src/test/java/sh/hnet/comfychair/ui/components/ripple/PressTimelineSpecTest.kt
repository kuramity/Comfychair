package sh.hnet.comfychair.ui.components.ripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏按压时序规格测试（v4.16.1，规格 A1/A2）。
 *
 * "快进 → 定格 → 慢出"三段时长钉在这里：任何一段被改动，"按下立即亮 /
 * 松手停留 0.15 秒再淡出"的手感就会回退成 spring 惯性态（快击时白框迟到）。
 * 动画序列本身（Animatable + tween）在真机采样验证，这里钉住的是驱动它的规格常量。
 */
class PressTimelineSpecTest {

    @Test
    fun `快进段不超过60ms`() {
        // 按下立即亮的上限：超过 60ms 用户就能感知"迟到"
        assertTrue("快进段应 ≤ 60ms，实际 $PRESS_FEEDBACK_IN_MS", PRESS_FEEDBACK_IN_MS <= 60)
        assertEquals(60, PRESS_FEEDBACK_IN_MS)
    }

    @Test
    fun `定格段为150ms`() {
        // 松手后停留 0.15 秒（这是"定格"，不是淡出的一部分）
        assertEquals(150, PRESS_FEEDBACK_HOLD_MS)
    }

    @Test
    fun `慢出段为150ms`() {
        assertEquals(150, PRESS_FEEDBACK_OUT_MS)
    }

    @Test
    fun `定格与慢出合计约300ms_属于直给动画的合理窗口`() {
        // 防止有人把定格调得过长（按完白框挂半秒会显得"粘"）或慢出过短（接近硬切）
        val total = PRESS_FEEDBACK_HOLD_MS + PRESS_FEEDBACK_OUT_MS
        assertTrue("松手后总时长应落在 200~500ms，实际 $total", total in 200..500)
    }
}
