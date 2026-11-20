package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视口剔除的纯函数契约测试。
 *
 * 剔除是"少画"的优化，出错方式是静默的：边界算错不会抛异常，只会让视口边缘的节点
 * 在平移/缩放时突然消失或闪现。这里把四条边、四种贴边情形、外扩 padding 以及
 * 屏幕像素 → 图坐标的换算逐个钉死。
 *
 * 覆盖要求（派工单）：完全在内、完全在外、四边各越界一半、正好贴边。
 */
class GraphViewportCullingTest {

    /** 基准视口：图坐标 (0,0)-(1000,800)，外扩 64（图坐标单位）。 */
    private val viewport = GraphViewport(
        left = 0f,
        top = 0f,
        right = 1000f,
        bottom = 800f,
        paddingPx = 64f
    )

    private fun visible(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        vp: GraphViewport = viewport
    ): Boolean = isNodeVisible(left, top, right, bottom, vp)

    // ---- 完全在内 / 完全在外 ----

    @Test
    fun `完全在视口内的节点可见`() {
        assertTrue(visible(100f, 100f, 300f, 250f))
    }

    @Test
    fun `完全在视口外的节点不可见`() {
        // 四条边各一个，且都离视口超过外扩 64
        assertFalse("右侧远处", visible(2000f, 100f, 2200f, 250f))
        assertFalse("左侧远处", visible(-300f, 100f, -100f, 250f))
        assertFalse("上方远处", visible(100f, -300f, 300f, -100f))
        assertFalse("下方远处", visible(100f, 900f, 300f, 1100f))
    }

    // ---- 四边各越界一半 ----

    @Test
    fun `左边越界一半的节点可见`() {
        assertTrue(visible(-50f, 100f, 150f, 250f))
    }

    @Test
    fun `右边越界一半的节点可见`() {
        assertTrue(visible(850f, 100f, 1050f, 250f))
    }

    @Test
    fun `上边越界一半的节点可见`() {
        assertTrue(visible(100f, -50f, 300f, 150f))
    }

    @Test
    fun `下边越界一半的节点可见`() {
        assertTrue(visible(100f, 650f, 300f, 850f))
    }

    // ---- 正好贴边（边界算可见，否则平移一像素就会闪）----

    @Test
    fun `四条边正好贴住视口的节点都可见`() {
        assertTrue("左贴边", visible(0f, 100f, 200f, 250f))
        assertTrue("右贴边", visible(800f, 100f, 1000f, 250f))
        assertTrue("上贴边", visible(100f, 0f, 300f, 200f))
        assertTrue("下贴边", visible(100f, 600f, 300f, 800f))
    }

    // ---- 外扩 padding（少了它视口边缘的节点会突然消失/闪现）----

    @Test
    fun `视口外但落在外扩范围内的节点可见`() {
        // 右边界 1000 + 外扩 64 = 1064，节点左边 1060 仍在范围内
        assertTrue(visible(1060f, 100f, 1260f, 250f))
        // 左边界 0 - 64 = -64，节点右边 -60 仍在范围内
        assertTrue(visible(-260f, 100f, -60f, 250f))
        // 上边界 0 - 64 = -64，节点底边 -60
        assertTrue(visible(100f, -260f, 300f, -60f))
        // 下边界 800 + 64 = 864，节点顶边 860
        assertTrue(visible(100f, 860f, 300f, 1060f))
    }

    @Test
    fun `超出外扩范围的节点不可见`() {
        // 外扩边界是 1064 / -64 / 864，各越出 1px 就该剔除
        assertFalse("右边越出外扩", visible(1065f, 100f, 1265f, 250f))
        assertFalse("左边越出外扩", visible(-265f, 100f, -65f, 250f))
        assertFalse("上边越出外扩", visible(100f, -265f, 300f, -65f))
        assertFalse("下边越出外扩", visible(100f, 865f, 300f, 1065f))
    }

    @Test
    fun `padding 为零时贴边算可见、出去半点算不可见`() {
        // 直接用带 paddingPx 的重载，确认外扩真的参与判断而不是摆设
        assertTrue(
            isNodeVisible(
                nodeLeft = 1000f, nodeTop = 100f, nodeRight = 1200f, nodeBottom = 250f,
                viewportLeft = 0f, viewportTop = 0f, viewportRight = 1000f, viewportBottom = 800f,
                paddingPx = 0f
            )
        )
        assertFalse(
            isNodeVisible(
                nodeLeft = 1000.5f, nodeTop = 100f, nodeRight = 1200f, nodeBottom = 250f,
                viewportLeft = 0f, viewportTop = 0f, viewportRight = 1000f, viewportBottom = 800f,
                paddingPx = 0f
            )
        )
    }

    // ---- 屏幕像素 → 图坐标的换算 ----

    @Test
    fun `视口按平移换算到图坐标`() {
        // 画布 1080x800，向左上平移 (-200,-100)、scale=1
        val vp = GraphViewport.of(
            canvasWidth = 1080f,
            canvasHeight = 800f,
            offsetX = -200f,
            offsetY = -100f,
            scale = 1f
        )
        assertEquals(200f, vp.left, 1e-4f)
        assertEquals(100f, vp.top, 1e-4f)
        assertEquals(1280f, vp.right, 1e-4f)
        assertEquals(900f, vp.bottom, 1e-4f)
    }

    @Test
    fun `缩放后外扩仍等于屏幕 64 像素`() {
        // 放大 2 倍：图坐标的 32 就是屏幕 64
        val zoomIn = GraphViewport.of(1080f, 800f, 0f, 0f, scale = 2f)
        assertEquals(32f, zoomIn.paddingPx, 1e-4f)
        assertEquals(540f, zoomIn.right, 1e-4f)

        // 缩小到 0.5：图坐标的 128 才是屏幕 64
        val zoomOut = GraphViewport.of(1080f, 800f, 0f, 0f, scale = 0.5f)
        assertEquals(128f, zoomOut.paddingPx, 1e-4f)
        assertEquals(2160f, zoomOut.right, 1e-4f)
    }

    @Test
    fun `非法缩放不会产生无穷或 NaN`() {
        // scale=0 时除零会得到 Infinity，剔除判断会整体失效（什么都"可见"或什么都不画）
        val vp = GraphViewport.of(1080f, 800f, 0f, 0f, scale = 0f)
        assertTrue(vp.left.isFinite())
        assertTrue(vp.top.isFinite())
        assertTrue(vp.right.isFinite())
        assertTrue(vp.bottom.isFinite())
        assertTrue(vp.paddingPx.isFinite())
    }

    @Test
    fun `视口面积为零时不做剔除`() {
        // 画布尺寸仍是 0（尚未完成测量）时，几何判断会把所有节点都算成不可见 → 整屏空白。
        // 这种情况必须退化为"全部绘制"，与改造前的行为一致。
        val degenerate = GraphViewport.of(0f, 0f, 0f, 0f, scale = 1f)
        assertTrue(isNodeVisible(10000f, 10000f, 10200f, 10100f, degenerate))
        assertTrue(isNodeVisible(-10000f, -10000f, -9800f, -9900f, degenerate))
    }
}
