package sh.hnet.comfychair.ui.components

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-14：连线的视口剔除。
 *
 * 修复前只有节点、输出插槽、输入插槽做了视口判断，`animatedEdges.forEach` 仍然
 * 无条件 `drawEdge`：150~300 节点的工作流平移/缩放时，视口外的连线照样要算路由
 * （三次贝塞尔 + 描边/虚线），是掉帧的剩余来源之一。
 *
 * 剔除必须**只跳过完全不可见的连线**。连线不是直线：两个内置路由的控制点会沿水平
 * 方向伸出端点包围盒，所以外扩量要随端点距离增长（[EDGE_CULL_CURVE_FACTOR]）。
 * 这里同时钉住纯函数边界与「绘制循环真的接上了」。
 */
class EdgeViewportCullingTest {

    /** 基准视口：图坐标 (0,0)-(1000,800)，外扩 64（图坐标单位）。 */
    private val viewport = GraphViewport(
        left = 0f,
        top = 0f,
        right = 1000f,
        bottom = 800f,
        paddingPx = 64f
    )

    private fun draw(from: Offset, to: Offset, vp: GraphViewport = viewport): Boolean =
        shouldDrawEdge(from, to, vp)

    // ---- 1. 完全在内 / 完全在外 ----

    @Test
    fun `两端都在视口内的连线要画`() {
        assertTrue(draw(Offset(100f, 100f), Offset(500f, 300f)))
        // 零长度边也在视口内
        assertTrue(draw(Offset(500f, 400f), Offset(500f, 400f)))
    }

    @Test
    fun `完全在视口外的连线不画`() {
        // 右侧远处：端点距离约 538，外扩上限 64 + 96 + 0.4*538 ≈ 375，仍远小于 3000
        assertFalse("右侧远处", draw(Offset(3000f, 100f), Offset(3500f, 300f)))
        assertFalse("左侧远处", draw(Offset(-4000f, 100f), Offset(-3500f, 300f)))
        assertFalse("上方远处", draw(Offset(100f, -3000f), Offset(500f, -2600f)))
        assertFalse("下方远处", draw(Offset(100f, 4000f), Offset(500f, 4500f)))
        // 零长度边在远处同样不画
        assertFalse("远处的零长度边", draw(Offset(5000f, 5000f), Offset(5000f, 5000f)))
    }

    // ---- 2. 跨界（一端在内 / 穿过视口）----

    @Test
    fun `一端在内或穿过视口的连线要画`() {
        assertTrue("从视口内到视口外", draw(Offset(900f, 400f), Offset(1600f, 420f)))
        assertTrue("从视口外到视口内", draw(Offset(-800f, 380f), Offset(200f, 420f)))
        // 两端都在视口外的两侧，但连线横穿视口
        assertTrue("横穿整个视口", draw(Offset(-3000f, 400f), Offset(4000f, 400f)))
    }

    // ---- 3. 外扩边界（padding + 常量余量真的参与判断）----

    @Test
    fun `刚好落在 外扩 范围内的连线要画`() {
        // 右边界 1000 + 视口外扩 64 + 常量余量 96 = 1160（零长度边，无限远也不增长）
        assertTrue(draw(Offset(1159f, 400f), Offset(1159f, 400f)))
        assertFalse(draw(Offset(1161f, 400f), Offset(1161f, 400f)))
    }

    @Test
    fun `曲线外扩随端点距离增长`() {
        // 竖直方向相距 2000 的短横向边：贝塞尔控制点会向两侧甩开约 0.4*2000。
        // 外扩 = 64 + 96 + 0.4*2000 = 960 → 端点 x=-200 仍在范围内（-200 + 960 > 0）。
        // 反证：把 EDGE_CULL_CURVE_FACTOR 改成 0，这条会变红（-200 + 160 < 0）。
        assertTrue(
            "竖直远距离的连线，其曲线可能甩进视口，不能只看端点包围盒",
            draw(Offset(-200f, 0f), Offset(-200f, 2000f))
        )
        // 同距离但再远 200：-420 + 960 > 0 仍然可见；-1000 + 960 < 0 必须剔除。
        assertFalse(draw(Offset(-1000f, 0f), Offset(-1000f, 2000f)))
    }

    // ---- 4. 退化视口 ----

    @Test
    fun `视口面积为零时不做剔除`() {
        // 画布尺寸仍为 0（尚未完成测量）时，几何判断会把所有连线判成不可见 → 整屏空白。
        val degenerate = GraphViewport.of(0f, 0f, 0f, 0f, scale = 1f)
        assertTrue(draw(Offset(10000f, 10000f), Offset(10200f, 10100f), degenerate))
        assertTrue(draw(Offset(-10000f, -10000f), Offset(-9800f, -9900f), degenerate))
    }

    // ---- 5. 接线：绘制循环必须真的调用剔除 ----

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    @Test
    fun `连线绘制循环在 drawEdge 之前调用 shouldDrawEdge`() {
        val source = readSource("src/main/java/sh/hnet/comfychair/ui/components/WorkflowGraphCanvas.kt")
        val cull = source.indexOf("if (!shouldDrawEdge(from, to, viewport))")
        assertTrue("绘制循环必须调用 shouldDrawEdge", cull >= 0)
        val drawEdge = source.indexOf("drawEdge(", cull)
        assertTrue("shouldDrawEdge（$cull）必须排在 drawEdge（$drawEdge）之前", drawEdge > cull)
        // 跳过必须是真的 return@forEach（否则判断了也不省绘制）
        val skip = source.indexOf("return@forEach", cull)
        assertTrue("剔除后必须 return@forEach 跳过 drawEdge", skip in (cull + 1) until drawEdge)
        // 端点必须取自与 drawEdge 相同的节点坐标表
        val block = source.substring(source.indexOf("animatedEdges.forEach"), drawEdge)
        assertTrue("端点必须来自 nodePositionsMap", block.contains("nodePositionsMap[edge.sourceNodeId]"))
        assertTrue("端点必须来自 nodePositionsMap", block.contains("nodePositionsMap[edge.targetNodeId]"))
        // P2-14 复核：drawEdge 会对拖拽节点的插槽做 1.08 倍缩放，剔除端点也必须
        // 过同一个 scaleSocketPosition —— 否则"端点完全一致"只是靠余量兜底。
        assertEquals(
            "剔除的两个端点都必须经过 scaleSocketPosition（与 drawEdge 同源）",
            2,
            Regex("scaleSocketPosition\\(").findAll(block).count()
        )
    }
}