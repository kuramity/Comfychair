package sh.hnet.comfychair.workflow.dynamic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数值参数的范围约束单测（Fix Round 3 / 审查报告 I-1）。
 *
 * 钉住三件事：
 * 1. [DynamicParamRange.violationOf] 的判定边界（谁算越界、谁不算）
 * 2. [DynamicParamRange.clampForSubmit] 夹出来的值**一定能通过服务器校验**
 *    （`min <= v <= max`，且是合法 JSON 数字）
 * 3. 端到端：越界值在 `applyDynamicParams` 替换那一刻被夹紧、合法值形态原样保留
 *
 * 背景：服务器 `validate_inputs()` 沿全部连线递归校验，`lazy` 只影响执行表 ——
 * 所以"关掉补采"并不会让 `hires_steps1 = 0` 免于 400 `value_smaller_than_min`。
 */
class DynamicParamRangeTest {

    private fun intControl(min: Double? = 1.0, max: Double? = 10000.0) =
        DynamicParamControl.NumberField(isInteger = true, min = min, max = max, step = 1.0, default = 20)

    private fun floatControl(min: Double? = null, max: Double? = null) =
        DynamicParamControl.NumberField(isInteger = false, min = min, max = max, step = null, default = null)

    // ================================================================
    // 1. 越界判定
    // ================================================================

    @Test
    fun `小于最小值被判越界并夹到最小`() {
        val out = DynamicParamRange.violationOf("0", intControl())

        assertEquals(DynamicParamRange.Violation.BELOW_MIN, out?.violation)
        assertEquals("1", out?.clamped)
    }

    @Test
    fun `大于最大值被判越界并夹到最大`() {
        val out = DynamicParamRange.violationOf("10001", intControl())

        assertEquals(DynamicParamRange.Violation.ABOVE_MAX, out?.violation)
        assertEquals("10000", out?.clamped)
    }

    @Test
    fun `边界值本身合法（min 与 max 都是闭区间）`() {
        // 服务器判定是 `val < min` / `val > max`，等于上下界必须放过
        assertNull(DynamicParamRange.violationOf("1", intControl()))
        assertNull(DynamicParamRange.violationOf("10000", intControl()))
    }

    @Test
    fun `合法值原样通过`() {
        assertEquals("35", DynamicParamRange.clampForSubmit("35", intControl()))
        assertEquals("0.5", DynamicParamRange.clampForSubmit("0.5", floatControl(min = 0.01, max = 8.0)))
    }

    @Test
    fun `没有范围约束的控件不做任何事`() {
        val noRange = intControl(min = null, max = null)

        assertNull(DynamicParamRange.violationOf("0", noRange))
        assertNull(DynamicParamRange.violationOf("-99999", noRange))
        assertEquals("0", DynamicParamRange.clampForSubmit("0", noRange))
    }

    @Test
    fun `只有一个界时只按那个界判`() {
        val minOnly = floatControl(min = 0.05, max = null)
        val maxOnly = floatControl(min = null, max = 4.0)

        assertEquals(DynamicParamRange.Violation.BELOW_MIN, DynamicParamRange.violationOf("0.01", minOnly)?.violation)
        assertNull(DynamicParamRange.violationOf("999", minOnly))
        assertEquals(DynamicParamRange.Violation.ABOVE_MAX, DynamicParamRange.violationOf("5", maxOnly)?.violation)
        assertNull(DynamicParamRange.violationOf("0.001", maxOnly))
    }

    @Test
    fun `非数值输入不算越界（交给既有的保留占位符路径）`() {
        val control = intControl()

        assertNull(DynamicParamRange.violationOf("", control))
        assertNull(DynamicParamRange.violationOf("abc", control))
        assertNull(DynamicParamRange.violationOf("1e999", control)) // 解析成 Infinity，不是数字
        assertEquals("abc", DynamicParamRange.clampForSubmit("abc", control))
    }

    @Test
    fun `整型控件里的小数不是范围问题`() {
        val control = intControl()

        // 既有 numberLiteral 对"整型控件 + 小数"本就不替换（保留占位符），这层不越权改判
        assertNull(DynamicParamRange.violationOf("3.5", control))
        assertNull(DynamicParamRange.violationOf("0.5", control))
    }

