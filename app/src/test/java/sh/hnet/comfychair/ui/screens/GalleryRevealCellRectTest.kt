package sh.hnet.comfychair.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 下拉透出目标格子矩形换算（N2，v4.16.8）的纯逻辑测试。
 *
 * R5 实测的根因坐标（320x640@160dpi，1dp = 1px）：
 * - 旧矩形（漏 contentPadding）：[156,244]；
 * - 真实格子：[164,252]（差正好 8px = 8dp 的 contentPadding）。
 *
 * 本测试把这两个实测值钉进断言，防回归；并用反证证明"去掉 padding 后
 * 矩形必然错位 8dp"（测试不是恒真的）。
 */
class GalleryRevealCellRectTest {

    /**
     * R5 实测场景：320x640 屏、2 列网格、第二行第二列格子。
     * grid 节点在 window (0,88)（状态栏 24 + TopAppBar 64），
     * 内容区起点 = 88 + 8（contentPadding），第二列/第二行 offset 均 = 156，
     * 格子尺寸 148x148（(320 − 2×8 − 8) / 2）。
     */
    private val gridOriginX = 0f
    private val gridOriginY = 88f
    private val paddingPx = 8f
    private val itemOffsetX = 156f
    private val itemOffsetY = 156f
    private val itemSize = 148f

    @Test
    fun `含padding的矩形与真实格子对齐`() {
        val rect = galleryRevealCellRect(
            gridOriginX = gridOriginX,
            gridOriginY = gridOriginY,
            contentPaddingPx = paddingPx,
            itemOffsetX = itemOffsetX,
            itemOffsetY = itemOffsetY,
            itemWidthPx = itemSize,
            itemHeightPx = itemSize
        )
        // R5 实测的真实格子位置：[164,252]（= gridOrigin + padding + offset）
        assertEquals(164f, rect.left, 1e-4f)
        assertEquals(252f, rect.top, 1e-4f)
        assertEquals(itemSize, rect.width, 1e-4f)
        assertEquals(itemSize, rect.height, 1e-4f)
    }

    @Test
    fun `任意列数下公式一致_第一列第一行也不偏`() {
        // 第一列第一行：offset = (0,0)，格子真实位置 = gridOrigin + padding
        val rect = galleryRevealCellRect(gridOriginX, gridOriginY, paddingPx, 0f, 0f, 148f, 148f)
        assertEquals(gridOriginX + paddingPx, rect.left, 1e-4f)
        assertEquals(gridOriginY + paddingPx, rect.top, 1e-4f)
    }

    /**
     * 反证：去掉 contentPadding 偏移（旧版公式）后矩形与真实格子错位 8dp。
     *
     * 把同输入按旧公式（不加 padding）算一遍，结果与 R5 实测的"错位矩形
     * [156,244]"一致、与真实格子 [164,252] 不一致——证明"含 padding 后矩形
     * 与格子对齐"这条断言真的钉住了修复（去掉 padding 断言必失败）。
     * 实现级反证（去掉 paddingPx 跑一次确认断言失败）见自测报告。
     */
    @Test
    fun `反证_去掉padding偏移后与真实格子错位8dp`() {
        val rectWithPadding = galleryRevealCellRect(
            gridOriginX, gridOriginY, paddingPx, itemOffsetX, itemOffsetY, itemSize, itemSize
        )
        // 旧公式（v4.16.7 及之前）：不加 contentPadding —— 正是 R5 实测的错位矩形
        val legacyRect = galleryRevealCellRect(
            gridOriginX, gridOriginY, 0f, itemOffsetX, itemOffsetY, itemSize, itemSize
        )
        // 旧公式结果 = R5 实测的错位黑矩形 [156,244]
        assertEquals(156f, legacyRect.left, 1e-4f)
        assertEquals(244f, legacyRect.top, 1e-4f)
        // 正实现与旧公式相差整整一个 padding，绝不相等
        assertNotEquals(rectWithPadding.left, legacyRect.left, 1e-4f)
        assertNotEquals(rectWithPadding.top, legacyRect.top, 1e-4f)
        // 错位量正是 8dp
        assertEquals(paddingPx, rectWithPadding.left - legacyRect.left, 1e-4f)
        assertEquals(paddingPx, rectWithPadding.top - legacyRect.top, 1e-4f)
    }

    @Test
    fun `padding为0时新旧公式重合`() {
        // 密度无关的退路：如果某天 contentPadding 设为 0，两种公式一致（不引入回归）
        val rect = galleryRevealCellRect(gridOriginX, gridOriginY, 0f, itemOffsetX, itemOffsetY, itemSize, itemSize)
        assertEquals(gridOriginX + itemOffsetX, rect.left, 1e-4f)
        assertEquals(gridOriginY + itemOffsetY, rect.top, 1e-4f)
    }
}
