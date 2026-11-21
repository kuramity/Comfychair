package sh.hnet.comfychair.ui.components.ripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.ui.components.BUSY_CONTENT_ALPHA
import sh.hnet.comfychair.ui.components.resolveGenerationButtonSkin

/**
 * v4.16.14「点生成瞬间水波消失」修复的判定契约测试。
 *
 * 缺陷：生成主按钮的 `isEnabled` 混着两类语义 ——「提示词为空」的真禁用 与
 * 「!isSubmitting」的提交忙碌态。修复前皮肤门控只看 enabled，忙碌瞬间
 * 渐变 / 点阵 / 白边整层被拆掉，用户看到的就是"点下生成按钮变深灰、水波消失"。
 *
 * 本测试钉住 [RippleSkinState] 的四象限（可点 / 忙碌 / 真禁用 × 皮肤开关开/关）：
 * - 忙碌必须与可点一样保留皮肤（修复点）；
 * - 真禁用必须保持零皮肤（回归保护：不能被顺手改成亮色）；
 * - 皮肤总开关关闭时任何状态都不画；
 * - 忙碌态的降档系数与文字 alpha 按规格（0.88 / 0.92）。
 *
 * 全部走纯函数 / 常量，JVM 单测可直接跑，不进 Compose 组装。
 */
class RippleSkinStateTest {

    // ============================================================
    // skinActive：四象限（皮肤总开关开）
    // ============================================================

    @Test
    fun `可点且皮肤开_皮肤启用`() {
        assertTrue(RippleSkinState.skinActive(dotFeedbackEnabled = true, enabled = true, busy = false))
    }

    @Test
    fun `忙碌且皮肤开_皮肤必须启用_修复点`() {
        // 关键断言：忙碌（提交闸门在途）不等于禁用，渐变/点阵/白边必须留下
        assertTrue(RippleSkinState.skinActive(dotFeedbackEnabled = true, enabled = false, busy = true))
    }

    @Test
    fun `真禁用且皮肤开_皮肤不得启用_回归保护`() {
        // 提示词为空 / 离线：灰底、无渐变、无点阵 —— 不许被忙碌态的改动顺手带亮
        assertFalse(RippleSkinState.skinActive(dotFeedbackEnabled = true, enabled = false, busy = false))
    }

    @Test
    fun `可点且忙碌同时成立_皮肤启用`() {
        // 取回结果的瞬间 isFetching=true 而按钮仍可点：不能因为忙就丢掉皮肤
        assertTrue(RippleSkinState.skinActive(dotFeedbackEnabled = true, enabled = true, busy = true))
    }

    // ============================================================
    // skinActive：皮肤总开关关闭（任何状态一律不启用）
    // ============================================================

    @Test
    fun `皮肤总开关关闭_可点忙碌真禁用一律不启用`() {
        assertFalse(RippleSkinState.skinActive(dotFeedbackEnabled = false, enabled = true, busy = false))
        assertFalse(RippleSkinState.skinActive(dotFeedbackEnabled = false, enabled = false, busy = true))
        assertFalse(RippleSkinState.skinActive(dotFeedbackEnabled = false, enabled = false, busy = false))
        assertFalse(RippleSkinState.skinActive(dotFeedbackEnabled = false, enabled = true, busy = true))
    }

    // ============================================================
    // dotsVisible：四象限（点阵开）
    // ============================================================

    @Test
    fun `点阵开_可点绘制_忙碌也绘制`() {
        assertTrue(RippleSkinState.dotsVisible(showDots = true, enabled = true, busy = false))
        // 关键断言：提交往返期间已扩散的水波必须继续画（修复点）
        assertTrue(RippleSkinState.dotsVisible(showDots = true, enabled = false, busy = true))
    }

    @Test
    fun `点阵开_真禁用不绘制_回归保护`() {
        assertFalse(RippleSkinState.dotsVisible(showDots = true, enabled = false, busy = false))
    }

    @Test
    fun `点阵关闭_任何状态一律不绘制`() {
        // U1 收敛：下拉/顶栏图标不传 showDots=true，忙碌与否都不得画点阵
        assertFalse(RippleSkinState.dotsVisible(showDots = false, enabled = true, busy = false))
        assertFalse(RippleSkinState.dotsVisible(showDots = false, enabled = false, busy = true))
        assertFalse(RippleSkinState.dotsVisible(showDots = false, enabled = false, busy = false))
    }

    // ============================================================
    // busyContainerTransparent：忙碌才透明（透明是为让渐变露出）
    // ============================================================

    @Test
    fun `忙碌且皮肤开_禁用容器透明以露出渐变`() {
        assertTrue(RippleSkinState.busyContainerTransparent(busy = true, dotFeedbackEnabled = true))
    }

    @Test
    fun `非忙碌_禁用容器保持M3灰底`() {
        assertFalse(RippleSkinState.busyContainerTransparent(busy = false, dotFeedbackEnabled = true))
        assertFalse(RippleSkinState.busyContainerTransparent(busy = false, dotFeedbackEnabled = false))
    }

