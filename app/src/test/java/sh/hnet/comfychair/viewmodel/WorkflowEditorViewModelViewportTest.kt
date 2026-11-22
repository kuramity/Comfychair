package sh.hnet.comfychair.viewmodel

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作流编辑器「视口缩放/平移」状态机的 JVM 直测（不碰 Android、不碰 ConnectionManager）。
 *
 * 覆盖 WorkflowEditorViewModel 中无 Context 依赖的纯状态机部分：
 * - 捏合手势缩放经 [WorkflowEditorViewModel.onTransform] 钳制在 0.2~3 倍；
 * - 按钮缩放（zoomIn/zoomOut/setZoom100）围绕画布中心缩放（GraphViewportCalculator.zoomTowardPoint
 *   是纯数学 object），画布中心由 setCanvasSize 记录的尺寸决定；
 * - 复位按钮的循环 fit 模式（resetView）依赖 graphBounds 注入，本文件不覆盖（见未覆盖清单）。
 *
 * 浮点断言统一带容差：按钮缩放的 1/1.2 回程存在 float 舍入累积。
 */
class WorkflowEditorViewModelViewportTest {

    private val vm = WorkflowEditorViewModel()

    private fun scale(): Float = vm.uiState.value.scale
    private fun offset(): Offset = vm.uiState.value.offset

    @Test
    fun `初始视口为100缩放且偏移在原点`() {
        assertEquals(1f, scale(), 0f)
        assertEquals(0f, offset().x, 0f)
        assertEquals(0f, offset().y, 0f)
    }

    @Test
    fun `捏合手势的超大缩放被钳制到3倍`() {
        vm.onTransform(10f, Offset(100f, 50f))
        assertEquals("缩放上限 3 倍：超出会被钳回，画布不应被放大到不可读", 3f, scale(), 0f)
        assertEquals("偏移直接透传，不做钳制", 100f, offset().x, 0f)
        assertEquals(50f, offset().y, 0f)
    }

    @Test
    fun `捏合手势的超小缩放被钳制到0点2倍`() {
        vm.onTransform(0.001f, Offset(0f, 0f))
        assertEquals("缩放下限 0.2 倍：缩太小节点文字不可读", 0.2f, scale(), 1e-6f)
    }

    @Test
    fun `setScale同样受钳制区间约束`() {
        vm.setScale(99f)
        assertEquals(3f, scale(), 0f)
        vm.setScale(-1f)
        assertEquals(0.2f, scale(), 1e-6f)
    }

    @Test
    fun `setOffset直接更新平移位置`() {
        vm.setOffset(Offset(12f, 34f))
        assertEquals(12f, offset().x, 0f)
        assertEquals(34f, offset().y, 0f)
    }

    @Test
    fun `zoomIn放大1点2倍并保持画布中心不动`() {
        vm.setCanvasSize(800f, 600f)
        vm.zoomIn()
        assertEquals("按钮放大固定步进 1.2 倍", 1.2f, scale(), 1e-4f)
        // 中心 (400,300) 静止：newOffset = center - (center - old) * 1.2 = (400,300) - (400,300)*1.2
        assertEquals(-80f, offset().x, 1e-3f)
        assertEquals(-60f, offset().y, 1e-3f)
    }

    @Test
    fun `zoomIn后zoomOut回到约100缩放`() {
        vm.setCanvasSize(800f, 600f)
        vm.zoomIn()
        vm.zoomOut()
        assertEquals("1.2 × (1/1.2) 回程因 float 舍入只近似 1.0", 1f, scale(), 1e-3f)
    }

    @Test
    fun `连续zoomIn封顶在3倍`() {
        vm.setCanvasSize(800f, 600f)
        repeat(20) { vm.zoomIn() }
        assertTrue("连点 20 次放大不得越过 3 倍上限", scale() <= 3f + 1e-6f)
        assertEquals(3f, scale(), 1e-4f)
    }

    @Test
    fun `连续zoomOut下限在0点2倍`() {
        vm.setCanvasSize(800f, 600f)
        repeat(20) { vm.zoomOut() }
        assertTrue(scale() >= 0.2f - 1e-6f)
        assertEquals(0.2f, scale(), 1e-4f)
    }

    @Test
    fun `已100缩放时setZoom100不产生任何偏移`() {
        vm.setCanvasSize(800f, 600f)
        vm.setZoom100()
        assertEquals("已是 100% 时点击「回到 100%」不应挪动画布", 0f, offset().x, 0f)
        assertEquals(0f, offset().y, 0f)
    }

    @Test
    fun `setZoom100从放大状态回到100缩放`() {
        vm.setCanvasSize(800f, 600f)
        vm.zoomIn()
        vm.setZoom100()
        assertEquals(1f, scale(), 1e-3f)
    }
}
