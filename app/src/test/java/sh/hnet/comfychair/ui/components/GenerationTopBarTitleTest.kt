package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Q6（v4.16.6）顶栏标题字号选择纯函数测试。
 *
 * 钉住三档判据与派工单给的两个主战场场景：
 * - 360dp 屏图生视频（5 控件占 276dp，标题槽剩 84dp）→ 降档（22sp 需 96 / 20sp 需 88 都不够）；
 * - 393dp 屏图生视频（标题槽剩 117dp）→ 不降档（22sp 原字号放得下）。
 */
class GenerationTopBarTitleTest {

    @Test
    fun `360dp屏图生视频四个汉字_降档不出省略号`() {
        // 360 - 276 = 84dp；"图生视频" 4 字：22sp 需 96 / 20sp 需 88 都放不下 → 16sp
        assertEquals(16, resolveTopBarTitleFontSize(availableWidthDp = 84f, textCharCount = 4))
    }

    @Test
    fun `393dp屏图生视频四个汉字_保持titleLarge不降档`() {
        // 393 - 276 = 117dp ≥ 96 → 22sp
        assertEquals(22, resolveTopBarTitleFontSize(availableWidthDp = 117f, textCharCount = 4))
    }

    @Test
    fun `360dp屏文生视频四个汉字_不降档`() {
        // 4 控件占 218dp：360 - 218 = 142dp ≥ 96 → 22sp
        assertEquals(22, resolveTopBarTitleFontSize(availableWidthDp = 142f, textCharCount = 4))
    }

    @Test
    fun `三档边界_按字符数乘字号加安全边距判定`() {
        // 恰好放得下（= 阈值）取大档，差 0.1 放不下降一档
        assertEquals(22, resolveTopBarTitleFontSize(4 * 22f + TITLE_WIDTH_SAFETY_DP, 4))
        assertEquals(20, resolveTopBarTitleFontSize(4 * 22f + TITLE_WIDTH_SAFETY_DP - 0.1f, 4))
        assertEquals(20, resolveTopBarTitleFontSize(4 * 20f + TITLE_WIDTH_SAFETY_DP, 4))
        assertEquals(16, resolveTopBarTitleFontSize(4 * 20f + TITLE_WIDTH_SAFETY_DP - 0.1f, 4))
    }

    @Test
    fun `分屏300dp以下极端场景_最低档兜底`() {
        // 5 控件 276dp 在 300dp 屏标题槽只剩 24dp：4 字 16sp 需 72 也放不下 → 仍给 16sp
        //（ellipsis 兜底截断，但没有更小的档了）
        assertEquals(16, resolveTopBarTitleFontSize(24f, 4))
    }
}