    @Test
    fun `整型控件的非整数界按方向取整`() {
        // 服务器上偶有非整数界的 INT 输入（如 min=0.5）：直接发 0.5 会被整型判定挡下、反而不替换
        assertEquals("1", DynamicParamRange.clampForSubmit("0", intControl(min = 0.5, max = 100.4)))
        assertEquals("100", DynamicParamRange.clampForSubmit("200", intControl(min = 0.5, max = 100.4)))
    }

    @Test
    fun `夹出来的浮点值不带科学计数法`() {
        val tiny = floatControl(min = 1.0E-5, max = 8.0)

        assertEquals("0.00001", DynamicParamRange.clampForSubmit("0", tiny))
        // 形态可逆：再解析回去必须还在范围内（不能因为格式化又越界）
        val back = DynamicParamRange.clampForSubmit("0", tiny).toDouble()
        assertTrue("夹紧值必须满足 min <= v", back >= 1.0E-5)
    }

    // ================================================================
    // 2. 端到端：替换那一刻夹紧
    // ================================================================

    private fun param(placeholder: String, control: DynamicParamControl) =
        DynamicParam(placeholder, "1", "KSampler", "steps", control)

    @Test
    fun `越界值在替换时被夹紧成合法 JSON 数字`() {
        val json = """{"nodes":{"1":{"class_type":"KSampler","inputs":{"steps":"{{my_steps}}"}}}}"""
        val values = listOf(DynamicParamValue(param("my_steps", intControl()), "0"))

        val out = DynamicParamValues.applyDynamicParams(json, values)

        assertEquals("""{"nodes":{"1":{"class_type":"KSampler","inputs":{"steps":1}}}}""", out)
        assertEquals(
            1,
            JSONObject(out).getJSONObject("nodes").getJSONObject("1").getJSONObject("inputs").getInt("steps")
        )
    }

    @Test
    fun `上界越界在替换时被夹紧`() {
        val json = """{"steps":"{{my_steps}}"}"""
        val values = listOf(DynamicParamValue(param("my_steps", intControl()), "999999"))

        assertEquals("""{"steps":10000}""", DynamicParamValues.applyDynamicParams(json, values))
    }

    @Test
    fun `合法值在替换时形态原样保留`() {
        val json = """{"a":"{{my_steps}}","b":"{{my_scale}}"}"""
        val values = listOf(
            DynamicParamValue(param("my_steps", intControl()), "35"),
            DynamicParamValue(param("my_scale", floatControl(min = 0.01, max = 8.0)), "1.75")
        )

        assertEquals("""{"a":35,"b":1.75}""", DynamicParamValues.applyDynamicParams(json, values))
    }

    @Test
    fun `无范围约束的数字控件行为与改造前一致`() {
        val json = """{"steps":"{{my_steps}}"}"""
        val values = listOf(DynamicParamValue(param("my_steps", intControl(min = null, max = null)), "0"))

        // 没有 min/max 就无从判越界：原样发出（保证不误伤没有范围定义的节点）
        assertEquals("""{"steps":0}""", DynamicParamValues.applyDynamicParams(json, values))
    }

    @Test
    fun `嵌入形态的越界数字同样被夹紧`() {
        val json = """{"file":"out_{{my_steps}}.png"}"""
        val values = listOf(DynamicParamValue(param("my_steps", intControl()), "0"))

        assertEquals("""{"file":"out_1.png"}""", DynamicParamValues.applyDynamicParams(json, values))
    }

    // ================================================================
    // 3. 提交前日志用的越界清单
    // ================================================================

    @Test
    fun `越界清单只列出越界项`() {
        val resolved = listOf(
            DynamicParamValue(param("a", intControl()), "0"),
            DynamicParamValue(param("b", intControl()), "35"),
            DynamicParamValue(param("c", intControl()), "10001")
        )

        val clamped = DynamicParamRange.outOfRangeIn(resolved)

        assertEquals(listOf("a", "c"), clamped.map { it.first })
        assertEquals(DynamicParamRange.Violation.BELOW_MIN, clamped[0].second.violation)
        assertEquals("10000", clamped[1].second.clamped)
    }
}
