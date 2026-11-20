package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查看器页背景分支选择（N3/N4，v4.16.8；G1，v4.16.11 黑化责任收敛）与并排接管归零判定
 * （N5，v4.16.8）的纯逻辑测试。
 *
 * 钉住四件事：
 * 1. 进入页全屏 → E1/E2 分段遮罩（透出逻辑不变）；
 * 2. 脱离拖动中 → 旧线性映射（并排态下拉同样走这条）；
 * 3. 全屏态**其余一律有黑**——非进入页全屏、加载中邻页都必须命中页内满页黑，
 *    绝不允许出现"无背景"分支（N3/N4 的透图库 bug）；
 * 4. 并排（队列）态页内**不画黑**（CONTAINER_BLACK）——G1 拆掉了"档位宽度黑"
 *    固定宽度补丁，背景黑化改由容器背景（MediaViewerScreen 根 Box 的纯黑）统一
 *    负责。并排态页内再画黑就等于把补丁加回来：补丁宽度（页宽 × 档位比例）与
 *    露边区/越界新带无关，只会重演"补丁宽度即漏洞宽度"；
 * 5. N5：pager 翻页残留只有超过 1% 视口宽才发起归零，0 与微小抖动不打扰 pager。
 *
 * 实现级绑定（v4.16.8 返工，v4.16.11 沿用）：生产绘制点 ImageViewer.drawBehind
 * 直接调用 resolveBackgroundBranch、按返回枚举绘制（ImageViewer.kt drawBehind 内的
 * when 分支），不再内联复制判定。因此本文件的判定表断言就是绘制行为的断言。
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

    // ---------- 3. 全屏态其余情况一律满页黑 ----------

    @Test
    fun `非进入页全屏必须画满页黑`() {
        // N4 核心：非进入页（邻页/已翻页）全屏静止时，旧版什么都不画 → 整页透明
        // 透出图库；新版必须命中页内满页黑
        assertEquals(
            ViewerBackgroundBranch.FULL_PAGE_BLACK,
            ViewerDismissGesture.resolveBackgroundBranch(false, true, 0f)
        )
    }

    @Test
    fun `加载中邻页无格子矩形也必须有黑`() {
        // 加载中/失败的邻页：内容为空且无格子矩形，全屏态。旧版整页透明（注释写着
        // "show black"实际什么都不画）；新版满页黑
        assertEquals(
            ViewerBackgroundBranch.FULL_PAGE_BLACK,
            ViewerDismissGesture.resolveBackgroundBranch(false, true, 0f)
        )
    }

    // ---------- 4. 并排（队列）态：页内不画，由容器背景负责 ----------

    @Test
    fun `并排态交给容器背景`() {
        // G1：并排态页内不再画"档位宽度黑"补丁，背景由容器（根 Box 纯黑）承接。
        // 无论本页有没有飞回格子矩形，并排态都走 CONTAINER_BLACK
        assertEquals(
            ViewerBackgroundBranch.CONTAINER_BLACK,
            ViewerDismissGesture.resolveBackgroundBranch(false, false, 0f)
        )
        assertEquals(
            ViewerBackgroundBranch.CONTAINER_BLACK,
            ViewerDismissGesture.resolveBackgroundBranch(true, false, 0f)
        )
    }

    /**
     * 反证：把 v4.16.7 的真实绘制判定链做成镜像，与当前实现逐格对比。
     *
     * 上一版这里是 `val legacyWouldDrawPageBlack = !inFullScreen` —— 一个只对单个
     * 硬编码向量成立的布尔常量，既没有真的建模旧代码，`assertFalse` 也只是断言了
     * 自己刚写下的字面量（恒真）。这里改成**完整镜像**被替换掉的 drawBehind
     * 分支链（197549a ImageViewer.kt:344-380），并按输入全矩阵对比。
     *
     * 旧链（查看器分页 pageBackground=false，197549a MediaViewerScreen.kt:860）：
     * ① flyBackCellRect != null && inFullScreen → E1/E2 分段遮罩；
     * ② dismissY > 0f → 线性遮罩；
     * ③ !inFullScreen → 档位宽度黑（blackWidth = size.width * sideScale）；
     * ④ 其余（全屏、非进入页、未拖动）→ 落到 pageBackground，而分页传 false → 不画。
     *
     * 关于③的常见误读：blackWidth 那段**只在非全屏时执行**（with-else 链上游已判空），
     * 全屏态 sideScale 虽为 1，代码根本走不到那里，所以"满屏 = 满宽满高黑"不成立，
     * 全屏非进入页在旧版是真的一整页透明（N4 的透图库 bug）。
     */
    private fun legacyV4167DrawsPageBlack(
        hasFlyBackCellRect: Boolean,
        inFullScreen: Boolean,
        dismissY: Float,
        pageBackground: Boolean = false
    ): Boolean = when {
        hasFlyBackCellRect && inFullScreen -> true
        dismissY > 0f -> true
        !inFullScreen -> true
        pageBackground -> true
        else -> false
    }

    /** 现实现的分支 → 页内是否画了黑（CONTAINER_BLACK 交给容器，页内不画） */
    private fun drawsPageBlack(branch: ViewerBackgroundBranch): Boolean = when (branch) {
        ViewerBackgroundBranch.FLYBACK_MASK,
        ViewerBackgroundBranch.DISMISS_LINEAR,
        ViewerBackgroundBranch.FULL_PAGE_BLACK -> true
        ViewerBackgroundBranch.CONTAINER_BLACK -> false
    }

    @Test
    fun `反证_旧绘制链只在全屏非进入页与现实现相反`() {
        // 全矩阵：进入页 / 非进入页 × 全屏 / 并排 × 静止 / 拖动，共 8 格。
        // 每格写成 输入 → (旧镜像是否画黑, 现实现是否画黑)
        val vectors = listOf(
            Triple(true, true, 0f) to (true to true),      // 进入页全屏静止：两边都画
            Triple(true, true, 120f) to (true to true),    // 进入页全屏拖动：分段遮罩
            Triple(false, true, 0f) to (false to true),    // ★ N4：旧版不画 / 新版满页黑
            Triple(false, true, 120f) to (true to true),   // 非进入页全屏拖动：线性遮罩
            Triple(true, false, 0f) to (true to false),    // 并排+进入页：旧档位宽黑 / 新交容器
            Triple(true, false, 120f) to (true to true),   // 并排拖动：两边线性遮罩
            Triple(false, false, 0f) to (true to false),   // 并排普通页：旧档位宽黑 / 新交容器
            Triple(false, false, 120f) to (true to true)   // 并排拖动：两边线性遮罩
        )
        for ((input, expected) in vectors) {
            val (hasFlyBackCellRect, inFullScreen, dismissY) = input
            val (legacyBlack, currentBlack) = expected
            val cell = "hasFlyBackCellRect=$hasFlyBackCellRect, " +
                "inFullScreen=$inFullScreen, dismissY=$dismissY"
            assertEquals(
                "$cell 旧镜像与 v4.16.7 判定链不符",
                legacyBlack,
                legacyV4167DrawsPageBlack(hasFlyBackCellRect, inFullScreen, dismissY)
            )
            assertEquals(
                "$cell 现实现判定与 N3/N4/G1 判定表不符",
                currentBlack,
                drawsPageBlack(
                    ViewerDismissGesture.resolveBackgroundBranch(
                        hasFlyBackCellRect, inFullScreen, dismissY
                    )
                )
            )
        }
        // ★ 被修复的那一格必须新旧相反：实现退回到"全屏非进入页不画"时，
        // 上面矩阵的 currentBlack 断言会先红；这条再把差异本身钉住
        assertNotEquals(
            legacyV4167DrawsPageBlack(false, true, 0f),
            drawsPageBlack(ViewerDismissGesture.resolveBackgroundBranch(false, true, 0f))
        )
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
