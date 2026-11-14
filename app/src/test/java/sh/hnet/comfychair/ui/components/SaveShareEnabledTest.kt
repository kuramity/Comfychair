package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏「保存/分享常驻」可用性判定的组合测试（bug 修复的回归钉子）。
 *
 * v5 起保存/分享按钮恒定渲染，可用性只由 [saveShareEnabled] 决定：
 * 4 种组合的行为必须精确如下，任何一个被改动都会复现
 * "按钮只在出图后出现、再生成一次还闪没"或"无结果也能点保存"的老 bug。
 */
class SaveShareEnabledTest {

    @Test
    fun `无结果且非编辑态_禁用`() {
        // 生成前的典型状态：按钮常驻显示但置灰
        assertFalse(saveShareEnabled(hasResult = false, isEditing = false))
    }

    @Test
    fun `有结果且非编辑态_可用`() {
        // 出图后的典型状态：正常可点
        assertTrue(saveShareEnabled(hasResult = true, isEditing = false))
    }

    @Test
    fun `有结果但处于编辑态_临时禁用`() {
        // 图生视频切到编辑视图：按钮不隐藏，只禁用（原逻辑是隐藏，v5 改为 enabled=false）
        assertFalse(saveShareEnabled(hasResult = true, isEditing = true))
    }

    @Test
    fun `无结果且编辑态_禁用`() {
        // 双重不可用：禁用语义保持一致，不产生任何"可点"窗口
        assertFalse(saveShareEnabled(hasResult = false, isEditing = true))
    }
}