    @Test
    fun `忙碌但皮肤关闭_禁用容器不透明_否则只会剩文字`() {
        assertFalse(RippleSkinState.busyContainerTransparent(busy = true, dotFeedbackEnabled = false))
    }

    // ============================================================
    // dotsRegister：忙碌态不登记新波纹（v4.16.14 复核 BUSY-01）
    // ============================================================

    @Test
    fun `点阵开_只有可点才登记新波纹_忙碌不登记`() {
        // 修复点：忙碌态只继续推进已扩散的波纹，disabled 按钮按下去不能再播一整圈
        assertTrue(RippleSkinState.dotsRegister(showDots = true, enabled = true))
        assertFalse(RippleSkinState.dotsRegister(showDots = true, enabled = false))
    }

    @Test
    fun `点阵关闭_任何状态都不登记新波纹`() {
        assertFalse(RippleSkinState.dotsRegister(showDots = false, enabled = true))
        assertFalse(RippleSkinState.dotsRegister(showDots = false, enabled = false))
    }

    // ============================================================
    // 生成主按钮整页判定（v4.16.14 复核 BUSY-03 / BUSY-04）
    // ============================================================

    @Test
    fun `本页提交中_忙碌皮肤启用且禁用容器透明`() {
        val skin = resolveGenerationButtonSkin(
            isEnabled = false,
            isOffline = false,
            isConnecting = false,
            isUploading = false,
            isFetching = false,
            isSubmitting = true,
            dotFeedbackEnabled = true,
        )
        assertFalse(skin.enabled)
        assertTrue(skin.busy)
        assertTrue(skin.skinActive)
        assertTrue(skin.busyContainerTransparent)
    }

    @Test
    fun `别页提交且本页输入不合法_必须保持真禁用灰底`() {
        // 页面侧已按 ownerId 过滤：别页提交时本页收到的 isSubmitting=false（BUSY-03）
        val skin = resolveGenerationButtonSkin(
            isEnabled = false,
            isOffline = false,
            isConnecting = false,
            isUploading = false,
            isFetching = false,
            isSubmitting = false,
            dotFeedbackEnabled = true,
        )
        assertFalse(skin.busy)
        assertFalse(skin.skinActive)
        assertFalse(skin.busyContainerTransparent)
    }

    @Test
    fun `离线时即使所有忙碌信号都亮_也必须真禁用灰底`() {
        // BUSY-04：提交途中掉线/强制离线不得继续显示忙碌皮肤
        val skin = resolveGenerationButtonSkin(
            isEnabled = true,
            isOffline = true,
            isConnecting = true,
            isUploading = true,
            isFetching = true,
            isSubmitting = true,
            dotFeedbackEnabled = true,
        )
        assertFalse(skin.enabled)
        assertFalse(skin.busy)
        assertFalse(skin.skinActive)
        assertFalse(skin.busyContainerTransparent)
    }

    @Test
    fun `在线可点_皮肤启用但禁用容器不透明`() {
        val skin = resolveGenerationButtonSkin(
            isEnabled = true,
            isOffline = false,
            isConnecting = false,
            isUploading = false,
            isFetching = false,
            isSubmitting = false,
            dotFeedbackEnabled = true,
        )
        assertTrue(skin.enabled)
        assertFalse(skin.busy)
        assertTrue(skin.skinActive)
        assertFalse(skin.busyContainerTransparent)
    }

    @Test
    fun `皮肤总开关关闭_忙碌也不启用皮肤`() {
        val skin = resolveGenerationButtonSkin(
            isEnabled = false,
            isOffline = false,
            isConnecting = false,
            isUploading = false,
            isFetching = false,
            isSubmitting = true,
            dotFeedbackEnabled = false,
        )
        assertTrue(skin.busy)
        assertFalse(skin.skinActive)
        assertFalse(skin.busyContainerTransparent)
    }

    // ============================================================
    // 视觉常数规格
    // ============================================================

    @Test
    fun `忙碌态降档系数为0_88_文字alpha为0_92`() {
        assertEquals(0.88f, BUSY_SKIN_DIM_ALPHA, 1e-6f)
        assertEquals(0.92f, BUSY_CONTENT_ALPHA, 1e-6f)
    }

    @Test
    fun `忙碌降档不是禁用降档_白边不会掉进禁用档`() {
        // 启用档深色 22% × 0.88 = 19.36%，明显高于禁用档 10% —— 白边不能"突然变淡一半"
        val busyBorderAlpha = BORDER_ALPHA_DARK_V2 * BUSY_SKIN_DIM_ALPHA
        assertTrue(
            "忙碌档 $busyBorderAlpha 必须高于禁用档 $BORDER_ALPHA_DARK_V2_DISABLED",
            busyBorderAlpha > BORDER_ALPHA_DARK_V2_DISABLED,
        )
    }
}