package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoRA 权重的手动输入链路：取值范围、显示格式、文本解析、量化夹紧。
 *
 * 背景：滑块原本只到 2.0，且只能拖（拖出来的是任意浮点，显示 `%.1f` / `%.2f` 两种精度）。
 * 现在要 0~10 全范围 + 手动输入精确值，就必须先把「值长什么样」定死在一处：
 * - 显示：最多两位小数、去掉无意义的尾随零（10 不是 "10.00"）
 * - 解析：非数字一律 null（不是 0，不能把打错的输入当成"零权重"提交出去）
 * - 量化：两位小数。拖动与手动输入走同一个收敛点，否则显示值会与实际提交值不一致
 */
class LoraStrengthRangeTest {

    // ── 取值范围 ─────────────────────────────────────────────────────────

    @Test
    fun `权重范围是 0 到 10`() {
        assertEquals(0.0f, LoraSelection.MIN_STRENGTH, 0f)
        assertEquals(
            "滑块上限必须是 10，否则大权重只能靠手动输入、滑块会卡在错误位置",
            10.0f,
            LoraSelection.MAX_STRENGTH,
            0f
        )
    }

    @Test
    fun `范围判定含两端`() {
        assertTrue(LoraSelection.isStrengthInRange(0.0f))
        assertTrue(LoraSelection.isStrengthInRange(10.0f))
        assertTrue(LoraSelection.isStrengthInRange(1.0f))

        assertFalse("负权重不在范围内", LoraSelection.isStrengthInRange(-0.01f))
        assertFalse("超过 10 不在范围内", LoraSelection.isStrengthInRange(10.01f))
        assertFalse("NaN 不是合法权重", LoraSelection.isStrengthInRange(Float.NaN))
        assertFalse("Infinity 不是合法权重", LoraSelection.isStrengthInRange(Float.POSITIVE_INFINITY))
    }

    // ── 显示格式 ─────────────────────────────────────────────────────────

    @Test
    fun `显示去掉尾随零`() {
        assertEquals("1", LoraSelection.formatStrength(1.0f))
        assertEquals("0", LoraSelection.formatStrength(0.0f))
        assertEquals("10", LoraSelection.formatStrength(10.0f))
        assertEquals("0.5", LoraSelection.formatStrength(0.5f))
        assertEquals("1.25", LoraSelection.formatStrength(1.25f))
        assertEquals("2.6", LoraSelection.formatStrength(2.60f))
    }

    @Test
    fun `显示最多两位小数`() {
        assertEquals("滑块拖出来的任意浮点也要收敛成两位", "3.74", LoraSelection.formatStrength(3.7421f))
        assertEquals("0.01", LoraSelection.formatStrength(0.014f))
    }

    // ── 文本解析 ─────────────────────────────────────────────────────────

    @Test
    fun `合法输入按两位小数量化`() {
        assertEquals(1.5f, LoraSelection.parseStrength("1.5")!!, 1e-6f)
        assertEquals(10.0f, LoraSelection.parseStrength("10")!!, 1e-6f)
        assertEquals(0.0f, LoraSelection.parseStrength("0")!!, 1e-6f)
        assertEquals(3.74f, LoraSelection.parseStrength("3.7421")!!, 1e-6f)
        assertEquals("前后空格不该让人重打一遍", 2.0f, LoraSelection.parseStrength(" 2 ")!!, 1e-6f)
        assertEquals("整数写法等价于带小数点的写法", 1.0f, LoraSelection.parseStrength("1.00")!!, 1e-6f)
    }

    @Test
    fun `非法输入返回 null 而不是零`() {
        assertNull("空串是「还没输入」，不是 0", LoraSelection.parseStrength(""))
        assertNull(LoraSelection.parseStrength("   "))
        assertNull(LoraSelection.parseStrength("abc"))
        assertNull(LoraSelection.parseStrength("1.2.3"))
        assertNull(LoraSelection.parseStrength("1,5"))
        assertNull("逗号/句号两种小数点都不能悄悄变成 0", LoraSelection.parseStrength("."))
        assertNull(LoraSelection.parseStrength("NaN"))
        assertNull(LoraSelection.parseStrength("Infinity"))
    }

    @Test
    fun `越界输入照常解析 由范围判定拦下`() {
        // 解析只负责"这是不是一个数"，越界与否交给 isStrengthInRange，
        // 这样对话框才能把「你填了 15」和「你填了 abc」分开提示
        assertEquals(15.0f, LoraSelection.parseStrength("15")!!, 1e-6f)
        assertEquals(-1.0f, LoraSelection.parseStrength("-1")!!, 1e-6f)
        assertFalse(LoraSelection.isStrengthInRange(LoraSelection.parseStrength("15")!!))
        assertFalse(LoraSelection.isStrengthInRange(LoraSelection.parseStrength("-1")!!))
    }

    // ── 量化夹紧（提交前收口） ───────────────────────────────────────────

    @Test
    fun `量化夹紧把任意浮点收进 0 到 10 的两位小数`() {
        assertEquals(3.74f, LoraSelection.sanitizeStrength(3.7421f), 1e-6f)
        assertEquals(10.0f, LoraSelection.sanitizeStrength(15.0f), 1e-6f)
        assertEquals(0.0f, LoraSelection.sanitizeStrength(-3.0f), 1e-6f)
        assertEquals(1.0f, LoraSelection.sanitizeStrength(1.0f), 1e-6f)
        assertEquals(
            "NaN 落到默认权重，不能把 NaN 写进工作流（JSON 里会变成 null / 非法字面量）",
            LoraSelection.DEFAULT_STRENGTH,
            LoraSelection.sanitizeStrength(Float.NaN),
            1e-6f
        )
    }

    @Test
    fun `格式化的结果能被解析回同一个值`() {
        // 对话框每次打开都拿 formatStrength 当初始文本，用户直接点确定必须不变值
        val samples = listOf(0f, 0.5f, 1f, 1.25f, 3.74f, 10f, 2.6f)
        samples.forEach { value ->
            val text = LoraSelection.formatStrength(value)
            assertEquals(
                "显示成 $text 后必须能原样解析回来",
                value,
                LoraSelection.parseStrength(text)!!,
                1e-6f
            )
        }
    }
}
