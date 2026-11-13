package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图位「选择 / 删除模式」的纯逻辑（配合 `ImageSlotGrid` 的 `selectionMode` / `selected` 参数）。
 *
 * 钉住三件容易出错的事：
 * 1. 长按进模式时必须带上被长按的图位——否则顶部操作栏一出现就是"没东西可删"的空壳；
 * 2. 图位定义变化后必须收敛——勾选集合里不能留下已不存在的占位符名，
 *    否则「删除所选」会拿一份包含幽灵图位的集合去删（设计 §3、§4.2）；
 * 3. 批量删除后（勾选全被摘掉）必须自动退出选择模式，页面不必额外收尾。
 */
class SlotSelectionTest {

    /** 简写：造一个"已处于选择模式、勾了这几个图位"的状态 */
    private fun selecting(vararg placeholders: String) =
        SlotSelection(active = true, selected = placeholders.toSet())

    @Test
    fun `长按已填图位进入选择模式并选中它`() {
        val entered = SlotSelection().enter("reference_image_0")

        assertTrue("长按后就该在选择模式里", entered.active)
        assertEquals("被长按的图位必须已经勾上", setOf("reference_image_0"), entered.selected)
        assertTrue("有勾选时「删除所选」才可用", entered.canDelete)
    }

    @Test
    fun `选择模式下点图位切换勾选`() {
        val toggled = selecting("reference_image_0")
            .toggle("reference_image_1")
            .toggle("reference_image_0")

        assertTrue("勾选变化不影响留在模式内", toggled.active)
        assertEquals(setOf("reference_image_1"), toggled.selected)
    }

    @Test
    fun `不在选择模式时点图位不产生隐藏勾选`() {
        val untouched = SlotSelection().toggle("reference_image_0")

        assertFalse("没进选择模式就不该被点进模式", untouched.active)
        assertTrue("也不该留下界面看不见的勾选（否则下次长按会凭空多出一个选中项）", untouched.selected.isEmpty())
    }

    @Test
    fun `长按第二个图位不清掉已有的勾选`() {
        val merged = selecting("reference_image_0").enter("reference_image_1")

        assertTrue(merged.active)
        assertEquals(setOf("reference_image_0", "reference_image_1"), merged.selected)
    }

    @Test
    fun `重复长按同一个图位是幂等的`() {
        val again = selecting("reference_image_0").enter("reference_image_0")

        assertEquals(setOf("reference_image_0"), again.selected)
    }

    @Test
    fun `勾掉最后一个仍留在选择模式内`() {
        val unchecked = selecting("reference_image_0").toggle("reference_image_0")

        assertTrue("退出只由取消/删除/图位定义变化触发，勾光不算退出", unchecked.active)
        assertTrue(unchecked.selected.isEmpty())
        assertFalse("没勾选时「删除所选」应不可用", unchecked.canDelete)
    }

    @Test
    fun `取消退出并清空勾选`() {
        val exited = selecting("reference_image_0", "reference_image_1").exit()

        assertFalse(exited.active)
        assertTrue(exited.selected.isEmpty())
    }

    @Test
    fun `图位定义变化只丢掉已不存在的图位`() {
        // 换工作流后占位符名变了，但还有一张勾选的图位活着 → 保持模式，只清掉幽灵项
        val converged = selecting("image_filename", "reference_image_0")
            .retainAvailable(setOf("reference_image_0", "reference_image_1"))

        assertTrue(converged.active)
        assertEquals(setOf("reference_image_0"), converged.selected)
    }

    @Test
    fun `批量删除后自动退出选择模式`() {
        // 复现路径：ref2v 长按参考图 1 → 勾上参考图 2 → 删除所选 → imageSlots 只剩主图
        val converged = selecting("reference_image_0", "reference_image_1")
            .retainAvailable(setOf("image_filename"))

        assertFalse("勾选的图位全没了就该退出选择模式", converged.active)
        assertTrue("勾选集合必须清空，不能留下已删除的占位符名", converged.selected.isEmpty())
    }

    @Test
    fun `勾选的图位被删光后连主图也一起没了也退出`() {
        // 极端情况：工作流重载失败 / 图位全清空，slots 变成空列表
        val converged = selecting("reference_image_0").retainAvailable(emptySet())

        assertFalse(converged.active)
        assertTrue(converged.selected.isEmpty())
    }

    @Test
    fun `未进入选择模式时收敛不产生副作用`() {
        // 首次组合时 LaunchedEffect 就会跑一次，此时 slots 已就绪但用户还没长按过
        val untouched = SlotSelection().retainAvailable(setOf("image_filename"))

        assertFalse(untouched.active)
        assertTrue(untouched.selected.isEmpty())
    }
}
