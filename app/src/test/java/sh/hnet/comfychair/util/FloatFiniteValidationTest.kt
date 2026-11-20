package sh.hnet.comfychair.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import sh.hnet.comfychair.model.NodeAttributeEdits

/**
 * P1-17：FLOAT 属性必须拒绝 NaN / ±Infinity。
 *
 * 旧实现 `ValidationUtils.validateFloat` 只做 `toFloatOrNull` + 区间比较，而
 * `"NaN".toFloatOrNull()` 会正常返回 NaN，NaN 与 min/max 的比较**永远为 false**，
 * 于是 min/max 两道闸门全部形同虚设；`"Infinity"` 在没设 max 时也能通过。
 * 这类值写进工作流 JSON 时 org.json 会抛 `Forbidden numeric value: NaN`，
 * 表现为「保存/生成莫名失败」，且用户完全看不出是哪个字段。
 */
class FloatFiniteValidationTest {

    @Test
    fun `NaN 被拒绝`() {
        assertNotNull(ValidationUtils.validateFloat("NaN"))
        assertNotNull(ValidationUtils.validateFloat("nan"))
    }

    @Test
    fun `正无穷被拒绝`() {
        assertNotNull(ValidationUtils.validateFloat("Infinity"))
        assertNotNull(ValidationUtils.validateFloat("+Infinity"))
    }

    @Test
    fun `负无穷被拒绝`() {
        assertNotNull(ValidationUtils.validateFloat("-Infinity"))
    }

    @Test
    fun `带上下限时非有限值依然被拒绝`() {
        assertNotNull(ValidationUtils.validateFloat("NaN", null, 0f, 2f))
        assertNotNull(ValidationUtils.validateFloat("Infinity", null, 0f, 2f))
    }

    @Test
    fun `普通数字通过`() {
        assertNull(ValidationUtils.validateFloat("1.5"))
        assertNull(ValidationUtils.validateFloat("0", null, 0f, 1f))
        assertNull(ValidationUtils.validateFloat("-3.25", null, -10f, 10f))
    }

    @Test
    fun `空串视为未填写`() {
        assertNull(ValidationUtils.validateFloat(""))
    }

    @Test
    fun `区间检查没有被放松`() {
        assertNotNull("大于上限", ValidationUtils.validateFloat("3", null, 0f, 2f))
        assertNotNull("小于下限", ValidationUtils.validateFloat("-1", null, 0f, 2f))
        assertNotNull("不是数字", ValidationUtils.validateFloat("abc"))
    }

    @Test
    fun `非有限浮点不会被写进节点属性 JSON`() {
        val edits = NodeAttributeEdits(
            mapOf(
                "12" to mapOf(
                    "cfg" to Float.NaN,
                    "denoise" to Float.POSITIVE_INFINITY,
                    "steps" to 20
                )
            )
        )

        val json = edits.toJson()

        assertFalse("不允许写入 NaN", json.contains("NaN", ignoreCase = true))
        assertFalse("不允许写入 Infinity", json.contains("Infinity", ignoreCase = true))
        // 正常值必须照常写入
        assertTrueContains(json, "\"steps\":20")
    }

    private fun assertTrueContains(haystack: String, needle: String) {
        org.junit.Assert.assertTrue("期望 JSON 里包含 $needle，实际：$haystack", haystack.contains(needle))
    }
}
