package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查看器页背景分支选择（N3/N4，v4.16.8）与并排接管归零判定（N5，v4.16.8）的纯逻辑测试。
 *
 * 钉住四件事：
 * 1. 进入页全屏 → E1/E2 分段遮罩（透出逻辑不变）；
 * 2. 脱离拖动中 → 旧线性映射（并排态下拉同样走这条）；
 * 3. **其余一律有黑**——非进入页全屏、并排态、加载中邻页都必须命中档位/满宽黑，
 *    绝不允许出现"无背景"分支（N3/N4 的透图库 bug）；
 * 4. N5：pager 翻页残留只有超过 1% 视口宽才发起归零，0 与微小抖动不打扰 pager。
 *
 * 实现级绑定（v4.16.8 返工）：生产绘制点 ImageViewer.drawBehind 直接调用
 * resolveBackgroundBranch、按返回枚举绘制（ImageViewer.kt drawBehind 内的
 * when 分支），不再内联复制判定。因此本文件的判定表断言就是绘制行为的
 * 断言：判定表退化 → 本组测试必红 → 绘制不可能悄悄回退。
 */
class ViewerBackgroundBranchTest {

    // ---------- 1. 进入页全屏：E1/E2 ----------

    @Test
    fun `进入页全屏走分段遮罩`() {
        // 进入查看器那一刻的那一页：带格子矩形、未并排、未拖动
        assertEquals(
            ViewerBackgroundBranch.FLYBACK_MASK,
            ViewerDismissGesture.resolveBackgroundBranch(
                hasFlyBackCellRect = true,
                inFullScreen = true,
                dismissY = 0f
            )
        )
        // 拖动中（分段遮罩的 alpha 由 maskAlphaAnimatable 驱动，分支选择不变）
        assertEquals(
            ViewerBackgroundBranch.FLYBACK_MASK,
            ViewerDismissGesture.resolveBackgroundBranch(true, true, 120f)
        )
    }

    // ---------- 2. 脱离拖动中：线性映射 ----------

    @Test
    fun `非进入页下滑走线性遮罩`() {
        // 已翻页（无格子矩形）的页下拉关闭：旧线性映射保持不变
        assertEquals(
            ViewerBackgroundBranch.DISMISS_LINEAR,
            ViewerDismissGesture.resolveBackgroundBranch(false, true, 300f)
        )
        // 并排态下拉（D5）：先于并排分支命中
        assertEquals(
            ViewerBackgroundBranch.DISMISS_LINEAR,
            ViewerDismissGesture.resolveBackgroundBranch(true, false, 300f)
        )
    }

    // ---------- 3. 其余一律档位/满宽黑 ----------

    @Test
    fun `非进入页全屏必须画满宽黑`() {
        // N4 核心：非进入页（邻页/已翻页）全屏静止时，旧版什么都不画 → 整页透明
        // 透出图库；新版必须命中档位黑（sideScale=1 → 满宽满高）
        assertEquals(
            ViewerBackgroundBranch.SIDE_OR_FULL_BLACK,
            ViewerDismissGesture.resolveBackgroundBranch(false, true, 0f)
        )
    }

    @Test
    fun `并排态走档位黑`() {
        // 并排态（不论有无格子矩形——并排时格子矩形页走分支一的条件不满足）
        assertEquals(
            ViewerBackgroundBranch.SIDE_OR_FULL_BLACK,
            ViewerDismissGesture.resolveBackgroundBranch(false, false, 0f)
        )
        assertEquals(
            ViewerBackgroundBranch.SIDE_OR_FULL_BLACK,
            ViewerDismissGesture.resolveBackgroundBranch(true, false, 0f)
        )
    }

    @Test
    fun `加载中邻页无格子矩形也必须有黑`() {
        // 加载中/失败的邻页：内容为空且无格子矩形，全屏态。旧版整页透明（注释写着
        // "show black"实际什么都不画）；新版满宽满高黑
        assertEquals(
            ViewerBackgroundBranch.SIDE_OR_FULL_BLACK,
            ViewerDismissGesture.resolveBackgroundBranch(false, true, 0f)
        )
    }

    /**
     * 反证：假如把"非进入页全屏"判成"无背景"（旧版行为），第 3 组断言必失败。
     *
     * 本测试在测试内放一个旧实现（v4.16.7 的分支三条件 `!inFullScreen`）的镜像，
     * 用同一输入对比：非进入页 + 全屏时旧镜像不画黑（无背景），正实现命中
     * SIDE_OR_FULL_BLACK——两者结论相反。若实现退化回旧条件，上面的
     * assertEquals(SIDE_OR_FULL_BLACK) 必失败（测试不是恒真的）。
     * 实现级反证已实测（v4.16.8 返工）：drawBehind 现在直接调用本函数，把
     * else 分支临时改成返回 DISMISS_LINEAR 后本组 4 条断言当场失败
     * （9 tests, 4 failed），改回后全绿——判定表被本组测试真实锁住。
     */
    @Test
    fun `反证_旧分支条件会把非进入页全屏判成无背景`() {
        val hasFlyBackCellRect = false
        val inFullScreen = true
        // 旧镜像：v4.16.7 的分支三条件 !inFullScreen——全屏态不命中 → 无背景
        val legacyWouldDrawBlack = !inFullScreen
        assertFalse(legacyWouldDrawBlack)
        // 正实现同一输入必须有黑：
        val resolved = ViewerDismissGesture.resolveBackgroundBranch(
            hasFlyBackCellRect, inFullScreen, 0f
        )
        assertEquals(ViewerBackgroundBranch.SIDE_OR_FULL_BLACK, resolved)
        // 新旧结论相反 → 正实现的断言真实钉住了这次修复
        assertNotEquals(legacyWouldDrawBlack, resolved == ViewerBackgroundBranch.SIDE_OR_FULL_BLACK)
    }

    // ---------- 4. N5：pager 残留归零判定 ----------

    @Test
    fun `翻页残留超过1个百分点就要归零`() {
        // 翻页拖到一半被双指打断：fraction 冻结在 ±0.5 都是残留
        assertTrue(ViewerSideBySide.shouldNormalizePager(0.5f))
        assertTrue(ViewerSideBySide.shouldNormalizePager(-0.5f))
        // 刚起步的残留也要归（>0.01 的开区间）
        assertTrue(ViewerSideBySide.shouldNormalizePager(0.011f))
        assertTrue(ViewerSideBySide.shouldNormalizePager(-0.011f))
    }

    @Test
    fun `无残留与微小抖动不归零`() {
        // 静止态 fraction 恒 0：绝大多数双指接管发生在这里，不发起无意义 scroll
        assertFalse(ViewerSideBySide.shouldNormalizePager(0f))
        // 1% 以内的浮点抖动容忍（闭区间边界：恰好 0.01 不触发）
        assertFalse(ViewerSideBySide.shouldNormalizePager(0.01f))
        assertFalse(ViewerSideBySide.shouldNormalizePager(-0.01f))
        assertFalse(ViewerSideBySide.shouldNormalizePager(0.005f))
    }

    @Test
    fun `异常fraction按需要归零兜底`() {
        // NaN / 无穷不属于"静止"，一律归零（scrollToPage 会复位到当前页原点）
        assertTrue(ViewerSideBySide.shouldNormalizePager(Float.NaN))
        assertTrue(ViewerSideBySide.shouldNormalizePager(Float.POSITIVE_INFINITY))
    }
}
